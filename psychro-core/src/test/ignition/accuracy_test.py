# Psychrometrics accuracy test, part 1 of 2.
# Paste this whole file into the Ignition Designer Script Console and run it.
# It calls system.psychro with fixed scenarios and random air states, times the functions,
# and writes every input and result to a CSV in the Designer computer's temp folder.
# Part 2, compare_ignition.py, checks that CSV in Python against PsychroLib and CoolProp:
#     python psychro-core/src/test/python/compare_ignition.py --csv <path printed below>
#
# Works on Ignition 8.1 and 8.3. The three imports are needed: Jython does not preload them.

import random
import time
from java.lang import IllegalArgumentException

OUT = system.file.getTempFile("csv")
N_RANDOM = 2000        # random air states; each one runs every function
SEED = 42
psy = system.psychro

rows = ["tag,func,a1,a2,a3,result"]


def run(tag, func, *args):
    try:
        result = repr(float(getattr(psy, func)(*args)))
    except IllegalArgumentException:
        result = "ERR"
    cells = ['"%s"' % tag, func] + [repr(float(a)) for a in args] + [""] * (3 - len(args)) + [result]
    rows.append(",".join(cells))


# 1. Named scenarios, used for the side-by-side table in part 2
P_SEA = 101325.0
P_NBO = psy.pressureFromAltitude(1700)   # Nairobi
scenarios = [
    ("Office at sea level", 25.0, 50.0, P_SEA),
    ("Office in Nairobi", 25.0, 50.0, P_NBO),
    ("Greenhouse in Nairobi", 28.0, 75.0, P_NBO),
    ("Cold room", 2.0, 90.0, P_SEA),
    ("Freezer", -20.0, 80.0, P_SEA),
    ("Cooling tower inlet", 35.0, 40.0, P_SEA),
    ("Tea dryer inlet in Nairobi", 110.0, 3.0, P_NBO),
    ("Hot air dryer", 200.0, 1.0, P_SEA),
]
for name, t, rh, p in scenarios:
    tag = "S:" + name
    for func in ("dewPoint", "frostPoint"):
        run(tag, func, t, rh)
    for func in ("wetBulb", "humidityRatio", "enthalpy", "density"):
        run(tag, func, t, rh, p)
run("S:Compressed air 3 C at 7 bar(g)", "dewPointAtPressure", 3.0, 801325.0, P_SEA)
run("S:Compressed air -40 C at 7 bar(g)", "dewPointAtPressure", -40.0, 801325.0, P_SEA)

# 2. Random air states over the whole valid range
rng = random.Random(SEED)
for i in range(N_RANDOM):
    r = rng.random()
    if r < 0.70:
        t = rng.uniform(-40.0, 120.0)
    elif r < 0.85:
        t = rng.uniform(-100.0, -40.0)
    else:
        t = rng.uniform(120.0, 200.0)
    rh = 100.0 if rng.random() < 0.05 else rng.uniform(0.5, 100.0)
    p = rng.uniform(60000.0, 110000.0)
    run("R", "saturationPressure", t)
    for func in ("vaporPressure", "vpd", "dewPoint", "frostPoint", "absoluteHumidity"):
        run("R", func, t, rh)
    for func in ("humidityRatio", "wetBulb", "enthalpy", "specificVolume", "density"):
        run("R", func, t, rh, p)
    run("R", "relHumFromWetBulb", t, max(-100.0, t - rng.uniform(0.0, 30.0)), p)
    td = max(-100.0, t - rng.uniform(0.0, 40.0))
    run("R", "relHumFromDewPoint", t, td)
    run("R", "humidityRatioFromDewPoint", td, p)
    run("R", "relHumFromHumidityRatio", t, rng.uniform(0.0, 0.05), p)
    run("R", "dewPointAtPressure", rng.uniform(-80.0, 60.0), rng.uniform(60000.0, 110000.0), rng.uniform(60000.0, 1.6e6))
    run("R", "pressureFromAltitude", rng.uniform(-1000.0, 11000.0))

# 3. Inputs that must be rejected with a clear error
for func, args in [
    ("dewPoint", (-150.0, 50.0)), ("dewPoint", (25.0, 101.0)), ("dewPoint", (25.0, 0.0)),
    ("humidityRatio", (25.0, 50.0, 101.325)), ("relHumFromDewPoint", (20.0, 25.0)),
    ("relHumFromWetBulb", (30.0, 5.0, P_SEA)), ("pressureFromAltitude", (20000.0,)),
]:
    run("E", func, *args)

system.file.writeFile(OUT, "\n".join(rows) + "\n")
print("Wrote %d results to %s" % (len(rows) - 1, OUT))

# 4. Speed: average time per call in this scope
for func, args in [("dewPoint", (25.0, 50.0)), ("wetBulb", (25.0, 50.0, P_SEA)), ("state", (25.0, 50.0, P_SEA))]:
    fn = getattr(psy, func)
    n = 20000
    start = time.time()
    for _ in range(n):
        fn(*args)
    print("%-10s %6.1f microseconds per call" % (func, (time.time() - start) / n * 1e6))
