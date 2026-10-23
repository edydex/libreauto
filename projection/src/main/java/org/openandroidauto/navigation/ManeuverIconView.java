// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import org.openandroidauto.ui.CarUi;

/** Small original vector guidance symbols. No bitmaps, animation, network or icon-font dependency. */
final class ManeuverIconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path route = new Path();
    private final Path otherRoad = new Path();
    private final RectF ring = new RectF(17, 16, 47, 46);
    private ManeuverSymbol symbol = ManeuverSymbol.UNKNOWN;
    private boolean mirror;

    ManeuverIconView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setManeuver(0, false);
    }

    void setManeuver(int type, boolean arrived) {
        ManeuverSymbol next = ManeuverSymbol.fromValhalla(type, arrived);
        if (symbol == next && getContentDescription() != null) return;
        symbol = next;
        setContentDescription(next.description);
        buildPaths();
        invalidate();
    }

    private void buildPaths() {
        route.reset(); otherRoad.reset();
        mirror = symbol == ManeuverSymbol.LEFT || symbol == ManeuverSymbol.SLIGHT_LEFT
                || symbol == ManeuverSymbol.SHARP_LEFT || symbol == ManeuverSymbol.UTURN_LEFT
                || symbol == ManeuverSymbol.EXIT_LEFT || symbol == ManeuverSymbol.KEEP_LEFT
                || symbol == ManeuverSymbol.MERGE_LEFT;
        switch (symbol) {
            case STRAIGHT:
                route.moveTo(32, 54); route.lineTo(32, 11); arrowHead(32, 11, 0, -1); break;
            case RIGHT: case LEFT:
                route.moveTo(17, 54); route.lineTo(17, 31); route.quadTo(17, 22, 27, 22);
                route.lineTo(53, 22); arrowHead(53, 22, 1, 0); break;
            case SLIGHT_RIGHT: case SLIGHT_LEFT:
                route.moveTo(21, 54); route.lineTo(21, 40); route.quadTo(21, 34, 27, 28);
                route.lineTo(49, 10); arrowHead(49, 10, .707f, -.707f); break;
            case SHARP_RIGHT: case SHARP_LEFT:
                route.moveTo(17, 54); route.lineTo(17, 28); route.quadTo(17, 14, 29, 23);
                route.lineTo(50, 41); arrowHead(50, 41, .707f, .707f); break;
            case UTURN_RIGHT: case UTURN_LEFT:
                route.moveTo(19, 54); route.lineTo(19, 25);
                route.cubicTo(19, 5, 47, 5, 47, 25); route.lineTo(47, 42);
                arrowHead(47, 42, 0, 1); break;
            case EXIT_RIGHT: case EXIT_LEFT: case KEEP_RIGHT: case KEEP_LEFT:
                otherRoad.moveTo(27, 54); otherRoad.lineTo(27, 11);
                route.moveTo(27, 54); route.lineTo(27, 42); route.quadTo(27, 34, 34, 27);
                route.lineTo(51, 10); arrowHead(51, 10, .707f, -.707f); break;
            case MERGE:
                otherRoad.moveTo(12, 50); otherRoad.lineTo(12, 43);
                otherRoad.quadTo(12, 33, 22, 28); otherRoad.lineTo(32, 23);
                route.moveTo(32, 54); route.lineTo(32, 11); arrowHead(32, 11, 0, -1); break;
            case MERGE_RIGHT: case MERGE_LEFT:
                otherRoad.moveTo(43, 54); otherRoad.lineTo(43, 11);
                route.moveTo(15, 54); route.lineTo(15, 46);
                route.cubicTo(15, 33, 43, 34, 43, 21); route.lineTo(43, 11);
                arrowHead(43, 11, 0, -1); break;
            case ROUNDABOUT:
                // Type alone has no exit bearing or traffic handedness. The circle is neutral;
                // retain the server's full text instead of inventing an exit or rotation arrow.
                route.addOval(ring, Path.Direction.CW); route.moveTo(32, 54); route.lineTo(32, 46); break;
            case DESTINATION:
                route.moveTo(18, 54); route.lineTo(18, 10); route.lineTo(49, 10);
                route.lineTo(43, 22); route.lineTo(49, 33); route.lineTo(18, 33); break;
            case FERRY:
                route.moveTo(21, 32); route.lineTo(21, 17); route.lineTo(43, 17); route.lineTo(43, 32);
                route.moveTo(32, 17); route.lineTo(32, 9);
                route.moveTo(11, 34); route.lineTo(53, 34); route.lineTo(44, 48);
                route.lineTo(20, 48); route.close();
                otherRoad.moveTo(13, 55); otherRoad.quadTo(21, 50, 29, 55);
                otherRoad.quadTo(37, 60, 51, 54); break;
            default: break;
        }
    }

    private void arrowHead(float x, float y, float dx, float dy) {
        float backX = x - dx * 11, backY = y - dy * 11;
        route.moveTo(backX - dy * 10, backY + dx * 10);
        route.lineTo(x, y); route.lineTo(backX + dy * 10, backY - dx * 10);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int saved = canvas.save();
        float size = Math.min(getWidth(), getHeight());
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 64f, size / 64f);
        if (mirror) { canvas.translate(64, 0); canvas.scale(-1, 1); }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(CarUi.TEXT_MUTED);
        canvas.drawPath(otherRoad, paint);
        paint.setColor(CarUi.ACCENT);
        canvas.drawPath(route, paint);
        if (symbol == ManeuverSymbol.UNKNOWN) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(17, 32, 3, paint); canvas.drawCircle(32, 32, 3, paint); canvas.drawCircle(47, 32, 3, paint);
        }
        canvas.restoreToCount(saved);
    }
}
