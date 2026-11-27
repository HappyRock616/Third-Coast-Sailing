# TCS Companion: device compatibility

Status: v0.1 (2026-11-21). Source: the 180 device definitions in the installed Connect IQ SDK 9.3.0
(`%APPDATA%\Garmin\ConnectIQ\Devices`), surveyed by `tools/device_survey.js`; full table in `docs/device_survey.csv`.
The feature API levels come from the SDK's own API docs. **Compass hardware is not in the SDK's device files**, so the
app detects it at runtime (see section 4), and the compass list below comes from Garmin product specs as I know
them. It needs checking.

## 1. Summary

**Now 107 devices** (2026-11-24: rectangular watches, bike/handheld units and the monochrome Instinct / Descent G1 dropped after the simulator review, see the rows below; HappyRock's rule: a model that looks cluttered or doesn't work is not built). Instinct dropped: its sub-screen window cuts the Next mark box and the phone icon, and the release build peaks at 88 of 94 KB.


| Tier | Devices | What they get |
|---|---|---|
| **F – Full** | **101** | Everything as designed. Round colour watches, CIQ 3.2+ and 768 KB+ for apps. Most current models: fēnix 5 Plus → 9, epix, Enduro 3/4, Forerunner 165–970 (incl. 245 Music, 255, 265, 570, 955, 965), Venu 1–4, vívoactive 3 Music/4/5/6, MARQ, Descent Mk2/Mk3/G2, D2, Approach S50/S70/S72, Instinct 3 AMOLED and Crossover AMOLED |
| ~~**R – Rectangular watch**~~ | 0 (was 4) | **Dropped 2026-11-24** (simulator review: text overflows boxes and panels on Venu Sq 2). Venu Sq Music, Venu Sq 2 / 2 Music, Venu X1 |
| **L – Lite** (128 KB) | 6 | Everything except the Sight page, with smaller buffers (section 3.4). fēnix 6, 6S, Enduro (1st), Forerunner 245, 55, Venu Sq |
| ~~**M – Monochrome**~~ | 0 (was 8) | **Dropped 2026-11-24** (sub-screen window cuts the Next mark box and phone icon; 88 of 94 KB). Mono theme and Lite limits; MOB time shown in the round sub-screen. Instinct 2 / 2S / 2X / Crossover, Instinct 3 Solar, Instinct E 40/45, Descent G1 |
| ~~**N – Bike and handheld**~~ | 0 (was 20) | **Dropped 2026-11-24** (simulator review: the round layout fills a third of the screen and the text is too small). Edge, GPSMAP, Montana, eTrex. Could return with their own layout |
| **X – Not supported** | 41 | See section 2 |

**139 of 180 devices (77%)** are supported at launch with `minApiLevel 3.2.0`, the same as Just Go 5K. That covers
essentially every Garmin watch sold in the last six or so years.

Possible later extension: **+22 devices** with CIQ 3.0–3.1 (fēnix 5 / 5S / 5X / Chronos, Forerunner 645 / 935,
vívoactive 3 / 3 LTE, D2 Charlie / Delta, Descent Mk1, Approach S62, GPSMAP 66 / 86, Oregon 7xx, Rino 7xx, Edge 520 Plus /
820 / Explore). The code is written so this only needs `minApiLevel 3.0.0` and testing: every newer API is behind a `has`
check. Most of these would be Lite (128 KB). Decide after the simulator tests.

**Not in this SDK's device list:** quatix (Garmin's marine watch). Check in the SDK Manager whether quatix 7 / 7 Pro /
8 are available as their own devices or share the fēnix 7 / 8 definitions. They are an obvious audience for this app.

## 2. Not supported (41) and why

| Reason | Devices |
|---|---|
| CIQ 1.x / 2.x (no Application.Storage or too old) | fēnix 3 / 3 HR, D2 Bravo (both), Forerunner 230 / 235 / 630 / 735XT / 920XT, epix (2015), vívoactive (1st) / HR, Approach S60, Edge 1000 / 520 |
| No watch-app support | Forerunner 45, Swim 2, Edge 130 / 130 Plus |
| CIQ 3.0–3.1 (later extension, above) | the 22 listed in section 1 |

## 3. What changes by device

### 3.1 Inputs
- **Touch + buttons** (most F devices): as designed.
- **Buttons only** (73 devices: fēnix, Forerunner, Instinct, Enduro, Edge 5xx, GPSMAP…): every action has a button path.
  Up/Down = pages (MOB one press up from the Gauge); Start = the page's main action (MARK, Lock / Set, Silence, MOB);
  **Menu (hold Up)** = the 0.6 s hold actions (open Set anchor, review the sight body, clear session); Back = back.
  Drums: Up/Down. Bearing dial: Up/Down ±1°, held ±10°.
- **Touch + only 2 buttons** (Venu, vívoactive 4/5/6, Approach S50/S72, Edge 1040/1050): no Up/Down buttons, so pages are
  swipes. MOB is one swipe down from the Gauge.
- **Ring drag** (Set anchor bearing) needs `onDrag` (CIQ 3.3+). Touch devices on 3.2 fall back to swipe left/right
  ±5° and tap ±1° on either side of the ring.

### 3.2 Colour
| Display | Devices | Treatment |
|---|---|---|
| AMOLED / LCD, 16-bit | 88 | Full TCS palette |
| MIP, 64 colours (8-bit) | 70 | Nearest palette colours, tested on the simulator. Navy `#0F2742` → dark blue `#000055`, light blue → `#55AAFF`, orange → `#FF5500`/`#FFAA00`. Thin lines get 1 px wider so they don't vanish |
| MIP, 16 colours (4-bit) | 1 supported (Forerunner 55) | 16-colour palette; polar fill dropped |
| Monochrome (1-bit) | 8 (Instinct, Descent G1) | **Mono theme**: everything white on black. Severity colours become fills: green = outline, yellow = half fill, red = solid. The MOB button is solid white with black text. The trend outlier is a hollow square |

AMOLED: Garmin's screen-protection rules apply mainly to watch faces, but the app still avoids large static bright areas.
The MOB disc is the exception: it only shows when that page is open.

### 3.3 Screen shapes and sizes
- **Round** (128): layouts are drawn on the 390-unit design and scaled by `min(w,h)/390`, from 208 px (Forerunner 55) to
  466 px. Garmin fonts don't scale smoothly, so each text item picks the largest font that fits. **Below 240 px** some
  secondary lines drop (ETA, "fix 1 min ago", the twilight text).
- **Semi-octagon with sub-screen** (Instinct 2/3/E, Descent G1, 176 px): main layout as round; the round **sub-screen**
  shows one number: MOB time, distance to the mark, or anchor distance, depending on the page.
- **Rectangular** (Venu Sq / X1, Edge, handhelds): the round design sits in the largest circle that fits. The leftover
  strip (bottom on portrait screens) shows extra numbers (SOG, VMC, depth). Big Edge and Montana screens make good helm
  displays on a binnacle mount.

### 3.4 Memory
| App memory | Devices | Limits |
|---|---|---|
| 768 KB – 2.3 MB | 140 | No limits worth noting |
| 256–512 KB | 3 | Same as Full |
| 128 KB (Lite) | 6 + most of M | Anchor trace 90 points (3 h at 2 min); polar sent as a 12-angle curve, not the full table; **no Sight page**; one page view kept in memory at a time; drawing straight from code, no bitmaps |
| 96 KB | Instinct 2 family, Descent G1 | As Lite, plus simpler Gauge (no polar fill) |

The Lite limits are a plan. The real numbers come from the simulator's memory view.

### 3.5 Haptics and sound
- Watches: `Attention.vibrate` patterns as in DESIGN.md section 4.
- **Edge and handhelds have no vibration motor:** the same events use `Attention.playTone` (the alarm and MOB use
  Garmin's loud alert tones). Decided at runtime with `Attention has :vibrate`.
- If the user has turned off vibration or tones on the watch, the app follows that, apart from the anchor alarm, which
  also flashes the screen.

### 3.6 Phone link
**Since 2026-11-21: direct Bluetooth** (`Toybox.BluetoothLowEnergy`, CIQ 3.1+; `PROTOCOL.md` section 8). Garmin's API
docs list about 160 supported products, covering nearly all 139 here. Six aren't named there: Venu Sq, Venu Sq 2,
Edge 1030 Bontrager, Edge 1060, Edge Explore 3 / 3 Plus. They compile, and the app checks `Toybox has
:BluetoothLowEnergy` when it starts. Without it, the watch shows "No phone link" and runs on its own (MOB on the
watch GPS, sights saved on the watch). The old notes below describe the Garmin Connect route, kept for reference.

- Every supported device talks to the phone through **Garmin Connect** with `Communications.registerForPhoneAppMessages`
  (CIQ 1.0+). Edge and handhelds pair with Garmin Connect the same way.
- Phone notifications (the TCS anchor alarm) reach all of them when the app is closed, if notifications are on in Garmin Connect.
- Waking the app from a phone message in the background (`Background.registerForPhoneAppMessageEvent`, CIQ 3.0.10+)
  works on all supported devices. It's optional and comes after v1.

## 4. Compass

The app checks at start-up whether `Sensor.Info.heading` gives a value. With a compass: Set anchor "aim" and MOB
"watch up" both work. Without one: Set anchor opens on the dial (starting into the wind), and MOB goes from boat up
straight to north up.

| Probably has a compass | Probably no compass |
|---|---|
| fēnix (all), epix, Enduro, MARQ, tactix, D2, Descent, Instinct (all), quatix, Forerunner 255 / 265 / 570 / 745 / 945 / 955 / 965 / 970, Approach S70 / S72, GPSMAP, Montana | vívoactive (all), Venu (all, incl. Sq / X1), Forerunner 55 / 165 / 170 / 245, Approach S50, most Edge |

## 5. Things to verify in the simulator
1. The memory peak on a 128 KB device (fēnix 6) and a 96 KB device (Instinct 2) with a full anchor trace.
2. The 64-colour palette mapping on fēnix 7 (MIP) next to the vívoactive 5 (AMOLED).
3. The smallest layouts: Instinct 2S (163×156), Forerunner 55 (208), fēnix 6S (240).
4. Rectangular layouts: Venu Sq 2, Edge 1040, GPSMAP 67.
5. That `onDrag` arrives on vívoactive 5 / Venu 3 and the ±5° fallback works elsewhere.
6. Whether `Sensor.Info.heading` is magnetic or true, on a compass device.
7. How long a touch hold takes before `onHold` fires; the app's 0.6 s anchor hold is added on top of it.
8. **Direct Bluetooth (real watch only; the simulator needs a Nordic USB dongle):** the vívoactive 5 finds the Pixel's
   TCS beacon, connects while still paired with Garmin Connect / Gadgetbridge, and the link survives walking out of
   range and back. Also check that the Bluetooth profile fits in memory on a 128 KB watch (fēnix 6).
9. Compile check: every supported device builds (`scripts/build_all.sh`); release sizes are in `bin/all/build_all.log`.
