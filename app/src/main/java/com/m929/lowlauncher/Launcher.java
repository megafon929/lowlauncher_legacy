package com.m929.lowlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;

/** Главная активность лаунчера: рабочий стол, Dock, меню приложений, виджеты. */
@SuppressWarnings("deprecation")
public final class Launcher extends Activity {
    private static final int REQ_PICK = 1, REQ_CONFIG = 2, REQ_BIND = 3;
    private static final int HOST_ID = 1024;

    private final Handler handler = new Handler();
    private final int[] spot = new int[2];

    private DragLayer root;
    private Workspace workspace;
    private PageDots dots;
    private Dock dock;
    private AppDrawer drawer;
    private View bar;
    private TextView aboutTv, removeTv;

    private AppWidgetManager awm;
    private AppWidgetHost host;
    private Store store;
    private LauncherModel model;
    private LauncherModel.Apps apps;

    private int cols, rows;
    private boolean destroyed;
    private int pendingWidgetId = -1;
    private int pendingPage;
    private CellLayout frameOwner;
    private boolean receiversOn;
    private FolderPopup folderPopup;
    /**
     * Элементы, чьи приложения сейчас недоступны (карта памяти, система ещё грузится, приложение отключено)
     * или не поместились после уменьшения сетки. На экране их нет, но из сохранённых данных они не стираются
     * и возвращаются на место, когда приложение снова находится.
     */
    private final ArrayList<ItemInfo> ghosts = new ArrayList<ItemInfo>();
    /** То же для Dock: недоступное приложение освобождает слот на экране, но запоминается в данных. */
    private final ComponentName[] dockGhosts = new ComponentName[Dock.SLOTS];
    /** Пакеты, удалённые на наших глазах (PACKAGE_REMOVED без REPLACING): их значки не сохраняются. */
    private final HashSet<String> removedPkgs = new HashSet<String>();
    private int emptyRetries;

    private static final class WidgetHost extends AppWidgetHost {
        WidgetHost(Context c, int id) {
            super(c, id);
        }

        @Override
        protected AppWidgetHostView onCreateView(Context c, int id, AppWidgetProviderInfo info) {
            return new HomeWidgetView(c);
        }
    }

    // ---- слушатели ----------------------------------------------------------------------------

    private final View.OnClickListener iconClick = new View.OnClickListener() {
        public void onClick(View v) {
            Object t = v.getTag();
            if (!(t instanceof ItemInfo)) return;
            ItemInfo it = (ItemInfo) t;
            if (it.type == ItemInfo.FOLDER) openFolder(it);
            else launch(it.component);
        }
    };

    private final View.OnLongClickListener workspaceLongClick = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            return startItemDrag(v, DragLayer.SRC_WORKSPACE);
        }
    };

    private final View.OnLongClickListener dockLongClick = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            return startItemDrag(v, DragLayer.SRC_DOCK);
        }
    };

    private final View.OnLongClickListener pageLongClick = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            pendingPage = workspace.getCurrentPage();
            showHomeMenu();
            return true;
        }
    };

    private final View.OnClickListener drawerClick = new View.OnClickListener() {
        public void onClick(View v) {
            Object t = v.getTag();
            if (t instanceof AppInfo) launch(((AppInfo) t).component);
        }
    };

    private final View.OnLongClickListener drawerLongClick = new View.OnLongClickListener() {
        public boolean onLongClick(View v) {
            Object t = v.getTag();
            if (!(t instanceof AppInfo)) return false;
            ItemInfo it = new ItemInfo(ItemInfo.APP);
            it.component = ((AppInfo) t).component;
            DragLayer.DragState d = new DragLayer.DragState();
            d.info = it;
            d.view = v;
            d.source = DragLayer.SRC_DRAWER;
            return root.startDrag(v, d);
        }
    };

    private final ResizeFrame.Callback frameCallback = new ResizeFrame.Callback() {
        public void onResized() {
            saveAll();
        }

        public void onRemove(View target) {
            dismissFrame();
            removeItem(target);
        }
    };

    private final Runnable reloadRunnable = new Runnable() {
        public void run() {
            loadApps(false);
        }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String pkg = i.getData() != null ? i.getData().getSchemeSpecificPart() : null;
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction())) {
                if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    return; // обновление: сразу следом придёт ADDED, иначе значки на миг пропали бы
                }
                if (pkg != null) removedPkgs.add(pkg);
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(i.getAction()) && pkg != null) {
                removedPkgs.remove(pkg);
            }
            handler.removeCallbacks(reloadRunnable);
            handler.postDelayed(reloadRunnable, 700);
        }
    };

    // ---- жизненный цикл -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        model = new LauncherModel(this);
        store = new Store(this);
        awm = AppWidgetManager.getInstance(this);
        IconView.labelSp = store.getInt("fontsp", 11);
        model.setPack(store.getString("pack", null));
        host = new WidgetHost(this, HOST_ID);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        IntentFilter f2 = new IntentFilter();
        f2.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        f2.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        registerReceiver(pkgReceiver, f2);
        receiversOn = true;

        if (!store.isInitialized()) Toast.makeText(this, R.string.hint, Toast.LENGTH_LONG).show();
        loadApps(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        host.startListening();
    }

    @Override
    protected void onResume() {
        super.onResume();
        root.settleDrawer();
    }

    @Override
    protected void onStop() {
        host.stopListening();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(reloadRunnable);
        if (receiversOn) unregisterReceiver(pkgReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        boolean alreadyOnHome = (intent.getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT;
        root.cancelDrag();
        folderPopup.close();
        dismissFrame();
        if (root.isDrawerOpen()) {
            root.closeDrawer(false);
        } else if (alreadyOnHome) {
            workspace.snapToPage(0, true);
        }
    }

    @Override
    public void onBackPressed() {
        if (root.isDragging()) {
            root.cancelDrag();
        } else if (folderPopup.isOpen()) {
            folderPopup.close();
        } else if (frameOwner != null) {
            dismissFrame();
        } else if (root.isDrawerOpen()) {
            root.closeDrawer(true);
        }
        // на рабочем столе «Назад» ничего не делает
    }

    // ---- интерфейс ----------------------------------------------------------------------------

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        cols = store.getInt("cols", 4);
        rows = store.getInt("rows", dm.heightPixels / dm.density >= 590 ? 5 : 4);

        root = new DragLayer(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        workspace = new Workspace(this);
        for (int i = 0; i < Workspace.PAGES; i++) {
            CellLayout cl = new CellLayout(this, cols, rows);
            cl.setOnLongClickListener(pageLongClick);
            workspace.addPage(cl);
        }
        dots = new PageDots(this);
        dots.setCount(Workspace.PAGES);
        workspace.setListener(new Workspace.Listener() {
            public void onPageChanged(int page) {
                dots.setCurrent(page);
            }
        });
        dock = new Dock(this, iconClick, dockLongClick);

        content.addView(workspace, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(dots, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 16)));
        content.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, model.iconSize() + U.dp(this, 16)));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new AppDrawer(this, drawerClick, drawerLongClick);
        drawer.setColumns(store.getInt("dcols", 0));
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        folderPopup = new FolderPopup(this, folderHost, model.iconSize());
        root.addView(folderPopup, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout barLayout = new LinearLayout(this);
        barLayout.setOrientation(LinearLayout.HORIZONTAL);
        aboutTv = makeBarText(R.string.app_info);
        removeTv = makeBarText(R.string.remove);
        barLayout.addView(aboutTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        barLayout.addView(removeTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        barLayout.setVisibility(View.GONE);
        bar = barLayout;
        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, U.dp(this, 56), Gravity.TOP));

        root.attach(this, workspace, dock, drawer, bar, aboutTv, removeTv);
        setContentView(root);
    }

    private TextView makeBarText(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setBackgroundColor(0xAA000000);
        return t;
    }

    void showAppInfo(ComponentName cn) {
        if (cn == null) return;
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", cn.getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- загрузка приложений ------------------------------------------------------------------

    private void loadApps(final boolean force) {
        final LauncherModel.Apps old = force ? null : apps;
        new android.os.AsyncTask<Void, Void, LauncherModel.Apps>() {
            @Override
            protected LauncherModel.Apps doInBackground(Void... p) {
                return model.load(old);
            }

            @Override
            protected void onPostExecute(LauncherModel.Apps a) {
                if (isFinishing() || destroyed) return;
                if (a.list.isEmpty()) {
                    // система ещё не отдала список приложений (загрузка, карта памяти) — раскладку не трогаем
                    if (emptyRetries < 5) {
                        emptyRetries++;
                        handler.removeCallbacks(reloadRunnable);
                        handler.postDelayed(reloadRunnable, 2000);
                        return;
                    }
                    if (apps != null) return;
                } else {
                    emptyRetries = 0;
                }
                boolean same = old != null && !a.changed && old.map.keySet().equals(a.map.keySet());
                apps = a;
                drawer.setApps(a.list);
                if (!same) rebuildHome();
            }
        }.execute();
    }

    AppInfo appFor(ComponentName cn) {
        if (apps == null || cn == null) return null;
        AppInfo a = apps.get(cn);
        if (a == null) {
            a = model.buildExtra(cn);
            if (a != null) apps.extra.put(cn, a);
        }
        return a;
    }

    private View createIcon(AppInfo a) {
        IconView v = new IconView(this, true);
        ItemInfo it = new ItemInfo(ItemInfo.APP);
        it.component = a.component;
        v.setTag(it);
        v.bind(a);
        v.setOnClickListener(iconClick);
        v.setOnLongClickListener(workspaceLongClick);
        return v;
    }

    private View createFolderView(ItemInfo it) {
        IconView v = new IconView(this, true);
        v.setTag(it);
        refreshFolderView(v, it);
        v.setOnClickListener(iconClick);
        v.setOnLongClickListener(workspaceLongClick);
        return v;
    }

    private void refreshFolderView(IconView v, ItemInfo it) {
        ArrayList<android.graphics.drawable.Drawable> icons = new ArrayList<android.graphics.drawable.Drawable>();
        for (int i = 0; i < it.apps.size() && icons.size() < 4; i++) {
            AppInfo a = appFor(it.apps.get(i));
            if (a != null) icons.add(a.icon);
        }
        v.bindFolder(it.title, new FolderDrawable(icons, model.iconSize()));
    }

    private View createWidgetView(ItemInfo it, AppWidgetProviderInfo info) {
        AppWidgetHostView hv = host.createView(this, it.appWidgetId, info);
        hv.setTag(it);
        hv.setOnLongClickListener(workspaceLongClick);
        return hv;
    }

    /** Строит рабочий стол и Dock из сохранённых данных. */
    private void rebuildHome() {
        root.cancelDrag();      // виды сейчас будут пересозданы — перетаскиваемый станет недействительным
        folderPopup.close();
        dismissFrame();
        for (int i = 0; i < workspace.getPageCount(); i++) workspace.getPage(i).removeAllViews();
        ghosts.clear();

        ArrayList<ItemInfo> items = store.loadItems();
        for (int n = 0; n < items.size(); n++) {
            ItemInfo it = items.get(n);
            View v;
            if (it.type == ItemInfo.FOLDER) {
                // приложения, удалённые на наших глазах, из папки убираем; недоступные пока — оставляем в данных
                for (int k = it.apps.size() - 1; k >= 0; k--) {
                    if (isRemoved(it.apps.get(k))) it.apps.remove(k);
                }
                if (it.apps.isEmpty()) continue;
                int visible = 0;
                for (int k = 0; k < it.apps.size(); k++) {
                    if (appFor(it.apps.get(k)) != null) visible++;
                }
                if (it.title.trim().length() == 0) it.title = getString(R.string.folder_default);
                if (visible == 0) {
                    ghosts.add(it);
                    continue;
                }
                if (it.apps.size() == 1) {
                    v = createIcon(appFor(it.apps.get(0)));
                } else {
                    v = createFolderView(it);
                }
                it.spanX = 1;
                it.spanY = 1;
            } else if (it.type == ItemInfo.APP) {
                AppInfo a = appFor(it.component);
                if (a == null) {
                    if (!isRemoved(it.component)) ghosts.add(it);
                    continue;
                }
                v = createIcon(a);
                it.spanX = 1;
                it.spanY = 1;
            } else {
                AppWidgetProviderInfo info = awm.getAppWidgetInfo(it.appWidgetId);
                if (info == null) {
                    host.deleteAppWidgetId(it.appWidgetId);
                    continue;
                }
                v = createWidgetView(it, info);
            }
            int sx = Math.max(1, Math.min(it.spanX, cols));
            int sy = Math.max(1, Math.min(it.spanY, rows));
            CellLayout cl = workspace.getPage(U.clamp(it.page, 0, Workspace.PAGES - 1));
            int x = it.cellX, y = it.cellY;
            if (!cl.isFree(x, y, sx, sy, null)) {
                boolean placed = false;
                for (int p = 0; p < Workspace.PAGES && !placed; p++) {
                    if (workspace.getPage(p).findSpot(sx, sy, spot)) {
                        cl = workspace.getPage(p);
                        x = spot[0];
                        y = spot[1];
                        placed = true;
                    }
                }
                if (!placed) {
                    if (it.type == ItemInfo.WIDGET) host.deleteAppWidgetId(it.appWidgetId);
                    else ghosts.add(it); // нет места (например, сетку уменьшили): значок не теряем
                    continue;
                }
            }
            cl.addItem(v, x, y, sx, sy);
        }

        ComponentName[] dc = resolveDock();
        for (int i = 0; i < Dock.SLOTS; i++) dock.set(i, appFor(dc[i]));
        saveAll();
    }

    /** По умолчанию: телефон, SMS, браузер, камера, музыка. После ручной правки Dock пустые места сохраняются. */
    private ComponentName[] resolveDock() {
        ComponentName[] saved = store.loadDock();
        HashSet<ComponentName> used = new HashSet<ComponentName>();
        java.util.Arrays.fill(dockGhosts, null);
        for (int i = 0; i < saved.length; i++) {
            ComponentName cn = saved[i];
            if (cn == null) continue;
            AppInfo ai = appFor(cn);
            if (ai != null && !used.contains(cn)) {
                used.add(cn);
            } else {
                if (ai == null && !isRemoved(cn)) dockGhosts[i] = cn; // недоступно сейчас — запоминаем место
                saved[i] = null;
            }
        }
        Intent[] defaults = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
                new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        };
        // Пока пользователь сам не менял Dock, недостающие места заполняются стандартными приложениями.
        if (store.getInt("dockEdited", 0) == 1) return saved;
        for (int i = 0; i < saved.length; i++) {
            if (saved[i] != null) continue;
            ComponentName cn = model.resolveFirst(defaults[i], used, apps);
            if (cn == null) {
                for (int k = 0; k < apps.list.size(); k++) {
                    ComponentName c = apps.list.get(k).component;
                    if (!used.contains(c)) {
                        cn = c;
                        break;
                    }
                }
            }
            if (cn != null) {
                saved[i] = cn;
                dockGhosts[i] = null;
                used.add(cn);
            }
        }
        return saved;
    }

    private boolean isRemoved(ComponentName cn) {
        return cn != null && removedPkgs.contains(cn.getPackageName());
    }

    void saveAll() {
        if (apps == null) return;
        ArrayList<ItemInfo> out = new ArrayList<ItemInfo>();
        for (int p = 0; p < workspace.getPageCount(); p++) {
            CellLayout cl = workspace.getPage(p);
            for (int i = 0; i < cl.getChildCount(); i++) {
                View c = cl.getChildAt(i);
                Object t = c.getTag();
                if (!(t instanceof ItemInfo)) continue;
                ItemInfo it = (ItemInfo) t;
                CellLayout.LP lp = (CellLayout.LP) c.getLayoutParams();
                it.page = p;
                it.cellX = lp.cellX;
                it.cellY = lp.cellY;
                it.spanX = lp.spanX;
                it.spanY = lp.spanY;
                out.add(it);
            }
        }
        out.addAll(ghosts);
        ComponentName[] dc = dock.components();
        for (int i = 0; i < dc.length; i++) {
            if (dc[i] == null) dc[i] = dockGhosts[i];
        }
        store.save(out, dc);
    }

    /** Открывает шторку уведомлений (скрытый API StatusBarManager, нужно разрешение EXPAND_STATUS_BAR). */
    void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            Class<?> c = Class.forName("android.app.StatusBarManager");
            java.lang.reflect.Method m;
            try {
                m = c.getMethod("expandNotificationsPanel"); // Android 4.2+
            } catch (NoSuchMethodException e) {
                m = c.getMethod("expand");                   // Android 4.0 / 4.1
            }
            m.invoke(sb);
        } catch (Exception e) {
            // шторка недоступна — жест просто игнорируется
        }
    }

    // ---- запуск приложений --------------------------------------------------------------------

    private void launch(ComponentName cn) {
        if (cn == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- drag & drop: операции над моделью ----------------------------------------------------

    private boolean startItemDrag(View v, int source) {
        Object t = v.getTag();
        if (!(t instanceof ItemInfo)) return false;
        ItemInfo it = (ItemInfo) t;
        if (it.type == ItemInfo.APP && it.component == null) return false;
        DragLayer.DragState d = new DragLayer.DragState();
        d.info = it;
        d.view = v;
        d.source = source;
        if (it.type == ItemInfo.WIDGET) {
            CellLayout.LP lp = (CellLayout.LP) v.getLayoutParams();
            d.spanX = lp.spanX;
            d.spanY = lp.spanY;
        }
        return root.startDrag(v, d);
    }

    boolean dropOnWorkspace(DragLayer.DragState d, CellLayout cl, int x, int y) {
        if (d.source == DragLayer.SRC_WORKSPACE) {
            View v = d.view;
            ViewGroup parent = (ViewGroup) v.getParent();
            CellLayout.LP lp = (CellLayout.LP) v.getLayoutParams();
            lp.cellX = x;
            lp.cellY = y;
            if (parent != cl) {
                if (parent != null) parent.removeView(v);
                cl.addView(v, lp);
            } else {
                cl.requestLayout();
            }
            v.setVisibility(View.VISIBLE);
            if (d.info.type == ItemInfo.WIDGET) showFrame(v);
        } else {
            AppInfo a = appFor(d.info.component);
            if (a == null) return false;
            if (d.source == DragLayer.SRC_DOCK) {
                store.putInt("dockEdited", 1);
                dock.set(d.info.dockSlot, null);
                d.view.setVisibility(View.VISIBLE);
            }
            cl.addItem(createIcon(a), x, y, 1, 1);
            if (d.source == DragLayer.SRC_FOLDER) removeFromFolder(d);
        }
        saveAll();
        return true;
    }

    boolean dropOnDock(DragLayer.DragState d, int slot) {
        store.putInt("dockEdited", 1);
        AppInfo a = appFor(d.info.component);
        if (a == null) return false;
        ComponentName displaced = dock.get(slot);
        AppInfo disp = appFor(displaced);
        if (d.source == DragLayer.SRC_DOCK) {
            int from = d.info.dockSlot;
            if (from == slot) {
                d.view.setVisibility(View.VISIBLE);
                return true;
            }
            dock.set(from, disp);
            d.view.setVisibility(View.VISIBLE);
        } else if (d.source == DragLayer.SRC_WORKSPACE) {
            if (disp != null) {
                ((ItemInfo) d.view.getTag()).component = disp.component;
                ((IconView) d.view).bind(disp);
                d.view.setVisibility(View.VISIBLE);
            } else {
                removeItem(d.view);
            }
        }
        // приложение уже стоит в другом месте Dock — переносим, а не дублируем (после перезапуска дубли всё равно исчезали)
        for (int i = 0; i < Dock.SLOTS; i++) {
            if (i != slot && a.component.equals(dock.get(i))) dock.set(i, null);
        }
        dockGhosts[slot] = null;
        dock.set(slot, a);
        if (d.source == DragLayer.SRC_FOLDER) removeFromFolder(d);
        saveAll();
        return true;
    }

    // ---- папки --------------------------------------------------------------------------------

    private final FolderPopup.Host folderHost = new FolderPopup.Host() {
        public AppInfo appFor(ComponentName cn) {
            return Launcher.this.appFor(cn);
        }

        public void onOpenApp(ComponentName cn) {
            folderPopup.close();
            launch(cn);
        }

        public boolean onDragOut(View v, ItemInfo folder, ComponentName cn) {
            View fv = findFolderView(folder);
            if (fv == null) return false;
            ItemInfo it = new ItemInfo(ItemInfo.APP);
            it.component = cn;
            DragLayer.DragState d = new DragLayer.DragState();
            d.info = it;
            d.view = v;
            d.source = DragLayer.SRC_FOLDER;
            d.folderView = fv;
            folderPopup.setVisibility(View.INVISIBLE); // скрыть окно, но не уничтожать значок под пальцем
            if (!root.startDrag(v, d)) {
                folderPopup.setVisibility(View.VISIBLE);
                return false;
            }
            return true;
        }

        public void onTitleChanged(ItemInfo folder, String title) {
            title = title.trim();
            folder.title = title.length() == 0 ? getString(R.string.folder_default) : title;
            View fv = findFolderView(folder);
            if (fv instanceof IconView) refreshFolderView((IconView) fv, folder);
            saveAll();
        }
    };

    boolean isFolderOpen() {
        return folderPopup != null && folderPopup.isOpen();
    }

    private void openFolder(ItemInfo it) {
        dismissFrame();
        folderPopup.open(it);
    }

    private View findFolderView(ItemInfo f) {
        for (int p = 0; p < workspace.getPageCount(); p++) {
            CellLayout cl = workspace.getPage(p);
            for (int i = 0; i < cl.getChildCount(); i++) {
                if (cl.getChildAt(i).getTag() == f) return cl.getChildAt(i);
            }
        }
        return null;
    }

    /** Значок сброшен на значок или папку: создаём папку либо добавляем в существующую. */
    boolean dropOnFolder(DragLayer.DragState d, View target) {
        if (d.info.type != ItemInfo.APP || d.info.component == null) return false;
        Object t = target.getTag();
        if (!(t instanceof ItemInfo) || target == d.view || target == d.folderView) return false;
        ItemInfo ti = (ItemInfo) t;
        ComponentName moved = d.info.component;
        if (appFor(moved) == null) return false;
        ItemInfo folder;
        if (ti.type == ItemInfo.FOLDER) {
            folder = ti;
            if (!folder.apps.contains(moved)) folder.apps.add(moved);
            refreshFolderView((IconView) target, folder);
        } else if (ti.type == ItemInfo.APP && ti.component != null) {
            if (ti.component.equals(moved)) return false;
            CellLayout cl = (CellLayout) target.getParent();
            CellLayout.LP lp = (CellLayout.LP) target.getLayoutParams();
            folder = new ItemInfo(ItemInfo.FOLDER);
            folder.title = getString(R.string.folder_default);
            folder.apps.add(ti.component);
            folder.apps.add(moved);
            int x = lp.cellX, y = lp.cellY;
            cl.removeView(target);
            cl.addItem(createFolderView(folder), x, y, 1, 1);
        } else {
            return false;
        }
        // убрать значок из прежнего места
        if (d.source == DragLayer.SRC_WORKSPACE) {
            ViewGroup p = (ViewGroup) d.view.getParent();
            if (p != null) p.removeView(d.view);
        } else if (d.source == DragLayer.SRC_DOCK) {
            store.putInt("dockEdited", 1);
            dock.set(d.info.dockSlot, null);
            d.view.setVisibility(View.VISIBLE);
        } else if (d.source == DragLayer.SRC_FOLDER) {
            removeFromFolder(d);
        }
        saveAll();
        return true;
    }

    /** Значок вынут из папки и положен в другое место: убрать его из папки, при необходимости упростить папку. */
    private void removeFromFolder(DragLayer.DragState d) {
        folderPopup.close();
        View fv = d.folderView;
        if (fv == null || !(fv.getTag() instanceof ItemInfo)) return;
        ItemInfo f = (ItemInfo) fv.getTag();
        f.apps.remove(d.info.component);
        ViewGroup parent = (ViewGroup) fv.getParent();
        if (parent == null) return;
        int visible = 0;
        for (int i = 0; i < f.apps.size(); i++) {
            if (appFor(f.apps.get(i)) != null) visible++;
        }
        if (visible >= 1 && f.apps.size() >= 2) {
            refreshFolderView((IconView) fv, f);
            return;
        }
        CellLayout.LP lp = (CellLayout.LP) fv.getLayoutParams();
        int x = lp.cellX, y = lp.cellY;
        parent.removeView(fv);
        if (f.apps.isEmpty()) return;
        if (visible == 0) {
            // остались только недоступные приложения: папку прячем, но данные сохраняем
            f.page = workspace.indexOfChild(parent);
            f.cellX = x;
            f.cellY = y;
            ghosts.add(f);
        } else {
            ((CellLayout) parent).addItem(createIcon(appFor(f.apps.get(0))), x, y, 1, 1);
        }
    }

    /** Бросок в «Удалить»: из папки убирает значок (приложение не удаляется), иначе удаляет элемент. */
    boolean removeDragged(DragLayer.DragState d) {
        if (d.source == DragLayer.SRC_FOLDER) {
            removeFromFolder(d);
            saveAll();
            return true;
        }
        return removeItem(d.view);
    }

    boolean removeItem(View v) {
        Object t = v.getTag();
        if (t instanceof ItemInfo && ((ItemInfo) t).dockSlot >= 0) {
            store.putInt("dockEdited", 1);
            dock.set(((ItemInfo) t).dockSlot, null);
            v.setVisibility(View.VISIBLE);
            saveAll();
            return true;
        }
        ViewGroup p = (ViewGroup) v.getParent();
        if (p != null) p.removeView(v);
        if (t instanceof ItemInfo && ((ItemInfo) t).type == ItemInfo.WIDGET) {
            host.deleteAppWidgetId(((ItemInfo) t).appWidgetId);
        }
        saveAll();
        return true;
    }

    void onDragCancelled(DragLayer.DragState d) {
        if (d.source == DragLayer.SRC_FOLDER) {
            folderPopup.close();
            return;
        }
        if (d.source != DragLayer.SRC_DRAWER && d.view != null) d.view.setVisibility(View.VISIBLE);
    }

    // ---- рамка изменения размера виджета ------------------------------------------------------

    private void showFrame(View v) {
        dismissFrame();
        if (!(v.getParent() instanceof CellLayout)) return;
        CellLayout cl = (CellLayout) v.getParent();
        ItemInfo it = (ItemInfo) v.getTag();
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(it.appWidgetId);
        int mode = info != null ? info.resizeMode : 0;
        boolean h = (mode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0;
        boolean vert = (mode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0;
        workspace.setLocked(true);
        cl.showFrame(v, h, vert, frameCallback);
        frameOwner = cl;
    }

    void dismissFrame() {
        if (frameOwner != null) {
            frameOwner.removeFrame();
            frameOwner = null;
        }
        workspace.setLocked(false);
    }

    boolean isFrameActive() {
        return frameOwner != null;
    }

    boolean isOnFrame(float rawX, float rawY) {
        if (frameOwner == null || frameOwner.getFrame() == null) return false;
        View f = frameOwner.getFrame();
        int[] loc = new int[2];
        f.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + f.getWidth()
                && rawY >= loc[1] && rawY <= loc[1] + f.getHeight();
    }

    // ---- меню рабочего стола и добавление виджета ---------------------------------------------

    private void showHomeMenu() {
        CharSequence[] items = {getString(R.string.menu_widget), getString(R.string.menu_wallpaper),
                getString(R.string.menu_settings)};
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) startAddWidget();
                        else if (which == 1) openWallpaperChooser();
                        else showSettings();
                    }
                })
                .show();
    }

    // ---- настройки ----------------------------------------------------------------------------

    private void showSettings() {
        int dcols = store.getInt("dcols", 0);
        String pack = model.packLabel(store.getString("pack", null));
        CharSequence[] items = {
                getString(R.string.set_grid) + ": " + cols + " × " + rows,
                getString(R.string.set_drawer_cols) + ": " + (dcols == 0 ? getString(R.string.auto) : String.valueOf(dcols)),
                getString(R.string.set_iconpack) + ": " + (pack != null ? pack : getString(R.string.default_icons)),
                getString(R.string.set_font) + ": " + fontName(IconView.labelSp)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_settings)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) showGridDialog();
                        else if (which == 1) showDrawerColsDialog();
                        else if (which == 2) showIconPackDialog();
                        else showFontDialog();
                    }
                })
                .show();
    }

    /** Сетка рабочего стола (колонки × строки). Применяется перезапуском экрана, значки переразмещаются. */
    private void showGridDialog() {
        final int[][] opts = {{4, 4}, {4, 5}, {4, 6}, {5, 5}, {5, 6}};
        String[] names = new String[opts.length];
        int checked = -1;
        for (int i = 0; i < opts.length; i++) {
            names[i] = opts[i][0] + " × " + opts[i][1];
            if (opts[i][0] == cols && opts[i][1] == rows) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_grid)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (opts[which][0] == cols && opts[which][1] == rows) return;
                        store.putInt("cols", opts[which][0]);
                        store.putInt("rows", opts[which][1]);
                        recreate();
                    }
                })
                .show();
    }

    private String fontName(int sp) {
        return getString(sp <= 9 ? R.string.font_small : sp >= 15 ? R.string.font_huge
                : sp >= 13 ? R.string.font_large : R.string.font_normal);
    }

    /** Размер подписей под значками. Применяется перезапуском экрана. */
    private void showFontDialog() {
        final int[] sizes = {9, 11, 13, 15};
        String[] names = new String[sizes.length];
        int checked = 1;
        for (int i = 0; i < sizes.length; i++) {
            names[i] = fontName(sizes[i]);
            if (sizes[i] == IconView.labelSp) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_font)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (sizes[which] == IconView.labelSp) return;
                        store.putInt("fontsp", sizes[which]);
                        recreate();
                    }
                })
                .show();
    }

    private void showDrawerColsDialog() {
        final int[] opts = {0, 3, 4, 5, 6};
        String[] names = new String[opts.length];
        int cur = store.getInt("dcols", 0);
        int checked = 0;
        for (int i = 0; i < opts.length; i++) {
            names[i] = opts[i] == 0 ? getString(R.string.auto) : String.valueOf(opts[i]);
            if (opts[i] == cur) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_drawer_cols)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        store.putInt("dcols", opts[which]);
                        drawer.setColumns(opts[which]);
                    }
                })
                .show();
    }

    private void showIconPackDialog() {
        final ArrayList<String[]> packs = model.findIconPacks();
        String cur = store.getString("pack", null);
        String[] names = new String[packs.size() + 1];
        names[0] = getString(R.string.default_icons);
        int checked = 0;
        for (int i = 0; i < packs.size(); i++) {
            names[i + 1] = packs.get(i)[1];
            if (packs.get(i)[0].equals(cur)) checked = i + 1;
        }
        if (packs.isEmpty()) Toast.makeText(this, R.string.no_icon_packs, Toast.LENGTH_LONG).show();
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_iconpack)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        String pkg = which == 0 ? null : packs.get(which - 1)[0];
                        store.putString("pack", pkg);
                        model.setPack(pkg);
                        loadApps(true);
                    }
                })
                .show();
    }

    private void openWallpaperChooser() {
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                    getString(R.string.menu_wallpaper)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void discardPendingWidget() {
        if (pendingWidgetId != -1) host.deleteAppWidgetId(pendingWidgetId);
        pendingWidgetId = -1;
    }

    private void startAddWidget() {
        discardPendingWidget();
        if (Build.VERSION.SDK_INT >= 16) {
            showWidgetList();
            return;
        }
        pendingWidgetId = host.allocateAppWidgetId();
        Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        // Системный выбор виджетов в Android 4.0 ожидает эти списки (даже пустые).
        pick.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, new ArrayList<Parcelable>());
        pick.putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, new ArrayList<Parcelable>());
        try {
            startActivityForResult(pick, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            discardPendingWidget();
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Android 4.1+: системный выбор виджетов для стороннего лаунчера ненадёжен, поэтому свой список
     * и привязка через AppWidgetManager (при необходимости — с запросом разрешения у системы).
     */
    @SuppressWarnings("NewApi") // вызывается только при SDK_INT >= 16
    private void showWidgetList() {
        final java.util.List<AppWidgetProviderInfo> list =
                new ArrayList<AppWidgetProviderInfo>(awm.getInstalledProviders());
        final android.content.pm.PackageManager pm = getPackageManager();
        final java.util.HashMap<AppWidgetProviderInfo, String> names =
                new java.util.HashMap<AppWidgetProviderInfo, String>();
        for (int i = 0; i < list.size(); i++) {
            AppWidgetProviderInfo p = list.get(i);
            if (Build.VERSION.SDK_INT >= 17
                    && (p.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0) {
                list.remove(i--); // виджеты только для экрана блокировки
                continue;
            }
            String app;
            try {
                app = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p.provider.getPackageName(), 0)));
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                app = p.provider.getPackageName();
            }
            names.put(p, app + " — " + widgetLabel(p, pm));
        }
        java.util.Collections.sort(list, new java.util.Comparator<AppWidgetProviderInfo>() {
            public int compare(AppWidgetProviderInfo a, AppWidgetProviderInfo b) {
                return String.CASE_INSENSITIVE_ORDER.compare(names.get(a), names.get(b));
            }
        });
        CharSequence[] items = new CharSequence[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = names.get(list.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_widget)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        bindWidget(list.get(which));
                    }
                })
                .show();
    }

    /** Название виджета: с Android 5.0 поле label не заполняется, нужен loadLabel(). */
    @SuppressWarnings("NewApi") // loadLabel() — только при SDK_INT >= 21
    private static String widgetLabel(AppWidgetProviderInfo p, android.content.pm.PackageManager pm) {
        String s = Build.VERSION.SDK_INT >= 21 ? p.loadLabel(pm) : p.label;
        if (s == null || s.trim().length() == 0) s = p.provider.getShortClassName();
        return s;
    }

    @SuppressWarnings("NewApi") // вызывается только при SDK_INT >= 16
    private void bindWidget(AppWidgetProviderInfo info) {
        pendingWidgetId = host.allocateAppWidgetId();
        if (awm.bindAppWidgetIdIfAllowed(pendingWidgetId, info.provider)) {
            configureOrAdd(pendingWidgetId);
            return;
        }
        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId);
        bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
        try {
            startActivityForResult(bind, REQ_BIND);
        } catch (ActivityNotFoundException e) {
            discardPendingWidget();
            Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void configureOrAdd(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            try {
                if (Build.VERSION.SDK_INT >= 16) {
                    host.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null);
                } else {
                    Intent c = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
                    c.setComponent(info.configure);
                    c.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    startActivityForResult(c, REQ_CONFIG);
                }
                return;
            } catch (ActivityNotFoundException e) {
                // настройка недоступна — добавляем как есть
            } catch (SecurityException e) {
                // то же
            }
        }
        addWidget(id);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK) {
            int id = data != null
                    ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) : pendingWidgetId;
            if (res == RESULT_OK && id != -1) {
                pendingWidgetId = id;
                configureOrAdd(id);
            } else {
                discardPendingWidget();
            }
        } else if (req == REQ_BIND) {
            if (res == RESULT_OK && pendingWidgetId != -1) configureOrAdd(pendingWidgetId);
            else discardPendingWidget();
        } else if (req == REQ_CONFIG) {
            if (res == RESULT_OK && pendingWidgetId != -1) addWidget(pendingWidgetId);
            else discardPendingWidget();
        }
    }

    private void addWidget(int id) {
        pendingWidgetId = -1;
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info == null) {
            host.deleteAppWidgetId(id);
            return;
        }
        CellLayout ref = workspace.getPage(pendingPage);
        int sx = 1, sy = 1;
        if (ref.cellW > 1 && ref.cellH > 1) {
            sx = Math.max(1, Math.min(cols, (int) Math.ceil((info.minWidth - U.dp(this, 8)) / (double) ref.cellW)));
            sy = Math.max(1, Math.min(rows, (int) Math.ceil((info.minHeight - U.dp(this, 8)) / (double) ref.cellH)));
        }
        int page = -1;
        for (int k = 0; k < Workspace.PAGES; k++) {
            int p = (pendingPage + k) % Workspace.PAGES;
            if (workspace.getPage(p).findSpot(sx, sy, spot)) {
                page = p;
                break;
            }
        }
        if (page < 0) {
            host.deleteAppWidgetId(id);
            Toast.makeText(this, R.string.no_space, Toast.LENGTH_SHORT).show();
            return;
        }
        ItemInfo it = new ItemInfo(ItemInfo.WIDGET);
        it.appWidgetId = id;
        View v = createWidgetView(it, info);
        workspace.getPage(page).addItem(v, spot[0], spot[1], sx, sy);
        workspace.snapToPage(page, true);
        saveAll();
        showFrame(v);
    }
}
