# Changelog

## 0.7.2 (not yet released)

### Added
- **Trip track.** A *Trip track* switch in the GPS section of the menu records a thin dashed line of where the boat has been, labelled with distance and elapsed time (e.g. "12.4 nm · 1:23", updated every minute). Switching it off stops recording and the clock but keeps the line; switching it back on continues it. The line is continuous: any gap (switched off, phone asleep) is spanned by a straight line and its distance counts, while time spent switched off doesn't. Once there's a track, a reset arrow (↺) appears beside the switch to clear it. Nothing is saved between app sessions. Points are recorded every 15 m (50 m when nearly stopped, so drifting at the dock adds nothing), and only while the app is open.

### Changed
- **Adding and moving route points.** "+" now drops the next point straight away, a quarter of the screen height ahead (so the spacing follows the zoom): the first point goes along the boat's course (straight up the screen when stopped or without GPS), later points continue the last leg. To move a point, hold it for about half a second without moving (the phone buzzes), then drag; quick taps, swipes, pans and pinches never move a point. Routes are limited to 6 points (the "+" fades at the limit).
- **Minimum depth per boat.** Boat setup has a new *Minimum depth* dropdown (6, 12, 18, 24 or 30 ft; default 24 ft). The weather router and the chart shading both use the active boat's setting.
- **Shallow water is shaded red, with one red line at the limit.** Water shallower than the minimum depth gets a light red fill instead of red outlines around every shallow area. This removes the confusing extra lines at chart seams, dredged channels and band edges. A single red line marks the charted contour at the minimum depth (from zoom 8 inward).
- **Disclaimer banner.** A yellow "Not for navigation · Reference use only" banner sits at the top of the Charts section of the menu.
- **Selection ring.** A blue ring marks the marina or buoy whose info card is open.
- **Lower battery use with waves on.** The wave-crest animation updates about 10 times a second instead of every screen frame (60–120 times a second). It looks the same and uses a fraction of the power.
- **Smaller app.** The APK now carries the map engine only for ARM phones and tablets (64- and 32-bit), dropping the two x86 emulator copies: about 27 MB smaller (74 MB → about 47 MB). The app no longer installs on x86 emulators or Intel Chromebooks.

### Fixed
- The router treated water starting exactly at the limit (for example 24–30 ft areas with a 24 ft limit) as too shallow, because of feet-to-metres rounding. It now matches the chart shading.

### Planned for this release
- Name-origin note in the README, store description and an About screen: "Third Coast" is a common name for the Great Lakes, and the app isn't affiliated with any organization.
- Wind gauge "target" label is clipped.
- Weather box reads "Updated updating…".
- Heading box shows "---°".

## 0.7.1
- Turned off R8 code shrinking in release builds (0.7 crashed on start).

## 0.7
- Downloadable lake charts from the GitHub `charts` release, with a first-launch lake picker and update checks.
