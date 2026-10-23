// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

/** Shared native car-screen styling. Icons are drawn once per invalidation, with no bitmap assets. */
public final class CarUi {
    public static final int BACKGROUND = 0xff101719;
    public static final int SURFACE = 0xff192326;
    public static final int SURFACE_RAISED = 0xff243135;
    public static final int TEXT = 0xfff1f5f3;
    public static final int TEXT_MUTED = 0xffadbfba;
    public static final int ACCENT = 0xffb4dfcc;
    public static final int OUTLINE = 0xff3b4a49;
    public static final int DANGER = 0xffffbbb3;
    public static final int ICON_MAP = 1, ICON_MESSAGES = 2, ICON_CALLS = 3, ICON_MUSIC = 4, ICON_MIC = 5;
    private CarUi() {}

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    public static GradientDrawable rounded(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static TextView text(Context context, String value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value); view.setTextSize(sizeSp); view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    public static Button button(Context context, String value, boolean primary) {
        Button view = new Button(context); view.setText(value); applyButton(view, primary); return view;
    }

    public static void applyButton(Button view, boolean primary) {
        Context context = view.getContext();
        view.setAllCaps(false); view.setTextSize(15); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setMinWidth(0); view.setMinimumWidth(0); view.setMinHeight(dp(context, 48)); view.setMinimumHeight(dp(context, 48));
        view.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        view.setGravity(Gravity.CENTER); view.setElevation(0); view.setStateListAnimator(null);
        view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{0xff72817d, primary ? BACKGROUND : TEXT}));
        int color = primary ? ACCENT : SURFACE_RAISED;
        GradientDrawable surface = rounded(context, color, 16);
        surface.setColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{SURFACE_RAISED, color}));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33344e44 : 0x3399bbae),
            surface, rounded(context, Color.WHITE, 16)));
    }

    public static Button dockButton(Context context, String value, int icon) {
        Button view = button(context, value, false);
        view.setTextSize(14); view.setCompoundDrawablePadding(dp(context, 8));
        view.setSingleLine(true); view.setEllipsize(TextUtils.TruncateAt.END);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(context, icon, 21, TEXT_MUTED), null, null, null);
        view.setContentDescription(value); applyTab(view, false); return view;
    }

    /** A labelled-to-accessibility icon target, without a visible caption. */
    public static ImageButton floatingIconButton(Context context, String description, int icon) {
        ImageButton view = new ImageButton(context);
        view.setContentDescription(description); view.setTooltipText(description);
        view.setImageDrawable(icon(context, icon, 27, BACKGROUND));
        view.setImageTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{TEXT_MUTED, BACKGROUND}));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        view.setMinimumWidth(dp(context, 56)); view.setMinimumHeight(dp(context, 56));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33445c52),
            rounded(context, ACCENT, 28), rounded(context, Color.WHITE, 28)));
        view.setElevation(dp(context, 6)); view.setStateListAnimator(null);
        return view;
    }

    public static void applyTab(Button view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? BACKGROUND : TEXT_MUTED);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x335f8c78),
            rounded(view.getContext(), selected ? ACCENT : SURFACE, 18), rounded(view.getContext(), Color.WHITE, 18)));
        for (Drawable drawable : view.getCompoundDrawablesRelative()) {
            if (drawable instanceof LineIcon) ((LineIcon) drawable).setColor(selected ? BACKGROUND : TEXT_MUTED);
        }
    }

    public static Drawable icon(Context context, int kind, int sizeDp, int color) {
        return new LineIcon(kind, dp(context, sizeDp), color);
    }

    private static final class LineIcon extends Drawable {
        private final int kind, size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        LineIcon(int kind, int size, int color) {
            this.kind = kind; this.size = size; paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        void setColor(int color) { paint.setColor(color); invalidateSelf(); }
        @Override public void draw(Canvas canvas) {
            int saved = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            path.reset();
            switch (kind) {
                case ICON_MAP:
                    path.moveTo(3, 5); path.lineTo(9, 3); path.lineTo(15, 5); path.lineTo(21, 3);
                    path.lineTo(21, 19); path.lineTo(15, 21); path.lineTo(9, 19); path.lineTo(3, 21); path.close();
                    canvas.drawPath(path, paint); canvas.drawLine(9, 3, 9, 19, paint); canvas.drawLine(15, 5, 15, 21, paint); break;
                case ICON_MESSAGES:
                    path.moveTo(5, 4); path.lineTo(19, 4); path.quadTo(21, 4, 21, 6); path.lineTo(21, 15);
                    path.quadTo(21, 17, 19, 17); path.lineTo(10, 17); path.lineTo(5, 21); path.lineTo(5, 17);
                    path.quadTo(3, 17, 3, 15); path.lineTo(3, 6); path.quadTo(3, 4, 5, 4); canvas.drawPath(path, paint);
                    canvas.drawLine(7, 9, 17, 9, paint); canvas.drawLine(7, 13, 14, 13, paint); break;
                case ICON_CALLS:
                    path.moveTo(7, 3); path.lineTo(10, 8); path.lineTo(7.5f, 10.5f); path.quadTo(9, 14.5f, 13.5f, 16.5f);
                    path.lineTo(16, 14); path.lineTo(21, 17); path.lineTo(20, 21); path.quadTo(19, 23, 14, 20.5f);
                    path.quadTo(4, 16, 2.5f, 7); path.quadTo(2, 4, 4, 3); path.close(); canvas.drawPath(path, paint); break;
                case ICON_MIC:
                    rect.set(8,2,16,15); canvas.drawRoundRect(rect,4,4,paint);
                    path.moveTo(5,10); path.lineTo(5,12); path.cubicTo(5,21,19,21,19,12); path.lineTo(19,10); canvas.drawPath(path,paint);
                    canvas.drawLine(12,19,12,23,paint); canvas.drawLine(8,23,16,23,paint); break;
                default:
                    canvas.drawLine(10, 17, 10, 5, paint); canvas.drawLine(10, 5, 20, 3, paint); canvas.drawLine(20, 3, 20, 15, paint);
                    rect.set(4, 15, 10, 20); canvas.drawOval(rect, paint); rect.set(14, 13, 20, 18); canvas.drawOval(rect, paint); break;
            }
            canvas.restoreToCount(saved);
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
