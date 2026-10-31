package com.m929.lowlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Индикатор текущего рабочего стола. */
@SuppressWarnings("deprecation")
final class PageDots extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float r, gap;
    private int count = 1, current;

    PageDots(Context c) {
        super(c);
        r = U.dp(c, 3);
        gap = U.dp(c, 12);
    }

    void setCount(int n) {
        count = n;
        invalidate();
    }

    void setCurrent(int p) {
        current = p;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f;
        float x0 = getWidth() / 2f - (count - 1) * gap / 2f;
        for (int i = 0; i < count; i++) {
            boolean on = i == current;
            paint.setColor(on ? 0xFFFFFFFF : 0x66FFFFFF);
            c.drawCircle(x0 + i * gap, cy, on ? r * 1.25f : r, paint);
        }
    }
}
