# Changelog

All notable changes to this project are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-18

First release.

### Added

- Separate signed modules for Ignition 8.1.33 or newer and 8.3.3 or newer, with the same module ID so a gateway
  upgraded from 8.1 to 8.3 recognises the module.
- `system.psychro` with 18 script functions in the gateway, Vision client and Designer scopes, with autocomplete
  documentation.
- 17 `psychro*` expression functions in a Psychrometrics category. Input quality carries through to the result.
- ASHRAE Handbook, Fundamentals (2017) equations, ported from PsychroLib 2.5.0, and Murphy and Koop (2005) for
  saturation over supercooled water.
- Relative humidity relative to liquid water at every temperature, with separate dew point (water) and frost
  point (ice) below 0 °C.
- Wet bulb solver that stays below the boiling point, so hot dryer air gives the right result, and returns the
  liquid wet bulb where ASHRAE's wet bulb and ice bulb equations overlap near 0 °C.
- Compressed air dew point conversion between pressures, and standard atmosphere pressure from altitude.
- Tests: randomised cross-checks against PsychroLib, Murphy and Koop, and CoolProp, and an Ignition accuracy test
  (34,057 results, zero failures on 8.1 and 8.3).

[0.1.0]: https://github.com/Nedama-Integrated-Solutions/psychrometrics-ignition/releases/tag/v0.1.0
