#!/usr/bin/env python3
"""
Randomised cross-check of the Psychrometrics Java core.

References
    Above 0 °C   PsychroLib (pip install psychrolib), which implements the same ASHRAE 2017
                 equations and is tested against ASHRAE tables. Its solver tolerance is
                 tightened here so differences reflect the Java code, not PsychroLib's rounding.
    Below 0 °C   An independent Python implementation of Murphy and Koop (2005) for
                 supercooled water, plus PsychroLib's ice equations for the frost point.
    Wet bulb     PsychroLib where its bracket is valid, plus a residual check everywhere:
                 the Java wet bulb is fed back into ASHRAE eq. 33/35 and must reproduce
                 the humidity ratio.
    Optional     CoolProp (pip install CoolProp), an independent real gas model. Its
                 differences from ASHRAE are physical, so this group uses loose tolerances
                 and only checks that nothing is grossly wrong, including above boiling.

Usage, from the psychro-core folder:
    mvn -q test-compile
    python src/test/python/random_check.py --n 5000 --seed 1

Exit code 0 when every check passes, 1 otherwise.
"""

import argparse
import math
import os
import random
import subprocess
import sys
from pathlib import Path

import psychrolib as pl

pl.SetUnitSystem(pl.SI)
pl.PSYCHROLIB_TOLERANCE = 1e-10

try:
    from CoolProp.HumidAirProp import HAPropsSI
except ImportError:
    HAPropsSI = None

EPS = 0.621945
R_DA = 287.042
TP = 0.01
ERR = "ERR"


# Reference implementation (mirrors the Java semantics, built on PsychroLib where it applies)

def check_t(t):
    if not (-100.0 <= t <= 200.0):
        raise ValueError("temperature out of range")


def check_rh(rh):
    if not (0.0 <= rh <= 100.0):
        raise ValueError("rh out of range")


def check_p(p):
    if not (p > 0.0) or math.isinf(p):
        raise ValueError("bad pressure")


def hw_water(t):
    tk = t + 273.15
    return math.exp(-5.8002206e3 / tk + 1.3914993 - 4.8640239e-2 * tk + 4.1764768e-5 * tk ** 2
                    - 1.4452093e-8 * tk ** 3 + 6.5459673 * math.log(tk))


def mk_water(t):
    """Murphy and Koop (2005) eq. 10, supercooled water, Pa."""
    tk = t + 273.15
    return math.exp(54.842763 - 6763.22 / tk - 4.210 * math.log(tk) + 0.000367 * tk
                    + math.tanh(0.0415 * (tk - 218.8))
                    * (53.878 - 1331.22 / tk - 9.44523 * math.log(tk) + 0.014025 * tk))


def sat_w(t):
    """Saturation over liquid water: PsychroLib above the triple point, Murphy and Koop below 0 °C."""
    check_t(t)
    if t > TP:
        return pl.GetSatVapPres(t)
    if t >= 0.0:
        return hw_water(t)
    return mk_water(t)


def sat_i(t):
    """Saturation over ice from PsychroLib (its ice branch applies up to the triple point)."""
    return pl.GetSatVapPres(min(t, TP))


def bisect(f, target, lo, hi):
    for _ in range(300):
        mid = 0.5 * (lo + hi)
        if f(mid) < target:
            lo = mid
        else:
            hi = mid
        if hi - lo < 1e-12:
            break
    return 0.5 * (lo + hi)


def dew_w(pw):
    if pw < sat_w(-100.0) or pw > sat_w(200.0):
        raise ValueError("dew point out of range")
    if pw > pl.GetSatVapPres(0.05):
        return pl.GetTDewPointFromVapPres(200.0, pw)
    return bisect(sat_w, pw, -100.0, 0.05)


def frost(pw):
    if pw >= sat_i(TP):
        return dew_w(pw)
    if pw < sat_i(-100.0):
        raise ValueError("frost point out of range")
    return pl.GetTDewPointFromVapPres(TP, pw)


def w_from_pw(pw, p):
    """Humidity ratio. PsychroLib clamps tiny values to 1e-7, so it is only used above 1e-6."""
    if pw >= p:
        raise ValueError("pw >= p")
    w = EPS * pw / (p - pw)
    return pl.GetHumRatioFromVapPres(pw, p) if w > 1e-6 else w


def enthalpy(t, w):
    return pl.GetMoistAirEnthalpy(t, w) if w > 1e-6 else (1.006 * t + w * (2501.0 + 1.86 * t)) * 1000.0


def volume(t, w, p):
    return pl.GetMoistAirVolume(t, w, p) if w > 1e-6 else R_DA * (t + 273.15) * (1.0 + 1.607858 * w) / p


def w_at_wetbulb(t, tw, p):
    """ASHRAE eq. 33 and 35, unclamped. +inf when tw is at or above boiling."""
    pws = pl.GetSatVapPres(tw)
    if pws >= p:
        return math.inf
    ws = EPS * pws / (p - pws)
    if tw >= 0.0:
        return ((2501.0 - 2.326 * tw) * ws - 1.006 * (t - tw)) / (2501.0 + 1.86 * t - 4.186 * tw)
    return ((2830.0 - 0.24 * tw) * ws - 1.006 * (t - tw)) / (2830.0 + 1.86 * t - 2.1 * tw)


def wet_overlap(t, w, p):
    """True when ASHRAE eq. 33 and eq. 35 both have a root (the band just around 0 °C).
    The module then returns the wet bulb (liquid) root, at or above 0 °C."""
    return w_at_wetbulb(t, -1e-9, p) > w >= w_at_wetbulb(t, 0.0, p)


def rh_from_w(t, w, p):
    rh = 100.0 * (p * w / (EPS + w)) / sat_w(t)
    if rh > 100.0 * (1.0 + 1e-9):
        raise ValueError("above saturation")
    return min(rh, 100.0)


# Test bookkeeping

class Check:
    __slots__ = ("group", "cmd", "expected", "atol", "rtol", "post")

    def __init__(self, group, cmd, expected, atol, rtol, post=None):
        self.group, self.cmd, self.expected, self.atol, self.rtol, self.post = group, cmd, expected, atol, rtol, post


def fmt(x):
    return "NaN" if isinstance(x, float) and math.isnan(x) else repr(float(x))


def cmd(fn, *args):
    return fn + " " + " ".join(fmt(a) for a in args)


def ref(fn):
    try:
        return fn()
    except (ValueError, ZeroDivisionError, OverflowError):
        return ERR


def run_java(cmds, classpath, java):
    proc = subprocess.run([java, "-cp", classpath, "com.nedama.psychro.PsychroCli"],
                          input="\n".join(cmds) + "\n", capture_output=True, text=True, encoding="utf-8")
    if proc.returncode != 0:
        sys.exit("Java driver failed:\n" + proc.stderr)
    lines = proc.stdout.splitlines()
    if len(lines) != len(cmds):
        sys.exit("Java driver returned %d lines for %d calls" % (len(lines), len(cmds)))
    out = []
    for line in lines:
        status, _, rest = line.partition(" ")
        if status == "OK":
            out.append(rest if "=" in rest else float(rest))
        elif status == "ERR":
            out.append(ERR)
        else:
            out.append("CRASH " + rest)
    return out


def region(t):
    return "above 0 °C" if t > TP else "below 0 °C"


# Case generation

def random_temperature(rng):
    r = rng.random()
    if r < 0.70:
        return rng.uniform(-40.0, 120.0)
    if r < 0.85:
        return rng.uniform(-100.0, -40.0)
    return rng.uniform(120.0, 200.0)


def random_rh(rng):
    r = rng.random()
    if r < 0.05:
        return 100.0
    if r < 0.10:
        return rng.uniform(0.0, 1.0)
    return rng.uniform(1.0, 100.0)


def build_checks(n, seed):
    rng = random.Random(seed)
    checks = []

    def add(group, c, expected, atol, rtol, post=None):
        checks.append(Check(group, c, expected, atol, rtol, post))

    for _ in range(n):
        t = random_temperature(rng)
        rh = random_rh(rng)
        p = rng.uniform(60000.0, 110000.0)
        reg = region(t)
        pws = sat_w(t)
        pw = rh / 100.0 * pws

        add("saturationPressure " + reg, cmd("saturationPressure", t), pws, 0.0, 1e-12)
        add("vaporPressure " + reg, cmd("vaporPressure", t, rh), pw, 0.0, 1e-12)
        add("vpd " + reg, cmd("vpd", t, rh), pws - pw, 1e-9, 1e-9)

        td = ref(lambda: dew_w(pw))
        add("dewPoint " + (region(td) if td != ERR else reg), cmd("dewPoint", t, rh), td, 1e-6, 0.0)
        tf = ref(lambda: frost(pw))
        add("frostPoint " + reg, cmd("frostPoint", t, rh), tf, 1e-6, 0.0)

        w = ref(lambda: w_from_pw(pw, p))
        add("humidityRatio " + reg, cmd("humidityRatio", t, rh, p), w, 1e-15, 1e-10)
        add("absoluteHumidity " + reg, cmd("absoluteHumidity", t, rh), EPS * pw / (R_DA * (t + 273.15)), 0.0, 1e-12)
        if w == ERR:
            for fn in ("enthalpy", "specificVolume", "density", "wetBulb"):
                add(fn + " errors when vapour pressure >= pressure", cmd(fn, t, rh, p), ERR, 0, 0)
        else:
            v = volume(t, w, p)
            add("enthalpy " + reg, cmd("enthalpy", t, rh, p), enthalpy(t, w), 1e-6, 1e-10)
            add("specificVolume " + reg, cmd("specificVolume", t, rh, p), v, 0.0, 1e-10)
            add("density " + reg, cmd("density", t, rh, p), (1.0 + w) / v, 0.0, 1e-10)

            # Wet bulb: PsychroLib only where its bracket stays below boiling and the root is unique.
            overlap = wet_overlap(t, w, p)
            if t > TP and w > 1e-6 and pl.GetSatVapPres(t) < p and not overlap:
                add("wetBulb vs PsychroLib", cmd("wetBulb", t, rh, p), pl.GetTWetBulbFromHumRatio(t, w, p), 1e-6, 0.0)

            def wet_residual(tw, t=t, w=w, p=p, overlap=overlap):
                if overlap and tw < 0.0:    # must pick the liquid root in the overlap band
                    return math.inf
                if abs(tw) < 1e-6:          # eq. 33/35 switch at exactly 0 °C
                    return 0.0
                return (w_at_wetbulb(t, tw, p) - w) / max(w, 1e-6)
            add("wetBulb residual " + ("0 °C overlap band" if overlap else reg), cmd("wetBulb", t, rh, p),
                None, 1e-6, 0.0, post=wet_residual)

            add("state " + reg, cmd("state", t, rh, p), None, 0.0, 0.0, post="state")

        # Inverse functions with their own random inputs
        tw = max(-100.0, t - rng.uniform(0.0, 30.0))
        def rh_wb(t=t, tw=tw, p=p):
            ww = w_at_wetbulb(t, tw, p)
            if math.isinf(ww) or ww < 0.0:
                raise ValueError("no physical humidity")
            return rh_from_w(t, ww, p)
        add("relHumFromWetBulb " + reg, cmd("relHumFromWetBulb", t, tw, p), ref(rh_wb), 1e-9, 1e-9)

        td_in = max(-100.0, t - rng.uniform(0.0, 40.0))
        expected = ref(lambda: 100.0 * pl.GetSatVapPres(td_in) / pl.GetSatVapPres(t)) if td_in > TP \
            else ref(lambda: 100.0 * sat_w(td_in) / sat_w(t))
        add("relHumFromDewPoint " + region(td_in), cmd("relHumFromDewPoint", t, td_in), expected, 1e-9, 1e-9)
        add("humidityRatioFromDewPoint " + region(td_in), cmd("humidityRatioFromDewPoint", td_in, p),
            ref(lambda: w_from_pw(sat_w(td_in), p)), 1e-15, 1e-10)

        w_in = rng.uniform(0.0, 0.05) if rng.random() < 0.9 else rng.uniform(0.0, 1.0)
        add("relHumFromHumidityRatio " + reg, cmd("relHumFromHumidityRatio", t, w_in, p),
            ref(lambda: rh_from_w(t, w_in, p)), 1e-9, 1e-9)

        p1 = rng.uniform(60000.0, 110000.0)
        p2 = rng.uniform(60000.0, 1.6e6)
        td_line = rng.uniform(-80.0, 60.0)
        def pdp(td_line=td_line, p1=p1, p2=p2):
            s = sat_w(td_line)
            if s >= p1:
                raise ValueError("impossible dew point")
            return dew_w(s * p2 / p1)
        res = ref(pdp)
        add("dewPointAtPressure " + (region(res) if res != ERR else "error"), cmd("dewPointAtPressure", td_line, p1, p2),
            res, 1e-6, 0.0)

        z = rng.uniform(-1000.0, 11000.0)
        add("pressureFromAltitude", cmd("pressureFromAltitude", z), pl.GetStandardAtmPressure(z), 0.0, 1e-12)

        # Optional independent real gas model, above 0 °C, loose tolerances.
        if HAPropsSI is not None and t > 1.0 and w != ERR and 0.5 < rh < 99.5 and pws < 0.9 * p:
            tk = t + 273.15
            try:
                add("CoolProp humidityRatio", cmd("humidityRatio", t, rh, p), HAPropsSI("W", "T", tk, "P", p, "R", rh / 100.0), 0.0, 0.02)
                # CoolProp's dew point is over ice below 0 °C, so it is compared with frostPoint.
                add("CoolProp frostPoint", cmd("frostPoint", t, rh), HAPropsSI("D", "T", tk, "P", p, "R", rh / 100.0) - 273.15, 0.15, 0.0)
                # Within 1 °C of 0, CoolProp may pick the ice root where the module picks the liquid one.
                cp_tw = HAPropsSI("B", "T", tk, "P", p, "R", rh / 100.0) - 273.15
                if abs(cp_tw) > 1.0 and not wet_overlap(t, w, p):
                    add("CoolProp wetBulb", cmd("wetBulb", t, rh, p), cp_tw, 0.15, 0.0)
            except ValueError:
                pass

    # Inputs that must raise a clear error
    for c in [
        cmd("dewPoint", -150, 50), cmd("dewPoint", 250, 50), cmd("dewPoint", float("nan"), 50),
        cmd("dewPoint", 25, -1), cmd("dewPoint", 25, 101), cmd("dewPoint", 25, 0),
        cmd("humidityRatio", 25, 50, 101.325), cmd("humidityRatio", 25, 50, 1.01325),
        cmd("humidityRatio", 25, 50, 0), cmd("humidityRatio", 25, 50, -101325),
        cmd("relHumFromDewPoint", 20, 25), cmd("relHumFromWetBulb", 20, 25, 101325),
        cmd("relHumFromWetBulb", 30, 5, 101325), cmd("relHumFromHumidityRatio", 25, 0.05, 101325),
        cmd("relHumFromHumidityRatio", 25, -0.001, 101325), cmd("pressureFromAltitude", 20000),
        cmd("pressureFromAltitude", -2000), cmd("dewPointAtPressure", 120, 101325, 800000),
    ]:
        add("expected errors", c, ERR, 0, 0)

    return checks


def build_round_trips(rng, n):
    """Round trips run in a second pass because they need Java outputs as inputs."""
    cases = []
    for _ in range(n):
        t = rng.uniform(-60.0, 120.0)
        rh = rng.uniform(1.0, 100.0)
        p = rng.uniform(60000.0, 110000.0)
        if rh / 100.0 * sat_w(t) >= p:
            continue
        cases.append((t, rh, p))
    return cases


# Main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n", type=int, default=5000, help="random air states (default 5000)")
    ap.add_argument("--seed", type=int, default=None, help="random seed (default: random, printed)")
    ap.add_argument("--java", default="java", help="java executable (default: java on PATH)")
    ap.add_argument("--show", type=int, default=10, help="failures to print per group (default 10)")
    args = ap.parse_args()

    seed = args.seed if args.seed is not None else random.randrange(1 << 30)
    core = Path(__file__).resolve().parents[3]
    classpath = os.pathsep.join([str(core / "target" / "classes"), str(core / "target" / "test-classes")])
    if not (core / "target" / "test-classes").exists():
        sys.exit("Compiled classes not found. Run 'mvn -q test-compile' in %s first." % core)

    print("Seed %d, %d random air states%s" % (seed, args.n, "" if HAPropsSI else " (CoolProp not installed, skipping that group)"))
    checks = build_checks(args.n, seed)
    results = run_java([c.cmd for c in checks], classpath, args.java)
    state_cache = {}

    groups = {}
    for c, got in zip(checks, results):
        g = groups.setdefault(c.group, {"n": 0, "fail": [], "max": 0.0})
        g["n"] += 1
        ok, err = True, 0.0
        if isinstance(got, str) and got.startswith("CRASH"):
            ok, err = False, math.inf
        elif c.post == "state":
            if got == ERR:
                ok = False
            else:
                state_cache[c.cmd] = got
                continue
        elif c.post is not None:
            if got == ERR:
                ok = False
            else:
                try:
                    err = abs(c.post(got))
                except ValueError:
                    err = 0.0
                ok = err <= c.atol
        elif c.expected == ERR or got == ERR:
            ok = c.expected == got
        else:
            err = abs(got - c.expected)
            ok = err <= c.atol + c.rtol * abs(c.expected)
        g["max"] = max(g["max"], err if math.isfinite(err) else g["max"])
        if not ok:
            g["fail"].append((c.cmd, c.expected, got))

    # state() must agree exactly with the individual functions
    keys_to_fn = {"dewPoint": "dewPoint", "frostPoint": "frostPoint", "wetBulb": "wetBulb", "humidityRatio": "humidityRatio",
                  "absoluteHumidity": "absoluteHumidity", "vaporPressure": "vaporPressure", "saturationPressure": "saturationPressure",
                  "vpd": "vpd", "enthalpy": "enthalpy", "specificVolume": "specificVolume", "density": "density"}
    st_cmds, st_meta = [], []
    for c_str, s in state_cache.items():
        _, t, rh, p = c_str.split()
        values = dict(kv.split("=") for kv in s.split(";"))
        values = {k: (None if v == "null" else float(v)) for k, v in values.items()}
        for key, fn in keys_to_fn.items():
            a = (t,) if fn == "saturationPressure" else (t, rh) if fn in ("dewPoint", "frostPoint", "absoluteHumidity", "vaporPressure", "vpd") else (t, rh, p)
            st_cmds.append(fn + " " + " ".join(a))
            st_meta.append((c_str, key, values[key]))
    rng = random.Random(seed + 1)
    trips = build_round_trips(rng, max(1, args.n // 2))
    trip_cmds = []
    for t, rh, p in trips:
        trip_cmds += [cmd("dewPoint", t, rh), cmd("wetBulb", t, rh, p), cmd("humidityRatio", t, rh, p)]
    second = run_java(st_cmds + trip_cmds, classpath, args.java)

    g = groups.setdefault("state matches individual functions", {"n": 0, "fail": [], "max": 0.0})
    for (c_str, key, v), got in zip(st_meta, second[:len(st_cmds)]):
        g["n"] += 1
        same = (v is None and got == ERR) or (isinstance(got, float) and got == v)
        if not same:
            g["fail"].append((c_str + " [" + key + "]", v, got))

    trip_out = second[len(st_cmds):]
    back_cmds, back_expect = [], []
    for i, (t, rh, p) in enumerate(trips):
        td, tw, w = trip_out[3 * i: 3 * i + 3]
        if td != ERR:
            back_cmds.append(cmd("relHumFromDewPoint", t, td)); back_expect.append(("round trip dew point", rh))
        if tw != ERR and tw <= t:
            back_cmds.append(cmd("relHumFromWetBulb", t, tw, p)); back_expect.append(("round trip wet bulb", rh))
        if w != ERR:
            back_cmds.append(cmd("relHumFromHumidityRatio", t, w, p)); back_expect.append(("round trip humidity ratio", rh))
        if td != ERR and -100 < td < 200:
            back_cmds.append(cmd("humidityRatioFromDewPoint", td, p)); back_expect.append(("round trip dew point to W", w))
    third = run_java(back_cmds, classpath, args.java)
    for (name, expected), got, c_str in zip(back_expect, third, back_cmds):
        g = groups.setdefault(name, {"n": 0, "fail": [], "max": 0.0})
        g["n"] += 1
        if expected == ERR or got == ERR or isinstance(got, str):
            g["fail"].append((c_str, expected, got))
            continue
        tol = 1e-5 if name != "round trip dew point to W" else 1e-7 * max(abs(expected), 1e-6)
        err = abs(got - expected)
        g["max"] = max(g["max"], err)
        if err > tol:
            g["fail"].append((c_str, expected, got))

    total_fail = 0
    width = max(len(k) for k in groups)
    print("\n%-*s %8s %8s %12s" % (width, "Check", "Cases", "Failed", "Max error"))
    print("-" * (width + 31))
    for name in sorted(groups):
        g = groups[name]
        total_fail += len(g["fail"])
        print("%-*s %8d %8d %12.3g" % (width, name, g["n"], len(g["fail"]), g["max"]))
    for name in sorted(groups):
        for c_str, expected, got in groups[name]["fail"][:args.show]:
            print("FAIL %-30s %s  expected %s  got %s" % (name, c_str, expected, got))

    print("\n%s: %d checks, %d failed (seed %d)" % ("PASS" if total_fail == 0 else "FAIL",
                                                  sum(g["n"] for g in groups.values()), total_fail, seed))
    sys.exit(0 if total_fail == 0 else 1)


if __name__ == "__main__":
    main()
