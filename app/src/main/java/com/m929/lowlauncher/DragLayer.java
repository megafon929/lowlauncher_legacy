package com.m929.lowlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Корневой слой. Отвечает за:
 *  - свайп снизу вверх / сверху вниз для меню приложений (из любой области экрана, включая Dock);
 *  - перетаскивание значков и виджетов (Drag & Drop) между рабочим столом, Dock и меню.
 */
@SuppressWarnings("deprecation")
final class DragLayer extends FrameLayout {
    static final int SRC_WORKSPACE = 0, SRC_DOCK = 1, SRC_DRAWER = 2, SRC_FOLDER = 3;

    static final class DragState {
        ItemInfo info;
        View view;
        int source;
        int spanX = 1, spanY = 1;
        float offX, offY;
        /** Для SRC_FOLDER: папка, из которой вынимают значок. */
        View folderView;
    }

    private static final int ST_CLOSED = 0, ST_OPEN = 1, ST_BUSY = 2;

    private Launcher l;
    private Workspace ws;
    private Dock dock;
    private AppDrawer drawer;
    private View bar;
    private TextView aboutTv, removeTv;

    private final int slop, flingV, edgeW, trashH, thumbZone;
    private final Handler h = new Handler();
    private final int[] tmp = new int[2], rootLoc = new int[2], cell = new int[2];

    // жесты меню
    private int drawerState = ST_CLOSED;
    private int animGen;
    private float downX, downY, downRawY;
    private boolean candidate, swiping, swallow;
    private float swipeStartTrans;
    private VelocityTracker vt;
    private float rawX, rawY;

    // drag & drop
    private DragState drag;
    private ImageView dragView;
    private int dragW, dragH;
    private boolean moved;
    private float dragDownX, dragDownY, lastX, lastY;
    private int dockSlot = -1;
    private CellLayout targetPage;
    private boolean haveCell, overTrash, overAbout;
    private View folderTarget;
    private int edgeDir;

    private final Runnable edgeRunnable = new Runnable() {
        public void run() {
            if (drag == null || edgeDir == 0) return;
            int p = ws.getCurrentPage() + edgeDir;
            edgeDir = 0;
            if (p >= 0 && p < ws.getPageCount()) {
                ws.snapToPage(p, true);
                h.postDelayed(refreshRunnable, 450);
            }
        }
    };
    private final Runnable refreshRunnable = new Runnable() {
        public void run() {
            if (drag != null) updateDrag(lastX, lastY);
        }
    };

    DragLayer(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        flingV = U.dp(c, 400);
        edgeW = U.dp(c, 28);
        trashH = U.dp(c, 56);
        thumbZone = U.dp(c, 44);
    }

    void attach(Launcher l, Workspace ws, Dock dock, AppDrawer drawer, View bar, TextView aboutTv, TextView removeTv) {
        this.l = l;
        this.ws = ws;
        this.dock = dock;
        this.drawer = drawer;
        this.bar = bar;
        this.aboutTv = aboutTv;
        this.removeTv = removeTv;
    }

    // =========================================================================================
    //  Меню приложений
    // =========================================================================================

    private View content() {
        return getChildAt(0);
    }

    /** Рабочий стол плавно гаснет под полупрозрачным меню; в открытом состоянии не рисуется. */
    private void applyProgress(float tr) {
        float open = 1f - tr / Math.max(1, getHeight());
        View c = content();
        c.setAlpha(1f - open);
        c.setVisibility(open >= 0.999f ? INVISIBLE : VISIBLE);
    }

    boolean isDrawerOpen() {
        return drawerState != ST_CLOSED;
    }

    void openDrawer() {
        if (drag != null || swiping) return;
        if (drawerState == ST_CLOSED) {
            drawer.scrollToTop();
            drawer.setVisibility(VISIBLE);
            drawer.setTranslationY(getHeight());
            applyProgress(getHeight());
        }
        animateDrawer(true);
    }

    void closeDrawer(boolean animate) {
        if (drawerState == ST_CLOSED) return;
        if (animate) {
            animateDrawer(false);
        } else {
            animGen++;
            drawer.animate().cancel();
            content().animate().cancel(); // иначе недоигравшая анимация сделает рабочий стол прозрачным
            drawer.setVisibility(GONE);
            drawer.setTranslationY(getHeight());
            applyProgress(getHeight());
            drawer.reset();
            U.layer(drawer, false);
            U.layer(content(), false);
            drawerState = ST_CLOSED;
        }
    }

    /** Доигрывает анимацию меню мгновенно (например, после возврата из другого окна). */
    void settleDrawer() {
        if (drawerState != ST_BUSY) return;
        animGen++;
        drawer.animate().cancel();
        content().animate().cancel();
        if (animOpening) {
            drawer.setVisibility(VISIBLE);
            drawer.setTranslationY(0);
            drawerState = ST_OPEN;
            applyProgress(0);
        } else {
            drawer.setVisibility(GONE);
            drawer.setTranslationY(getHeight());
            drawer.reset();
            drawerState = ST_CLOSED;
            applyProgress(getHeight());
        }
        U.layer(drawer, false);
        U.layer(content(), false);
    }

    private boolean animOpening;

    private void animateDrawer(final boolean open) {
        final int gen = ++animGen;
        animOpening = open;
        drawer.animate().cancel();
        drawerState = ST_BUSY;
        U.layer(drawer, true);
        U.layer(content(), true);
        float target = open ? 0f : getHeight();
        float dist = Math.abs(drawer.getTranslationY() - target);
        long dur = 90 + (long) (210f * dist / Math.max(1, getHeight()));
        content().setVisibility(VISIBLE);
        content().animate().cancel();
        content().animate().alpha(open ? 0f : 1f).setDuration(dur).setListener(null);
        drawer.animate().translationY(target).setDuration(dur)
                .setInterpolator(new DecelerateInterpolator(1.3f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        if (gen != animGen) return;
                        drawerState = open ? ST_OPEN : ST_CLOSED;
                        U.layer(drawer, false);
                        U.layer(content(), false);
                        if (!open) {
                            drawer.setVisibility(GONE);
                            drawer.reset();
                        }
                        applyProgress(open ? 0 : getHeight());
                    }
                });
    }

    /** Свайп возможен, если меню не анимируется и касание не у полосы быстрой прокрутки списка. */
    private boolean canStartSwipe(MotionEvent ev) {
        if (drawerState == ST_BUSY || l.isFolderOpen()) return false;
        return !(drawerState == ST_OPEN && ev.getX() > getWidth() - thumbZone);
    }

    private boolean checkSwipeStart(MotionEvent ev) {
        float dx = ev.getX() - downX, dy = ev.getY() - downY;
        float ax = Math.abs(dx), ay = Math.abs(dy);
        if (ax > slop && ax >= ay) {
            candidate = false; // горизонтальный жест — листание страниц
            return false;
        }
        if (ay > slop && ay > ax) {
            if (drawerState == ST_CLOSED && dy > 0 && !l.isFrameActive()) {
                candidate = false;
                l.expandNotifications(); // свайп вниз по рабочему столу — открыть шторку уведомлений
                return false;
            }
            boolean start = false;
            if (drawerState == ST_CLOSED && dy < 0 && !l.isFrameActive()) start = true;
            else if (drawerState == ST_OPEN && dy > 0 && drawer.atTop()) start = true;
            if (start) {
                beginSwipe(ev);
                return true;
            }
            candidate = false;
        }
        return false;
    }

    private void beginSwipe(MotionEvent ev) {
        swiping = true;
        candidate = false;
        animGen++;
        drawer.animate().cancel();
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        if (drawerState == ST_CLOSED) {
            drawer.scrollToTop();
            swipeStartTrans = getHeight();
        } else {
            swipeStartTrans = 0;
        }
        content().animate().cancel();
        U.layer(drawer, true);
        U.layer(content(), true);
        drawer.setVisibility(VISIBLE);
        drawer.setTranslationY(swipeStartTrans);
        applyProgress(swipeStartTrans);
        drawerState = ST_BUSY;
    }

    private void handleSwipe(MotionEvent ev) {
        vt.addMovement(ev);
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE: {
                float tr = swipeStartTrans + (ev.getRawY() - downRawY);
                tr = tr < 0 ? 0 : (tr > getHeight() ? getHeight() : tr);
                drawer.setTranslationY(tr);
                applyProgress(tr);
                break;
            }
            case MotionEvent.ACTION_UP:
                vt.computeCurrentVelocity(1000);
                finishSwipe(vt.getYVelocity());
                break;
            case MotionEvent.ACTION_CANCEL:
                finishSwipe(0);
                break;
        }
    }

    private void finishSwipe(float vy) {
        swiping = false;
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
        boolean open;
        if (vy < -flingV) open = true;
        else if (vy > flingV) open = false;
        else open = drawer.getTranslationY() < getHeight() / 2f;
        animateDrawer(open);
    }

    // =========================================================================================
    //  Перехват касаний
    // =========================================================================================

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        rawX = ev.getRawX();
        rawY = ev.getRawY();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int a = ev.getAction() & MotionEvent.ACTION_MASK;
        if (drag != null) {
            handleDrag(ev);
            return true;
        }
        if (swiping) {
            handleSwipe(ev);
            return true;
        }
        if (swallow) return true;
        if (a == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            downRawY = ev.getRawY();
            candidate = canStartSwipe(ev);
            if (drawerState == ST_CLOSED && l.isFrameActive() && !l.isOnFrame(ev.getRawX(), ev.getRawY())) {
                l.dismissFrame();
                swallow = true;
                return true;
            }
        } else if (a == MotionEvent.ACTION_MOVE) {
            if (candidate && checkSwipeStart(ev)) return true;
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            candidate = false;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int a = ev.getAction() & MotionEvent.ACTION_MASK;
        if (drag != null) return handleDrag(ev);
        if (swiping) {
            handleSwipe(ev);
            return true;
        }
        if (swallow) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallow = false;
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            downRawY = ev.getRawY();
            candidate = canStartSwipe(ev);
        } else if (a == MotionEvent.ACTION_MOVE) {
            if (candidate) checkSwipeStart(ev);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            candidate = false;
        }
        return true;
    }

    // =========================================================================================
    //  Drag & Drop
    // =========================================================================================

    boolean isDragging() {
        return drag != null;
    }

    void cancelDrag() {
        if (drag != null) endDrag(false);
    }

    /** Начать перетаскивание; палец уже лежит на экране (вызывается из long click). */
    boolean startDrag(View v, DragState d) {
        if (drag != null || swiping || l == null) return false;
        getLocationOnScreen(rootLoc);
        Bitmap bmp = snapshot(v);
        dragW = bmp.getWidth();
        dragH = bmp.getHeight();
        if (d.source == SRC_WORKSPACE && d.info.type == ItemInfo.WIDGET) {
            v.getLocationOnScreen(tmp);
            d.offX = rawX - tmp[0];
            d.offY = rawY - tmp[1];
        } else {
            d.offX = dragW / 2f;
            d.offY = dragH / 2f;
        }
        drag = d;
        moved = false;
        dragDownX = rawX;
        dragDownY = rawY;
        dragView = new ImageView(getContext());
        dragView.setImageBitmap(bmp);
        dragView.setAlpha(0.85f);
        U.layer(dragView, true);
        addView(dragView, new LayoutParams(dragW, dragH));
        l.dismissFrame();
        aboutTv.setVisibility(d.info.type == ItemInfo.APP ? VISIBLE : GONE);
        removeTv.setVisibility(d.source != SRC_DRAWER ? VISIBLE : GONE);
        bar.setVisibility(VISIBLE);
        if (d.source != SRC_DRAWER) v.setVisibility(INVISIBLE);
        if (d.source == SRC_DRAWER) animateDrawer(false);
        updateDrag(rawX, rawY);
        return true;
    }

    private Bitmap snapshot(View v) {
        int w = Math.max(1, v.getWidth()), hh = Math.max(1, v.getHeight());
        try {
            v.setDrawingCacheEnabled(true);
            v.buildDrawingCache();
            Bitmap c = v.getDrawingCache();
            Bitmap b = c != null ? Bitmap.createBitmap(c) : null;
            v.destroyDrawingCache();
            v.setDrawingCacheEnabled(false);
            if (b != null) return b;
        } catch (OutOfMemoryError e) {
            // ниже — пустая заглушка
        }
        try {
            return Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    private boolean handleDrag(MotionEvent ev) {
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_MOVE:
                updateDrag(ev.getRawX(), ev.getRawY());
                break;
            case MotionEvent.ACTION_UP:
                updateDrag(ev.getRawX(), ev.getRawY());
                endDrag(true);
                break;
            case MotionEvent.ACTION_CANCEL:
                endDrag(false);
                break;
        }
        return true;
    }

    private void clearHover() {
        dock.highlight(-1);
        if (targetPage != null) targetPage.hideHighlight();
        targetPage = null;
        haveCell = false;
        dockSlot = -1;
        folderTarget = null;
        overTrash = false;
        overAbout = false;
    }

    private void updateDrag(float x, float y) {
        DragState d = drag;
        if (d == null) return;
        lastX = x;
        lastY = y;
        if (!moved && (Math.abs(x - dragDownX) > slop || Math.abs(y - dragDownY) > slop)) moved = true;
        dragView.setTranslationX(x - rootLoc[0] - d.offX);
        dragView.setTranslationY(y - rootLoc[1] - d.offY);
        clearHover();
        boolean edgeOk = false;
        boolean aboutOn = aboutTv.getVisibility() == VISIBLE;
        boolean removeOn = removeTv.getVisibility() == VISIBLE;
        if (y - rootLoc[1] < trashH && (aboutOn || removeOn)) {
            if (aboutOn && removeOn) overAbout = x < getWidth() / 2f;
            else overAbout = aboutOn;
            overTrash = !overAbout;
        } else if (dock.containsRawY(y)) {
            if (d.info.type == ItemInfo.APP) {
                dockSlot = dock.slotAt(x);
                dock.highlight(dockSlot);
            }
        } else {
            edgeOk = true;
            CellLayout cl = ws.currentPage();
            cl.getLocationOnScreen(tmp);
            float cx = x - d.offX + dragW / 2f - tmp[0];
            float cy = y - d.offY + dragH / 2f - tmp[1];
            // значок, вынимаемый из папки, не должен считать целью саму эту папку
            View skip = d.source == SRC_FOLDER && d.folderView != null ? d.folderView : d.view;
            View ft = d.info.type == ItemInfo.APP ? cl.folderTargetAt(cx, cy, skip, d.info.component) : null;
            if (ft != null) {
                // значок над значком/папкой: бросок создаст папку или добавит в неё
                folderTarget = ft;
                targetPage = cl;
                CellLayout.LP flp = (CellLayout.LP) ft.getLayoutParams();
                cl.showHighlight(flp.cellX, flp.cellY, 1, 1);
            } else if (cl.nearestFree(cx, cy, d.spanX, d.spanY, d.view, cell)) {
                targetPage = cl;
                haveCell = true;
                cl.showHighlight(cell[0], cell[1], d.spanX, d.spanY);
            }
        }
        removeTv.setBackgroundColor(overTrash ? 0xDDCC0000 : 0xAA000000);
        aboutTv.setBackgroundColor(overAbout ? 0xDD2266CC : 0xAA000000);

        int dir = 0;
        if (edgeOk) {
            if (x < edgeW) dir = -1;
            else if (x > getWidth() - edgeW) dir = 1;
        }
        if (dir != edgeDir) {
            h.removeCallbacks(edgeRunnable);
            edgeDir = dir;
            if (dir != 0) h.postDelayed(edgeRunnable, 600);
        }
    }

    private void endDrag(boolean drop) {
        DragState d = drag;
        if (d == null) return;
        boolean ok = false;
        boolean overAboutDrop = false;
        if (drop && !(d.source == SRC_DRAWER && !moved)) {
            if (overAbout) {
                overAboutDrop = true;
                l.showAppInfo(d.info.component);
            } else if (overTrash) {
                ok = l.removeDragged(d);
            } else if (dockSlot >= 0) {
                ok = l.dropOnDock(d, dockSlot);
            } else if (folderTarget != null) {
                ok = l.dropOnFolder(d, folderTarget);
            } else if (haveCell) {
                ok = l.dropOnWorkspace(d, targetPage, cell[0], cell[1]);
            }
        }
        drag = null;
        h.removeCallbacks(edgeRunnable);
        h.removeCallbacks(refreshRunnable);
        edgeDir = 0;
        if (dragView != null) {
            removeView(dragView);
            dragView = null;
        }
        bar.setVisibility(GONE);
        clearHover();
        if (!ok) {
            l.onDragCancelled(d);
            if (d.source == SRC_DRAWER) {
                animateDrawer(true);
                if (overAboutDrop) settleDrawer(); // открывается другое окно: без анимации
            }
        }
    }
}
