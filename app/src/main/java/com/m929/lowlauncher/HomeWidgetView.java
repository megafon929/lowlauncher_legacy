package com.m929.lowlauncher;

import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/** Виджет с распознаванием долгого нажатия (содержимое виджета само съедает касания). */
@SuppressWarnings("deprecation")
final class HomeWidgetView extends AppWidgetHostView {
    private final int slop;
    private boolean longPressed;
    private boolean pending;
    private float downX, downY;

    private final Runnable check = new Runnable() {
        public void run() {
            pending = false;
            longPressed = true;
            performLongClick();
        }
    };

    HomeWidgetView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    private void cancelCheck() {
        if (pending) {
            removeCallbacks(check);
            pending = false;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                longPressed = false;
                downX = ev.getX();
                downY = ev.getY();
                cancelCheck();
                pending = true;
                postDelayed(check, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - downX) > slop || Math.abs(ev.getY() - downY) > slop) cancelCheck();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelCheck();
                boolean r = longPressed;
                longPressed = false;
                return r;
        }
        return longPressed;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelCheck();
        super.onDetachedFromWindow();
    }
}
