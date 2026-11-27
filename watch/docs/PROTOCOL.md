# TCS Companion: phone ↔ watch messages

Status: v0.3 (2026-11-21, after the review in `REVIEW_2026-11-21.md`). The watch app and the TCS phone `WatchLink` (TCS_CHANGES A2) are both built against
this. **Transport: a direct Bluetooth Low Energy link between the watch app and TCS** (section 8). Garmin Connect and
Gadgetbridge are not involved, so it works the same with either (or neither), and TCS needs no Garmin SDK: one build
for F-Droid, GitHub and Play.

## 1. Rules
- **Every message is an Array:** `[ver, type, seq, ...fields]`. `ver` = 1. `seq` counts up per sender (wraps at 65535).
  Arrays are smaller than Dictionaries over the link. Only Numbers (32-bit), Booleans, null, short Strings and nested
  Arrays: no Floats on the wire (fixed point instead), so both ends round the same way.
- **Units on the wire:** angles in **tenths of a degree** (0–3599), speeds in **tenths of a knot**, depths and
  distances in **feet** (route positions excepted), lat/lon as **degrees × 1e6** (fits a 32-bit Number), times as
  **seconds since 1970 UTC**, except sight marks, which are **milliseconds** split into `[sec, ms]` (Monkey C Numbers
  are 32-bit). Unknown = `null`.
- **Size:** keep each message under **1 KB** encoded (section 8.2; about 50 chunks). The only bigger payload, the
  anchor trace, is sent in chunks of 60 points. Arrays and Strings hold at most 255 items / bytes.
- **Ordering and duplicates:** the receiver ignores a `seq` it has already seen from that sender.
  - **The phone keeps the last 64 seqs per `watchId`, across connections and app restarts.** The watch's seq is
    persistent, and a reliable message whose ACK was lost is resent after a reconnect. It must not act twice (a
    repeated `ANCHOR_SET` would re-drop the anchor from wherever the boat has moved).
  - The watch forgets the phone's seqs on each new connection (the phone's seq may restart with TCS).
- **Time-critical commands carry `tCmd`** (seconds UTC, when the user pressed). The phone refuses `ANCHOR_SET`,
  `ANCHOR_RESET` and `ANCHOR_SILENCE` over **60 s** old (`ACK` result 4), so a command queued while out of range can't
  act late (dropping an anchor from the wrong spot, or silencing a later alarm). `MOB_MARK` carries its own `tMark`
  and is always accepted.
- **Reliable messages** (marked ★) are kept in the sender's outbox (watch: `Application.Storage`, so they survive the
  app closing) and resent every 10 s until an `ACK` with that `seq` arrives. Everything else is "latest wins":
  a lost update is replaced by the next one.
- **Battery:** the phone sends only what the page on screen needs. The watch reports its page (`PAGE`) and the phone
  gates its sends on it (section 4). Nothing is sent while the watch app is closed.

## 2. Phone → watch

| Type | Name | Fields after `[ver, type, seq]` | When |
|---|---|---|---|
| 1 | `STATE` | `phoneTime, units(0 imperial / 1 metric / 2 nautical-ft), magVar×10, celestial(0 off / 1 training), anchorOn, mobOn, routeOn, outside` | On `HELLO`, and when any flag changes |
| 2 | `WX` | `twd×10, tws×10, gust×10, waveHt×10 ft, waveDir×10, wavePeriod×10 s, sca(bool), validTime` | On `HELLO`; when the forecast at the boat changes (hourly), or the boat moves into another grid cell |
| 3 | `POLAR` | `tws[]×10, twa[]×10, speed[][]×10` (one row per TWA, one value per TWS; the watch ignores a table that isn't that shape) | On `HELLO` (Full); when the active polar changes. ~8 × 20 values ≈ 0.7 KB |
| 4 | `POLAR_CURVE` | `tws×10, speed[13]×10`: **13** values at TWA 0, 15 … 165, 180; the watch ignores any other length (Lite devices only) | On `HELLO` (Lite) and when TWS changes by ≥ 1 kt |
| 5 | `DEPTH` | `depthFt` (shallow end of the charted range at the boat), or null | Every 30 s while page = Gauge |
| 6 | `ROUTE` | `next, [[latE6, lonE6], …]` (pins in order; pin 0 = start) | On `HELLO`; when pins change. ≤ 6 pins (TCS `MAX_ROUTE_POINTS`) |
| 7 | `ROUTE_NEXT` | `next` | When the phone advances (`POINT_REACHED_NM`) or accepts `NEXT_MARK` |
| 8 | `ANCHOR` | `on, latE6, lonE6, radiusFt, alarm(0 ok / 1 alarm / 2 silenced), silencedUntil, fixState(0 good / 1 stale / 2 lost; TCS decides; older TCS sends a bool "lost")` | On `HELLO`; on any change; with an alarm, immediately (any page) |
| 9 | `ANCHOR_TRACE` | `chunk, chunks, t0, intervalS, [dxFt, dyFt, dxFt, dyFt, …]` (offsets from the anchor, east and north) | When page becomes Anchor: the last 6 h (Full) or 3 h (Lite), 60 points per chunk |
| 10 | `ANCHOR_FIX` | `t, dxFt, dyFt, fixAgeS, accuracyFt` | Each new phone fix (every 2 min) while page = Anchor |
| 11 | `MOB` | `on, latE6, lonE6, tMark, source(0 phone / 1 watch)` | On any change (any page), in the full set after pairing, and as `STATE.mobOn`. **Latest wins** (the watch sends no ACKs). While a watch `MOB_MARK` / `MOB_RESET` is waiting for its ACK, the watch keeps its own MOB state |
| 12 | `TWILIGHT` | `nautStart, nautEnd, civilStart, civilEnd` (the next window) | On `HELLO` when celestial = training |
| 13 | `ACK` | `ackSeq, result` (0 ok; 1 no fresh GPS fix; 2 not allowed now; 3 unknown; 4 too old, see `tCmd`) | For every ★ message from the watch, duplicates included (ACK again, don't act again) |
| 14 | `SHOTS_DELETED` | `[shotId, …]` (deleted in the app) | When the user deletes watch shots in the app |
| 15 | `PAIR` | `result, extra`: 0 allowed (`extra` = the new token as **4 int32 words** when a new pairing is accepted, else null); 1 denied; 2 waiting for the user to tap Allow; 3 unknown watch or wrong proof (forget the token, show a new code); 4 prove it (`extra` = a fresh **2-word nonce**) | Answer to every `HELLO` and `PROVE` (section 8.4) |

## 3. Watch → phone

| Type | Name | Fields after `[ver, type, seq]` | When |
|---|---|---|---|
| 101 | `HELLO` | `appVersion (major*10000 + minor*100 + patch, e.g. 101 = 0.1.1), deviceId(string, Garmin part number), caps` (bits: 1 compass, 2 touch, 4 mono, 8 lite, 16 vibrate), `watchTime, watchId, hasToken(bool), pairCode or null`. **The token itself is never sent** | Each new Bluetooth connection, and after 5 quiet minutes as a check |
| 102 | `PAGE` | `page` (0 Gauge, 1 Next mark, 2 Anchor, 3 Conditions, 4 Sight, 5 MOB, 6 Set anchor), `screenOn(bool)` | On every page change, and when the display goes off/on if the device reports it |
| 103 | `MOB_MARK` ★ | `tMark, latE6, lonE6, fixAgeS` (watch GPS, only if its fix is at most 10 s old; else all three null) | MOB tapped on the watch. The phone uses its own fix if it has a fresh one, else the watch's. Never refused for age |
| 104 | `MOB_RESET` ★ | `tMark` (of the MOB being reset) | Hold 5 s completed |
| 105 | `ANCHOR_SET` ★ | `radiusFt, aheadFt, bearingT×10, tCmd` (`aheadFt` 0 = drop here; bearing ignored) | SET / DROP on Set anchor. Answer: `ACK` 0 (then `ANCHOR`), 1 (no fresh fix) or 4 (too old). The watch never sends a second one while one is waiting |
| 106 | `ANCHOR_RADIUS` ★ | `radiusFt` | Radius changed while anchored |
| 107 | `ANCHOR_RESET` ★ | `tCmd` | Reset anchor (2 s hold). Not sent by the watch yet |
| 108 | `ANCHOR_SILENCE` ★ | `tCmd` | Tap during an alarm. Refused over 60 s old, so it can't silence a later alarm |
| 109 | `NEXT_MARK` ★ | `fromNext` (the leg it applies to, so a late duplicate can't skip twice) | Hold on Next mark |
| 110 | `SHOT` ★ | `shotId, body(1–3), [tSec, tMs], hs×10 arc-min or null, clockOffsetMs` (null: not measured yet, see section 6) | Each saved shot |
| 111 | `SHOT_DELETE` ★ | `shotId` | Shot deleted on the watch |
| 112 | `SESSION_CLEAR` ★ | `[shotId, …]` | Hold 3 s on the Sight page |
| 114 | `PROVE` | `p0, p1`: the pairing proof (section 8.4) | Answer to `PAIR` 4 |

Type 113 (`BYE`) is retired: the Bluetooth disconnect tells the phone the app has closed.

`shotId` = (mark time in seconds mod 10,000,000) × 10 + the body. It fits a 32-bit Number, is unique for about 115 days
without a counter, and is the same if resent.

## 4. What the phone sends, by page

| Watch page | Phone sends | Watch GPS |
|---|---|---|
| Gauge | `WX` when it changes, `DEPTH` every 30 s | On, 1 s |
| Next mark | `ROUTE` / `ROUTE_NEXT` on change | On, 1 s |
| Anchor | `ANCHOR_TRACE` once, then `ANCHOR_FIX` every 2 min | **Off** |
| Conditions | `WX` when it changes | Off |
| Sight | `TWILIGHT` once | Off (marks use the watch clock) |
| MOB (active) | `MOB` on change | On, 1 s |
| Set anchor | nothing until `ANCHOR_SET` | Off (compass only) |

**The full set** (`STATE`, `WX`, `POLAR` or `POLAR_CURVE`, `ROUTE`, `ANCHOR`, `MOB`, and `TWILIGHT` in training) goes
**once per connection**, right after the phone sends `PAIR` 0. A later `HELLO` on the same connection (the 5-minute
check) gets the `PAIR` 4 / `PROVE` / `PAIR` 0 exchange only.

Always sent whatever the page: `ANCHOR` alarm changes, `MOB`, `ACK`, `STATE`, `PAIR`. Nothing at all goes to a
watch that hasn't been allowed (section 8.4), and nothing while the watch app is closed (there's no connection then).
Anchor alarms and MOB reach a closed watch app as ordinary phone notifications, through Garmin Connect or Gadgetbridge.

## 5. Sizes and rates (worst case, one 10-hour day)
- `WX` ≈ 40 B hourly; `DEPTH` ≈ 20 B every 30 s while the Gauge is showing (≈ 2.4 KB/h).
- Anchor night: one trace (6 h, 180 points ≈ 1.5 KB in 3 chunks) when the page opens, then ≈ 30 B every 2 min.
- Shots: ≈ 40 B each. MOB: a few messages.
- All told, well under 50 KB a day. The radio cost is in keeping the link awake, so the phone sends nothing while
  the watch page doesn't need it.

## 6. Clock check (sights)
`STATE.phoneTime` and `HELLO.watchTime` are both seconds UTC. The phone compares `HELLO.watchTime` with its own clock
when the watch connects, and warns in the workbook if they differ by more than 2 s. That's whole seconds only: a finer
check (`SHOT.clockOffsetMs`) is reserved and sent as null for now. Both clocks normally come from GPS or the network,
so this is a check, not a correction.

## 7. Versioning
`ver` changes only for an incompatible change. A receiver ignores a message with an unknown `type`, and ignores extra
fields at the end, so fields can be added without a new `ver`.

## 8. Direct Bluetooth link
Reference code with the exact bytes: `reference/wire.js` and its tests `reference/wire.test.js` (run
`node --test reference/wire.test.js`). The watch side is `source/Wire.mc` and `source/Ble.mc`. The phone side must pass
the same test vectors.

### 8.1 Roles and GATT
- **Phone = peripheral** (advertiser + GATT server). **Watch = central** (scans, connects). Connect IQ only lets a
  watch app act as a central, and only while the app is open.
- Service `7c3e0001-5b1a-4c8e-9f2d-3a6b8e1c4d70`:

| Characteristic | UUID | Properties | Direction |
|---|---|---|---|
| RX | `7c3e0002-5b1a-4c8e-9f2d-3a6b8e1c4d70` | Write (with response) | watch → phone |
| TX | `7c3e0003-5b1a-4c8e-9f2d-3a6b8e1c4d70` | Notify, with the standard CCCD (0x2902) | phone → watch |

- The watch connects, writes `01 00` to TX's CCCD, then sends `HELLO`. The phone may only notify after the CCCD
  write. Unencrypted GATT; trust comes from pairing (8.4), not from Bluetooth bonding.
- Up to **4 watches** at once on one phone (crew watches). Each connection has its own trust state.

### 8.2 Encoding and chunks
- One value = a tag byte + data, little-endian: `00` null, `01` false, `02` true, `03` int8, `04` int16,
  `05` int32, `06` string (length byte + UTF-8), `07` array (count byte + values). Numbers use the smallest size
  that fits. A message is one encoded array.
- Each GATT write and notification is **at most 20 bytes** (fits the default ATT MTU, so no MTU negotiation is
  needed): 1 header byte + up to 19 bytes of the message. Header: bit 7 first chunk, bit 6 last chunk, bits 0–5 a
  message counter (0–63, per direction, rolling). A one-chunk message has both bits set.
- The receiver drops a message with a missing or out-of-order chunk. Reliable messages come back through the outbox.
- The watch keeps one write in flight at a time. The phone should likewise wait for `onNotificationSent` before
  the next notification.

### 8.3 Finding the boat (advertising)
- While a TCS track or anchor watch is running, the phone advertises **manufacturer-specific data, company ID
  0xFFFF**: `54 43 01` ("TC", version 1), `boatId` (4 bytes, little-endian; random, made once per TCS install and kept),
  then the boat name in UTF-8, **at most 14 bytes** (cut at a whole character). No name → the watch shows "TCS" + the
  last 4 hex digits of the id. Nothing personal goes in the beacon: anyone nearby can read it.
- The service UUID is not advertised (no room). The watch filters on the "TC" data, with or without the company-ID
  bytes in front (scanners differ).
- **Nothing else goes in the advertisement:** Android must not add the phone's name or Tx power
  (`setIncludeDeviceName(false)`, `setIncludeTxPowerLevel(false)`). That's 3 bytes of flags + up to 25 of manufacturer
  data = 28 of the 31 bytes.
- Company ID 0xFFFF is the Bluetooth SIG's "testing" value. Open-source projects without a SIG membership commonly use
  it, and the "TC" + version bytes keep other 0xFFFF beacons out.
- Privacy: `boatId` doesn't change, so the beacon makes the phone recognisable while TCS advertises. That's acceptable
  for a boat, but the TCS privacy policy should say so.
- The watch scans for 30 s, rests 30 s, while the app is open and not connected (continuously while its picker is
  open). It connects straight away to the remembered `boatId`.

### 8.4 Pairing ("Allow this watch?") and proving it later
The token goes over the air **once**, at pairing. After that the watch proves it has the token with an HMAC, so a
Bluetooth sniffer can't capture it and replay it to silence or reset the anchor alarm.

**First time**
1. The user picks the boat on the watch. The watch connects and sends `HELLO` with `hasToken` false and a random
   4-digit `pairCode`, shown big on the watch.
2. The phone answers `PAIR` 2 (waiting) and asks: **"Allow Garmin watch? Check the watch shows 4821."**
   - **Allow** → `PAIR` 0 with a new random **128-bit token** (4 int32 words, from `SecureRandom`).
   - **Don't allow** → `PAIR` 1.
   - One prompt at a time. After a "Don't allow", ignore further `HELLO`s from that connection or `watchId` for 60 s, so
     nobody nearby can flood TCS with prompts.
3. The phone stores `watchId` + token (Settings → Watches; removing a watch forgets its token). The watch stores
   `boatId`, the boat name and the token.

**Every later connection**
1. The watch sends `HELLO` with `hasToken` true.
2. The phone answers `PAIR` 4 with a fresh random **nonce** (2 int32 words, new for every connection and every check).
3. The watch answers `PROVE` `[p0, p1]`: the first 8 bytes of
   **HMAC-SHA256(key = the token as 16 bytes, message = nonce (8 bytes) + watchId (4 bytes))**, every word little-endian,
   read back as two signed int32.
   - Reference: `proof()` in `reference/wire.js`, with a test vector cross-checked against .NET's HMACSHA256.
   - Watch: Garmin `Cryptography.HashBasedMessageAuthenticationCode` (API 3.0+; a watch without it shows "No phone link").
4. The phone computes the same and compares. Match → `PAIR` 0 (no token this time). Unknown `watchId` or wrong proof →
   `PAIR` 3, and the watch forgets its token and shows a new code.

**Rules on both sides**
- Until `PAIR` 0 on this connection, the phone accepts only `HELLO` and `PROVE` from that connection and sends only
  `PAIR`. The watch sends only `HELLO` / `PROVE` and accepts only `PAIR`.
- "Forget boat" on the watch (Menu on the Gauge → hold Forget) clears the boat and token.
- What this does not protect: someone sniffing the one pairing exchange could copy the token. Pairing takes seconds and
  happens once, at the dock. A key exchange (Garmin `Cryptography.KeyAgreement`, ECDH) could close that later.

## 9. Gadgetbridge transport (2026-11-22, replaces section 8 for now)
Section 8's direct link can't work while the watch is connected to Gadgetbridge or Garmin Connect (`LINKTEST.md`).
Messages, units, seq, ACK and the v0.3 pairing rules (sections 1–7, 8.4) stay the same; only how they travel
changes. Setup on the phone: `LINKTEST.md` / `GADGETBRIDGE.md`.

### 9.1 Two paths
| Path | Carries | Mechanism |
|---|---|---|
| **Request** (watch → phone, with a reply) | everything the watch sends; **every pairing step**; big items (`POLAR`, `ANCHOR_TRACE`, anything over 240 B) | Watch `Communications.makeWebRequest` **POST `http://127.0.0.1:52741/tcs`** (port 52742 if 52741 is busy; the watch tries 52741 first), JSON both ways. Gadgetbridge + Internet Helper carry it to TCS's server, which listens on 127.0.0.1 only. |
| **Push** (phone → watch, no reply) | small live updates (`STATE`, `WX`, `DEPTH`, `ROUTE_NEXT`, `ANCHOR`, `ANCHOR_FIX`, `MOB`, `TWILIGHT`, `ACK` of an earlier request if late) and the `PULL` nudge | Gadgetbridge Intent API broadcast `nodomain.freeyourgadget.gadgetbridge.command.SEND_WATCH_APP_MESSAGE`, `setPackage("nodomain.freeyourgadget.gadgetbridge")`, extras `appId` = `5c1e7a2b-9d4f-4e0a-8b3c-6d1f2e7a9b40`, `payload` = the JSON below. The watch receives it with `registerForPhoneAppMessages`. **Each push must be ≤ 240 bytes in Garmin's encoding** (about 45 numbers). |

New phone → watch type **16 `PULL`** (no fields): "something big is waiting, make a request now". Sent when a
message is too big to push (for example `POLAR` after a polar change), or when the phone has queued items.

### 9.2 Request body and reply
- Request: `{"w": watchId, "a": [a0, a1] or null, "m": [[msg], [msg], …]}`. Messages are the usual arrays, oldest
  first. At most 20 messages and **4 KB** per request (larger → HTTP 413).
- Reply (HTTP 200): `{"m": [[msg], …], "more": true/false}`. Contains `PAIR` answers, `ACK`s for this request's
  reliable messages, and queued items for the watch.
- **Reply size: at most 8 KB of JSON, or 2 KB for watches with the Lite bit (8) in `HELLO.caps`.** The phone splits
  anything bigger across replies and sets `"more": true`. The watch then requests again at once with `"m": []`.
  (Connect IQ refuses oversized responses with error -402, and the 96/128 KB watches have little memory to parse
  JSON.)
- An empty `"m": []` request is a poll. It's used after `PULL`, after `"more": true`, and as the keep-alive (9.4).

### 9.3 Authentication (v0.3, adapted to requests)
- A request carrying `HELLO` or `PROVE` needs no `a`. The reply carries the `PAIR` answer, as in 8.4:
  - first pairing: `PAIR 2` until Allow, then `PAIR 0` with the token (in the HTTP reply, never in a push);
  - known watch: `PAIR 4` with a fresh 2-word **nonce**, the watch answers `PROVE` in its next request, then `PAIR 0`
    or `PAIR 3`.
- Every other request: `a` = the first 8 bytes of **HMAC-SHA256(token, nonce (8) + watchId (4) + seq of the request's
  first message (4))**, little-endian, as two int32. For a poll with no messages, use seq = 0 plus a request counter:
  the watch sends `"c": n` (counting up per nonce) and uses n instead of seq. A missing or wrong `a` gives **HTTP 403**
  with an empty body. On 403 the watch sends `HELLO` again.
- **After a first pairing** (`PAIR 0` with a new token) the watch sends `HELLO` with `hasToken` true in its next request and goes through `PAIR 4` → `PROVE` → `PAIR 0`, like any reconnect. That gives it a nonce. `PAIR 0` never carries a nonce.
- `n` in the HMAC = the seq of the request's first message, or for an empty poll its counter `c`; always a 4-byte little-endian word. Vector: `auth()` in `reference/wire.js` / `wire.test.js`.
- Big items (`POLAR`, `ANCHOR_TRACE`) go in the next reply after `PULL`, whatever the page.
- The nonce lasts until the next `HELLO`. The watch sends `HELLO` when the app starts, after any 403, and every
  30 min.
- **Pushes are authenticated too**, because any app can send Intent API broadcasts once that Gadgetbridge switch is
  on. The push payload is `{"m": [msg], "a": [a0, a1]}` with `a` = the first 8 bytes of
  HMAC-SHA256(token, nonce (8) + watchId (4) + the message's seq (4)). The watch drops pushes without a valid `a`
  (and all pushes before `PAIR 0`).
- **Planned hardening (before release):** extend the HMAC to cover the content, not just `n`. A local app holding port 52741 could otherwise relay the handshake and change message contents. The watch can't see the exact JSON bytes `makeWebRequest` sends, so the MAC input becomes nonce + watchId + n + **`Wire.encode(m)`** (the section 8.2 binary encoding of the messages array, which both sides compute the same way). Pushes get the same treatment. A new test vector will come with it.
- Duplicates: the phone keeps seen seqs per `watchId` (section 1). A replayed request is ACKed, not acted on.

### 9.4 Liveness and "app closed"
- While the app is open, the watch makes a request at least every **2 minutes** (a poll if it has nothing to send),
  plus on every page change (`PAGE`).
- The phone treats the watch app as closed after **5 minutes** with no request. Then it stops pushing and gating
  sends by page.
- On `onStop` the watch sends a best-effort request containing **`BYE` (113, revived for this transport)**. It may
  not arrive, hence the timeout.

### 9.5 Open, to test first
1. Does the vívoactive 5 accept a plain `http://127.0.0.1` URL in `makeWebRequest`? If not: HTTPS with a
   self-signed certificate, which needs Internet Helper's "trust user certificates" and a certificate install, or
   another route.
2. Round-trip time watch → Gadgetbridge → Internet Helper → TCS → back. Target: under 2 s for MOB.
3. What happens when TCS isn't running: Connect IQ error code, so the watch can show "Open TCS".

### 9.6 Phones that can't push (Garmin Connect without Garmin's SDK)
- Every reply carries **`"p": true/false`**: whether TCS can push to this watch. TCS decides by detecting the
  watch's phone app: Gadgetbridge installed, holding this watch, with the Intent API switch on → `true`; otherwise
  (for example the watch is paired with Garmin Connect) → `false`.
- With `"p": false` the watch polls every **5 s** while open, so data arrives in replies instead of pushes. With
  `true` (the default) it keeps the 2-minute keep-alive.
- Open: whether Garmin Connect forwards a watch's `makeWebRequest` to `http://127.0.0.1` on the phone. Test with a
  phone paired through Garmin Connect. If it doesn't, Garmin Connect users need Garmin's SDK (HappyRock's decision).
