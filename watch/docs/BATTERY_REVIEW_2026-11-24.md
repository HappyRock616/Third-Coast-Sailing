# Battery review, 2026-11-24

Why: the vívoactive 5 lost about 25% overnight and about 5% an hour while idle. This reviews TCS Companion's code
against Garmin's documentation (SDK 9.3.0 docs, Core Topics) and against lessons from the Self Watch and Just Go 5K
projects. Fixes 1–7 below are in version 0.1.1.

## What Garmin's documentation says
- **Background code** runs only through `Toybox.Background` (temporal, phone-message, sleep, steps and similar
  events). It needs the Background permission and a `ServiceDelegate` (Backgrounding page).
- **Watch-app lifetime:** an app opened from the activity or apps menu "will not time out, and the user must explicitly
  exit". Only apps opened from the glance list time out (Application and System Modules page).
- **On exit:** the app is terminated and `onStop()` runs.

## App closed
| Check | Result |
|---|---|
| Background permission or `:background` code | None (manifest: BluetoothLowEnergy, Communications, Positioning, Sensor) |
| Glance or widget | None |
| Code that changes watch settings | None. Connect IQ has no API for it; the app only reads device settings |
| `onStop` | Stops the 1 s heartbeat, GPS off, sends BYE (the phone also gives up after 5 min) |

With the app closed, TCS Companion runs no code and uses no battery.

## App left open (it never closes by itself)
| # | Finding | Effect | Lesson from |
|---|---|---|---|
| B1 | No idle timeout or auto-exit | A forgotten app keeps the GPS on (Gauge, Next mark, MOB), the heartbeat running and the phone link busy, indefinitely | Just Go 5K ends a run after 30 min paused, so a forgotten watch doesn't keep the GPS on |
| B2 | No back-off on retries: TCS unreachable means HELLO every 10 s, forever | Phone or Gadgetbridge gone, so a web request every 10 s all night | Self Watch retried every 1–2 s with no back-off: about 20 connect/drop cycles in 3 hours |
| B3 | Waiting for Allow on the phone polls every 3 s with no limit | If nobody answers, polling continues until the app is closed | Self Watch: repeated requests to something that never answers (GPS assist every 5 min) |
| B4 | Every reconnect gets the full state again from the phone (route, polars, anchor) | Combined with B2, each successful retry is a full sync | Self Watch: every reconnect started a full sync |
| B5 | Full-screen redraw every second on Gauge and Next mark, whether or not anything changed | CPU and display work on the AMOLED screen | Just Go 5K: 1 Hz, faster only briefly |
| B6 | Faster timers: Sight 100 ms (redraws 1 Hz), Set anchor 250 ms while aiming, MOB 250 ms with compass, anchor alarm 500 ms | Only on those screens or during alarms; acceptable, but runs as long as the screen stays open | Just Go 5K: 4 Hz only in the last 4 s |
| B7 | `Storage.setValue("seq")` on every message sent; the outbox is written on every reliable send and ACK | Flash writes on every message | none |
| B9 | Each pushed screen made its own Timer (Anchor → Set anchor → Dial = 4 with the heartbeat); Toybox.Timer allows 3 by default. Matches the "Too Many Timers" crash on 11-22 | Crash | crash log |
| B8 | Back on any page except the Gauge jumps to the Gauge; a second Back is needed to exit | Easy to think the app closed when it didn't | none |

## Debug code in the installed copy
`bin/linktest/TCS.PRG` (installed 2026-11-22) is the **link-test build**: release compile (no demo data), but the
`:log` module is in. It writes every event to `TCS.TXT` and to Storage, including each failed request (every 10 s
under B2). Only while the app is open. Replace it with the store build (`monkey.jungle`, `-r`) before daily use.

## Ruled out on the phone side (TCS Android)
- TCS pushes only to open sessions.
- Sessions close on BYE, or after 5 min without a request.
- The direct Bluetooth link is off (`BLE_LINK`).
- The test phone holds no TCS Bluetooth registration.

## Fixes (made in 0.1.1, 2026-11-24, B4 is phone-side and not changed)
1. B1: after 10 min with no input and no anchor watch, MOB or route active: GPS off, polls stop; exit after 30 min.
2. B2: back-off of 10 s, 30 s, 60 s, 2 min, 5 min (reset on success).
3. B3: stop pair polling after 3 min (the phone's pairing window is 3 min).
4. B5: redraw only when a shown value changed.
5. B7: write `seq` only every 50 messages and on exit.
6. B8: Back exits from any loop page.
7. Install the store build instead of the link-test build.
8. B9: one shared page timer for every screen (the one on top runs it): at most 2 timers. Ble.mc timers never run (the direct link is not started).

## Memory leaks and sampling rates (follow-up, same day)
**Leaks found and fixed**
- Page ↔ Host reference cycle: every pushed screen (link, Set anchor, dial, angle, review) leaked about 0.7 KB, never
  freed (Monkey C counts references; cycles are never collected). Pages now hold a weak reference to their Host.
  Simulator tour: memory flat at about 80 KB on the vívoactive 5 (was climbing 78 → 87 KB in one loop).
- Unbounded lists capped: send queue 30 (reliable messages are also in the outbox, max 20), route 60 points,
  anchor trace 180 (Full) / 90 (Lite), shots 8 per body, seen list 16, log 14 lines.
- Dead code compiled out: Ble.mc and Wire.mc (direct link off); demo data only in tour builds.

**Sampling rates (app open; nothing runs when closed or paused)**
| What | Rate | When |
|---|---|---|
| App heartbeat | 1 s | always while open (idle check, link polls) |
| GPS (`LOCATION_CONTINUOUS`, 1 Hz) | 1 s | only on Gauge, Next mark, MOB, or while a MOB is active; off on Anchor, Sight, Set anchor, paused, closed |
| Gauge, Next mark redraw | 1 s tick, redraw only when a shown value changed | page on screen |
| Next mark ring blink | 1 s | under 30 s to the mark |
| Anchor page | no timer; redraw on phone messages | 500 ms only while alarming or the fix is lost (blink + buzz every 2 s) |
| Set anchor | 250 ms | only while aiming with the compass; 1 s while a send result shows; none once locked |
| MOB | 250 ms with compass (arrow), else 1 s | only while a MOB is active |
| Sight | 50 ms for about 1 s to find the UTC second, then 1 s | page on screen (was 100 ms the whole time) |
| Phone polls, Gadgetbridge (pushes work) | keep-alive every 2 min | trusted |
| Phone polls, Garmin Connect (no pushes) | 15 s, backing off to 30 s after 4 empty replies and 60 s after 8; back to 15 s on news or any key/touch; 5 s while MOB / anchor alarm / fix lost | trusted, app open only (was 5 s always) |
| Retries, TCS unreachable | 10 s, 30 s, 1, 2, then 5 min | untrusted |
| Waiting for Allow | 3 s, at most 3 min | pairing |
| Idle | pause after 10 min, exit after 30 min | no anchor watch, MOB or route |
