# Changelog

## 0.7.2 (not yet released)

### Added
- **Trip track.** A *Trip track* switch in the GPS section of the menu draws a thin dashed line of where the boat has been, labelled with distance and elapsed time (e.g. "12.4 nm · 1:23"). It isn't saved: switching it off clears the track. Points are recorded every 15 m (50 m when nearly stopped, so drifting at the dock adds nothing), and only while the app is open.

### Changed
- **Minimum depth per boat.** Boat setup has a new *Minimum depth* dropdown (6, 12, 18, 24 or 30 ft; default 24 ft). The weather router and the chart shading both use the active boat's setting.
- **Shallow water is shaded red, with one red line at the limit.** Water shallower than the minimum depth gets a light red fill instead of red outlines around every shallow area. This removes the confusing extra lines at chart seams, dredged channels and band edges. A single red line marks the charted contour at the minimum depth (from zoom 8 inward).
- **Disclaimer banner.** A yellow "Not for navigation · Reference use only" banner sits at the top of the Charts section of the menu.
- **Selection ring.** A blue ring marks the marina or buoy whose info card is open.

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
