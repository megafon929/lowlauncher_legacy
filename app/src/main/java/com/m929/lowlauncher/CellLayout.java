package com.m929.lowlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/** Одна страница рабочего стола: сетка cols x rows. Положение детей задаётся в ячейках. */
@SuppressWarnings("deprecation")
final class CellLayout extends ViewGroup {

    static final class LP extends ViewGroup.LayoutParams {
        int cellX, cellY, spanX, spanY;

        LP(int cx, int cy, int sx, int sy) {
            super(WRAP_CONTENT, WRAP_CONTENT);
            cellX = cx;
            cellY = cy;
            spanX = sx;
            spanY = sy;
        }
    }

    final int cols, rows;
    int cellW, cellH, padL, padT;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float radius, inset;
    private boolean hl, hlValid;
    private int hx, hy, hsx, hsy;
    private ResizeFrame frame;

    CellLayout(Context c, int cols, int rows) {
        super(c);
        this.cols = cols;
        this.rows = rows;
        radius = U.dp(c, 8);
        inset = U.dp(c, 3);
        setWillNotDraw(false);
        setClickable(true);
        setLongClickable(true);
        setPadding(U.dp(c, 4), U.dp(c, 8), U.dp(c, 4), U.dp(c, 4));
    }

    // ---- LayoutParams -------------------------------------------------------------------------

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LP;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LP(0, 0, 1, 1);
    }

    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return new LP(0, 0, 1, 1);
    }

    // ---- измерение и размещение ---------------------------------------------------------------

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        padL = getPaddingLeft();
        padT = getPaddingTop();
        cellW = Math.max(1, (w - padL - getPaddingRight()) / cols);
        cellH = Math.max(1, (h - padT - getPaddingBottom()) / rows);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c == frame) {
                LP t = (LP) frame.target.getLayoutParams();
                int m = frame.margin;
                c.measure(MeasureSpec.makeMeasureSpec(t.spanX * cellW + 2 * m, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(t.spanY * cellH + 2 * m, MeasureSpec.EXACTLY));
            } else {
                LP lp = (LP) c.getLayoutParams();
                c.measure(MeasureSpec.makeMeasureSpec(lp.spanX * cellW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(lp.spanY * cellH, MeasureSpec.EXACTLY));
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c == frame) {
                LP tl = (LP) frame.target.getLayoutParams();
                int x = padL + tl.cellX * cellW - frame.margin;
                int y = padT + tl.cellY * cellH - frame.margin;
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
            } else {
                LP lp = (LP) c.getLayoutParams();
                int x = padL + lp.cellX * cellW;
                int y = padT + lp.cellY * cellH;
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
            }
        }
    }

    // ---- работа с ячейками --------------------------------------------------------------------

    void addItem(View v, int x, int y, int sx, int sy) {
        addView(v, new LP(x, y, sx, sy));
    }

    boolean isFree(int x, int y, int sx, int sy, View ignore) {
        if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return false;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c == ignore || c == frame) continue;
            LP lp = (LP) c.getLayoutParams();
            if (x < lp.cellX + lp.spanX && lp.cellX < x + sx && y < lp.cellY + lp.spanY && lp.cellY < y + sy) {
                return false;
            }
        }
        return true;
    }

    /**
     * Ищет ближайшее свободное место для блока sx*sy, чей центр должен оказаться около точки
     * (cx, cy) в координатах этой страницы. Результат — в out[0], out[1].
     */
    boolean nearestFree(float cx, float cy, int sx, int sy, View ignore, int[] out) {
        if (sx > cols || sy > rows) return false;
        float fx = (cx - padL) / cellW - sx / 2f;
        float fy = (cy - padT) / cellH - sy / 2f;
        float best = Float.MAX_VALUE;
        boolean found = false;
        for (int y = 0; y <= rows - sy; y++) {
            for (int x = 0; x <= cols - sx; x++) {
                if (!isFree(x, y, sx, sy, ignore)) continue;
                float d = (x - fx) * (x - fx) + (y - fy) * (y - fy);
                if (d < best) {
                    best = d;
                    out[0] = x;
                    out[1] = y;
                    found = true;
                }
            }
        }
        return found;
    }

    /** Первое свободное место (по строкам). */
    boolean findSpot(int sx, int sy, int[] out) {
        for (int y = 0; y <= rows - sy; y++) {
            for (int x = 0; x <= cols - sx; x++) {
                if (isFree(x, y, sx, sy, null)) {
                    out[0] = x;
                    out[1] = y;
                    return true;
                }
            }
        }
        return false;
    }

    // ---- подсветка цели при перетаскивании ----------------------------------------------------

    void showHighlight(int x, int y, int sx, int sy) {
        hl = true;
        hx = x;
        hy = y;
        hsx = sx;
        hsy = sy;
        invalidate();
    }

    void hideHighlight() {
        if (hl) {
            hl = false;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!hl) return;
        rect.set(padL + hx * cellW + inset, padT + hy * cellH + inset,
                padL + (hx + hsx) * cellW - inset, padT + (hy + hsy) * cellH - inset);
        paint.setColor(0x55FFFFFF);
        c.drawRoundRect(rect, radius, radius, paint);
    }

    // ---- рамка изменения размера виджета ------------------------------------------------------

    void showFrame(View target, boolean canH, boolean canV, ResizeFrame.Callback cb) {
        removeFrame();
        frame = new ResizeFrame(getContext(), this, target, canH, canV, cb);
        addView(frame, new LP(0, 0, 1, 1));
    }

    void removeFrame() {
        if (frame != null) {
            removeView(frame);
            frame = null;
        }
    }

    ResizeFrame getFrame() {
        return frame;
    }
}
