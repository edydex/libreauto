package org.openandroidauto.navigation;

/** Plain values for the secondary trip row. No traffic assumption or extra signal penalty. */
public final class TripSummary {
    private TripSummary() {}
    public static String duration(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 30 * 86400) return null;
        long minutes = (long) Math.ceil(seconds / 60);
        if (minutes < 60) return minutes + " min";
        if (minutes < 1440) return (minutes / 60) + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
        return (minutes / 1440) + " d" + (minutes / 60 % 24 == 0 ? "" : " " + minutes / 60 % 24 + " h");
    }
    public static long arrivalMillis(long nowMillis, double remainingSeconds) {
        if (nowMillis < 0 || duration(remainingSeconds) == null) return -1;
        long delta = Math.round(remainingSeconds * 1000);
        return nowMillis > Long.MAX_VALUE - delta ? -1 : nowMillis + delta;
    }
}
