# Changelog

## 0.7.2 (not yet released)

### Added
- **Trip track.** A *Trip track* switch in the GPS section of the menu records a thin dashed line of where the boat has been, labelled with distance and elapsed time (e.g. "12.4 nm · 1:23", updated every minute). Switching it off stops recording and the clock but keeps the line; switching it back on continues it. The line is continuous: any gap (switched off, phone asleep) is spanned by a straight line and its distance counts, while time spent switched off doesn't. Once there's a track, a reset arrow (↺) appears beside the switch to clear it. Nothing is saved between app sessions. Points are recorded every 15 m (50 m when nearly stopped, so drifting at the dock adds nothing). While Trip track is on and the app is in the background or the screen is off, the position is still sampled every 5 minutes; Android shows a "Trip track recording" notification during that time. GPS isn't used in the background at any other time, and swiping the app away stops it.
- **Celestial navigation trainer.** Menu → *Celestial navigation trainer*. A yellow "Training · not for navigation" banner sits at the top. Pick a skill level (Beginner, Intermediate, Advanced); it applies to all three tabs:
  - **Plan:** today's dawn, sunrise, noon, sunset and dusk times. Beginner and Intermediate also get what to shoot and when, with preset altitude and bearing, plus *Find in sky* and *Add to workbook* buttons. Beginner adds the sextant steps with pictures and tips. Advanced gets the times only.
  - **Workbook:** one card per sight. Tap **MARK** the instant the body touches the horizon (this records the time and position), then type the sextant reading. Take 3–5 shots: they're averaged, and any off the trend are dropped. The working is laid out line by line the way a navigator writes it, each line with its equation:
    - almanac values at the hour, plus the minutes-and-seconds increment and the small v/d corrections;
    - index correction, dip, refraction, parallax and semi-diameter, giving the observed altitude;
    - assumed position, LHA, calculated altitude and azimuth;
    - intercept (miles toward or away).
    
    Levels:
    - **Beginner:** filled in, with a *From the almanac* drop-down showing the table rows the numbers came from.
    - **Intermediate:** filled in, with those rows always shown.
    - **Advanced:** you type every line from the Almanac tab. Wrong lines are outlined red and never corrected; the line of position and fix are plotted from your own numbers.
    
    Height of eye defaults to 9 ft (standing on deck). Beginner picks from a drop-down (sitting in the cockpit 6 ft, standing on deck 9 ft, standing on the cabin top 11 ft); the other levels type any height. There's also a box for index error. With no GPS, type a DR position. Sights are kept between app sessions.
  - **Almanac:** printed-almanac style tables for any day: Sun and Aries, Moon, planets, stars, increments, dip and refraction.
  - **On the chart:** lines of position and the fix are drawn in purple, labelled with the body and time. The workbook shows how far the fix is from the GPS position. Tap the fix to open the workbook. The reset arrow (↺) in the workbook clears every sight and removes them from the chart, after a confirmation.
- **3D star finder** (*★ Sky* in the trainer, *★ Find* on a sight, or *Find in sky* on a suggestion). Hold the phone up and the sky turns with it, using the phone's compass with magnetic variation applied. It's for a rough look only, not for measuring.
  - **Sky:** shaded for the real time, date and place, without the camera. It runs from blue day through an orange glow on the horizon under the Sun at twilight (with the pink band opposite the Sun just after sunset), to dark night. Stars come out as it darkens. Constellation figures are drawn, with names, and so are the navigation stars, planets and Moon.
  - **Boat:** a 3D model with the round logo and "Third Coast" on both sides and its navigation lights on. It lies on its last course inside a compass ring fixed to north, seen from behind in the direction you face. A vertical line runs from the boat up to a box showing where the phone points (bearing and altitude).
  - **Using it:**
    - Beginner: an orange arrow points to the suggested star.
    - Any level: tap a star, planet or the Moon to select it, then *Use* or *Add sight*.
    - Pinch to zoom; on devices with no compass, drag to look around.
    - ✕ or Back returns to the workbook.
- **Celestial navigation trainer: calculations.** Built-in almanac for the Sun, Moon, Venus, Mars, Jupiter, Saturn and the 58 navigational stars (checked against JPL's DE421 ephemeris: within 0.3 arc-minutes); full sight reduction from sextant reading to line of position (index error, dip, dip short of a shoreline, refraction with temperature and pressure, parallax, semi-diameter); fixes from two or more sights, including running fixes for a moving boat; daily sight planning following Bowditch (twilight star windows, the best three stars about 120° apart with preset altitude and bearing, shooting order, morning / noon / afternoon Sun, Sun–Moon daytime fixes); star finder data with constellation figures and names; printed-almanac style tables for working by hand; three skill levels (beginner step-by-step, intermediate suggestions, advanced tables only); and eight sextant step pictures.

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
- Workbook: an optional temperature and pressure setting for refraction (fixed at 10 °C and 1010 mb for now).
- Name-origin note in the README, store description and an About screen: "Third Coast" is a common name for the Great Lakes, and the app isn't affiliated with any organization.
- Wind gauge "target" label is clipped.
- Weather box reads "Updated updating…".
- Heading box shows "---°".

## 0.7.1
- Turned off R8 code shrinking in release builds (0.7 crashed on start).

## 0.7
- Downloadable lake charts from the GitHub `charts` release, with a first-launch lake picker and update checks.
