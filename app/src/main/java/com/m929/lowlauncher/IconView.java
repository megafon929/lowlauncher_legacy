package com.m929.lowlauncher;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Значок приложения: иконка сверху и (необязательно) подпись. Один TextView — минимум view. */
@SuppressWarnings("deprecation")
final class IconView extends TextView {
    /** Размер подписи (sp), задаётся в настройках. */
    static int labelSp = 11;

    private final boolean withLabel;

    IconView(Context c, boolean withLabel) {
        super(c);
        this.withLabel = withLabel;
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setTextColor(0xFFFFFFFF);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp);
        setShadowLayer(3f, 0f, 1f, 0xFF000000);
        if (withLabel) {
            setCompoundDrawablePadding(U.dp(c, 2));
            setPadding(U.dp(c, 2), U.dp(c, 4), U.dp(c, 2), U.dp(c, 2));
        } else {
            // Dock без подписи: drawable «сверху» TextView всегда рисует у верхнего padding, поэтому
            // отступ сверху задаём явно: высота Dock = иконка + 16dp => сверху и снизу ровно по 8dp.
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 1f);
            setIncludeFontPadding(false);
            setPadding(U.dp(c, 2), U.dp(c, 8), U.dp(c, 2), 0);
        }
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x40FFFFFF));
        bg.addState(new int[]{android.R.attr.state_selected}, new ColorDrawable(0x55FFFFFF));
        bg.addState(new int[]{}, new ColorDrawable(0));
        setBackgroundDrawable(bg);
    }

    void bind(AppInfo a) {
        if (a == null) {
            setCompoundDrawables(null, null, null, null);
            setText("");
            return;
        }
        setCompoundDrawables(null, a.icon, null, null);
        setText(withLabel ? a.label : "");
    }
}
