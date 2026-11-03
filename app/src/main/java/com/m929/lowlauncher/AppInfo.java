package com.m929.lowlauncher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

@SuppressWarnings("deprecation")
final class AppInfo {
    String label;
    ComponentName component;
    Drawable icon;
    /** Отпечаток APK: при обновлении приложения меняется, и значок/название перечитываются. */
    long stamp;
}
