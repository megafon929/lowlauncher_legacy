package com.m929.lowlauncher;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.InputStream;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Список установленных приложений (через PackageManager) и кэш иконок нужного размера. */
@SuppressWarnings("deprecation")
final class LauncherModel {

    static final class Apps {
        final ArrayList<AppInfo> list = new ArrayList<AppInfo>();
        final HashMap<ComponentName, AppInfo> map = new HashMap<ComponentName, AppInfo>();
        /** Активности без LAUNCHER-категории (например, звонилка по умолчанию). Только UI-поток. */
        final HashMap<ComponentName, AppInfo> extra = new HashMap<ComponentName, AppInfo>();
        /** true, если у какого-то из прежних приложений пришлось перечитать значок (обновление). */
        boolean changed;

        AppInfo get(ComponentName cn) {
            AppInfo a = map.get(cn);
            return a != null ? a : extra.get(cn);
        }
    }

    private final Context ctx;
    private final PackageManager pm;
    private final int iconPx;
    private final int iconDensity;

    LauncherModel(Context c) {
        ctx = c.getApplicationContext();
        pm = ctx.getPackageManager();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        int px = am.getLauncherLargeIconSize();
        int den = am.getLauncherLargeIconDensity();
        iconPx = px > 0 ? px : U.dp(ctx, 48);
        iconDensity = den > 0 ? den : ctx.getResources().getDisplayMetrics().densityDpi;
    }

    // ---- наборы иконок (ADW / Nova / Apex / Go формат appfilter.xml) --------------------------

    private static final String[] PACK_ACTIONS = {
            "org.adw.launcher.THEMES", "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME", "com.gau.go.launcherex.theme"
    };

    private String packPkg;
    private IconPackData pack;
    private boolean packLoaded;

    synchronized void setPack(String pkg) {
        packPkg = pkg;
        pack = null;
        packLoaded = false;
    }

    private synchronized IconPackData pack() {
        if (!packLoaded) {
            packLoaded = true;
            pack = packPkg == null ? null : IconPackData.load(pm, packPkg);
        }
        return pack;
    }

    /** Установленные наборы иконок: {пакет, название}. */
    ArrayList<String[]> findIconPacks() {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        for (int i = 0; i < PACK_ACTIONS.length; i++) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(PACK_ACTIONS[i]), 0);
            for (int k = 0; k < l.size(); k++) {
                ActivityInfo ai = l.get(k).activityInfo;
                if (ai == null || out.containsKey(ai.packageName)) continue;
                out.put(ai.packageName, String.valueOf(ai.applicationInfo.loadLabel(pm)));
            }
        }
        ArrayList<String[]> r = new ArrayList<String[]>();
        for (String k : out.keySet()) r.add(new String[]{k, out.get(k)});
        return r;
    }

    String packLabel(String pkg) {
        if (pkg == null) return null;
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static final class IconPackData {
        final Resources res;
        final String pkg;
        final HashMap<String, String> items = new HashMap<String, String>();
        final ArrayList<String> backs = new ArrayList<String>();
        String mask, upon;
        float scale = 1f;

        IconPackData(Resources r, String p) {
            res = r;
            pkg = p;
        }

        static IconPackData load(PackageManager pm, String pkg) {
            XmlPullParser xpp = null;
            InputStream in = null;
            try {
                Resources res = pm.getResourcesForApplication(pkg);
                int id = res.getIdentifier("appfilter", "xml", pkg);
                if (id != 0) {
                    xpp = res.getXml(id);
                } else {
                    try {
                        in = res.getAssets().open("appfilter.xml");
                        xpp = Xml.newPullParser();
                        xpp.setInput(in, "utf-8");
                    } catch (java.io.IOException e) {
                        xpp = null;
                    }
                }
                if (xpp == null) return null;
                IconPackData d = new IconPackData(res, pkg);
                int ev = xpp.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) d.parseTag(xpp);
                    ev = xpp.next();
                }
                return d;
            } catch (Exception e) {
                return null;
            } finally {
                // освобождаем разобранный XML и поток assets (раньше они оставались открытыми до сборки мусора)
                if (xpp instanceof android.content.res.XmlResourceParser) {
                    ((android.content.res.XmlResourceParser) xpp).close();
                }
                if (in != null) {
                    try {
                        in.close();
                    } catch (java.io.IOException e) {
                        // не критично
                    }
                }
            }
        }

        private void parseTag(XmlPullParser x) {
            String n = x.getName();
            if ("item".equals(n)) {
                String comp = x.getAttributeValue(null, "component");
                String dr = x.getAttributeValue(null, "drawable");
                if (comp == null || dr == null) return;
                int a = comp.indexOf('{'), b = comp.lastIndexOf('}');
                if (a >= 0 && b > a) comp = comp.substring(a + 1, b);
                ComponentName cn = ComponentName.unflattenFromString(comp);
                if (cn != null) items.put(cn.flattenToString(), dr);
            } else if ("iconback".equals(n)) {
                for (int i = 0; i < x.getAttributeCount(); i++) {
                    if (x.getAttributeName(i).startsWith("img")) backs.add(x.getAttributeValue(i));
                }
            } else if ("iconmask".equals(n)) {
                mask = firstImg(x);
            } else if ("iconupon".equals(n)) {
                upon = firstImg(x);
            } else if ("scale".equals(n)) {
                try {
                    scale = Float.parseFloat(x.getAttributeValue(null, "factor"));
                } catch (Exception e) {
                    scale = 1f;
                }
            }
        }

        private static String firstImg(XmlPullParser x) {
            for (int i = 0; i < x.getAttributeCount(); i++) {
                if (x.getAttributeName(i).startsWith("img")) return x.getAttributeValue(i);
            }
            return null;
        }

        Drawable drawable(String name, int density) {
            if (name == null) return null;
            int id = res.getIdentifier(name, "drawable", pkg);
            if (id == 0) return null;
            try {
                return res.getDrawableForDensity(id, density);
            } catch (Exception e) {
                try {
                    return res.getDrawable(id);
                } catch (Exception e2) {
                    return null;
                }
            }
        }

        Drawable iconFor(ComponentName cn, int density) {
            return drawable(items.get(cn.flattenToString()), density);
        }

        boolean hasFallback() {
            return !backs.isEmpty() || mask != null || upon != null;
        }

        /** Для приложений без своей иконки в наборе: фон + уменьшенная иконка (с маской) + накладка. */
        Drawable compose(Drawable orig, int px, int density) {
            Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Rect full = new Rect(0, 0, px, px);
            if (!backs.isEmpty()) drawInto(c, drawable(backs.get(0), density), full, null);
            Bitmap layer = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas lc = new Canvas(layer);
            int inset = (int) (px * (1f - scale) / 2f);
            orig.setBounds(inset, inset, px - inset, px - inset);
            orig.draw(lc);
            if (mask != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                drawInto(lc, drawable(mask, density), full, p);
            }
            c.drawBitmap(layer, 0, 0, null);
            if (upon != null) drawInto(c, drawable(upon, density), full, null);
            return new BitmapDrawable(res, out);
        }

        private static void drawInto(Canvas c, Drawable d, Rect r, Paint p) {
            if (d instanceof BitmapDrawable) {
                c.drawBitmap(((BitmapDrawable) d).getBitmap(), null, r, p);
            } else if (d != null) {
                d.setBounds(r);
                d.draw(c);
            }
        }
    }

    int iconSize() {
        return iconPx;
    }

    /** Вызывать в фоновом потоке. Уже загруженные иконки берутся из old. */
    Apps load(Apps old) {
        Apps out = new Apps();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        String self = ctx.getPackageName();
        for (int i = 0; i < list.size(); i++) {
            ActivityInfo ai = list.get(i).activityInfo;
            if (ai == null || self.equals(ai.packageName)) continue;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            if (out.map.containsKey(cn)) continue;
            AppInfo a = old != null ? old.map.get(cn) : null;
            if (a != null && a.stamp != stampOf(ai)) {
                a = null;
                out.changed = true;
            }
            if (a == null) a = build(ai);
            out.map.put(cn, a);
            out.list.add(a);
        }
        Collections.sort(out.list, new Comparator<AppInfo>() {
            public int compare(AppInfo a, AppInfo b) {
                return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
            }
        });
        return out;
    }

    AppInfo buildExtra(ComponentName cn) {
        try {
            return build(pm.getActivityInfo(cn, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Первая подходящая активность для intent, которой нет в used и которая известна лаунчеру. */
    ComponentName resolveFirst(Intent intent, HashSet<ComponentName> used, Apps apps) {
        List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
        for (int i = 0; i < list.size(); i++) {
            ActivityInfo ai = list.get(i).activityInfo;
            if (ai == null) continue;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            if (used.contains(cn)) continue;
            if (apps.get(cn) == null) {
                AppInfo a = buildExtra(cn);
                if (a == null) continue;
                apps.extra.put(cn, a);
            }
            return cn;
        }
        return null;
    }

    private static long stampOf(ActivityInfo ai) {
        try {
            String dir = ai.applicationInfo != null ? ai.applicationInfo.sourceDir : null;
            if (dir == null) return 0;
            return dir.hashCode() * 31L + new java.io.File(dir).lastModified();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private AppInfo build(ActivityInfo ai) {
        AppInfo a = new AppInfo();
        a.stamp = stampOf(ai);
        a.component = new ComponentName(ai.packageName, ai.name);
        CharSequence l = ai.loadLabel(pm);
        a.label = l != null ? l.toString() : ai.packageName;
        a.icon = loadIcon(ai);
        return a;
    }

    private Drawable loadIcon(ActivityInfo ai) {
        IconPackData ip = pack();
        if (ip != null) {
            try {
                Drawable themed = ip.iconFor(new ComponentName(ai.packageName, ai.name), iconDensity);
                if (themed != null) return toIcon(themed);
            } catch (OutOfMemoryError e) {
                // используем обычную иконку
            }
        }
        Drawable d = null;
        try {
            int res = ai.getIconResource();
            if (res != 0) {
                Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                d = r.getDrawableForDensity(res, iconDensity);
            }
        } catch (Exception e) {
            d = null;
        } catch (OutOfMemoryError e) {
            d = null;
        }
        try {
            if (d == null) d = ai.loadIcon(pm);
            if (d == null) d = pm.getDefaultActivityIcon();
            if (ip != null && ip.hasFallback()) d = ip.compose(d, iconPx, iconDensity);
            return toIcon(d);
        } catch (OutOfMemoryError e) {
            return pm.getDefaultActivityIcon();
        }
    }

    /** Приводит иконку к единому размеру: быстрее рисуется и меньше памяти. */
    private Drawable toIcon(Drawable d) {
        Bitmap b = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, iconPx, iconPx);
        d.draw(c);
        BitmapDrawable bd = new BitmapDrawable(ctx.getResources(), b);
        bd.setBounds(0, 0, iconPx, iconPx);
        return bd;
    }
}
