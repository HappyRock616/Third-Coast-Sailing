# Third Coast Sailing

Offline Great Lakes chart viewer, weather and sail-routing app for Android, with a celestial navigation trainer.

**Where it works**

- **Charts, weather, buoys and routing: the Great Lakes only** (and the inland lakes connected to them). The charts, wind and wave forecasts and buoy reports all come from US NOAA sources for the Great Lakes; other waters aren't covered and aren't planned.
- **Celestial navigation trainer: anywhere in the world.** The almanac, star finder, sight planning and sight workbook are calculated for any position and any date, so you can practise anywhere, at sea or at home. Fixes are plotted on the chart where there's a Great Lakes chart; elsewhere the workbook gives the position in numbers.

> **Not for navigation.** Official NOAA charts and notices govern. Use at your own risk.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_wind_gauge.png" width="200" alt="Wind field and wind gauge with polar">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_waves_barbs.png" width="200" alt="Wind barbs and animated wave crests with forecast slider">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_live_buoy.png" width="200" alt="Live NOAA buoy report with 24-hour graph">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_harbor.png" width="200" alt="Marina details with call and website buttons">
</p>

## What it does

- **Charts:** NOAA electronic charts for Superior, Michigan, Huron, Erie and Ontario (plus connected inland lakes), stored on the phone and usable with no signal. Depth shading, hazards, and water shallower than your boat's minimum depth shaded red, with one red line at that depth.
- **Weather:** wind, gusts and waves from NOAA (NDFD and the GLWU wave model), downloaded by the phone directly — no server in between. Colour field, wind barbs, animated wave crests and a 48-hour forecast slider.
- **Buoys:** NOAA NDBC stations coloured by wind speed, ringed for waves over 3 ft / 5 ft; tap for the latest report and a 24-hour graph.
- **Boat:** GPS position, course line with 1/2/3-hour marks, and a heading-up wind gauge with your polar.
- **Routes:** lay in points, see leg distance and magnetic bearing, distance / VMG / time to destination, and calculate a weather-optimised route (isochrones, your polar at a cruise factor, tack penalty, depth limit).
- **Celestial navigation trainer:** 3D star finder that turns with the phone, sight planning, a step-by-step sight workbook with every equation shown, printed-almanac style tables, and the fix plotted on the chart. Three skill levels.
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

Harbour data derived from OpenStreetMap is available under the ODbL.

## About the name

"Third Coast" is a common name for the Great Lakes and their shoreline. Third Coast Sailing is an independent, non-commercial project. It isn't affiliated with, endorsed by or connected to any business, club, school or other organization, including any that use "Third Coast" in their name.

## License

The app is licensed under the **GNU Affero General Public License v3.0** — see [LICENSE](LICENSE).
