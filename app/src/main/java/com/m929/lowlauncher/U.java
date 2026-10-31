package com.m929.lowlauncher;

import android.content.Context;
import android.view.View;

@SuppressWarnings("deprecation")
final class U {
    private U() {}

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Аппаратный слой на время анимации/прокрутки: view рисуется в текстуру GPU и двигается без
     * перерисовки содержимого. Вне анимации слой снимается, чтобы не держать память.
     */
    static void layer(View v, boolean on) {
        v.setLayerType(on && v.isHardwareAccelerated() ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
    }
}
