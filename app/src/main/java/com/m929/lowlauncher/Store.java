package com.m929.lowlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;

/** Хранение раскладки в SharedPreferences (простой текстовый формат). */
@SuppressWarnings("deprecation")
final class Store {
    private static final String KEY_ITEMS = "items";
    private static final String KEY_DOCK = "dock";

    private final SharedPreferences sp;

    Store(Context c) {
        sp = c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    int getInt(String k, int def) {
        return sp.getInt(k, def);
    }

    void putInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    String getString(String k, String def) {
        return sp.getString(k, def);
    }

    void putString(String k, String v) {
        if (v == null) sp.edit().remove(k).apply();
        else sp.edit().putString(k, v).apply();
    }

    boolean isInitialized() {
        return sp.contains(KEY_DOCK);
    }

    ArrayList<ItemInfo> loadItems() {
        ArrayList<ItemInfo> out = new ArrayList<ItemInfo>();
        String s = sp.getString(KEY_ITEMS, "");
        if (s.length() == 0) return out;
        String[] lines = s.split("\n");
        for (int i = 0; i < lines.length; i++) {
            try {
                String[] f = lines[i].split("\\|");
                if (f[0].equals("A")) {
                    ItemInfo it = new ItemInfo(ItemInfo.APP);
                    it.page = Integer.parseInt(f[1]);
                    it.cellX = Integer.parseInt(f[2]);
                    it.cellY = Integer.parseInt(f[3]);
                    it.component = ComponentName.unflattenFromString(f[4]);
                    if (it.component != null) out.add(it);
                } else if (f[0].equals("W")) {
                    ItemInfo it = new ItemInfo(ItemInfo.WIDGET);
                    it.page = Integer.parseInt(f[1]);
                    it.cellX = Integer.parseInt(f[2]);
                    it.cellY = Integer.parseInt(f[3]);
                    it.spanX = Integer.parseInt(f[4]);
                    it.spanY = Integer.parseInt(f[5]);
                    it.appWidgetId = Integer.parseInt(f[6]);
                    out.add(it);
                }
            } catch (RuntimeException e) {
                // битая строка — пропускаем
            }
        }
        return out;
    }

    ComponentName[] loadDock() {
        ComponentName[] r = new ComponentName[Dock.SLOTS];
        String s = sp.getString(KEY_DOCK, "");
        String[] f = s.split("\n", -1);
        for (int i = 0; i < r.length && i < f.length; i++) {
            if (f[i].length() > 0) r[i] = ComponentName.unflattenFromString(f[i]);
        }
        return r;
    }

    void save(ArrayList<ItemInfo> items, ComponentName[] dock) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            ItemInfo it = items.get(i);
            if (it.type == ItemInfo.APP) {
                sb.append("A|").append(it.page).append('|').append(it.cellX).append('|')
                        .append(it.cellY).append('|').append(it.component.flattenToString());
            } else {
                sb.append("W|").append(it.page).append('|').append(it.cellX).append('|')
                        .append(it.cellY).append('|').append(it.spanX).append('|')
                        .append(it.spanY).append('|').append(it.appWidgetId);
            }
            sb.append('\n');
        }
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < dock.length; i++) {
            if (i > 0) d.append('\n');
            if (dock[i] != null) d.append(dock[i].flattenToString());
        }
        sp.edit().putString(KEY_ITEMS, sb.toString()).putString(KEY_DOCK, d.toString()).apply();
    }
}
