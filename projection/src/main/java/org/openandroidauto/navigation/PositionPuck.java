// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Point;

/** Two original, cached vector-drawn map symbols; rotation never rasterizes another bitmap. */
final class PositionPuck {
    private static final String DIRECTION_IMAGE = "open-auto-position-direction";
    private static final String UNKNOWN_IMAGE = "open-auto-position-unknown";
    private static final String ICON_PROPERTY = "position-icon";
    private static final String BEARING_PROPERTY = "travel-bearing";
    private final Bitmap direction = draw(true);
    private final Bitmap unknown = draw(false);

    void addToStyle(Style style, String source) {
        style.addImage(DIRECTION_IMAGE, direction);
        style.addImage(UNKNOWN_IMAGE, unknown);
        style.addLayer(new SymbolLayer(source, source).withProperties(
                PropertyFactory.iconImage(Expression.get(ICON_PROPERTY)),
                PropertyFactory.iconRotate(Expression.get(BEARING_PROPERTY)),
                // A travel bearing is geographic, so course-up and panned maps remain correct.
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(1f)));
    }

    Feature feature(GeoPoint point, TravelHeading heading) {
        Feature current = Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude));
        current.addStringProperty(ICON_PROPERTY, heading.hasHeading() ? DIRECTION_IMAGE : UNKNOWN_IMAGE);
        current.addNumberProperty(BEARING_PROPERTY, heading.hasHeading() ? heading.bearingDegrees() : 0);
        return current;
    }

    private static Bitmap draw(boolean directional) {
        // MapLibre uses bitmap density /160 as sprite pixel ratio:96px at320dpi is48dp.
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(320);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(2, 2);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x20202f35); canvas.drawCircle(24, 24, 21, paint);
        paint.setColor(0x50202f35); canvas.drawCircle(24, 24, 19.8f, paint);
        paint.setColor(Color.WHITE); canvas.drawCircle(24, 24, 18.5f, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.1f);
        paint.setColor(0xff28424a); canvas.drawCircle(24, 24, 18.5f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff008c8c);
        if (directional) {
            Path arrow = new Path();
            arrow.moveTo(24, 10.8f); arrow.lineTo(12.2f, 34.5f);
            arrow.lineTo(24, 29.5f); arrow.lineTo(35.8f, 34.5f); arrow.close();
            canvas.drawPath(arrow, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(0.8f);
            paint.setStrokeJoin(Paint.Join.ROUND); paint.setColor(0xff07565f);
            canvas.drawPath(arrow, paint);
        } else {
            // An unknown course is a dot, never a triangle falsely pointing north.
            canvas.drawCircle(24, 24, 6, paint);
        }
        return bitmap;
    }
}
