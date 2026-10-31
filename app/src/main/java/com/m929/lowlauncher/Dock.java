package com.m929.lowlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

/** Нижняя панель: строго 5 значков, равномерно по всей ширине экрана (вес 1 у каждого). */
@SuppressWarnings("deprecation")
final class Dock extends LinearLayout {
    static final int SLOTS = 5;

    private final IconView[] views = new IconView[SLOTS];
    private final ItemInfo[] infos = new ItemInfo[SLOTS];
    private final int[] loc = new int[2];

    Dock(Context c, View.OnClickListener click, View.OnLongClickListener longClick) {
        super(c);
        setOrientation(HORIZONTAL);
        setBackgroundColor(0x66000000);
        for (int i = 0; i < SLOTS; i++) {
            IconView v = new IconView(c, false);
            ItemInfo it = new ItemInfo(ItemInfo.APP);
            it.dockSlot = i;
            v.setTag(it);
            v.setOnClickListener(click);
            v.setOnLongClickListener(longClick);
            addView(v, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            views[i] = v;
            infos[i] = it;
        }
    }

    void set(int slot, AppInfo a) {
        infos[slot].component = a != null ? a.component : null;
        views[slot].bind(a);
    }

    ComponentName get(int slot) {
        return infos[slot].component;
    }

    ComponentName[] components() {
        ComponentName[] r = new ComponentName[SLOTS];
        for (int i = 0; i < SLOTS; i++) r[i] = infos[i].component;
        return r;
    }

    int slotAt(float rawX) {
        getLocationOnScreen(loc);
        int w = Math.max(1, getWidth());
        return U.clamp((int) ((rawX - loc[0]) * SLOTS / w), 0, SLOTS - 1);
    }

    boolean containsRawY(float rawY) {
        getLocationOnScreen(loc);
        return rawY >= loc[1];
    }

    void highlight(int slot) {
        for (int i = 0; i < SLOTS; i++) views[i].setSelected(i == slot);
    }
}
