package org.openandroidauto.navigation;

/**
 * Bounded location matching and guidance progress for the prototype. Does no network or Android work.
 * A three-fix/three-second off-route decision avoids rerouting on a single noisy GPS observation.
 */
public final class RouteTracker {
    public static final class Progress {
        public final GeoPoint snapped;
        public final double alongMeters;
        public final double remainingMeters;
        public final double crossTrackMeters;
        public final double nextManeuverMeters;
        public final int maneuverIndex;
        public final boolean offRoute;
        public final boolean arrived;
        public final boolean usableFix;
        /** Conservative route-corridor confidence, separate from the slower rerouting decision. */
        public final boolean matched;
        public final double remainingSeconds;
        /** NaN means unknown; +Infinity means the provider explicitly reported unlimited. */
        public final double speedLimitKph;

        Progress(GeoPoint snapped, double along, double remaining, double crossTrack, double nextDistance,
                 int maneuver, boolean offRoute, boolean arrived, boolean usableFix,
                 boolean matched, double remainingSeconds, double speedLimitKph) {
            this.snapped = snapped;
            alongMeters = along;
            remainingMeters = remaining;
            crossTrackMeters = crossTrack;
            nextManeuverMeters = nextDistance;
            maneuverIndex = maneuver;
            this.offRoute = offRoute;
            this.arrived = arrived;
            this.usableFix = usableFix;
            this.matched = matched;
            this.remainingSeconds = remainingSeconds;
            this.speedLimitKph = speedLimitKph;
        }
    }

    private RoutePlan route;
    private int lastSegment = -1;
    private double lastAlong;
    private long lastElapsed = -1;
    private long offRouteSince = -1;
    private int offRouteFixes;
    private Progress lastProgress;

    public RouteTracker(RoutePlan route) { this.route = route; }

    /** Replace timing/limits only. Retains matching continuity, timestamps and off-route history. */
    public Progress updatePlan(RoutePlan replacement) {
        if (replacement == null || replacement.points.size() != route.points.size()
                || !replacement.maneuvers.equals(route.maneuvers) || replacement.seconds != route.seconds
                || replacement.offline != route.offline
                || !java.util.Objects.equals(replacement.destinationName, route.destinationName))
            throw new IllegalArgumentException("Metadata must preserve the current route");
        for (int index = 0; index < route.points.size(); index++) {
            GeoPoint before = route.points.get(index), after = replacement.points.get(index);
            if (before.latitude != after.latitude || before.longitude != after.longitude)
                throw new IllegalArgumentException("Metadata must preserve the current route geometry");
        }
        route = replacement;
        if (lastProgress == null) return null;
        Progress previous = lastProgress;
        // This is a data refresh, not a second observation of the previous GPS sample.
        lastProgress = new Progress(previous.snapped, previous.alongMeters, previous.remainingMeters,
                previous.crossTrackMeters, previous.nextManeuverMeters, previous.maneuverIndex,
                previous.offRoute, previous.arrived, previous.usableFix, previous.matched,
                previous.matched ? (previous.arrived ? 0 : route.remainingSecondsAt(previous.alongMeters)) : Double.NaN,
                previous.matched ? route.speedLimitKphAt(previous.alongMeters) : Double.NaN);
        return lastProgress;
    }

    public Progress update(GeoPoint fix, double accuracyMeters, long elapsedMillis) {
        return update(fix, accuracyMeters, elapsedMillis, null);
    }

    /** Supply a course only for a moving fix with a meaningful bearing, not a stationary compass. */
    public Progress update(GeoPoint fix, double accuracyMeters, long elapsedMillis, Float bearingDegrees) {
        if (lastElapsed >= 0 && elapsedMillis <= lastElapsed) return null;
        lastElapsed = elapsedMillis;
        boolean usable = Double.isFinite(accuracyMeters) && accuracyMeters >= 0 && accuracyMeters <= 60;
        int searchBegin = lastSegment < 0 ? 0 : Math.max(0, lastSegment - 12);
        int searchEnd = lastSegment < 0 ? route.points.size() - 2 : Math.min(route.points.size() - 2, lastSegment + 100);
        Match nearest = nearest(fix, searchBegin, searchEnd);
        // Reacquire globally after real displacement; use continuity to avoid shortcuts at route crossings.
        if (nearest.distance > Math.max(100, accuracyMeters * 3)) {
            Match global = nearest(fix, 0, route.points.size() - 2);
            if (global.distance + 20 < nearest.distance) {
                nearest = global; searchBegin = 0; searchEnd = route.points.size() - 2;
            }
        }
        if (usable && nearest.distance <= Math.max(35, accuracyMeters * 2)) {
            offRouteSince = -1;
            offRouteFixes = 0;
            lastSegment = nearest.segment;
            // Permit small reverse movement without jumping instructions backward under GPS jitter.
            lastAlong = Math.max(lastAlong - 10, nearest.along);
        } else if (usable) {
            if (offRouteSince < 0) offRouteSince = elapsedMillis;
            offRouteFixes++;
        }
        boolean offRoute = offRouteFixes >= 3 && elapsedMillis - offRouteSince >= 3000;
        double along = usable && !offRoute && nearest.distance < Math.max(35, accuracyMeters * 2)
                ? lastAlong : Math.max(0, lastAlong);
        int maneuver = route.maneuvers.size() - 1;
        for (int index = 0; index < route.maneuvers.size(); index++) {
            if (route.cumulativeMeters[route.maneuvers.get(index).beginIndex] > along + 12) {
                maneuver = index;
                break;
            }
        }
        double remaining = Math.max(0, route.lengthMeters() - along);
        double nextDistance = Math.max(0, route.cumulativeMeters[route.maneuvers.get(maneuver).beginIndex] - along);
        boolean arrived = usable && !offRoute && remaining <= 25 && fix.distanceTo(route.destination()) <= 35;
        boolean matched = usable && !offRoute && accuracyMeters <= 20
                && nearest.distance <= Math.max(6, Math.min(12, accuracyMeters))
                && Math.abs(nearest.along - along) <= 12
                && headingMatches(nearest.segment, bearingDegrees)
                && !ambiguous(fix, nearest, accuracyMeters, searchBegin, searchEnd);
        double remainingSeconds = matched ? (arrived ? 0 : route.remainingSecondsAt(along)) : Double.NaN;
        double speedLimitKph = matched ? route.speedLimitKphAt(along) : Double.NaN;
        lastProgress = new Progress(nearest.point, along, remaining, nearest.distance, nextDistance, maneuver,
                offRoute, arrived, usable, matched, remainingSeconds, speedLimitKph);
        return lastProgress;
    }

    private boolean headingMatches(int segment, Float bearing) {
        if (bearing == null) return true;
        if (!Float.isFinite(bearing) || bearing < 0 || bearing >= 360) return false;
        GeoPoint a = route.points.get(segment), b = route.points.get(segment + 1);
        if (a.distanceTo(b) < 1) return false;
        double x = wrapped(b.longitude - a.longitude) * Math.cos(Math.toRadians((a.latitude + b.latitude) / 2));
        double heading = (Math.toDegrees(Math.atan2(x, b.latitude - a.latitude)) + 360) % 360;
        double difference = Math.abs(((bearing - heading + 540) % 360) - 180);
        return difference <= 55;
    }

    private boolean ambiguous(GeoPoint fix, Match best, double accuracy, int from, int to) {
        // Adjacent pieces of the same road are expected. Distinct route passages near a crossing
        // or parallel return road must not confidently assign a limit or time to the wrong one.
        double tolerance = best.distance + Math.max(3, accuracy);
        for (int index = from; index <= to; index++) {
            if (route.cumulativeMeters[index + 1] >= best.along - 40
                    && route.cumulativeMeters[index] <= best.along + 40) continue;
            Match other = nearest(fix, index, index);
            if (Math.abs(other.along - best.along) > 40 && other.distance <= tolerance) return true;
        }
        return false;
    }

    private Match nearest(GeoPoint fix, int start, int end) {
        Match best = null;
        for (int index = start; index <= end; index++) {
            GeoPoint a = route.points.get(index);
            GeoPoint b = route.points.get(index + 1);
            double latitudeScale = 111195.08;
            double longitudeScale = latitudeScale * Math.cos(Math.toRadians((a.latitude + b.latitude) / 2));
            double dx = wrapped(b.longitude - a.longitude) * longitudeScale;
            double dy = (b.latitude - a.latitude) * latitudeScale;
            double px = wrapped(fix.longitude - a.longitude) * longitudeScale;
            double py = (fix.latitude - a.latitude) * latitudeScale;
            double squared = dx * dx + dy * dy;
            double fraction = squared == 0 ? 0 : Math.max(0, Math.min(1, (px * dx + py * dy) / squared));
            double longitude = a.longitude + wrapped(b.longitude - a.longitude) * fraction;
            if (longitude > 180) longitude -= 360;
            if (longitude < -180) longitude += 360;
            GeoPoint point = new GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction, longitude);
            double distance = fix.distanceTo(point);
            double along = route.cumulativeMeters[index]
                    + fraction * (route.cumulativeMeters[index + 1] - route.cumulativeMeters[index]);
            // At a crossing choose the nearer point, then the point nearest existing route progress.
            double score = distance + (lastSegment < 0 ? 0 : Math.min(20, Math.abs(along - lastAlong) / 100));
            if (best == null || score < best.score) best = new Match(index, point, distance, along, score);
        }
        return best;
    }

    private static double wrapped(double longitude) {
        if (longitude > 180) return longitude - 360;
        if (longitude < -180) return longitude + 360;
        return longitude;
    }

    private static final class Match {
        final int segment;
        final GeoPoint point;
        final double distance;
        final double along;
        final double score;
        Match(int segment, GeoPoint point, double distance, double along, double score) {
            this.segment = segment;
            this.point = point;
            this.distance = distance;
            this.along = along;
            this.score = score;
        }
    }
}
