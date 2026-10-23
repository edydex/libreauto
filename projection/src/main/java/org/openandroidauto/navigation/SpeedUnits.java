package org.openandroidauto.navigation;

import java.util.Locale;

/** Display conversion only. Provider limits remain in km/h and GPS speed remains in m/s. */
public enum SpeedUnits {
    AUTOMATIC, MPH, KPH;

    public SpeedUnits resolved(Locale locale) {
        if (this != AUTOMATIC) return this;
        String country = locale == null ? "" : locale.getCountry();
        return "US".equals(country) || "GB".equals(country) ? MPH : KPH;
    }

    public String symbol() { return this == MPH ? "mph" : "km/h"; }
    public double fromMetersPerSecond(double value) { return value * (this == MPH ? 2.2369362920544 : 3.6); }
    public double fromKilometersPerHour(double value) { return this == MPH ? value / 1.609344 : value; }
    public static SpeedUnits fromPreference(String value) {
        if ("mph".equals(value)) return MPH;
        if ("kmh".equals(value)) return KPH;
        return AUTOMATIC;
    }
    public String preference() { return this == MPH ? "mph" : this == KPH ? "kmh" : "auto"; }
}
