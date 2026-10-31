package com.m929.lowlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Рамка вокруг виджета: ручки на сторонах меняют размер (с привязкой к ячейкам),
 * кружок в правом верхнем углу удаляет виджет.
 */
@SuppressWarnings("deprecation")
final class ResizeFrame extends View {

    interface Callback {
        void onResized();

        void onRemove(View target);
    }

    private static final int NONE = 0, LEFT = 1, RIGHT = 2, TOP = 3, BOTTOM = 4, DEL = 5;

    final View target;
    final int margin;

    private final CellLayout cl;
    private final boolean canH, canV;
    private final Callback cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float handleR, delR, hit, stroke, inner;
    private int active = NONE;

    ResizeFrame(Context c, CellLayout cl, View target, boolean canH, boolean canV, Callback cb) {
        super(c);
        this.cl = cl;
        this.target = target;
        this.canH = canH;
        this.canV = canV;
        this.cb = cb;
        margin = U.dp(c, 18);
        handleR = U.dp(c, 7);
        delR = U.dp(c, 11);
        hit = U.dp(c, 26);
        stroke = U.dp(c, 2);
        inner = U.dp(c, 6);
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = margin, t = margin, r = getWidth() - margin, b = getHeight() - margin;
        float cx = (l + r) / 2f, cy = (t + b) / 2f;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(0xFF33B5E5);
        c.drawRect(l, t, r, b, p);
        p.setStyle(Paint.Style.FILL);
        if (canH) {
            c.drawCircle(l, cy, handleR, p);
            c.drawCircle(r, cy, handleR, p);
        }
        if (canV) {
            c.drawCircle(cx, t, handleR, p);
            c.drawCircle(cx, b, handleR, p);
        }
        // кнопка удаления — внутри виджета, в правом верхнем углу
        float dx = r - delR - inner, dy = t + delR + inner;
        p.setColor(0xFFCC0000);
        c.drawCircle(dx, dy, delR, p);
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(stroke);
        float k = delR * 0.45f;
        c.drawLine(dx - k, dy - k, dx + k, dy + k, p);
        c.drawLine(dx - k, dy + k, dx + k, dy - k, p);
    }

    private static boolean near(float x, float y, float px, float py, float d) {
        float dx = x - px, dy = y - py;
        return dx * dx + dy * dy <= d * d;
    }

    private int hitTest(float x, float y) {
        float l = margin, t = margin, r = getWidth() - margin, b = getHeight() - margin;
        float cx = (l + r) / 2f, cy = (t + b) / 2f;
        if (near(x, y, r - delR - inner, t + delR + inner, hit)) return DEL;
        if (canH && near(x, y, l, cy, hit)) return LEFT;
        if (canH && near(x, y, r, cy, hit)) return RIGHT;
        if (canV && near(x, y, cx, t, hit)) return TOP;
        if (canV && near(x, y, cx, b, hit)) return BOTTOM;
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                active = hitTest(ev.getX(), ev.getY());
                if (active == NONE) return false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (active != NONE && active != DEL) resize(ev.getX() + getLeft(), ev.getY() + getTop());
                return true;
            case MotionEvent.ACTION_UP:
                int a = active;
                active = NONE;
                if (a == DEL) {
                    if (hitTest(ev.getX(), ev.getY()) == DEL) cb.onRemove(target);
                } else if (a != NONE) {
                    cb.onResized();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (active != NONE && active != DEL) cb.onResized();
                active = NONE;
                return true;
        }
        return true;
    }

    /** px, py — координаты пальца в системе страницы. */
    private void resize(float px, float py) {
        CellLayout.LP lp = (CellLayout.LP) target.getLayoutParams();
        int x = lp.cellX, y = lp.cellY;
        int right = x + lp.spanX, bottom = y + lp.spanY;
        int nx = x, ny = y, nr = right, nb = bottom;
        int gx = Math.round((px - cl.padL) / (float) cl.cellW);
        int gy = Math.round((py - cl.padT) / (float) cl.cellH);
        switch (active) {
            case RIGHT:
                nr = U.clamp(gx, x + 1, cl.cols);
                while (nr > right && !cl.isFree(nx, ny, nr - nx, nb - ny, target)) nr--;
                break;
            case LEFT:
                nx = U.clamp(gx, 0, right - 1);
                while (nx < x && !cl.isFree(nx, ny, nr - nx, nb - ny, target)) nx++;
                break;
            case BOTTOM:
                nb = U.clamp(gy, y + 1, cl.rows);
                while (nb > bottom && !cl.isFree(nx, ny, nr - nx, nb - ny, target)) nb--;
                break;
            case TOP:
                ny = U.clamp(gy, 0, bottom - 1);
                while (ny < y && !cl.isFree(nx, ny, nr - nx, nb - ny, target)) ny++;
                break;
        }
        if (nx != lp.cellX || ny != lp.cellY || nr - nx != lp.spanX || nb - ny != lp.spanY) {
            lp.cellX = nx;
            lp.cellY = ny;
            lp.spanX = nr - nx;
            lp.spanY = nb - ny;
            target.requestLayout();
        }
    }
}
