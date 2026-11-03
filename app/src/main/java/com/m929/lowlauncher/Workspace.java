package com.m929.lowlauncher;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;

/** Горизонтально листаемый набор страниц (CellLayout). */
@SuppressWarnings("deprecation")
final class Workspace extends ViewGroup {
    static final int PAGES = 5;

    interface Listener {
        void onPageChanged(int page);
    }

    private static final int REST = 0, SCROLLING = 1;

    private final Scroller scroller;
    private final int slop, minFling, maxFling;
    private int current;
    private int state = REST;
    private boolean locked;
    private float downX, downY, lastX;
    private VelocityTracker vt;
    private Listener listener;
    private float lastOffset = -1f;
    private int lastW;
    private boolean layersOn;

    Workspace(Context c) {
        super(c);
        scroller = new Scroller(c, new DecelerateInterpolator(1.4f));
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = U.dp(c, 250);
        maxFling = vc.getScaledMaximumFlingVelocity();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void addPage(CellLayout cl) {
        addView(cl);
    }

    CellLayout getPage(int i) {
        return (CellLayout) getChildAt(i);
    }

    int getPageCount() {
        return getChildCount();
    }

    int getCurrentPage() {
        return current;
    }

    CellLayout currentPage() {
        return getPage(current);
    }

    /** Блокирует листание (пока показана рамка изменения размера). */
    void setLocked(boolean b) {
        locked = b;
    }

    void snapToPage(int page, boolean animate) {
        page = U.clamp(page, 0, getChildCount() - 1);
        int w = getWidth();
        boolean changed = page != current;
        current = page;
        int dx = page * w - getScrollX();
        if (animate && w > 0) {
            layers(true);
            scroller.startScroll(getScrollX(), 0, dx, 0, Math.min(400, 150 + Math.abs(dx) / 2));
            invalidate();
        } else {
            scroller.abortAnimation();
            scrollTo(page * w, 0);
        }
        if (changed && listener != null) listener.onPageChanged(page);
    }

    // ---- измерение ----------------------------------------------------------------------------

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(ws, hs);
        if (w != lastW) {
            lastW = w;
            scroller.abortAnimation();
            scrollTo(current * w, 0);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h);
        }
    }

    /** Аппаратные слои страниц только на время прокрутки. */
    private void layers(boolean on) {
        if (on == layersOn) return;
        layersOn = on;
        for (int i = 0; i < getChildCount(); i++) U.layer(getChildAt(i), on);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidate();
        } else if (layersOn && state == REST) {
            layers(false);
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        updateWallpaper();
    }

    private void updateWallpaper() {
        IBinder tok = getWindowToken();
        int w = getWidth(), n = getChildCount();
        if (tok == null || w == 0 || n < 2) return;
        float off = getScrollX() / (float) (w * (n - 1));
        off = off < 0f ? 0f : (off > 1f ? 1f : off);
        if (Math.abs(off - lastOffset) < 0.002f) return;
        lastOffset = off;
        try {
            WallpaperManager wm = WallpaperManager.getInstance(getContext());
            wm.setWallpaperOffsetSteps(1f / (n - 1), 0f);
            wm.setWallpaperOffsets(tok, off, 0.5f);
        } catch (RuntimeException e) {
            // не критично
        }
    }

    // ---- жесты --------------------------------------------------------------------------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (locked) return false;
        int a = ev.getAction() & MotionEvent.ACTION_MASK;
        if (a == MotionEvent.ACTION_MOVE && state == SCROLLING) return true;
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                lastX = downX;
                state = scroller.isFinished() ? REST : SCROLLING;
                if (state == SCROLLING) layers(true);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(ev.getX() - downX);
                float dy = Math.abs(ev.getY() - downY);
                if (dx > slop && dx > dy) {
                    state = SCROLLING;
                    layers(true);
                    lastX = ev.getX();
                    scroller.abortAnimation();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                state = REST;
                break;
        }
        return state == SCROLLING;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (locked) return false;
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);
        int a = ev.getAction() & MotionEvent.ACTION_MASK;
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) scroller.abortAnimation();
                lastX = ev.getX();
                break;
            case MotionEvent.ACTION_MOVE:
                if (state == SCROLLING) {
                    float d = lastX - ev.getX();
                    lastX = ev.getX();
                    int max = (getChildCount() - 1) * getWidth();
                    scrollTo(U.clamp((int) (getScrollX() + d), 0, max), 0);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (state == SCROLLING) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float vx = vt.getXVelocity();
                    int w = Math.max(1, getWidth());
                    int page;
                    if (vx > minFling && current > 0) page = current - 1;
                    else if (vx < -minFling && current < getChildCount() - 1) page = current + 1;
                    else page = (getScrollX() + w / 2) / w;
                    snapToPage(page, true);
                }
                state = REST;
                recycle();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (state == SCROLLING) {
                    int w = Math.max(1, getWidth());
                    snapToPage((getScrollX() + w / 2) / w, true);
                }
                state = REST;
                recycle();
                break;
        }
        return true;
    }

    private void recycle() {
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
    }
}
