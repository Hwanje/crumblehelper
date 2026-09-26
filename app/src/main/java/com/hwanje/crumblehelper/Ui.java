package com.hwanje.crumblehelper;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

/** 코드로 화면을 만들 때 쓰는 작은 도우미들. */
final class Ui {

    static final int ACCENT = 0xFFF4A623;
    static final int COOKIE = 0xFF8D5524;
    static final int PANEL_BG = 0xFF221811;
    static final int CARD_BG = 0xFF33251B;
    static final int TEXT = 0xFFF7EFE6;
    static final int TEXT_DIM = 0xFFBFAF9F;
    static final int WARN = 0xFFE57373;

    private Ui() {}

    static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(Context ctx, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    static GradientDrawable outline(Context ctx, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setStroke(dp(ctx, 1), stroke);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }
}
