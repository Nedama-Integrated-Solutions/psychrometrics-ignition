package com.nedama.psychro.common83;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.script.hints.ScriptArg;
import com.inductiveautomation.ignition.common.script.hints.JythonElement;
import com.nedama.psychro.Psychrometrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code system.psychro} scripting namespace. The functions are pure calculations, so the same
 * object serves the gateway, Vision client and designer scopes (Perspective scripts run on the gateway).
 *
 * <p>Units: temperature °C, pressure Pa (absolute), relative humidity % (0 to 100, relative to water),
 * humidity ratio kg/kg dry air, enthalpy J/kg dry air. Invalid inputs raise
 * {@link com.nedama.psychro.PsychroException}, a java.lang.IllegalArgumentException.
 */
public class PsychroScriptModule {

    private static final String DOCS = "PsychroScriptModule";

    static {
        BundleUtil.get().addBundle(
                PsychroScriptModule.class.getSimpleName(),
                PsychroScriptModule.class.getClassLoader(),
                PsychroScriptModule.class.getName().replace('.', '/'));
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double saturationPressure(@ScriptArg("temperature") double temperature) {
        return Psychrometrics.saturationPressure(temperature);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double vaporPressure(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum) {
        return Psychrometrics.vaporPressure(dryBulb, relHum);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double vpd(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum) {
        return Psychrometrics.vpd(dryBulb, relHum);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double dewPoint(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum) {
        return Psychrometrics.dewPoint(dryBulb, relHum);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double frostPoint(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum) {
        return Psychrometrics.frostPoint(dryBulb, relHum);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double relHumFromDewPoint(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("dewPoint") double dewPoint) {
        return Psychrometrics.relHumFromDewPoint(dryBulb, dewPoint);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double dewPointAtPressure(@ScriptArg("dewPoint") double dewPoint,
                                     @ScriptArg("fromPressure") double fromPressure,
                                     @ScriptArg("toPressure") double toPressure) {
        return Psychrometrics.dewPointAtPressure(dewPoint, fromPressure, toPressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double humidityRatio(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum,
                                @ScriptArg("pressure") double pressure) {
        return Psychrometrics.humidityRatio(dryBulb, relHum, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double humidityRatioFromDewPoint(@ScriptArg("dewPoint") double dewPoint, @ScriptArg("pressure") double pressure) {
        return Psychrometrics.humidityRatioFromDewPoint(dewPoint, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double relHumFromHumidityRatio(@ScriptArg("dryBulb") double dryBulb,
                                          @ScriptArg("humidityRatio") double humidityRatio,
                                          @ScriptArg("pressure") double pressure) {
        return Psychrometrics.relHumFromHumidityRatio(dryBulb, humidityRatio, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double absoluteHumidity(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum) {
        return Psychrometrics.absoluteHumidity(dryBulb, relHum);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double wetBulb(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum,
                          @ScriptArg("pressure") double pressure) {
        return Psychrometrics.wetBulb(dryBulb, relHum, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double relHumFromWetBulb(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("wetBulb") double wetBulb,
                                    @ScriptArg("pressure") double pressure) {
        return Psychrometrics.relHumFromWetBulb(dryBulb, wetBulb, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double enthalpy(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum,
                           @ScriptArg("pressure") double pressure) {
        return Psychrometrics.enthalpy(dryBulb, relHum, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double specificVolume(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum,
                                 @ScriptArg("pressure") double pressure) {
        return Psychrometrics.specificVolume(dryBulb, relHum, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double density(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum,
                          @ScriptArg("pressure") double pressure) {
        return Psychrometrics.density(dryBulb, relHum, pressure);
    }

    @JythonElement(docBundlePrefix = DOCS)
    public double pressureFromAltitude(@ScriptArg("altitude") double altitude) {
        return Psychrometrics.pressureFromAltitude(altitude);
    }

    /** Returns a new, editable map each call; in Jython it behaves like a dictionary. */
    @JythonElement(docBundlePrefix = DOCS)
    public Map<String, Double> state(@ScriptArg("dryBulb") double dryBulb, @ScriptArg("relHum") double relHum,
                                     @ScriptArg("pressure") double pressure) {
        return new LinkedHashMap<>(Psychrometrics.state(dryBulb, relHum, pressure));
    }
}
