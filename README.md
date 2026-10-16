# Third Coast Sailing

Offline Great Lakes chart viewer, weather and sail-routing app for Android, with a celestial navigation trainer.

**Where it works**

- **Charts, weather, buoys and routing: the Great Lakes only** (and the inland lakes connected to them). The charts, wind and wave forecasts and buoy reports all come from US NOAA sources for the Great Lakes; other waters aren't covered and aren't planned.
- **Celestial navigation trainer: anywhere in the world.** The almanac, star finder, sight planning and sight workbook are calculated for any position and any date, so you can practice anywhere, at sea or at home. Fixes are plotted on the chart where there's a Great Lakes chart; elsewhere the workbook gives the position in numbers.

> **Not for navigation.** Official NOAA charts and notices govern. Use at your own risk.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_wind_gauge.png" width="200" alt="Wind field and wind gauge with polar">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_waves_barbs.png" width="200" alt="Wind barbs and animated wave crests with forecast slider">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_live_buoy.png" width="200" alt="Live NOAA buoy report with 24-hour graph">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_harbor.png" width="200" alt="Marina details with call and website buttons">
</p>

## What it does

- **Charts:** NOAA electronic charts for Superior, Michigan, Huron, Erie and Ontario (plus connected inland lakes), stored on the phone and usable with no signal. Depth shading, hazards, and water shallower than your boat's minimum depth shaded red, with one red line at that depth.
- **Weather:** wind, gusts and waves from NOAA (NDFD and the GLWU wave model), downloaded by the phone directly — no server in between. Color field, wind barbs, animated wave crests and a 48-hour forecast slider.
- **Buoys:** NOAA NDBC stations colored by wind speed, ringed for waves over 3 ft / 5 ft; tap for the latest report and a 24-hour graph.
- **Boat:** GPS position, course line with 1/2/3-hour marks, and a heading-up wind gauge with your polar.
- **Routes:** lay in points, see leg distance and magnetic bearing, distance / VMG / time to destination, and calculate a weather-optimized route (isochrones, your polar at a cruise factor, tack penalty, depth limit).
- **Celestial navigation trainer:** 3D star finder that turns with the phone, sight planning, a step-by-step sight workbook with every equation shown, printed-almanac style tables, and the fix plotted on the chart. Three skill levels.
- **Anchor watch:** drop or offset the anchor, bold red alarm circle, swing trace, and the Third Coast Ships Bell (with vibration) if you drag or lose GPS. Runs all night with the screen off.
- **MOB (man overboard):** one tap marks the spot, clears the chart, and shows bearing, distance and time in the water. An orange drift triangle grows downwind from the mark using US Coast Guard person-in-water leeway figures (see below).
- **Private by design:** no Google Play Services, no accounts, no tracking. Runs on GrapheneOS and LineageOS.

## Getting the app

**[Download the latest version](../../releases/latest)** — open the `.apk` file on your phone (Android 14 or newer). On first launch, pick your lakes; the charts download inside the app from the [charts release](../../releases/tag/charts).

## Building the app from source

1. Install Android Studio.
2. Open the `app/` folder.
3. Connect a phone with USB debugging on and press **Run**.

Requirements: Android 14 (API 34) or newer. The build fails on purpose if any Google Play Services or Firebase library is added.

## Data sources and credits

| Data | Source | Terms |
|---|---|---|
| Charts (ENC) | NOAA Office of Coast Survey | US Government work, public domain |
| Lake bathymetry | NOAA NCEI | Public domain |
| Wind forecasts | NOAA National Weather Service (NDFD) | Public domain |
| Wave forecasts | NOAA NCEP (GLWU) | Public domain |
| Buoy observations | NOAA National Data Buoy Center | Public domain |
| Marinas, clubs, docks, anchorages | © OpenStreetMap contributors | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/) |
| Map engine | [MapLibre Native](https://maplibre.org) | BSD 2-Clause |
| Almanac calculations (celestial trainer) | [Astronomy Engine](https://github.com/cosinekitty/astronomy) by Don Cross | MIT |
| Star positions (celestial trainer) | Yale Bright Star Catalogue, 5th ed. | Public domain |
| Constellation figures and names | [d3-celestial](https://github.com/ofrohn/d3-celestial) by Olaf Frohn | BSD 3-Clause |
| MOB drift (person-in-water leeway) | US Coast Guard R&D Center leeway studies: Allen & Plourde (1999), *Review of Leeway: Field Experiments and Implementation*, CG-D-08-99; Allen (2005), *Leeway Divergence*. Values as tabulated in [OpenDrift](https://github.com/OpenDrift/opendrift/blob/master/opendrift/models/OBJECTPROP.DAT) | US Government work, public domain |

Harbor data derived from OpenStreetMap is available under the ODbL.

### How the MOB drift triangle is worked out

A person in the water is pushed downwind by the wind ("leeway"). The US Coast Guard measured this in field trials and uses the results in search planning. The app uses the coefficients for **"Person-in-water (PIW), unknown state (mean values)"**: downwind leeway = **0.96 %** of the 10 m wind speed, crosswind leeway = **±0.54 %** of the wind speed (both with zero offset). The triangle's tip is at the MOB mark; it points downwind (the forecast wind at the mark, refreshed hourly); its length is 0.96 % × wind × time since the mark and its half-width at the far end is 0.54 % × wind × time. Example: 15 kn of wind, 12 minutes → about 175 ft long and ±98 ft wide.

Limits: the studies show large scatter around these averages (standard deviation about 12 cm/s, roughly 0.2 kn, downwind), so the triangle is a best estimate of where to look first, not a boundary. Surface current is not included (the app has no current data), and the wind is a forecast, not a measurement. Use it to start a search, and call the Coast Guard (VHF 16) at once.

## About the name

"Third Coast" is a common name for the Great Lakes and their shoreline. Third Coast Sailing is an independent, non-commercial project. It isn't affiliated with, endorsed by or connected to any business, club, school or other organization, including any that use "Third Coast" in their name.

## License

The app is licensed under the **GNU Affero General Public License v3.0** — see [LICENSE](LICENSE).
