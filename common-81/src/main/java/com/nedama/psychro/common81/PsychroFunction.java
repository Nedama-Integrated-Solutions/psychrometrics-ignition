package com.nedama.psychro.common81;

import com.inductiveautomation.ignition.common.expressions.Expression;
import com.inductiveautomation.ignition.common.expressions.ExpressionException;
import com.inductiveautomation.ignition.common.expressions.functions.AbstractFunction;
import com.inductiveautomation.ignition.common.model.values.BasicQualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.nedama.psychro.PsychroException;
import com.nedama.psychro.Psychrometrics;

import java.util.List;

/**
 * One psychro* expression function. Instances are stateless and shared, as Ignition expects.
 *
 * <p>Quality: the result carries the first non-good input quality, so a bad sensor makes the result bad
 * too. A null input gives a null result. Invalid inputs raise an ExpressionException with the reason.
 */
public final class PsychroFunction extends AbstractFunction {

    /** The calculation behind a function, given its arguments as doubles. */
    @FunctionalInterface
    interface Calculation {
        double apply(double[] a);
    }

    private final String name;
    private final String argDoc;
    private final int arity;
    private final Calculation calculation;

    PsychroFunction(String name, String argDoc, int arity, Calculation calculation) {
        this.name = name;
        this.argDoc = argDoc;
        this.arity = arity;
        this.calculation = calculation;
    }

    /** Every expression function the module provides, in the order they appear in the designer. */
    static List<PsychroFunction> all() {
        String t = "dryBulb (°C)";
        String rh = "relHum (%)";
        String p = "pressure (Pa)";
        return List.of(
                new PsychroFunction("psychroDewPoint", t + ", " + rh, 2,
                        a -> Psychrometrics.dewPoint(a[0], a[1])),
                new PsychroFunction("psychroFrostPoint", t + ", " + rh, 2,
                        a -> Psychrometrics.frostPoint(a[0], a[1])),
                new PsychroFunction("psychroWetBulb", t + ", " + rh + ", " + p, 3,
                        a -> Psychrometrics.wetBulb(a[0], a[1], a[2])),
                new PsychroFunction("psychroHumidityRatio", t + ", " + rh + ", " + p, 3,
                        a -> Psychrometrics.humidityRatio(a[0], a[1], a[2])),
                new PsychroFunction("psychroAbsoluteHumidity", t + ", " + rh, 2,
                        a -> Psychrometrics.absoluteHumidity(a[0], a[1])),
                new PsychroFunction("psychroEnthalpy", t + ", " + rh + ", " + p, 3,
                        a -> Psychrometrics.enthalpy(a[0], a[1], a[2])),
                new PsychroFunction("psychroSpecificVolume", t + ", " + rh + ", " + p, 3,
                        a -> Psychrometrics.specificVolume(a[0], a[1], a[2])),
                new PsychroFunction("psychroDensity", t + ", " + rh + ", " + p, 3,
                        a -> Psychrometrics.density(a[0], a[1], a[2])),
                new PsychroFunction("psychroVaporPressure", t + ", " + rh, 2,
                        a -> Psychrometrics.vaporPressure(a[0], a[1])),
                new PsychroFunction("psychroSatPressure", "temperature (°C)", 1,
                        a -> Psychrometrics.saturationPressure(a[0])),
                new PsychroFunction("psychroVpd", t + ", " + rh, 2,
                        a -> Psychrometrics.vpd(a[0], a[1])),
                new PsychroFunction("psychroRhFromDewPoint", t + ", dewPoint (°C)", 2,
                        a -> Psychrometrics.relHumFromDewPoint(a[0], a[1])),
                new PsychroFunction("psychroRhFromWetBulb", t + ", wetBulb (°C), " + p, 3,
                        a -> Psychrometrics.relHumFromWetBulb(a[0], a[1], a[2])),
                new PsychroFunction("psychroRhFromHumidityRatio", t + ", humidityRatio (kg/kg), " + p, 3,
                        a -> Psychrometrics.relHumFromHumidityRatio(a[0], a[1], a[2])),
                new PsychroFunction("psychroHumidityRatioFromDewPoint", "dewPoint (°C), " + p, 2,
                        a -> Psychrometrics.humidityRatioFromDewPoint(a[0], a[1])),
                new PsychroFunction("psychroDewPointAtPressure", "dewPoint (°C), fromPressure (Pa), toPressure (Pa)", 3,
                        a -> Psychrometrics.dewPointAtPressure(a[0], a[1], a[2])),
                new PsychroFunction("psychroPressureFromAltitude", "altitude (m)", 1,
                        a -> Psychrometrics.pressureFromAltitude(a[0]))
        );
    }

    String getName() {
        return name;
    }

    @Override
    public Class<?> getType() {
        return Double.class;
    }

    @Override
    protected String getFunctionDisplayName() {
        return name;
    }

    @Override
    public String getArgDocString() {
        return argDoc;
    }

    @Override
    protected boolean validateNumArgs(int num) {
        return num == arity;
    }

    @Override
    public QualifiedValue execute(Expression[] expressions) throws ExpressionException {
        double[] args = new double[expressions.length];
        QualityCode quality = QualityCode.Good;
        for (int i = 0; i < expressions.length; i++) {
            QualifiedValue qv = expressions[i].execute();
            QualityCode q = qv.getQuality();
            if (q != null && !q.isGood() && quality.isGood()) {
                quality = q;
            }
            Object value = qv.getValue();
            if (value == null) {
                return new BasicQualifiedValue(null, quality);
            }
            args[i] = toDouble(value, i);
        }
        try {
            return new BasicQualifiedValue(calculation.apply(args), quality);
        } catch (PsychroException e) {
            throw new ExpressionException(name + ": " + e.getMessage());
        }
    }

    private double toDouble(Object value, int index) throws ExpressionException {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                // fall through to the error below
            }
        }
        throw new ExpressionException(name + ": argument " + (index + 1) + " is not a number: " + value);
    }
}
