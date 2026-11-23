# Third Coast Sailing — Watch Companion · Design Spec

Status: draft v0.4 (2026-11-21). Decisions so far are in section 0. Phone-app changes are tracked in `docs/TCS_CHANGES.md`. Interactive renders: v2 (all pages) and v3 (next mark colours, sights, MOB) in the chat.
Nothing in `Documents\ThirdCoastSailing` has been changed for this.

## 0. Decisions (2026-11-21)
- Route ring: the **whole route**, one segment per leg, current leg draining.
- Next mark gets Just Go 5K **semicircle panels**: **VMC** left (speed toward this mark; drives time to go), **SOG** right.
  (The phone's route box says "VMG" for speed toward the final point; the watch uses VMC because it means the next mark.)
- Anchor and Conditions stay **two pages**. The Sight page is **hidden unless switched on** in settings.
- **Set anchor is one screen** (section 3.6), replacing the three-screen version.
- **Sight marks times and angles only.** No body on the watch: shots are grouped and named in the app later (section 3.7).
- MOB active: **white boat silhouette, bow up**, and a **red arrow on the ring** at the person's bearing relative to the bow.
- **No teal anywhere:** TCS palette only. Route ring: current leg light blue `#8FBCE0`, legs to come TCS blue `#2F6FA3`, done navy.
- **Celestial is Off / Training only.** Sight page: up to **3 bodies**, several shots each, review and delete. Body
  identification is a Training aid on the phone (from DR, or GPS as an extra training aid).
- **MOB with no reliable GPS heading:** the background becomes a **watch outline** and the arrow runs off the **watch
  compass**; with no compass, north up. The TCS phone app should do the same with a phone outline (TCS_CHANGES B5).
- **Phone link = direct Bluetooth** (2026-11-21): the watch app connects straight to TCS (PROTOCOL.md section 8). There's
  no Garmin SDK and no Gadgetbridge bridge, and TCS stays one build. First time: the watch lists TCS boats in range
  ("Pick boat"), you pick yours, then tap Allow for the 4-digit code in TCS. After that it reconnects by itself.
  On the Gauge, an orange pill shows the link state while unlinked (tap or Start opens the boat link screen), and
  Menu on the Gauge always opens it. "Forget boat" is a 1.5 s hold. Up to 4 watches per boat (crew can trigger MOB).
- **Build notes (v0.1 code):** compass labels are drawn upright on the watch (most devices can't rotate text);
  skipping a mark needs a **1.5 s** hold (0.6 s was too easy to trigger by accident); buttons-only watches: Menu
  switches sight body, holding Start opens review, Start held 5 s resets MOB.
- Wind thresholds: `Buoys.kt` now colours wind on a continuous scale, so the 3-level wind colours need numbers
  (placeholders: yellow 15 kt, red 22 kt or any SCA).
- Wind colour thresholds from `Buoys.kt`; wave thresholds yellow 3 ft / red 4.5 ft (placeholders, to confirm).

## 1. Product

A Garmin Connect IQ **device app** that is a wrist companion to the Third Coast Sailing
(TCS) Android app. The phone does the charts, weather, routing and anchor watch; the watch
shows the key numbers, buzzes, and sends a few commands back (MOB, silence alarm, next mark).

- Name on the watch: **Third Coast Sailing** (icon: `tcs_icon_v3.svg`, reduced for the
  launcher sizes each watch needs).
- Primary device: vívoactive 5 class (round 390×390 AMOLED, touch + 2 buttons), same as
  Just Go 5K. Round and rectangular, AMOLED and MIP, touch and buttons-only, as in Just Go 5K.
- Style: the **Just Go 5K layout language** (black background, one thick ring at the screen
  edge, a few very large numbers, concise words, icons drawn in code) in the **TCS palette and
  symbols** (navy, orange, foam/mist text, the gauge, hull, anchor, pin and MOB graphics).
- Not for navigation, as the TCS app says.

## 2. Page loop

Vertical loop, as in Just Go 5K: swipe up/down or the Up/Down buttons.

| # | Page | Notes |
|---|---|---|
| 1 | Gauge | Opens here |
| 2 | Next mark | Shown when a route is laid in on the phone; otherwise "No route" |
| 3 | Anchor | Shown when anchor watch is on; otherwise "Anchor watch off" and a Drop anchor button |
| 4 | Conditions | Wind and waves at the boat, for anchor watch (useful any time) |
| 5 | Sight | Celestial: mark sight times and sextant readings (section 3.7) |
| 6 | **MOB** | Always last, so it is **one swipe or press up from the Gauge** |

Hold Menu: settings (units, haptics, data source, Sight page on/off). Buttons-only watches: Start acts on the
page's main control (MOB, Silence, Next mark, MARK).

## 3. Pages

### 3.1 Gauge (all of the phone gauge's behaviour)
Taken from `Gauge.kt` and fitted to the round screen:
- **Heading-up compass ring** rotating with heading. Ticks every 5°, labels every 30°, N in orange.
- **Heading box** (navy, orange border) over the top of the ring with the heading, "—" with no course.
- **No-go arcs** fixed to the bow: red 30° to port, green 30° to starboard.
- **Polar** for the current forecast true wind speed, fixed to the boat, faded until wind exists.
- **Wind line** (dashed blue) from the centre toward the forecast wind direction, with the
  **orange target dot** where it crosses the polar and "target x.x".
- **Wind triangle** pointing at the centre with the speed inside. On the phone it sits outside
  the ring; on the watch it sits **on the ring band**, because the ring is at the screen edge.
- **Boat** (orange hull, mast) with the **SOG box** on deck and the **depth box** at the stern.
- **Outside the Great Lakes / coastal zones:** wind, polar target and depth greyed out, as on the phone.
- "no wind forecast yet" when there is no forecast.
- Not carried over: drag and pinch to move or resize (the gauge fills the screen).

### 3.2 Next mark (heading, distance and time to the next route point)
- **Ring = the whole route**, the Just Go 5K session ring applied to legs: one segment
  per leg, starting at 12 o'clock and running clockwise. Segment length is proportional to
  the leg's distance. Finished legs are dark navy `#1B3B5F`, and the **current leg drains**
  from light blue `#8FBCE0` to dark navy as the distance to the mark falls. Future legs are TCS blue `#2F6FA3`.
  The orange dot shows where the boat is, small dots mark the marks, and the green dot is the start.
  - Simpler option: the ring shows only the current leg, as a full circle draining to zero.
- Top: "MARK 2 of 4".
- **Side panels** (semicircles clipped by the ring, as in Just Go 5K): VMC on the left, SOG on the right.
- **Heading box** (same style as the gauge): magnetic bearing to the mark, with "M", as the
  TCS route box shows it (`steerDegM`).
- **Distance** (largest number), nm or the TCS distance unit.
- **Time to go** and **ETA**. Time = distance ÷ VMC (velocity toward the mark), or "—" when
  VMC ≤ 0.
- **Orange steer bug** just inside the ring at the mark's bearing relative to heading, which
  shows which way to turn.
- Hold the page: **Next mark** (skip ahead, sent to the phone). The phone stays the owner of the route.
- Mark reached: same rule as the phone (`POINT_REACHED_NM` = 0.1 nm); the phone advances and tells the watch.

### 3.3 Anchor (overhead plot)
- **North-up** overhead view, like a map, so no boat heading is needed to draw it.
- **Anchor icon prominently in the centre.** The **bold red circle** is the alarm radius (default
  100 ft, from the phone). The plot scale fits the circle to the screen. If the boat goes
  outside, the scale stretches so the boat stays on screen.
- **Boat icon** (TCS orange hull) at its GPS position relative to the anchor. Which way it points
  is covered in section 6.
- **Light blue line** (`#8FBCE0`) from the boat to the anchor, with the TCS **yellow
  anchor box** on it: "80 ft · 030°T" (distance and true bearing to the anchor, same as the phone banner).
- **Swing trace:** dashed line of the boat's past positions **from the phone's GPS**
  (the anchor service already takes a fix every 2 minutes). See section 5 for battery use.
- Bottom: radius and age of the last fix ("fix 1 min ago"). "GPS fix lost" when the phone reports it.
- **Alarm** (from the phone): red border ring that blinks with the buzz, "ALARM", and **tap
  anywhere = Silence 15 min** (sent to the phone, same as tapping the yellow box on the phone).
- No anchor set: "Anchor watch off" and a **Drop anchor** button. The phone drops it at its own GPS
  fix with the same fresh-fix rules as the phone menu.
- **Hold ~0.6 s** anywhere on the page: opens **Set anchor** (section 3.6).

### 3.4 Conditions (wind and waves)
- North-up compass ring (same ring as the gauge, with N, E, S, W labels).
- **Wind triangle** on the ring and **wave triangle** just inside it, both pointing at the
  centre from the direction they come from (gauge behaviour and style), with the magnitude
  inside: wind speed in kt and wave height in ft. Separate radii keep them readable when wind and
  waves come from almost the same direction, which they usually do.
- **Colour coding:** fill uses the TCS buoy scale, green = full sail, yellow = first reef and
  red = second reef or a Small Craft Advisory, with the same thresholds as `Buoys.kt`. Waves get
  their own green/yellow/red thresholds (to decide). Text is ink on green/yellow and foam on red.
- Centre: small anchor icon, "WIND 14 G19 kt", "WAVES 2.6 ft · 5 s", and the forecast time.
  An SCA shows as a red "SMALL CRAFT ADVISORY" line.
- Source: the phone's forecast at the boat (NDFD wind/gust, GLWU waves). The nearest buoy reading
  could replace it where one is close (to decide).

### 3.5 MOB (last page)
Same behaviour as the phone MOB box (`MobWatch.kt`):
- **Not active:** the whole screen is a **big red button** (TCS alarm red `#FF5A4F`, thick darker
  border), with the lifebuoy and "MOB". **One tap marks it.** Buttons-only watches: Start.
- On tap: a strong buzz, then the watch sends MOB to the phone. **The phone marks the pin at its
  GPS fix and does everything it does from its own button** (red pin, clears the chart, drift area).
  If the phone can't be reached, the watch marks it with its own GPS, keeps it, and sends it as soon
  as the phone is back.
- **Active:** a **white boat silhouette** fills the background with the **bow fixed at the top of the watch**.
  A **red arrow on the ring** (with a dashed line from the centre) sits at the person's bearing **relative to the bow**,
  so a glance shows "they're off the starboard quarter". Over it: **time in the water** counting up, **true bearing
  and distance to the pin** ("214°T · 0.42 nm"), and "**hold 5 s to reset**" (a darker fill grows while held).
  Resetting on either device resets both.
- **Heading for the arrow** (section 6), three modes:
  1. **Boat up** (faster than about 1 kt, GPS course, or the phone compass if set as mounted): white boat silhouette,
     arrow relative to the bow.
  2. **Watch up** (no reliable GPS heading, watch has a compass): the silhouette becomes a **watch outline**, and the
     arrow points from the watch toward the person using the watch compass ("watch up · hold it flat").
  3. **North up** (no heading and no compass, e.g. vívoactive 5): faded silhouette, N at the top, arrow at the
     true bearing.
  It switches automatically and goes back to boat up once there is a GPS heading. A boat doing a MOB recovery
  slows down and turns, so modes 2 and 3 will be common. The phone app should behave the same (TCS_CHANGES B5).
- While MOB is active the watch opens on the MOB page, uses its own GPS once per second, and
  updates every second. Battery use doesn't matter here.

### 3.6 Set anchor (one screen)
The phone's anchor setup (`AnchorUi.kt`: radius, "Drop here", or point at the anchor + distance + "Set ahead") on
**one watch screen**.
- **Open:** hold the Anchor page **0.6 s** (setting 0.5–0.75 s). An orange fill grows around the ring while held, and a
  short buzz confirms. Letting go early does nothing. Back closes it without changes.
- **Ring = bearing.** The heading-up compass ring follows the watch compass. Aim the **top of the watch** at the anchor,
  then **tap the ring** to lock it (status line: "aim · tap the ring to lock" → "locked · drag the ring"). Once locked,
  **drag along the ring** for 1° steps; tap it again to re-aim. The heading box shows the bearing in °T.
  - No compass: the screen opens locked, at the bearing into the forecast wind (or bow-to-anchor), ready to drag.
- **Middle = distance ahead**, a scroll drum: swipe up/down in the middle. Steps 5 ft to 100, then 10, then 25.
  At **0 ft** the ring dims, the bearing reads "—" and the button reads **DROP** (anchor at the boat).
- **Radius chip** ("R 100") swaps the drum to the alarm radius; the chip then shows the distance ahead to swap back.
- **SET** sends radius, distance and true bearing. **The phone places the anchor from its own fresh fix** (same rules
  as its "Set ahead") and answers. The button shows "…" and then "SET ✓" with 2 short buzzes, and the Anchor page
  returns. If the phone can't be reached: "Phone not connected", and nothing is set.
- **Buttons-only watches:** Up/Down = drum, Start = lock bearing, then Start again = Set, Menu = swap to radius.
- **While anchored:** the same hold opens it with the current values (change the radius, or move the anchor);
  **Reset anchor** is a 2 s hold on the chip (as on the phone).
- Magnetic to true: the phone sends the magnetic variation. Whether the watch compass gives magnetic or true needs
  checking in the SDK.

### 3.7 Sight (celestial, Training mode only)
Celestial is **Off** or **Training**. Off hides the page. The watch captures times and angles; working and
naming happen in the app (to be re-tooled to match; TCS_CHANGES section C).
- **Sight page:** three **body slots** (1, 2, 3) across the top, with a dot per shot. Tap a slot to switch body;
  tap the selected slot again to review it. Bodies are numbers only on the watch.
- **MARK:** the disc, or **any button**, records the time to the millisecond with a sharp buzz.
- **Who presses it:** watch on the sextant (right) wrist, pressed by the left hand; or mark-then-count with a delay
  setting (0–5 s) subtracted; or a crew member. All three can be offered.
- **Angle (optional):** degrees and minutes drums (0.1′), **starting at this body's last reading** so it only
  needs nudging. Enter saves; skip saves the time only.
- **Review and delete:** altitude against time for the body, with the trend line. A shot more than 2.5′ off the
  trend is red (the workbook already drops those). Tap a dot to select it, **hold the bar 1.5 s to delete it**.
  **Hold 3 s on the Sight page to clear all three bodies** (new session).
- **Which body? (Training aid, worked out on the phone):** from the **DR position** and the exact time, the almanac
  gives each navigational body's altitude (Hc) and bearing (Zn). Keep those within a few degrees of the measured
  altitude whose altitude is rising or falling the way the shots trend. A DR that is 60 nm out moves Hc by about
  1°, so this works without GPS, the way navigators identify an unknown star. Usually one to three candidates are
  left, shown with the reasons (altitude ✓/✗, rising/falling ✓/✗). **GPS training aid:** the true position
  instead of DR, which narrows it to one and shows the intercept; labelled "training aid · not for the logbook".
  A compass watch could also record which way the watch faced at the mark as a tie-breaker (rough, ±20° or so).
- **Ring:** time left in nautical twilight (from the phone's plan), draining. One buzz when it starts and 2 long at 5
  minutes left.
- **Time accuracy:** 1 s ≈ 0.25′ of longitude. The watch clock is set from GPS and the phone, and the phone warns if the
  two clocks differ by more than 1 s when shots arrive.
- **Storage:** shots stay on the watch until the phone receives them, and are sent with body number, time and Hs. The
  phone adds its own position at each mark from its track.
- **The watch's tilt sensor** could measure altitude to a few degrees only (hundreds of miles): not used.

## 4. Haptics

Haptics run on whichever page is showing, while the app is open. Each pattern can be switched off in settings.

| Event | Pattern |
|---|---|
| Next mark: each quarter of the leg done (75%, 50%, 25% left) | 1 short |
| Next mark: approaching, 3× the arrival radius (0.3 nm) | 2 short; the ring blinks with them |
| Mark reached, next leg starts | 1 long + 1 short; the ring redraws for the new leg |
| Finish reached | 3 long |
| Anchor alarm | Repeating strong pattern until silenced (matches the phone's paired bell) |
| MOB marked | 1 very long |
| Phone link lost for > 2 min (when anchor or MOB is active) | 3 short, once |
| Set anchor: hold reached / anchor set by the phone | 1 short / 2 short |
| Sight MARK | 1 sharp short |
| Sight shot deleted | 2 short |
| Twilight starts / 5 min left | 1 long / 2 long |

## 5. Data, link and battery

### Who supplies what
| Data | Source | Rate |
|---|---|---|
| Heading (COG) and SOG on Gauge and Next mark | **Watch GPS**, only while those pages are showing | 1 s |
| Forecast wind (TWD/TWS/gust), waves, SCA | Phone | When it changes (hourly) |
| Polar table | Phone, sent once and again if the boat changes | Rare |
| Depth at the boat | Phone (chart lookup) | Every 30 s on Gauge |
| Route marks (lat/lon), leg index, magnetic variation | Phone | When changed |
| Anchor point, radius, alarm state, fixes for the trace | Phone | Every 2 min (its existing anchor fix) |
| MOB pin and time | Either one; whichever marked it | On event |

Moving numbers come from the watch's own GPS, because sending them from the phone once a second
over Garmin's phone-to-watch messages is slow and drains both batteries. Slow data comes from the phone.

### Anchor page battery plan
- **No watch GPS on the anchor and conditions pages.** All positions come from the phone, which
  already takes a fix every 2 minutes.
- The watch tells the phone when it opens and leaves the anchor page. The phone sends updates
  **only while the anchor page is showing**: one small message every 2 minutes. On opening, the phone
  sends the recent trace once, packed as offsets in feet from the anchor (for example the last 6 h,
  ~180 points, about 1 KB).
- The screen redraws **only when a message arrives** (about every 2 minutes), with no timers. When the
  watch's display is off or dimmed, nothing is drawn.
- **When the watch app is closed, the alarm still reaches the wrist:** the TCS anchor alarm
  notification on the phone is mirrored to the watch by Garmin Connect, if notifications for TCS are
  on there. This works without our app being open. Separately, I'll check whether a Connect IQ
  background service can wake the app on an alarm message.

## 6. Which way is the boat pointing? (no GPS movement)

COG only means something when the boat is moving (about > 1 kt). At anchor, drifting or
head-to-wind, we need another source. The options:

| Source | Good for | Problems |
|---|---|---|
| **GPS course (COG)**, watch or phone | Under way, > ~1 kt | Meaningless when stopped; jumps around at anchor |
| **Watch compass** | Some watches only (Fenix, Epix, Forerunner 255 and up, Instinct) | Shows where the **wrist** points, not the boat. I believe the vívoactive 5 has no compass (to confirm). Only usable as "point the watch at the bow and hold" for a one-off reading |
| **Phone compass** (already used in TCS anchor setup) | Only if the phone is **mounted fixed to the boat** | Pocket or chart table = wrong; deviation near the engine or steel; needs a "phone mounted, top faces bow" setting and an offset |
| **Geometry at anchor**: bow points toward the anchor | Anchor page, when the rode is taut (boat > ~½ radius from the anchor) | Wrong when lying slack or wind against current |
| **Into the wind**: bow points into the forecast wind | Anchor page fallback | A forecast, not measured; current and swing |
| Instruments (NMEA 2000 / wireless heading sensor) | Real heading | Not in TCS today; would be a separate project |

**Proposal:** pick automatically in this order, and show which one is in use with a small letter beside the boat:
1. **GPS** (G) when SOG > 1 kt.
2. **Phone compass** (P) only if the user has turned on "phone mounted to the boat" in TCS.
3. At anchor: **toward the anchor** (A) when the boat is more than half the radius away, otherwise
   **into the wind** (W).
4. Otherwise the boat icon is drawn as a dot with no direction, and the gauge shows "—".

The anchor plot is north-up, so its layout never depends on heading; only the boat icon's direction does.

## 7. Phone app changes (TCS), for later and each with approval

- A **build flavour with Garmin's Connect IQ Mobile SDK** (`garmin`) for Play/GitHub, and the current
  build kept free of it for F-Droid (`foss`), because the SDK isn't open source.
- Users need the **Garmin Connect** app on the phone. Whether it works on GrapheneOS without
  sandboxed Google Play has to be tested on the Pixel 8.
- A small `WatchLink` class: sends the data in section 5 and handles MOB, Silence, Drop anchor and
  Next mark from the watch. No user data leaves the phone except to the watch over Bluetooth, and
  nothing is backed up (allowBackup stays false).
- Optional "phone mounted to the boat" compass setting (section 6).

## 8. Visual style

Black background (AMOLED), as in Just Go 5K. Colours come from the TCS theme and gauge:

| Use | Colour |
|---|---|
| Ring band / boxes | Navy `#0F2742`, lines `#2C5078` |
| Finished / drained ring | Navy light `#1B3B5F` |
| Main text | Foam `#F4F1EA` |
| Secondary text | Mist `#AFC2D6` |
| Heading box border, hull, target dot, boat dot, N | Orange `#F28C28` |
| Wind triangle on the gauge, wind line, depth box | Blue `#2F6FA3` |
| Polar | `#8FBCE0` |
| Route ring | Current leg `#8FBCE0`, legs to come `#2F6FA3`, done `#1B3B5F` (no teal) |
| No-go arcs | Red `#E5484D` / green `#3DBE6B` |
| Anchor box | Caution yellow `#F2C14E` with ink `#1B1F23` text |
| Alarm circle / alarm ring | `#E5484D` / `#FF5A4F` |
| MOB | Alarm red `#FF5A4F`, border `#B3261E` |
| Severity scale (conditions) | Green `#3DBE6B`, yellow `#F2C14E`, red `#E5484D` |

Ring thickness is the same constant as Just Go 5K, except the gauge's compass band, which is wider.
On MIP (64-colour) screens each colour maps to the nearest palette colour. All icons are drawn in code
so they scale: hull, anchor, lifebuoy, pin, flag and triangles.

## 9. Open questions
1. Colour thresholds: wind yellow 15 / red 22 kt, waves yellow 3 / red 4.5 ft (placeholders; TCS has no 3-level wind scale today).
2. First watches: vívoactive 5 only, or also a compass watch to test aiming?
3. Sight marking: watch on the sextant wrist, mark-then-count with a delay, or crew? (All three can be offered.)
4. Should the app also record a Garmin sailing activity (FIT) while it is open?
5. Is the "phone mounted to the boat" compass setting worth adding to TCS? It would give the MOB arrow and the
   anchor boat a heading when stopped.
