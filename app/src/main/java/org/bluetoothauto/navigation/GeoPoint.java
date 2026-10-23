// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation;

/** A validated WGS84 position, independent of Android and map renderer classes. */
public final class GeoPoint {
    public final double latitude;
    public final double longitude;

    public GeoPoint(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new IllegalArgumentException("Invalid geographic coordinate");
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double distanceTo(GeoPoint other) {
        double a = Math.sin(Math.toRadians(other.latitude - latitude) / 2);
        double b = Math.sin(Math.toRadians(other.longitude - longitude) / 2);
        double h = a * a + Math.cos(Math.toRadians(latitude))
                * Math.cos(Math.toRadians(other.latitude)) * b * b;
        return 6371008.8 * 2 * Math.atan2(Math.sqrt(Math.min(1, h)), Math.sqrt(Math.max(0, 1 - h)));
    }
}
