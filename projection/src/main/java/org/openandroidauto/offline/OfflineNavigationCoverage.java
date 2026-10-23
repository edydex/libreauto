package org.openandroidauto.offline;

import org.openandroidauto.navigation.GeoPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Continuous rectangular-union coverage, including gaps between route vertices. */
public final class OfflineNavigationCoverage {
    private OfflineNavigationCoverage() {}
    public static boolean contains(List<OfflineAreaPolicy.Bounds> areas, GeoPoint point) {
        if (point == null) return false;
        for (OfflineAreaPolicy.Bounds b : areas) if (point.latitude >= b.south && point.latitude <= b.north
                && point.longitude >= b.west && point.longitude <= b.east) return true;
        return false;
    }
    public static boolean covers(List<OfflineAreaPolicy.Bounds> areas, List<GeoPoint> points) {
        if (areas.isEmpty() || points == null || points.isEmpty() || points.size() > 100000) return false;
        if (!contains(areas, points.get(0))) return false;
        for (int i = 1; i < points.size(); i++) if (!segment(areas, points.get(i - 1), points.get(i))) return false;
        return true;
    }
    private static boolean segment(List<OfflineAreaPolicy.Bounds> areas, GeoPoint a, GeoPoint b) {
        List<double[]> intervals = new ArrayList<>();
        double dx = b.longitude - a.longitude, dy = b.latitude - a.latitude;
        for (OfflineAreaPolicy.Bounds area : areas) {
            double[] t = {0, 1};
            if (clip(-dx, a.longitude - area.west, t) && clip(dx, area.east - a.longitude, t)
                    && clip(-dy, a.latitude - area.south, t) && clip(dy, area.north - a.latitude, t)) intervals.add(t);
        }
        intervals.sort(Comparator.comparingDouble(t -> t[0]));
        double covered = 0;
        for (double[] interval : intervals) {
            if (interval[0] > covered + 1e-10) return false;
            covered = Math.max(covered, interval[1]);
            if (covered >= 1 - 1e-10) return true;
        }
        return false;
    }
    private static boolean clip(double p, double q, double[] t) {
        if (p == 0) return q >= 0;
        double r = q / p;
        if (p < 0) { if (r > t[1]) return false; t[0] = Math.max(t[0], r); }
        else { if (r < t[0]) return false; t[1] = Math.min(t[1], r); }
        return true;
    }
}
