# ⛵ Third Coast Sailing

### An Android app for sailing the Great Lakes

Charts, weather, routing, safety tools and a celestial navigation trainer, built for sailors on Superior, Michigan, Huron, Erie and Ontario. Free, open source, and private by design.

> **Not for navigation.** Official NOAA charts and notices govern. Use at your own risk.

## 🌎 Why this exists

Nearly everything a Great Lakes sailor needs to know is already measured, modeled and published by NOAA: the charts, the lake depths, the wind and wave forecasts, and the live buoy reports. That data is paid for by citizens, and it belongs to all of us.

Third Coast Sailing is a wrapper around that public data, made **for sailors, by a sailor**. It puts the data on a phone you already own, in a form you can use from the cockpit, without subscriptions, accounts or chart fees.

The goal is to make sailing affordable at any scale, whether you're on a dinghy, a weekend cruiser or a 40-footer heading across the lake.

- 💸 **Free.** No ads, no subscriptions, no in-app purchases.
- 🔒 **Private.** No Google Play Services, no accounts, no tracking. Runs on GrapheneOS and LineageOS.
- 📴 **Offline.** Charts are stored on the phone and work with no signal.
- 🧾 **Open.** Licensed under the AGPL-3.0, so it stays free.

## 🗺️ Where it works

- **Charts, weather, buoys and routing: the Great Lakes only**, plus the inland lakes connected to them. All of it comes from US NOAA sources for the Great Lakes, so other waters aren't covered and aren't planned.
- **Celestial navigation: anywhere in the world.** The almanac, star finder and sight calculations work for any position and date, so you can practice at sea or in your backyard.

## ✨ Features

### 🧭 Celestial navigation: sight reduction tables and calculator

<!-- screenshot -->

- 📖 Printed-almanac style tables for the Sun, Moon, planets and navigation stars, for any date.
- 🧮 A step-by-step sight workbook that reduces your sextant sight and shows every equation along the way.
- 📐 Lines of position and the final fix plotted on the chart.
- 🎓 Three skill levels, from guided beginner to working navigator.

### ⭐ Interactive star finder with instructions

<!-- screenshot -->

- 📱 A 3D sky that turns with your phone. Hold it up and the stars on screen line up with the stars overhead.
- ✨ Every star is shown, the named navigation stars are labeled, and the constellation lines help you learn to recognize them by eye.
- 🧡 An orange arrow guides you to the star you've selected, even when it's behind you.
- 📋 Sight planning suggests good stars for your time and place, with step-by-step instructions for taking and working the sight.

### 🆘 Man overboard with a real-time search area prediction

<!-- screenshot -->

- 🔴 One tap marks the spot with a red pin and clears the chart down to what matters.
- 📍 A live line from the boat to the mark gives bearing and distance back.
- ⏱️ A timer counts up how long the person has been in the water.
- 🔺 An orange drift triangle grows downwind from the mark, showing where to search first. It uses US Coast Guard person-in-water leeway figures (see [How the MOB drift triangle is worked out](#how-the-mob-drift-triangle-is-worked-out)).
- ✋ Reset needs a 5-second hold, so it can't be cleared by accident.

### ⚓ Anchor alarm

<!-- screenshot -->

- 📌 Drop the anchor where you stand, or point the phone at it to set it ahead of the boat.
- ⭕ A bold red alarm circle (100 ft by default, adjustable) and a dashed swing trace on the chart.
- 🔔 The Third Coast Ships Bell rings, with vibration, if you drag outside the circle or lose your GPS fix.
- 🌙 Runs all night with the screen off. The volume buttons silence the alarm for 15 minutes.
- 💾 The anchor position is saved, so it survives if the phone dies.

### 📉 Barograph

<!-- screenshot -->

- 🌡️ Logs the phone's barometer every few minutes (every minute when the weather turns), filtered so a slammed hatch doesn't count.
- 📊 Scrolling graph colored by the 3-hour tendency, with the nearest NOAA buoy's pressure for comparison.
- ⛈️ Trend in Met Office terms and an outlook symbol from the pressure alone.
- ⚠️ A quiet yellow warning when the pressure starts falling fast or a squall jump arrives, shown once, not a nag.

### 🛤️ Weather routing

<!-- screenshot -->

- 📍 Drop pins anywhere: a green start, numbered waypoints and a 🏁 finish, with each leg's distance and magnetic bearing. Plan "what if" passages far from where you are, or snap the start onto the boat to get VMG and ETA as you sail.
- 🧠 Calculate a weather-optimized route using isochrones, your boat's polar, a cruise factor, a tack penalty and your minimum depth, departing at the time on the forecast slider.
- 💥 Crash warning: a little red broken-boat mark shows the first spot shallower than your depth limit, either on your current heading or along your plotted route.

### 🌊 Wave and wind forecast overlays

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_waves_barbs.png" width="220" alt="Wind barbs and animated wave crests with forecast slider">
</p>

- 💨 Wind speed and gusts as a color field over the water, plus wind barbs.
- 🌊 Animated wave crests showing wave height and direction.
- 🎚️ A 48-hour forecast slider to see what's coming.
- 📡 Data comes straight from NOAA (NDFD wind and the GLWU wave model) to your phone, with no server in between.

### 📟 Sailing instrument from GPS and the wind forecast

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_wind_gauge.png" width="220" alt="Wind field and wind gauge with polar">
</p>

- 🧭 A heading-up wind gauge built from your GPS course and speed and the forecast wind at your position.
- 📈 Your boat's polar drawn on the gauge.
- 🚫🔌 No wind instrument or NMEA connection needed (or supported). It's a phone-only instrument, so treat the wind as a forecast estimate, not a measurement.
- ⏳ Course line with 1, 2 and 3-hour marks, and a GPS track you can reset.

### 🛟 Live buoy data on the chart with history graphs

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_live_buoy.png" width="220" alt="Live NOAA buoy report with 24-hour graph">
</p>

- 🎨 NOAA NDBC stations colored by wind speed and ringed for waves over 3 ft or 5 ft.
- 🔄 Reports update live on the chart.
- 📊 Tap a buoy for the latest report and detailed 24-hour history graphs.

### 🏝️ Marina locations and contact information

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_harbor.png" width="220" alt="Marina details with call and website buttons">
</p>

- ⚓ Marinas, yacht clubs, docks and anchorages marked on the chart.
- 📞 Tap for details, with buttons to call or open the website.

### 🗺️ Offline NOAA charts

- 📥 NOAA electronic charts for all five lakes and connected inland lakes, stored on the phone.
- 🟥 Depth shading and hazards, with water shallower than your boat's minimum depth shaded red.

## 📲 Getting the app

**[Download the latest version](../../releases/latest)** and open the `.apk` file on your phone (Android 14 or newer). On first launch, pick your lakes; the charts download inside the app from the [charts release](../../releases/tag/charts).

To get updates automatically, add this repository to [Obtainium](https://github.com/ImranR98/Obtainium).

## 🛠️ Building the app from source

1. Install Android Studio.
2. Open the `app/` folder.
3. Connect a phone with USB debugging on and press **Run**.

Requirements: Android 14 (API 34) or newer. The build fails on purpose if any Google Play Services or Firebase library is added.

## 🙏 Data sources and credits

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

## ℹ️ About the name

"Third Coast" is a common name for the Great Lakes and their shoreline. Third Coast Sailing is an independent, non-commercial project. It isn't affiliated with, endorsed by or connected to any business, club, school or other organization, including any that use "Third Coast" in their name.

## 📜 License

The app is licensed under the **GNU Affero General Public License v3.0**. See [LICENSE](LICENSE).
