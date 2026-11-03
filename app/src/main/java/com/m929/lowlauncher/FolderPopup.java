package com.m929.lowlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Окно открытой папки поверх рабочего стола. Это обычный view внутри DragLayer (а не Dialog),
 * чтобы значок можно было вытащить из папки не отпуская палец.
 */
@SuppressWarnings("deprecation")
final class FolderPopup extends FrameLayout {

    interface Host {
        AppInfo appFor(ComponentName cn);

        void onOpenApp(ComponentName cn);

        boolean onDragOut(View v, ItemInfo folder, ComponentName cn);

        void onTitleChanged(ItemInfo folder, String title);
    }

    /** ScrollView с ограничением высоты. */
    private static final class Limited extends ScrollView {
        private final int max;

        Limited(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    private final Host host;
    private final int rowH;
    private ItemInfo folder;
    private EditText title;
    private LinearLayout panel;

    FolderPopup(Context c, Host host, int iconSize) {
        super(c);
        this.host = host;
        rowH = iconSize + U.dp(c, 32);
        setBackgroundColor(0x99000000);
        setClickable(true);
        setVisibility(GONE);
        setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                close();
            }
        });
    }

    boolean isOpen() {
        return getVisibility() != GONE;
    }

    void open(ItemInfo f) {
        folder = f;
        removeAllViews();
        Context c = getContext();
        int maxH = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.55f);
        int colsN = c.getResources().getDisplayMetrics().widthPixels / c.getResources().getDisplayMetrics().density < 340 ? 3 : 4;

        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE252525);
        bg.setCornerRadius(U.dp(c, 16));
        panel.setBackgroundDrawable(bg);
        panel.setPadding(U.dp(c, 8), U.dp(c, 8), U.dp(c, 8), U.dp(c, 8));
        panel.setClickable(true); // клики по панели не закрывают папку
        panel.setFocusableInTouchMode(true);

        title = new EditText(c);
        title.setText(f.title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setImeOptions(EditorInfo.IME_ACTION_DONE);
        title.setBackgroundColor(0);
        title.setPadding(U.dp(c, 8), U.dp(c, 6), U.dp(c, 8), U.dp(c, 10));
        title.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                hideKeyboard();
                return true;
            }
        });
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Limited sv = new Limited(c, maxH);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = null;
        int n = 0;
        for (int i = 0; i < f.apps.size(); i++) {
            final ComponentName cn = f.apps.get(i);
            AppInfo a = host.appFor(cn);
            if (a == null) continue;
            if (n % colsN == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
            }
            IconView v = new IconView(c, true);
            v.bind(a);
            v.setTag(cn);
            v.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    host.onOpenApp(cn);
                }
            });
            v.setOnLongClickListener(new OnLongClickListener() {
                public boolean onLongClick(View v) {
                    return host.onDragOut(v, folder, cn);
                }
            });
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            n++;
        }
        if (row != null) {
            for (int k = n % colsN; k != 0 && k < colsN; k++) {
                row.addView(new View(c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
        }
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.setMargins(U.dp(c, 16), U.dp(c, 48), U.dp(c, 16), U.dp(c, 48));
        addView(panel, lp);
        setVisibility(VISIBLE);
        panel.requestFocus();
    }

    /** Закрыть с сохранением названия. */
    void close() {
        if (!isOpen()) return;
        hideKeyboard();
        if (folder != null && title != null) host.onTitleChanged(folder, title.getText().toString());
        setVisibility(GONE);
        removeAllViews();
        folder = null;
        title = null;
    }

    ItemInfo current() {
        return folder;
    }

    private void hideKeyboard() {
        if (title == null) return;
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(title.getWindowToken(), 0);
        if (panel != null) panel.requestFocus();
    }
}
