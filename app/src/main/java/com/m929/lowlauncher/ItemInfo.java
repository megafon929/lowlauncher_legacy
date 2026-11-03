package com.m929.lowlauncher;

import android.content.ComponentName;

import java.util.ArrayList;

/** Описание элемента: ярлык приложения, виджет или папка. Положение берётся из LayoutParams при сохранении. */
@SuppressWarnings("deprecation")
final class ItemInfo {
    static final int APP = 0;
    static final int WIDGET = 1;
    static final int FOLDER = 2;

    final int type;
    ComponentName component;
    int appWidgetId = -1;
    int page, cellX, cellY, spanX = 1, spanY = 1;
    int dockSlot = -1;
    /** Для папки: название и содержимое. */
    String title = "";
    final ArrayList<ComponentName> apps = new ArrayList<ComponentName>();

    ItemInfo(int type) {
        this.type = type;
    }
}
