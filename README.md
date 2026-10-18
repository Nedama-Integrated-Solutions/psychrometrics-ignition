# Psychrometrics for Ignition

[![Release](https://img.shields.io/github/v/release/Nedama-Integrated-Solutions/psychrometrics-ignition)](https://github.com/Nedama-Integrated-Solutions/psychrometrics-ignition/releases/latest)
![License](https://img.shields.io/badge/license-Apache%202.0-blue)
![Ignition 8.1](https://img.shields.io/badge/Ignition-8.1.33%2B-orange)
![Ignition 8.3](https://img.shields.io/badge/Ignition-8.3.3%2B-orange)

Psychrometric script and expression functions for Inductive Automation Ignition. Turn the temperature, humidity
and pressure readings a plant already has into the values engineers act on: dew point, frost point, wet bulb,
moisture content, enthalpy and air density. Built on the ASHRAE Handbook equations by Nedama Integrated Solutions.

| Module file | Ignition | Module ID |
| --- | --- | --- |
| `Psychrometrics-0.1.0-Ignition-8.1.modl` | 8.1.33 or newer | `com.nedama.ignition.psychro` |
| `Psychrometrics-0.1.0-Ignition-8.3.modl` | 8.3.3 or newer | `com.nedama.ignition.psychro` |

Both files are free, signed by Nedama Integrated Solutions and Maker Edition compatible. Download them from the
[latest release](https://github.com/Nedama-Integrated-Solutions/psychrometrics-ignition/releases/latest) and install the file that matches your gateway's version line.

## Quick start

In any script (gateway, Perspective, Vision client, Designer):

```python
p = system.psychro.pressureFromAltitude(1700)        # site has no barometer
system.psychro.dewPoint(25, 50)                      # 13.864 °C
system.psychro.wetBulb(25, 50, p)                    # 17.402 °C
system.psychro.state(25, 50, p)["humidityRatio"]     # 0.01218 kg/kg dry air
```

In an expression tag or binding, straight from two sensor tags:

```
psychroDewPoint({[.]AirTemp}, {[.]AirRH})
```

## Units and conventions

| Quantity | Unit |
| --- | --- |
| Temperature | °C, valid from -100 to 200 °C |
| Pressure | Pa, absolute. Add atmospheric pressure to gauge readings |
| Relative humidity | %, 0 to 100 |
| Humidity ratio | kg water per kg dry air |
| Absolute humidity, density | kg/m³ |
| Enthalpy | J per kg dry air |
| Specific volume | m³ per kg dry air |
| Altitude | m, -1000 to 11000 |

- **Pressure is always an argument.** At 1,700 m the air holds about a fifth more water per kilogram than at sea
  level, so assuming sea level gives wrong results at altitude. Use `pressureFromAltitude` when the site has no
  barometer.
- **Relative humidity is relative to liquid water at every temperature**, as most RH sensors report it. Below 0 °C,
  `dewPoint` is over supercooled water and `frostPoint` is over ice. Wet bulb below 0 °C is an ice bulb (ASHRAE).
- **Invalid inputs raise an error instead of returning a wrong number**, for example RH above 100 % or a pressure
  entered in kPa. In scripts it is a `java.lang.IllegalArgumentException`:

  ```python
  from java.lang import IllegalArgumentException
  try:
      td = system.psychro.dewPoint(t, rh)
  except IllegalArgumentException as e:
      logger.warn(str(e))
  ```

- **Expression functions follow tag quality.** A bad input quality makes the result bad, a null input gives null,
  and an invalid input gives an expression error with the reason.

## Functions

| Script (`system.psychro.*`) | Expression | Returns |
| --- | --- | --- |
| `dewPoint(dryBulb, relHum)` | `psychroDewPoint` | °C |
| `frostPoint(dryBulb, relHum)` | `psychroFrostPoint` | °C (frost point below 0 °C, else dew point) |
| `wetBulb(dryBulb, relHum, pressure)` | `psychroWetBulb` | °C |
| `humidityRatio(dryBulb, relHum, pressure)` | `psychroHumidityRatio` | kg/kg |
| `absoluteHumidity(dryBulb, relHum)` | `psychroAbsoluteHumidity` | kg/m³ |
| `enthalpy(dryBulb, relHum, pressure)` | `psychroEnthalpy` | J/kg |
| `specificVolume(dryBulb, relHum, pressure)` | `psychroSpecificVolume` | m³/kg |
| `density(dryBulb, relHum, pressure)` | `psychroDensity` | kg/m³ |
| `vaporPressure(dryBulb, relHum)` | `psychroVaporPressure` | Pa |
| `saturationPressure(temperature)` | `psychroSatPressure` | Pa |
| `vpd(dryBulb, relHum)` | `psychroVpd` | Pa |
| `relHumFromDewPoint(dryBulb, dewPoint)` | `psychroRhFromDewPoint` | % |
| `relHumFromWetBulb(dryBulb, wetBulb, pressure)` | `psychroRhFromWetBulb` | % |
| `relHumFromHumidityRatio(dryBulb, humidityRatio, pressure)` | `psychroRhFromHumidityRatio` | % |
| `humidityRatioFromDewPoint(dewPoint, pressure)` | `psychroHumidityRatioFromDewPoint` | kg/kg |
| `dewPointAtPressure(dewPoint, fromPressure, toPressure)` | `psychroDewPointAtPressure` | °C |
| `pressureFromAltitude(altitude)` | `psychroPressureFromAltitude` | Pa |
| `state(dryBulb, relHum, pressure)` | (script only) | dictionary of all of the above |

`dewPointAtPressure` converts between atmospheric and pressure dew point for compressed air, for example a 3 °C
pressure dew point at 7 bar(g) is about -23 °C at atmospheric pressure.

## Accuracy

Checked against PsychroLib (the ASHRAE reference implementation) above 0 °C and an independent Murphy and Koop
implementation below 0 °C: 34,057 results across every function from -100 to 200 °C, run inside Ignition 8.1 and
8.3, with zero failures. Results agree to better than a billionth of a degree. Against CoolProp, an independent
real-gas model, dew points differ by about 0.06 °C, roughly ten times less than the uncertainty of a typical ±2 % RH
sensor. In a 200 °C dryer, where PsychroLib's wet bulb fails (returning 200 °C), this module returns 63.19 °C, in line
with CoolProp's 63.17 °C.

## Installing a signed module

On the gateway, go to **Config > System > Modules > Install or Upgrade a Module** and choose the file for your
version line. The first time, Ignition asks you to accept the Nedama Integrated Solutions certificate. Compare its
thumbprint with this one before accepting:

```
SHA-1 thumbprint:  65:83:84:55:22:06:7C:CC:F9:C6:9B:C6:F3:C6:41:98:3A:28:BD:20
SHA-256:           1C:3C:66:C6:FE:02:27:85:63:29:9B:0F:E0:3B:F4:FA:11:EB:87:8F:21:C9:7D:92:3B:54:ED:59:97:B6:BE:DF
```

## Building from source

Requires JDK 17, Maven 3.8 or newer, and Python 3 for the tests.

```
git clone https://github.com/Nedama-Integrated-Solutions/psychrometrics-ignition.git
cd psychrometrics-ignition
mvn clean package
```

This builds `build-81\target\Psychrometrics-unsigned.modl` and `build-83\target\Psychrometrics-unsigned.modl`.
Only the release files are signed. To install your own build, use a test gateway that allows unsigned modules.

## Running the tests

```
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r psychro-core\src\test\python\requirements.txt
mvn -q test-compile
python psychro-core\src\test\python\random_check.py --n 5000
```

`random_check.py` cross-checks every function on random air states against PsychroLib, Murphy and Koop, and CoolProp.

To test inside Ignition, paste `psychro-core\src\test\ignition\accuracy_test.py` into the Designer Script Console and
run it. It prints the path of the CSV it wrote. Then:

```
python psychro-core\src\test\python\compare_ignition.py --csv <that path>
```

| Folder | Contents |
| --- | --- |
| `psychro-core` | The calculations in plain Java, no Ignition dependency, plus the tests |
| `common-81`, `common-83` | The script module and expression functions for each Ignition line |
| `gateway-8x`, `client-8x`, `designer-8x` | One small hook per scope |
| `build-81`, `build-83` | Module packaging and `license.html` |

## Credits and license

Equations and coefficients are from the ASHRAE Handbook, Fundamentals (2017), chapter 1, as implemented in
[PsychroLib](https://github.com/psychrometrics/psychrolib) (MIT). Saturation over supercooled water uses Murphy and
Koop (2005). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Copyright 2026 Nedama Integrated Solutions. Licensed under the [Apache License 2.0](LICENSE).
Ignition is a trademark of Inductive Automation; this module is not affiliated with or endorsed by Inductive Automation.
