package com.m929.lowlauncher;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/** Меню приложений: поиск сверху и сетка установленных приложений. */
@SuppressWarnings("deprecation")
final class AppDrawer extends FrameLayout {
    private final ListView grid;       // строки по cols значков: ровная сетка без GridView
    private final EditText search;
    private final LinearLayout column;
    private final int rowH;
    private final View.OnClickListener click;
    private final View.OnLongClickListener longClick;
    private int fixedCols; // 0 = автоматически
    private int cols = 4;
    private ArrayList<AppInfo> all = new ArrayList<AppInfo>();
    private ArrayList<AppInfo> shown = all;

    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() {
            return (shown.size() + cols - 1) / cols;
        }

        public Object getItem(int i) {
            return null;
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int row, View convert, ViewGroup parent) {
            LinearLayout r;
            if (convert instanceof LinearLayout && ((LinearLayout) convert).getChildCount() == cols) {
                r = (LinearLayout) convert;
            } else {
                r = new LinearLayout(getContext());
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
                for (int i = 0; i < cols; i++) {
                    IconView v = new IconView(getContext(), true);
                    v.setOnClickListener(click);
                    v.setOnLongClickListener(longClick);
                    r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                }
            }
            for (int i = 0; i < cols; i++) {
                IconView v = (IconView) r.getChildAt(i);
                int idx = row * cols + i;
                if (idx < shown.size()) {
                    AppInfo a = shown.get(idx);
                    v.setTag(a);
                    v.bind(a);
                    v.setVisibility(VISIBLE);
                } else {
                    v.setTag(null);
                    v.setVisibility(INVISIBLE);
                }
            }
            return r;
        }
    };

    AppDrawer(Context c, View.OnClickListener click, View.OnLongClickListener longClick) {
        super(c);
        this.click = click;
        this.longClick = longClick;
        rowH = U.dp(c, 92 + (IconView.labelSp - 11) * 2);
        setBackgroundColor(0x99000000);
        setClickable(true); // не пропускать касания к рабочему столу под меню

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        // фокус по умолчанию у контейнера, чтобы клавиатура не появлялась сама
        column.setFocusable(true);
        column.setFocusableInTouchMode(true);
        column.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        search = new EditText(c);
        search.setHint(R.string.search_hint);
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackgroundColor(0x33FFFFFF);
        search.setPadding(U.dp(c, 12), U.dp(c, 8), U.dp(c, 12), U.dp(c, 8));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int d) {
            }

            public void afterTextChanged(Editable e) {
                applyFilter(e.toString());
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                hideKeyboard();
                return true;
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(U.dp(c, 8), U.dp(c, 8), U.dp(c, 8), 0);
        column.addView(search, slp);

        grid = new ListView(c);
        grid.setDivider(null);
        grid.setDividerHeight(0);
        grid.setSelector(new ColorDrawable(0));
        grid.setCacheColorHint(0);
        grid.setFastScrollEnabled(true);
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setPadding(0, U.dp(c, 8), 0, U.dp(c, 12));
        grid.setClipToPadding(false);
        grid.setAdapter(adapter);
        column.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void setApps(ArrayList<AppInfo> l) {
        all = l;
        applyFilter(search.getText().toString());
    }

    /** Показывает только приложения, в названии которых есть запрос. */
    private void applyFilter(String q) {
        q = q.trim().toLowerCase(Locale.getDefault());
        if (q.length() == 0) {
            shown = all;
        } else {
            ArrayList<AppInfo> r = new ArrayList<AppInfo>();
            for (int i = 0; i < all.size(); i++) {
                AppInfo a = all.get(i);
                if (a.label.toLowerCase(Locale.getDefault()).contains(q)) r.add(a);
            }
            shown = r;
        }
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        column.requestFocus();
    }

    /** Вызывается при закрытии меню: очищает поиск и прячет клавиатуру. */
    void reset() {
        hideKeyboard();
        if (search.getText().length() > 0) search.setText("");
    }

    void setColumns(int n) {
        fixedCols = n;
        applyColumns(getWidth());
    }

    private void applyColumns(int w) {
        if (w <= 0) return;
        int n = fixedCols > 0 ? fixedCols : Math.max(4, w / U.dp(getContext(), 84));
        if (n != cols) {
            cols = n;
            grid.setAdapter(adapter); // сбросить переиспользуемые строки со старым числом колонок
        }
    }

    /** Сетка прокручена до самого верха — тогда свайп вниз закрывает меню. */
    boolean atTop() {
        if (grid.getChildCount() == 0) return true;
        return grid.getFirstVisiblePosition() == 0 && grid.getChildAt(0).getTop() >= grid.getListPaddingTop();
    }

    void scrollToTop() {
        grid.setSelection(0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        applyColumns(w);
    }
}
