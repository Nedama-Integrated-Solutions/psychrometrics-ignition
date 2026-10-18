package com.nedama.psychro;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Line-based test driver for src/test/python/random_check.py. Lives in test sources, so it
 * is never packaged into the module.
 *
 * <p>Input, one call per line: {@code functionName arg1 arg2 ...}
 * <br>Output, one line per call: {@code OK value}, {@code ERR message} for a PsychroException,
 * or {@code CRASH exception} for anything else (always a bug).
 */
public final class PsychroCli {

    private PsychroCli() {
    }

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16),
                false, "UTF-8");
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                out.println("OK " + call(line.split("\\s+")));
            } catch (PsychroException e) {
                out.println("ERR " + e.getMessage());
            } catch (RuntimeException e) {
                out.println("CRASH " + e);
            }
        }
        out.flush();
    }

    private static String call(String[] a) {
        double[] x = new double[a.length - 1];
        for (int i = 1; i < a.length; i++) {
            x[i - 1] = Double.parseDouble(a[i]);
        }
        switch (a[0]) {
            case "saturationPressure":
                return str(Psychrometrics.saturationPressure(x[0]));
            case "vaporPressure":
                return str(Psychrometrics.vaporPressure(x[0], x[1]));
            case "vpd":
                return str(Psychrometrics.vpd(x[0], x[1]));
            case "dewPoint":
                return str(Psychrometrics.dewPoint(x[0], x[1]));
            case "frostPoint":
                return str(Psychrometrics.frostPoint(x[0], x[1]));
            case "relHumFromDewPoint":
                return str(Psychrometrics.relHumFromDewPoint(x[0], x[1]));
            case "dewPointAtPressure":
                return str(Psychrometrics.dewPointAtPressure(x[0], x[1], x[2]));
            case "humidityRatio":
                return str(Psychrometrics.humidityRatio(x[0], x[1], x[2]));
            case "humidityRatioFromDewPoint":
                return str(Psychrometrics.humidityRatioFromDewPoint(x[0], x[1]));
            case "relHumFromHumidityRatio":
                return str(Psychrometrics.relHumFromHumidityRatio(x[0], x[1], x[2]));
            case "absoluteHumidity":
                return str(Psychrometrics.absoluteHumidity(x[0], x[1]));
            case "wetBulb":
                return str(Psychrometrics.wetBulb(x[0], x[1], x[2]));
            case "relHumFromWetBulb":
                return str(Psychrometrics.relHumFromWetBulb(x[0], x[1], x[2]));
            case "enthalpy":
                return str(Psychrometrics.enthalpy(x[0], x[1], x[2]));
            case "specificVolume":
                return str(Psychrometrics.specificVolume(x[0], x[1], x[2]));
            case "density":
                return str(Psychrometrics.density(x[0], x[1], x[2]));
            case "pressureFromAltitude":
                return str(Psychrometrics.pressureFromAltitude(x[0]));
            case "state": {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Double> e : Psychrometrics.state(x[0], x[1], x[2]).entrySet()) {
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue() == null ? "null" : str(e.getValue()));
                }
                return sb.toString();
            }
            default:
                throw new IllegalArgumentException("Unknown function " + a[0]);
        }
    }

    private static String str(double v) {
        return Double.toString(v);
    }
}
