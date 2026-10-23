package org.openandroidauto.navigation;

import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;

public class SpeedReadoutTest {
    private SpeedReadout.Observation fix(double speed) { return new SpeedReadout.Observation(1000, true, speed, 5, true, .4); }
    @Test public void convertsGpsAndPostedLimitIndependently() {
        SpeedReadout mph = SpeedReadout.create(fix(13.4112), SpeedUnits.MPH, 1000, true, 40);
        assertEquals("30", mph.speed); assertEquals("25", mph.limit); assertEquals("mph", mph.units);
        SpeedReadout kph = SpeedReadout.create(fix(10), SpeedUnits.KPH, 1000, true, 50);
        assertEquals("36", kph.speed); assertEquals("50", kph.limit);
    }
    @Test public void missingLimitDoesNotInventPostedLimitFromActualSpeed() {
        SpeedReadout value = SpeedReadout.create(fix(10), SpeedUnits.MPH, 1000, true, Double.NaN);
        assertEquals("22", value.speed); assertEquals("--", value.limit); assertFalse(value.limitKnown);
    }
    @Test public void unmatchedRoadHidesOldLimitButKeepsGpsSpeed() {
        SpeedReadout value = SpeedReadout.create(fix(10), SpeedUnits.MPH, 1000, false, 50);
        assertEquals("22", value.speed); assertEquals("--", value.limit);
    }
    @Test public void staleOrFutureFixDoesNotDisplayOldSpeedOrLimit() {
        for (long now : new long[]{999, 11001}) {
            SpeedReadout value = SpeedReadout.create(fix(10), SpeedUnits.MPH, now, true, 50);
            assertEquals("--", value.speed); assertEquals("--", value.limit);
        }
        assertEquals("22", SpeedReadout.create(fix(10), SpeedUnits.MPH, 11000, true, 50).speed);
    }
    @Test public void badMeasurementIsNotZero() {
        for (double speed : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, 151})
            assertEquals("--", SpeedReadout.create(fix(speed), SpeedUnits.MPH, 1000, true, 50).speed);
        SpeedReadout.Observation uncertain = new SpeedReadout.Observation(1000, true, 10, 5, true, 4);
        assertEquals("--", SpeedReadout.create(uncertain, SpeedUnits.MPH, 1000, true, 50).speed);
        assertEquals("25", SpeedReadout.create(uncertain, SpeedUnits.MPH, 1000, true, 40).limit);
    }
    @Test public void unknownSpeedCanHaveKnownRoadLimitAndStationaryZeroIsValid() {
        SpeedReadout.Observation noSpeed = new SpeedReadout.Observation(1000, false, 0, 5, false, 0);
        SpeedReadout value = SpeedReadout.create(noSpeed, SpeedUnits.MPH, 1000, true, 40);
        assertEquals("--", value.speed); assertEquals("25", value.limit);
        assertEquals("0", SpeedReadout.create(fix(0), SpeedUnits.MPH, 1000, true, 40).speed);
    }
    @Test public void unlimitedIsExplicitAndNotZeroOrUnknown() {
        SpeedReadout unlimited = SpeedReadout.create(fix(10), SpeedUnits.KPH, 1000, true, Double.POSITIVE_INFINITY);
        assertEquals("∞", unlimited.limit); assertTrue(unlimited.unlimited);
        for (double limit : new double[]{0, -1, 301, Double.NEGATIVE_INFINITY})
            assertEquals("--", SpeedReadout.create(fix(10), SpeedUnits.KPH, 1000, true, limit).limit);
    }
    @Test public void missingOrCoarseLocationSuppressesBothNumbers() {
        assertEquals("--", SpeedReadout.create(null, SpeedUnits.MPH, 1000, true, 40).speed);
        SpeedReadout.Observation coarse = new SpeedReadout.Observation(1000, true, 10, 51, false, 0);
        SpeedReadout value = SpeedReadout.create(coarse, SpeedUnits.MPH, 1000, true, 40);
        assertEquals("--", value.speed); assertEquals("--", value.limit);
    }
    @Test public void automaticUnitsCanBeOverridden() {
        assertEquals(SpeedUnits.MPH, SpeedUnits.AUTOMATIC.resolved(Locale.US));
        assertEquals(SpeedUnits.MPH, SpeedUnits.AUTOMATIC.resolved(Locale.UK));
        assertEquals(SpeedUnits.KPH, SpeedUnits.AUTOMATIC.resolved(Locale.GERMANY));
        assertEquals(SpeedUnits.KPH, SpeedUnits.KPH.resolved(Locale.US));
        assertEquals(SpeedUnits.AUTOMATIC, SpeedUnits.fromPreference("invalid"));
    }
}
