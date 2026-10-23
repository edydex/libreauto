package org.openandroidauto.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import org.openandroidauto.ui.CarUi;

/** Passive, allocation-free onDraw: posted sign on the left, measured speed on the right. */
public final class SpeedTileView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectangle = new RectF();
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private String speed = "--", limit = "--", units = "mph";
    public SpeedTileView(Context context) {
        super(context);
        setBackground(CarUi.rounded(context, CarUi.SURFACE, 14));
        setElevation(CarUi.dp(context, 3));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Speed limit unknown. Current speed unavailable");
    }
    public void show(SpeedReadout value) {
        setContentDescription(value.description());
        if (speed.equals(value.speed) && limit.equals(value.limit) && units.equals(value.units)) return;
        speed = value.speed; limit = value.limit; units = value.units; invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 156f, getHeight() / 72f);
        canvas.save(); canvas.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
        rectangle.set(6, 5, 58, 67); canvas.drawRoundRect(rectangle, 5, 5, paint);
        paint.setColor(0xff151719); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.4f);
        rectangle.set(8.5f, 7.5f, 55.5f, 64.5f); canvas.drawRoundRect(rectangle, 3, 3, paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(bold);
        paint.setTextSize(9); canvas.drawText("SPEED", 32, 19, paint); canvas.drawText("LIMIT", 32, 29, paint);
        paint.setTextSize(limit.length() > 2 ? 23 : 28); canvas.drawText(limit, 32, 56, paint);
        paint.setColor(CarUi.TEXT); paint.setTypeface(medium); paint.setTextSize(speed.length() > 2 ? 30 : 35);
        canvas.drawText(speed, 106, 43, paint);
        paint.setColor(CarUi.TEXT_MUTED); paint.setTextSize(12); canvas.drawText(units, 106, 61, paint);
        canvas.restore();
    }
}
