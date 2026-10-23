package org.openandroidauto.navigation;

/** Pure display policy: missing, stale or uncertain observations never become a made-up zero. */
public final class SpeedReadout {
    public static final long FRESH_MILLIS = 10000;
    public static final class Observation {
        public final long elapsedMillis;
        public final boolean hasSpeed, hasSpeedAccuracy;
        public final double metersPerSecond, accuracyMeters, speedAccuracyMetersPerSecond;
        public Observation(long elapsedMillis, boolean hasSpeed, double metersPerSecond,
                           double accuracyMeters, boolean hasSpeedAccuracy, double speedAccuracy) {
            this.elapsedMillis = elapsedMillis; this.hasSpeed = hasSpeed;
            this.metersPerSecond = metersPerSecond; this.accuracyMeters = accuracyMeters;
            this.hasSpeedAccuracy = hasSpeedAccuracy; speedAccuracyMetersPerSecond = speedAccuracy;
        }
    }
    public final String speed, limit, units;
    public final boolean speedKnown, limitKnown, unlimited;

    private SpeedReadout(String speed, String limit, String units, boolean speedKnown,
                         boolean limitKnown, boolean unlimited) {
        this.speed = speed; this.limit = limit; this.units = units;
        this.speedKnown = speedKnown; this.limitKnown = limitKnown; this.unlimited = unlimited;
    }
    public static SpeedReadout create(Observation observation, SpeedUnits resolvedUnits, long nowMillis,
                                      boolean roadMatched, double speedLimitKph) {
        SpeedUnits units = resolvedUnits == SpeedUnits.MPH ? SpeedUnits.MPH : SpeedUnits.KPH;
        boolean fresh = observation != null && observation.elapsedMillis >= 0
                && nowMillis >= observation.elapsedMillis && nowMillis - observation.elapsedMillis <= FRESH_MILLIS
                && Double.isFinite(observation.accuracyMeters) && observation.accuracyMeters >= 0
                && observation.accuracyMeters <= 50;
        boolean speedKnown = fresh && observation.hasSpeed && Double.isFinite(observation.metersPerSecond)
                && observation.metersPerSecond >= 0 && observation.metersPerSecond <= 150
                && (!observation.hasSpeedAccuracy || (Double.isFinite(observation.speedAccuracyMetersPerSecond)
                    && observation.speedAccuracyMetersPerSecond >= 0 && observation.speedAccuracyMetersPerSecond <= 3));
        boolean unlimited = fresh && roadMatched && speedLimitKph == Double.POSITIVE_INFINITY;
        boolean limitKnown = fresh && roadMatched && Double.isFinite(speedLimitKph) && speedLimitKph > 0 && speedLimitKph <= 300;
        String speed = speedKnown ? Long.toString(Math.round(units.fromMetersPerSecond(observation.metersPerSecond))) : "--";
        String limit = unlimited ? "∞" : limitKnown ? Long.toString(Math.round(units.fromKilometersPerHour(speedLimitKph))) : "--";
        return new SpeedReadout(speed, limit, units.symbol(), speedKnown, limitKnown, unlimited);
    }
    public String description() {
        String limitDescription = unlimited ? "No posted speed limit" : limitKnown ? "Speed limit " + limit + " " + units : "Speed limit unknown";
        return limitDescription + ". " + (speedKnown ? "Current speed " + speed + " " + units : "Current speed unavailable");
    }
}
