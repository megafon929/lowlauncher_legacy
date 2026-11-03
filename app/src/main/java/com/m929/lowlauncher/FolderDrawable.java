package com.m929.lowlauncher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;

/** Значок папки: скруглённая подложка и до четырёх уменьшенных значков внутри (сетка 2x2). */
@SuppressWarnings("deprecation")
final class FolderDrawable extends Drawable {
    private final ArrayList<Drawable> icons;
    private final int size;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    FolderDrawable(ArrayList<Drawable> src, int size) {
        // Копии значков: общие drawable имеют общие границы и callback — менять их при рисовании нельзя.
        this.icons = new ArrayList<Drawable>(src.size());
        for (int i = 0; i < src.size(); i++) {
            Drawable d = src.get(i);
            Drawable.ConstantState cs = d != null ? d.getConstantState() : null;
            icons.add(cs != null ? cs.newDrawable() : null);
        }
        this.size = size;
        setBounds(0, 0, size, size);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        paint.setColor(0x66FFFFFF);
        rect.set(b.left, b.top, b.left + s, b.top + s);
        c.drawRoundRect(rect, s * 0.22f, s * 0.22f, paint);
        float pad = s * 0.12f, gap = s * 0.06f;
        float cell = (s - 2 * pad - gap) / 2f;
        int n = Math.min(4, icons.size());
        for (int i = 0; i < n; i++) {
            Drawable d = icons.get(i);
            if (d == null) continue;
            int x = (int) (b.left + pad + (i % 2) * (cell + gap));
            int y = (int) (b.top + pad + (i / 2) * (cell + gap));
            d.setBounds(x, y, (int) (x + cell), (int) (y + cell));
            d.draw(c);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int a) {
    }

    @Override
    public void setColorFilter(ColorFilter f) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
