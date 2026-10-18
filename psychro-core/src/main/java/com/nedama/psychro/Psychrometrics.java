package com.nedama.psychro;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * Psychrometric properties of moist air.
 *
 * <p>Units used throughout: temperature in °C, pressure in Pa (absolute), relative humidity
 * in percent (0 to 100), humidity ratio in kg of water per kg of dry air, enthalpy in J per kg
 * of dry air, specific volume in m³ per kg of dry air, density and absolute humidity in kg/m³,
 * altitude in m.
 *
 * <p>Relative humidity is always relative to saturation over liquid water, including below
 * 0 °C. This is the meteorological convention that most industrial RH sensors follow. Below
 * 0 °C the dew point is therefore over supercooled water and the frost point is over ice.
 *
 * <p>Sources:
 * <ul>
 *   <li>ASHRAE Handbook, Fundamentals (2017), chapter 1: saturation pressure over ice (eq. 5)
 *       and over water from 0 to 200 °C (eq. 6), humidity ratio, enthalpy, specific volume,
 *       wet bulb (eq. 33 and 35) and standard atmosphere (eq. 3). The implementation follows
 *       PsychroLib 2.5.0 (MIT licence), see THIRD_PARTY_NOTICES.md.</li>
 *   <li>Murphy and Koop (2005), Q. J. R. Meteorol. Soc. 131, pages 1539 to 1565: saturation
 *       pressure over supercooled water below 0 °C.</li>
 * </ul>
 *
 * <p>All methods are static, stateless and thread safe.
 */
public final class Psychrometrics {

    /** Lowest valid temperature, °C. */
    public static final double T_MIN = -100.0;
    /** Highest valid temperature, °C. */
    public static final double T_MAX = 200.0;
    /** Lowest valid altitude for {@link #pressureFromAltitude(double)}, m. */
    public static final double Z_MIN = -1000.0;
    /** Highest valid altitude for {@link #pressureFromAltitude(double)}, m (top of the troposphere). */
    public static final double Z_MAX = 11000.0;

    /** Triple point of water, °C. ASHRAE switches between ice and water here. */
    static final double TRIPLE_POINT = 0.01;
    /** Freezing point, °C. ASHRAE switches between wet bulb (eq. 33) and ice bulb (eq. 35) here. */
    static final double FREEZING_POINT = 0.0;
    static final double KELVIN = 273.15;
    /** Gas constant of dry air, J/(kg K), ASHRAE. */
    static final double R_DA = 287.042;
    /** Ratio of the molar masses of water and dry air, ASHRAE. */
    static final double EPS = 0.621945;
    /** Inverse of EPS as published by ASHRAE. */
    static final double INV_EPS = 1.607858;

    private static final double SOLVER_TOLERANCE = 1e-9; // °C
    private static final int SOLVER_MAX_ITER = 200;
    private static final double SATURATION_SLACK = 1e-9; // relative, absorbs rounding at 100 % RH

    private Psychrometrics() {
    }

    // Saturation and vapour pressure

    /**
     * Saturation vapour pressure over liquid water, Pa. Below 0 °C this is over supercooled
     * water, which is the basis of relative humidity in this module.
     *
     * @param t temperature, °C
     */
    public static double saturationPressure(double t) {
        checkTemperature("Temperature", t);
        return satWater(t);
    }

    /**
     * Partial pressure of water vapour, Pa.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, % (relative to water)
     */
    public static double vaporPressure(double t, double rh) {
        checkTemperature("Dry bulb temperature", t);
        checkRelHum(rh);
        return rh / 100.0 * satWater(t);
    }

    /**
     * Vapour pressure deficit, Pa: saturation pressure minus actual vapour pressure.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     */
    public static double vpd(double t, double rh) {
        double pw = vaporPressure(t, rh);
        return satWater(t) - pw;
    }

    // Dew point and frost point

    /**
     * Dew point over liquid water, °C. Below 0 °C this is the dew point over supercooled
     * water; use {@link #frostPoint(double, double)} for the temperature at which frost forms.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     */
    public static double dewPoint(double t, double rh) {
        return dewPointFromVaporPressure(vaporPressure(t, rh));
    }

    /**
     * Dew/frost point, °C: the temperature at which condensate first forms. Returns the frost
     * point (over ice) when it is below 0 °C, and the dew point otherwise.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     */
    public static double frostPoint(double t, double rh) {
        return frostPointFromVaporPressure(vaporPressure(t, rh));
    }

    /**
     * Relative humidity from dew point, %.
     *
     * @param t  dry bulb temperature, °C
     * @param td dew point over water, °C
     */
    public static double relHumFromDewPoint(double t, double td) {
        checkTemperature("Dry bulb temperature", t);
        checkTemperature("Dew point", td);
        if (td > t) {
            throw new PsychroException("Dew point " + td + " °C is above the dry bulb temperature " + t + " °C.");
        }
        return 100.0 * satWater(td) / satWater(t);
    }

    /**
     * Converts a dew point measured at one pressure to the dew point at another pressure, °C.
     * Typical use: compressed air, converting between atmospheric dew point and pressure dew
     * point. Both pressures are absolute: add atmospheric pressure to a gauge reading.
     * Real gas effects at line pressure are ignored (ideal mixture, as in ASHRAE).
     *
     * @param td dew point at pressure p1, °C (over water)
     * @param p1 absolute pressure at which td applies, Pa
     * @param p2 absolute pressure at which the dew point is wanted, Pa
     */
    public static double dewPointAtPressure(double td, double p1, double p2) {
        checkTemperature("Dew point", td);
        checkPressure("Pressure p1", p1);
        checkPressure("Pressure p2", p2);
        double pws = satWater(td);
        if (pws >= p1) {
            throw new PsychroException("A dew point of " + td + " °C is not possible at " + p1
                    + " Pa: its vapour pressure exceeds the total pressure. Pressures must be absolute and in Pa.");
        }
        return dewPointFromVaporPressure(pws * p2 / p1);
    }

    // Humidity ratio and absolute humidity

    /**
     * Humidity ratio, kg of water per kg of dry air.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     * @param p  absolute pressure, Pa
     */
    public static double humidityRatio(double t, double rh, double p) {
        double pw = vaporPressure(t, rh);
        checkPressure("Pressure", p);
        return humidityRatioFromVaporPressure(pw, p);
    }

    /**
     * Humidity ratio from dew point, kg of water per kg of dry air.
     *
     * @param td dew point over water, °C
     * @param p  absolute pressure, Pa
     */
    public static double humidityRatioFromDewPoint(double td, double p) {
        checkTemperature("Dew point", td);
        checkPressure("Pressure", p);
        return humidityRatioFromVaporPressure(satWater(td), p);
    }

    /**
     * Relative humidity from humidity ratio, %.
     *
     * @param t dry bulb temperature, °C
     * @param w humidity ratio, kg/kg dry air
     * @param p absolute pressure, Pa
     */
    public static double relHumFromHumidityRatio(double t, double w, double p) {
        checkTemperature("Dry bulb temperature", t);
        checkPressure("Pressure", p);
        if (!(w >= 0.0) || Double.isInfinite(w)) {
            throw new PsychroException("Humidity ratio " + w + " kg/kg must be zero or positive.");
        }
        return relHumFromHumidityRatioUnchecked(t, w, p);
    }

    /**
     * Absolute humidity (water vapour density), kg/m³. Does not depend on total pressure.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     */
    public static double absoluteHumidity(double t, double rh) {
        double pw = vaporPressure(t, rh);
        return absoluteHumidityFromVaporPressure(t, pw);
    }

    // Wet bulb

    /**
     * Thermodynamic wet bulb temperature, °C. Below 0 °C the wet bulb is an ice bulb, as in
     * ASHRAE eq. 35. When air below 0 °C is supersaturated with respect to ice (possible while
     * still below 100 % RH over water), the ice bulb is slightly above the dry bulb. Just around
     * 0 °C the two ASHRAE equations overlap and some states have both a wet bulb just above 0 °C
     * and an ice bulb just below; the wet bulb (liquid) value is returned.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     * @param p  absolute pressure, Pa
     */
    public static double wetBulb(double t, double rh, double p) {
        double w = humidityRatio(t, rh, p);
        return wetBulbFromHumidityRatio(t, w, p);
    }

    /**
     * Relative humidity from wet bulb temperature, %.
     *
     * @param t  dry bulb temperature, °C
     * @param tw wet bulb temperature, °C
     * @param p  absolute pressure, Pa
     */
    public static double relHumFromWetBulb(double t, double tw, double p) {
        checkTemperature("Dry bulb temperature", t);
        checkTemperature("Wet bulb temperature", tw);
        checkPressure("Pressure", p);
        if (tw > t) {
            throw new PsychroException("Wet bulb temperature " + tw + " °C is above the dry bulb temperature " + t + " °C.");
        }
        double w = humidityRatioAtWetBulb(t, tw, p);
        if (Double.isInfinite(w)) {
            throw new PsychroException("Wet bulb temperature " + tw + " °C is at or above the boiling point at " + p + " Pa.");
        }
        if (w < 0.0) {
            throw new PsychroException("Wet bulb temperature " + tw + " °C is too low for a dry bulb of " + t
                    + " °C: no physical humidity matches.");
        }
        return relHumFromHumidityRatioUnchecked(t, w, p);
    }

    // Enthalpy, volume, density

    /**
     * Moist air enthalpy, J per kg of dry air (reference: dry air and liquid water at 0 °C).
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     * @param p  absolute pressure, Pa
     */
    public static double enthalpy(double t, double rh, double p) {
        return enthalpyFromHumidityRatio(t, humidityRatio(t, rh, p));
    }

    /**
     * Moist air specific volume, m³ per kg of dry air.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     * @param p  absolute pressure, Pa
     */
    public static double specificVolume(double t, double rh, double p) {
        return specificVolumeFromHumidityRatio(t, humidityRatio(t, rh, p), p);
    }

    /**
     * Moist air density, kg/m³.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     * @param p  absolute pressure, Pa
     */
    public static double density(double t, double rh, double p) {
        double w = humidityRatio(t, rh, p);
        return (1.0 + w) / specificVolumeFromHumidityRatio(t, w, p);
    }

    // Atmosphere

    /**
     * Standard atmosphere pressure at an altitude, Pa (ASHRAE eq. 3). Use it when a site has
     * no barometric pressure measurement.
     *
     * @param z altitude above sea level, m
     */
    public static double pressureFromAltitude(double z) {
        if (!(z >= Z_MIN && z <= Z_MAX)) {
            throw new PsychroException("Altitude " + z + " m is outside the valid range of " + Z_MIN + " to " + Z_MAX + " m.");
        }
        return 101325.0 * Math.pow(1.0 - 2.25577e-5 * z, 5.2559);
    }

    // Full state

    /**
     * Every property of the air state in one call. Keys, in order: dryBulb, relHum, pressure,
     * dewPoint, frostPoint, wetBulb, humidityRatio, absoluteHumidity, vaporPressure,
     * saturationPressure, vpd, enthalpy, specificVolume, density. Units as for the individual
     * functions. Dew point, frost point and wet bulb are null when they fall outside the valid
     * range (for example a dew point below -100 °C at very low humidity); the call itself only
     * fails when the inputs are invalid.
     *
     * @param t  dry bulb temperature, °C
     * @param rh relative humidity, %
     * @param p  absolute pressure, Pa
     */
    public static Map<String, Double> state(double t, double rh, double p) {
        double pw = vaporPressure(t, rh);
        checkPressure("Pressure", p);
        double w = humidityRatioFromVaporPressure(pw, p);
        double pws = satWater(t);
        double v = specificVolumeFromHumidityRatio(t, w, p);

        Map<String, Double> s = new LinkedHashMap<>();
        s.put("dryBulb", t);
        s.put("relHum", rh);
        s.put("pressure", p);
        s.put("dewPoint", orNull(() -> dewPointFromVaporPressure(pw)));
        s.put("frostPoint", orNull(() -> frostPointFromVaporPressure(pw)));
        s.put("wetBulb", orNull(() -> wetBulbFromHumidityRatio(t, w, p)));
        s.put("humidityRatio", w);
        s.put("absoluteHumidity", absoluteHumidityFromVaporPressure(t, pw));
        s.put("vaporPressure", pw);
        s.put("saturationPressure", pws);
        s.put("vpd", pws - pw);
        s.put("enthalpy", enthalpyFromHumidityRatio(t, w));
        s.put("specificVolume", v);
        s.put("density", (1.0 + w) / v);
        return Collections.unmodifiableMap(s);
    }

    // Internal: saturation equations

    /** Saturation pressure over liquid water, Pa: ASHRAE eq. 6 from 0 °C, Murphy and Koop below. */
    static double satWater(double t) {
        double tk = t + KELVIN;
        if (t >= FREEZING_POINT) {
            return Math.exp(-5.8002206e3 / tk + 1.3914993 - 4.8640239e-2 * tk + 4.1764768e-5 * tk * tk
                    - 1.4452093e-8 * tk * tk * tk + 6.5459673 * Math.log(tk));
        }
        double lnT = Math.log(tk);
        return Math.exp(54.842763 - 6763.22 / tk - 4.210 * lnT + 0.000367 * tk
                + Math.tanh(0.0415 * (tk - 218.8)) * (53.878 - 1331.22 / tk - 9.44523 * lnT + 0.014025 * tk));
    }

    /** Saturation pressure over ice, Pa: ASHRAE eq. 5, valid from -100 °C to the triple point. */
    static double satIce(double t) {
        double tk = t + KELVIN;
        return Math.exp(-5.6745359e3 / tk + 6.3925247 - 9.677843e-3 * tk + 6.2215701e-7 * tk * tk
                + 2.0747825e-9 * tk * tk * tk - 9.484024e-13 * tk * tk * tk * tk + 4.1635019 * Math.log(tk));
    }

    /** ASHRAE saturation pressure as used by the wet bulb equations: ice up to the triple point, water above. */
    static double satAshrae(double t) {
        return t <= TRIPLE_POINT ? satIce(t) : satWater(t);
    }

    // Internal: shared calculations (inputs already validated)

    static double dewPointFromVaporPressure(double pw) {
        if (pw < satWater(T_MIN)) {
            throw new PsychroException("The dew point is below " + T_MIN + " °C, outside the valid range (humidity too low).");
        }
        if (pw > satWater(T_MAX)) {
            throw new PsychroException("The dew point is above " + T_MAX + " °C, outside the valid range.");
        }
        return invert(Psychrometrics::satWater, pw, T_MIN, T_MAX);
    }

    static double frostPointFromVaporPressure(double pw) {
        if (pw >= satIce(TRIPLE_POINT)) {
            return dewPointFromVaporPressure(pw);
        }
        if (pw < satIce(T_MIN)) {
            throw new PsychroException("The frost point is below " + T_MIN + " °C, outside the valid range (humidity too low).");
        }
        return invert(Psychrometrics::satIce, pw, T_MIN, TRIPLE_POINT);
    }

    static double humidityRatioFromVaporPressure(double pw, double p) {
        if (pw >= p) {
            throw new PsychroException("Vapour pressure " + pw + " Pa is not below the total pressure " + p
                    + " Pa. Pressure must be absolute and in Pa.");
        }
        return EPS * pw / (p - pw);
    }

    static double relHumFromHumidityRatioUnchecked(double t, double w, double p) {
        double pw = p * w / (EPS + w);
        double rh = 100.0 * pw / satWater(t);
        if (rh > 100.0 * (1.0 + SATURATION_SLACK)) {
            throw new PsychroException("The humidity is above saturation at " + t + " °C (relative humidity would be " + rh + " %).");
        }
        return Math.min(rh, 100.0);
    }

    static double absoluteHumidityFromVaporPressure(double t, double pw) {
        return EPS * pw / (R_DA * (t + KELVIN));
    }

    static double enthalpyFromHumidityRatio(double t, double w) {
        return (1.006 * t + w * (2501.0 + 1.86 * t)) * 1000.0;
    }

    static double specificVolumeFromHumidityRatio(double t, double w, double p) {
        return R_DA * (t + KELVIN) * (1.0 + INV_EPS * w) / p;
    }

    /**
     * Humidity ratio that a wet bulb temperature tw implies, ASHRAE eq. 33 (tw at or above
     * freezing) and eq. 35 (ice bulb). Returns +infinity when tw is at or above the boiling
     * point at pressure p, which keeps the wet bulb solver below boiling.
     */
    static double humidityRatioAtWetBulb(double t, double tw, double p) {
        double pws = satAshrae(tw);
        if (pws >= p) {
            return Double.POSITIVE_INFINITY;
        }
        double wsStar = EPS * pws / (p - pws);
        if (tw >= FREEZING_POINT) {
            return ((2501.0 - 2.326 * tw) * wsStar - 1.006 * (t - tw)) / (2501.0 + 1.86 * t - 4.186 * tw);
        }
        return ((2830.0 - 0.24 * tw) * wsStar - 1.006 * (t - tw)) / (2830.0 + 1.86 * t - 2.1 * tw);
    }

    static double wetBulbFromHumidityRatio(double t, double w, double p) {
        // Upper end of the search is normally the dry bulb, but ice supersaturation below 0 °C
        // puts the ice bulb slightly above the dry bulb, so widen it until it brackets.
        double hi = t;
        while (humidityRatioAtWetBulb(t, hi, p) < w) {
            hi += 1.0;
            if (hi > T_MAX) {
                throw new PsychroException("No wet bulb temperature exists for these conditions.");
            }
        }
        // ASHRAE's wet bulb (eq. 33, from 0 °C) and ice bulb (eq. 35, below 0 °C) equations
        // overlap just around 0 °C, so some air states satisfy both: one root on each side.
        // Prefer the wet bulb (liquid water) root whenever one exists.
        if (hi >= FREEZING_POINT && humidityRatioAtWetBulb(t, FREEZING_POINT, p) <= w) {
            return solveWetBulb(t, w, p, FREEZING_POINT, hi);
        }
        if (humidityRatioAtWetBulb(t, T_MIN, p) > w) {
            throw new PsychroException("The wet bulb temperature is below " + T_MIN + " °C, outside the valid range.");
        }
        return solveWetBulb(t, w, p, T_MIN, Math.min(hi, FREEZING_POINT));
    }

    /** Bisection on ASHRAE eq. 33/35 for the wet bulb in [lo, hi]. */
    private static double solveWetBulb(double t, double w, double p, double lo, double hi) {
        for (int i = 0; i < SOLVER_MAX_ITER && hi - lo > SOLVER_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            if (humidityRatioAtWetBulb(t, mid, p) < w) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private static Double orNull(DoubleSupplier f) {
        try {
            return f.getAsDouble();
        } catch (PsychroException e) {
            return null;
        }
    }

    /** Bisection for f(x) = target on [lo, hi], f increasing. */
    private static double invert(DoubleUnaryOperator f, double target, double lo, double hi) {
        for (int i = 0; i < SOLVER_MAX_ITER && hi - lo > SOLVER_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            if (f.applyAsDouble(mid) < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    // Internal: validation

    private static void checkTemperature(String name, double t) {
        if (!(t >= T_MIN && t <= T_MAX)) {
            throw new PsychroException(name + " " + t + " °C is outside the valid range of " + T_MIN + " to " + T_MAX + " °C.");
        }
    }

    private static void checkRelHum(double rh) {
        if (!(rh >= 0.0 && rh <= 100.0)) {
            throw new PsychroException("Relative humidity " + rh + " % is outside the valid range of 0 to 100 %.");
        }
    }

    private static void checkPressure(String name, double p) {
        if (!(p > 0.0) || Double.isInfinite(p)) {
            throw new PsychroException(name + " " + p + " Pa must be a positive absolute pressure in Pa.");
        }
    }
}
