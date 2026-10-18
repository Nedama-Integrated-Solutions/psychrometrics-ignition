#!/usr/bin/env python3
"""
Psychrometrics accuracy test, part 2 of 2.

Reads the CSV written by src/test/ignition/accuracy_test.py in the Ignition Script Console and
checks every Ignition result against independent Python references (the same ones random_check.py
uses: PsychroLib above 0 °C, Murphy and Koop below 0 °C, PsychroLib's ice equations for frost).
Then it prints the named scenarios side by side: Ignition, PsychroLib and CoolProp.

Usage, from the repo root with the venv active, with the path accuracy_test.py printed:
    python psychro-core\\src\\test\\python\\compare_ignition.py --csv C:\\Users\\you\\AppData\\Local\\Temp\\....csv

Exit code 0 when every Ignition result matches, 1 otherwise.
"""

import argparse
import csv
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import random_check as R          # noqa: E402  (shared reference implementation)

pl = R.pl
HAPropsSI = R.HAPropsSI
ERR = "ERR"


# Expected values, with the module's validation rules

def pw_of(t, rh):
    R.check_t(t)
    R.check_rh(rh)
    return rh / 100.0 * R.sat_w(t)


def w_of(t, rh, p):
    R.check_p(p)
    return R.w_from_pw(pw_of(t, rh), p)


def rh_from_wb(t, tw, p):
    R.check_t(t); R.check_t(tw); R.check_p(p)
    if tw > t:
        raise ValueError("wet bulb above dry bulb")
    w = R.w_at_wetbulb(t, tw, p)
    if math.isinf(w) or w < 0.0:
        raise ValueError("no physical humidity")
    return R.rh_from_w(t, w, p)


def rh_from_dp(t, td):
    R.check_t(t); R.check_t(td)
    if td > t:
        raise ValueError("dew point above dry bulb")
    return 100.0 * R.sat_w(td) / R.sat_w(t)


def rh_from_w(t, w, p):
    R.check_t(t); R.check_p(p)
    if not (w >= 0.0):
        raise ValueError("negative humidity ratio")
    return R.rh_from_w(t, w, p)


def dp_at_pressure(td, p1, p2):
    R.check_p(p1); R.check_p(p2)
    s = R.sat_w(td)
    if s >= p1:
        raise ValueError("impossible dew point")
    return R.dew_w(s * p2 / p1)


def altitude(z):
    if not (-1000.0 <= z <= 11000.0):
        raise ValueError("altitude out of range")
    return pl.GetStandardAtmPressure(z)


# func: (expected value function, absolute tolerance, relative tolerance)
EXPECTED = {
    "saturationPressure":        (lambda t: R.sat_w(t), 0.0, 1e-12),
    "vaporPressure":             (pw_of, 0.0, 1e-12),
    "vpd":                       (lambda t, rh: R.sat_w(t) - pw_of(t, rh), 1e-9, 1e-9),
    "dewPoint":                  (lambda t, rh: R.dew_w(pw_of(t, rh)), 1e-6, 0.0),
    "frostPoint":                (lambda t, rh: R.frost(pw_of(t, rh)), 1e-6, 0.0),
    "absoluteHumidity":          (lambda t, rh: R.EPS * pw_of(t, rh) / (R.R_DA * (t + 273.15)), 0.0, 1e-12),
    "humidityRatio":             (w_of, 1e-15, 1e-10),
    "enthalpy":                  (lambda t, rh, p: R.enthalpy(t, w_of(t, rh, p)), 1e-6, 1e-10),
    "specificVolume":            (lambda t, rh, p: R.volume(t, w_of(t, rh, p), p), 0.0, 1e-10),
    "density":                   (lambda t, rh, p: (1.0 + w_of(t, rh, p)) / R.volume(t, w_of(t, rh, p), p), 0.0, 1e-10),
    "relHumFromWetBulb":         (rh_from_wb, 1e-9, 1e-9),
    "relHumFromDewPoint":        (rh_from_dp, 1e-9, 1e-9),
    "humidityRatioFromDewPoint": (lambda td, p: (R.check_p(p), R.w_from_pw(R.sat_w(td), p))[1], 1e-15, 1e-10),
    "relHumFromHumidityRatio":   (rh_from_w, 1e-9, 1e-9),
    "dewPointAtPressure":        (dp_at_pressure, 1e-6, 0.0),
    "pressureFromAltitude":      (altitude, 0.0, 1e-12),
}


def check_wet_bulb(args, got):
    """Wet bulb: PsychroLib where its answer is unique and valid, otherwise the ASHRAE residual."""
    t, rh, p = args
    try:
        w = w_of(t, rh, p)
    except ValueError:
        return got == ERR, 0.0
    if got == ERR:
        return False, math.inf
    overlap = R.wet_overlap(t, w, p)
    if t > R.TP and w > 1e-6 and pl.GetSatVapPres(t) < p and not overlap:
        err = abs(got - pl.GetTWetBulbFromHumRatio(t, w, p))
        return err <= 1e-6, err
    if overlap and got < 0.0:          # the module must return the liquid root
        return False, math.inf
    if abs(got) < 1e-6:
        return True, 0.0
    err = abs(R.w_at_wetbulb(t, got, p) - w) / max(w, 1e-6)
    return err <= 1e-6, err


# Side-by-side scenario table

def fmt(v, digits):
    return "n/a" if v is None else ("%.*f" % (digits, v))


def safe(fn):
    try:
        v = fn()
        return None if v is None or (isinstance(v, float) and math.isnan(v)) else v
    except Exception:
        return None


def scenario_rows(tag, results):
    """results: {func: (args, value)} for one scenario. Yields printable rows."""
    if "dewPointAtPressure" in results:
        args, got = results["dewPointAtPressure"]
        td, p1, p2 = args
        pw2 = R.sat_w(td) * p2 / p1
        ref = safe(lambda: pl.GetTDewPointFromVapPres(200.0, pw2))
        ref = ref if ref is not None and ref > R.TP else None   # below 0 °C PsychroLib gives a frost point
        yield ("dew point at 1 atm (°C)", got, ref, None, 3)
        return
    args = results["wetBulb"][0]
    t, rh, p = args
    pw = pw_of(t, rh)
    w = R.w_from_pw(pw, p)
    tk = t + 273.15
    cp = HAPropsSI
    yield ("dew point (°C)", results["dewPoint"][1],
           safe(lambda: pl.GetTDewPointFromVapPres(max(t, R.TP), pw)) if R.dew_w(pw) > R.TP else None,
           safe(lambda: cp("D", "T", tk, "P", p, "W", w) - 273.15) if cp and R.dew_w(pw) > R.TP else None, 3)
    yield ("frost point (°C)", results["frostPoint"][1],
           safe(lambda: pl.GetTDewPointFromVapPres(max(t, R.TP), pw)),
           safe(lambda: cp("D", "T", tk, "P", p, "W", w) - 273.15) if cp else None, 3)
    yield ("wet bulb (°C)", results["wetBulb"][1],
           safe(lambda: pl.GetTWetBulbFromHumRatio(t, w, p)),
           safe(lambda: cp("B", "T", tk, "P", p, "W", w) - 273.15) if cp else None, 3)
    yield ("humidity ratio (g/kg)", results["humidityRatio"][1] * 1000.0,
           safe(lambda: pl.GetHumRatioFromVapPres(pw, p) * 1000.0),
           safe(lambda: cp("W", "T", tk, "P", p, "R", rh / 100.0) * 1000.0) if cp and t > 0.0 else None, 3)
    yield ("enthalpy (kJ/kg)", results["enthalpy"][1] / 1000.0,
           safe(lambda: pl.GetMoistAirEnthalpy(t, w) / 1000.0), None, 2)
    yield ("density (kg/m3)", results["density"][1],
           safe(lambda: pl.GetMoistAirDensity(t, w, p)),
           safe(lambda: 1.0 / cp("Vha", "T", tk, "P", p, "W", w)) if cp else None, 4)


# Main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="CSV path that accuracy_test.py printed")
    ap.add_argument("--show", type=int, default=10, help="failures to print per function (default 10)")
    args = ap.parse_args()

    path = Path(args.csv)
    if not path.exists():
        sys.exit("Not found: %s. Run accuracy_test.py in the Ignition Script Console first." % path)

    groups, scenarios = {}, {}
    with path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            func = row["func"]
            vals = tuple(float(row[k]) for k in ("a1", "a2", "a3") if row[k] != "")
            got = ERR if row["result"] == ERR else float(row["result"])
            tag = row["tag"]
            if tag.startswith("S:"):
                scenarios.setdefault(tag[2:], {})[func] = (vals, got)
            name = "expected errors" if tag == "E" else func
            g = groups.setdefault(name, {"n": 0, "fail": [], "max": 0.0})
            g["n"] += 1

            if tag == "E":
                ok, err = got == ERR, 0.0
            elif func == "wetBulb":
                ok, err = check_wet_bulb(vals, got)
            else:
                fn, atol, rtol = EXPECTED[func]
                try:
                    exp = fn(*vals)
                except (ValueError, ZeroDivisionError, OverflowError):
                    exp = ERR
                if exp == ERR or got == ERR:
                    ok, err = exp == got, 0.0
                else:
                    err = abs(got - exp)
                    ok = err <= atol + rtol * abs(exp)
            if math.isfinite(err):
                g["max"] = max(g["max"], err)
            if not ok:
                g["fail"].append((tag, func, vals, got))

    total = sum(g["n"] for g in groups.values())
    failed = sum(len(g["fail"]) for g in groups.values())
    width = max(len(k) for k in groups)
    print("Ignition results checked against the Python reference (%s)\n" % path)
    print("%-*s %7s %7s %12s" % (width, "Function", "Cases", "Failed", "Max diff"))
    print("-" * (width + 29))
    for name in sorted(groups):
        g = groups[name]
        print("%-*s %7d %7d %12.3g" % (width, name, g["n"], len(g["fail"]), g["max"]))
    for name in sorted(groups):
        for tag, func, vals, got in groups[name]["fail"][:args.show]:
            print("FAIL %s %s%s -> %s" % (tag, func, vals, got))
    print("\n%s: %d Ignition results, %d failed" % ("PASS" if failed == 0 else "FAIL", total, failed))

    print("\nScenarios side by side. Ignition and PsychroLib use the same ASHRAE equations, so they should")
    print("agree to many decimals. CoolProp is an independent real-gas model, so small differences there are")
    print("physics, not errors. n/a means that tool has no comparable value.\n")
    print("%-34s %-24s %10s %11s %10s" % ("Scenario", "Property", "Ignition", "PsychroLib", "CoolProp"))
    print("-" * 93)
    for name, results in scenarios.items():
        first = True
        for prop, got, ref, cp, digits in scenario_rows(name, results):
            print("%-34s %-24s %10s %11s %10s" % (name if first else "", prop,
                                                  "ERR" if got == ERR else fmt(got, digits), fmt(ref, digits), fmt(cp, digits)))
            first = False
        print()

    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    main()
