package com.m929.lowlauncher;

import android.content.ComponentName;

/** Описание элемента: ярлык приложения или виджет. Положение берётся из LayoutParams при сохранении. */
@SuppressWarnings("deprecation")
final class ItemInfo {
    static final int APP = 0;
    static final int WIDGET = 1;

    final int type;
    ComponentName component;
    int appWidgetId = -1;
    int page, cellX, cellY, spanX = 1, spanY = 1;
    int dockSlot = -1;

    ItemInfo(int type) {
        this.type = type;
    }
}
