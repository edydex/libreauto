// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;

/** The useful navigation subset of a route, with distances calculated along its actual geometry. */
public final class RoutePlan {
    /** Exact index-bound road labels returned for this unchanged route shape. */
    public static final class RoadSpan {
        public final int beginIndex;
        public final int endIndex;
        public final List<String> names;
        public final String travelMode;
        public final String use;
        public RoadSpan(int beginIndex, int endIndex, List<String> names, String travelMode, String use) {
            if (beginIndex < 0 || endIndex <= beginIndex || travelMode == null || use == null
                    || travelMode.length() > 40 || use.length() > 40)
                throw new IllegalArgumentException("Invalid road span");
            this.beginIndex = beginIndex;
            this.endIndex = endIndex;
            this.names = Maneuver.labels(names);
            this.travelMode = travelMode;
            this.use = use;
        }
    }
    public static final class Maneuver {
        public final int beginIndex;
        public final int endIndex;
        public final String instruction;
        public final String spokenInstruction;
        public final int type;
        /** Provider road/sign labels, retained for validating an external ordered turn list. */
        public final List<String> streetNames;
        public final List<String> beginStreetNames;
        public final List<String> signTexts;
        /** Provider-estimated traversal duration including its modeled transition costs. */
        public final double seconds;

        public Maneuver(int beginIndex, int endIndex, String instruction, String spokenInstruction, int type) {
            this(beginIndex, endIndex, instruction, spokenInstruction, type, Double.NaN);
        }

        public Maneuver(int beginIndex, int endIndex, String instruction, String spokenInstruction, int type, double seconds) {
            this(beginIndex, endIndex, instruction, spokenInstruction, type, seconds,
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        public Maneuver(int beginIndex, int endIndex, String instruction, String spokenInstruction, int type, double seconds,
                        List<String> streetNames, List<String> beginStreetNames, List<String> signTexts) {
            this.beginIndex = beginIndex;
            this.endIndex = endIndex;
            this.instruction = instruction;
            this.spokenInstruction = spokenInstruction;
            this.type = type;
            this.seconds = seconds;
            this.streetNames = labels(streetNames);
            this.beginStreetNames = labels(beginStreetNames);
            this.signTexts = labels(signTexts);
        }

        private static List<String> labels(List<String> source) {
            if (source == null || source.size() > 64) throw new IllegalArgumentException("Too many maneuver labels");
            List<String> result = new ArrayList<>();
            for (String value : source) {
                if (value == null || value.length() > 240) throw new IllegalArgumentException("Invalid maneuver label");
                if (!value.trim().isEmpty()) result.add(value.trim());
            }
            return Collections.unmodifiableList(result);
        }
    }

    public final List<GeoPoint> points;
    public final List<Maneuver> maneuvers;
    public final double[] cumulativeMeters;
    public final double seconds;
    public final String destinationName;
    public final boolean metadataEnriched;
    /** Local routing provenance; map drawing and saved destinations are independent. */
    public final boolean offline;
    public final List<RoadSpan> roadSpans;
    private final double[] cumulativeSeconds;
    private final double[] segmentSpeedLimitsKph;

    public RoutePlan(List<GeoPoint> points, List<Maneuver> maneuvers, double seconds, String destinationName) {
        this(points, maneuvers, seconds, destinationName, null, null, false, false);
    }

    private RoutePlan(List<GeoPoint> points, List<Maneuver> maneuvers, double seconds, String destinationName,
                      double[] segmentSeconds, double[] speedLimitsKph, boolean enriched, boolean offline) {
        if (points.size() < 2 || points.size() > 200000 || maneuvers.isEmpty()
                || !Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("Route has invalid geometry, guidance or duration");
        }
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.maneuvers = Collections.unmodifiableList(new ArrayList<>(maneuvers));
        this.seconds = seconds;
        this.destinationName = destinationName;
        metadataEnriched = enriched;
        this.offline = offline;
        roadSpans = Collections.emptyList();
        cumulativeMeters = new double[points.size()];
        for (int index = 1; index < points.size(); index++) {
            cumulativeMeters[index] = cumulativeMeters[index - 1] + points.get(index - 1).distanceTo(points.get(index));
        }
        int previous = -1;
        for (Maneuver item : maneuvers) {
            if (item.beginIndex < previous || item.beginIndex < 0 || item.endIndex < item.beginIndex
                    || item.endIndex >= points.size() || item.instruction == null || item.instruction.trim().isEmpty()) {
                throw new IllegalArgumentException("Route maneuver indexes are invalid");
            }
            previous = item.beginIndex;
        }
        double[] durations = segmentSeconds == null ? maneuverDurations() : segmentSeconds.clone();
        cumulativeSeconds = durations == null ? null : cumulativeTimes(durations);
        segmentSpeedLimitsKph = new double[points.size() - 1];
        Arrays.fill(segmentSpeedLimitsKph, Double.NaN);
        if (speedLimitsKph != null) {
            if (speedLimitsKph.length != segmentSpeedLimitsKph.length) throw new IllegalArgumentException("Speed-limit geometry mismatch");
            for (int index = 0; index < speedLimitsKph.length; index++) {
                double value = speedLimitsKph[index];
                if (!(Double.isNaN(value) || value == Double.POSITIVE_INFINITY || (Double.isFinite(value) && value > 0 && value < 255)))
                    throw new IllegalArgumentException("Invalid posted speed limit");
                segmentSpeedLimitsKph[index] = value;
            }
        }
    }

    public double lengthMeters() { return cumulativeMeters[cumulativeMeters.length - 1]; }
    public GeoPoint destination() { return points.get(points.size() - 1); }

    /** Copies validated, geometry-aligned metadata; unknown is NaN, explicitly unlimited is +Infinity. */
    public RoutePlan withRoadMetadata(double[] segmentSeconds, double[] speedLimitsKph) {
        RoutePlan result = new RoutePlan(points, maneuvers, seconds, destinationName, segmentSeconds, speedLimitsKph, true, offline);
        return roadSpans.isEmpty() ? result : result.withRoadSpans(roadSpans);
    }

    private RoutePlan(RoutePlan source, boolean offline) {
        this(source, offline, source.roadSpans);
    }

    private RoutePlan(RoutePlan source, boolean offline, List<RoadSpan> spans) {
        points = source.points;
        maneuvers = source.maneuvers;
        cumulativeMeters = source.cumulativeMeters.clone();
        seconds = source.seconds;
        destinationName = source.destinationName;
        metadataEnriched = source.metadataEnriched || !spans.isEmpty();
        this.offline = offline;
        if (spans.size() > 20_000) throw new IllegalArgumentException("Too many road spans");
        int next = 0;
        for (RoadSpan span : spans) {
            if (span == null || span.beginIndex != next || span.endIndex >= points.size())
                throw new IllegalArgumentException("Road spans do not match route");
            next = span.endIndex;
        }
        if (!spans.isEmpty() && next != points.size() - 1)
            throw new IllegalArgumentException("Road spans do not cover route");
        roadSpans = Collections.unmodifiableList(new ArrayList<>(spans));
        cumulativeSeconds = source.cumulativeSeconds == null ? null : source.cumulativeSeconds.clone();
        segmentSpeedLimitsKph = source.segmentSpeedLimitsKph.clone();
    }

    /** Preserve geometry, guidance and exact timing while recording the local route source. */
    public RoutePlan withOffline() { return offline ? this : new RoutePlan(this, true); }

    public RoutePlan withRoadSpans(List<RoadSpan> spans) {
        if (spans == null) throw new IllegalArgumentException("Missing road spans");
        return new RoutePlan(this, offline, spans);
    }

    public boolean hasTimingProfile() { return cumulativeSeconds != null; }

    /** Interpolates provider traversal/transition times, rather than assuming a driving speed. */
    public double remainingSecondsAt(double alongMeters) {
        if (!Double.isFinite(alongMeters) || cumulativeSeconds == null) return Double.NaN;
        if (alongMeters <= 0) return seconds;
        if (alongMeters >= lengthMeters()) return 0;
        int segment = segmentIndexAt(alongMeters);
        double length = cumulativeMeters[segment + 1] - cumulativeMeters[segment];
        double fraction = length <= 0 ? 0 : Math.max(0, Math.min(1, (alongMeters - cumulativeMeters[segment]) / length));
        double elapsed = cumulativeSeconds[segment] + fraction * (cumulativeSeconds[segment + 1] - cumulativeSeconds[segment]);
        return Math.max(0, seconds - elapsed);
    }

    public double speedLimitKphAt(double alongMeters) {
        if (!Double.isFinite(alongMeters)) return Double.NaN;
        return segmentSpeedLimitsKph[segmentIndexAt(alongMeters)];
    }

    private int segmentIndexAt(double alongMeters) {
        int low = 0, high = cumulativeMeters.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (cumulativeMeters[middle] <= alongMeters) low = middle; else high = middle - 1;
        }
        return Math.min(low, segmentSpeedLimitsKph.length - 1);
    }

    private double[] maneuverDurations() {
        double[] durations = new double[points.size() - 1];
        int next = 0;
        double total = 0;
        for (Maneuver item : maneuvers) {
            if (item.endIndex == item.beginIndex && (item.seconds == 0 || !Double.isFinite(item.seconds))) continue;
            if (item.beginIndex != next || item.endIndex <= item.beginIndex || !Double.isFinite(item.seconds) || item.seconds < 0)
                return null;
            double length = cumulativeMeters[item.endIndex] - cumulativeMeters[item.beginIndex];
            if (length <= 0) return null;
            for (int index = item.beginIndex; index < item.endIndex; index++)
                durations[index] = item.seconds * (cumulativeMeters[index + 1] - cumulativeMeters[index]) / length;
            total += item.seconds;
            next = item.endIndex;
        }
        if (next != durations.length || Math.abs(total - seconds) > Math.max(5, seconds * .02)) return null;
        return durations;
    }

    private double[] cumulativeTimes(double[] durations) {
        if (durations.length != points.size() - 1) throw new IllegalArgumentException("Travel-time geometry mismatch");
        double[] result = new double[points.size()];
        for (int index = 0; index < durations.length; index++) {
            if (!Double.isFinite(durations[index]) || durations[index] < 0) throw new IllegalArgumentException("Invalid segment travel time");
            result[index + 1] = result[index] + durations[index];
        }
        double total = result[result.length - 1];
        if (!Double.isFinite(total) || (total <= 0 && seconds > 0)) return null;
        // Tiny serialization differences must not change the original route's total estimate.
        double scale = total > 0 ? seconds / total : 0;
        for (int index = 1; index < result.length; index++) result[index] *= scale;
        return result;
    }

    public static String encodePolyline6(List<GeoPoint> points) {
        StringBuilder encoded = new StringBuilder();
        long latitude = 0, longitude = 0;
        for (GeoPoint point : points) {
            long nextLatitude = Math.round(point.latitude * 1000000), nextLongitude = Math.round(point.longitude * 1000000);
            encodeComponent(encoded, nextLatitude - latitude); encodeComponent(encoded, nextLongitude - longitude);
            latitude = nextLatitude; longitude = nextLongitude;
        }
        return encoded.toString();
    }

    private static void encodeComponent(StringBuilder encoded, long value) {
        value = value < 0 ? ~(value << 1) : value << 1;
        while (value >= 32) { encoded.append((char) ((32 | (value & 31)) + 63)); value >>= 5; }
        encoded.append((char) (value + 63));
    }

    /** Valhalla's native JSON shape uses six decimal places, unlike Google's default five. */
    public static List<GeoPoint> decodePolyline6(String encoded) {
        if (encoded.length() > 4000000) throw new IllegalArgumentException("Route geometry is too large");
        List<GeoPoint> points = new ArrayList<>();
        long latitude = 0;
        long longitude = 0;
        int[] cursor = {0};
        while (cursor[0] < encoded.length()) {
            latitude += decodeComponent(encoded, cursor);
            longitude += decodeComponent(encoded, cursor);
            points.add(new GeoPoint(latitude / 1000000.0, longitude / 1000000.0));
            if (points.size() > 200000) throw new IllegalArgumentException("Too many route points");
        }
        return points;
    }

    private static long decodeComponent(String input, int[] cursor) {
        long value = 0;
        int shift = 0;
        while (true) {
            if (cursor[0] >= input.length() || shift > 35) throw new IllegalArgumentException("Truncated route shape");
            int piece = input.charAt(cursor[0]++) - 63;
            if (piece < 0 || piece > 63) throw new IllegalArgumentException("Invalid route shape character");
            value |= (long) (piece & 31) << shift;
            if (piece < 32) return (value & 1) != 0 ? ~(value >>> 1) : value >>> 1;
            shift += 5;
        }
    }
}
