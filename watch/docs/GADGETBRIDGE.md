# Gadgetbridge and the TCS watch app: source review

> **Superseded 2026-11-21:** the watch now talks to TCS over a direct Bluetooth link (`PROTOCOL.md` section 8), which
> doesn't go through Gadgetbridge or Garmin Connect at all. This review is kept as background on why.

Reviewed 2026-11-21. Gadgetbridge source: codeberg.org/Freeyourgadget/Gadgetbridge, commit `93d4d79` (2026-11-19). It was
read only; nothing was built or changed. Paths below are under
`app/src/main/java/nodomain/freeyourgadget/gadgetbridge/`.

## Verdict
| Direction | Works with Gadgetbridge today? |
|---|---|
| Install the `.prg` on the watch | **Yes**: Gadgetbridge file upload (`devices/garmin/GarminPrgInstallHandler.kt`), or copy it over USB to `GARMIN\APPS` |
| Phone → watch (wind, route, anchor, MOB…) | **Yes, with limits**: a public Intent API, which the user switches on per watch |
| Watch → phone as app messages | **No**: Gadgetbridge receives these messages and then drops them |
| Watch → phone as web requests to the phone itself | **Should work**, see "Route without changing Gadgetbridge"; needs testing |
| App settings (Celestial etc.) | Partly: Gadgetbridge has a Connect IQ app-settings handler (`service/devices/garmin/AppConfigHandler.kt`); not yet tested |

## What the code does

**Phone → watch** (`externalevents/IntentApiReceiver.java`)
- Broadcast action `nodomain.freeyourgadget.gadgetbridge.command.SEND_WATCH_APP_MESSAGE`.
- Extras: `appId` (the watch app UUID), `payload` (a JSON string), and `device` (the watch's address; optional).
- Only works when the user turns on "Allow messages to watch apps" in that watch's settings in Gadgetbridge
  (`third_party_apps_watch_app_messages`, off by default).
- The JSON is converted to Garmin's own format (`service/devices/garmin/http/GarminJson`), so on the watch it arrives
  in `registerForPhoneAppMessages` exactly as a message from Garmin Connect would. **The watch code needs no
  changes.**
- Changelog: "Garmin: Add Intent API to send watch messages" (a recent release).

**Watch → phone** (`service/devices/garmin/communicator/GarminWatchAppCommunicator.java`, `GarminSupport.java`)
- Gadgetbridge receives and decrypts a watch app's `Communications.transmit` (`onInboundComplete`), then calls
  `GarminSupport.onWatchAppMessage`.
- That function looks the app up in a fixed map holding only Gadgetbridge's two built-in watch apps (navigation and
  loyalty cards). Any other app is logged as "Message from unknown watch app" and **dropped**. There is no Intent,
  broadcast or other way for a third-party phone app to receive it.

**Size limit**
- `MAX_FRAGMENTS = 14` × 19 bytes = **266 bytes per encrypted message** (the code notes that the watch rejects
  15 fragments). That leaves about 250 bytes of encoded data, roughly **45 numbers** per message (5 bytes each).
- Garmin Connect allows much more, and PROTOCOL.md currently plans up to 1 KB.

**Garmin's phone SDK**
- Gadgetbridge does **not** imitate Garmin Connect's service, so a TCS build using Garmin's Connect IQ Mobile SDK
  can't talk to the watch through it. TCS would need a separate Gadgetbridge path (the Intent API above).

## Route without changing Gadgetbridge (decided 2026-11-21: Gadgetbridge itself can't be changed)

Gadgetbridge forwards a watch app's **web requests** (`Communications.makeWebRequest`). It doesn't make them itself:
it passes them to its companion app **Gadgetbridge Internet Helper**, a separate F-Droid app reviewed at commit `bc39e66`.
Nothing in that chain blocks a request to the phone itself:
- `service/devices/garmin/http/HttpHandler.java`: the special handlers (weather, AGPS, contacts…) only match their own
  addresses. Anything else goes to `FirewallInterceptor`.
- `FirewallInterceptor.java`: blocks Garmin's own domains. Anything else must pass `internet/InternetFirewall.kt`.
- `InternetFirewall.kt` (`WATCH_APP`) allows the request only when **both** of these are true:
  - "Internet access" is on in the watch's settings (`device_internet_access`, off by default).
  - The user has added an **allow rule for the host** (for example `127.0.0.1`). The default is block.
- Internet Helper `aidl/http/HttpService.kt`: a plain OkHttp client, `usesCleartextTraffic="true"`, with no host
  filter. So `http://127.0.0.1:<port>/…` reaches whatever is listening on the phone.

**So the TCS link through Gadgetbridge would be:**

| Direction | Carried by | Limits |
|---|---|---|
| Phone → watch, pushed | Gadgetbridge Intent API `SEND_WATCH_APP_MESSAGE` | ~240 bytes per message |
| Watch → phone | Watch `makeWebRequest` POST to `http://127.0.0.1:<port>/tcs`, served by a tiny server inside TCS (localhost only) | One request at a time; the reply can carry bigger data |
| Phone → watch, big items (polar table, anchor trace) | The reply to the watch's request | Watch memory only, not 240 bytes |

- Commands from the watch (MOB, set anchor, silence, next mark, sights) go as the request. TCS's reply carries the
  ACK plus anything waiting, so one request does both jobs.
- The watch only makes requests when it has something to send, or when a page needs a big item. There is no
  constant polling; live updates still come by the Intent push.
- **Security:** the server listens on 127.0.0.1 only, so nothing off the phone can reach it. Other apps on the
  phone could, so every request carries a random token that TCS sends to the watch over the Intent push (only the
  watch paired in Gadgetbridge gets it). Requests without the token are ignored.
- TCS already has the `INTERNET` permission, which a listening socket needs. Nothing goes to the internet.

**One-time setup for a Gadgetbridge user** (all settings, no changes to Gadgetbridge; TCS would show these steps):
1. Install Gadgetbridge Internet Helper (F-Droid).
2. In Gadgetbridge, in the watch's settings:
   - Turn on "Allow messages to watch apps" (Intent API).
   - Turn on "Internet access".
   - Add a firewall allow rule for `127.0.0.1`.
3. In TCS, choose "Watch link: Gadgetbridge".

**Changes in our two apps only:**
- **Watch app:** `Link.mc` gets a second way to send (web request). The phone says which to use: TCS's first
  message includes the link type, port and token. Same message arrays, so `apply()` is unchanged.
- **TCS `foss` flavour:** a `WatchLink` that sends by the Intent API and listens on localhost. The `garmin` flavour
  stays on Garmin's SDK.
- **Protocol:**
  - Keep pushed messages under ~240 bytes encoded (5 bytes per number). The `garmin` link benefits too.
  - Big items (polar, anchor trace) are sent only in replies on the Gadgetbridge link, or in chunks on the Garmin
    link.

**Needs testing before relying on it** (simulator first, then the watch with Gadgetbridge):
1. Whether the watch's firmware accepts a plain `http://` URL in `makeWebRequest`. Garmin requires HTTPS for
   iPhones; on Android it has allowed http. If it doesn't, TCS would need a local HTTPS certificate, which Internet
   Helper only trusts with its "trust user certificates" option (another settings step).
2. Delay: watch → Gadgetbridge → Internet Helper → TCS → back. A MOB should arrive in a second or two.
3. That the user's firewall rule matches `127.0.0.1` exactly (rules match the host name exactly).

## Link selection: automatic, no switch (decided 2026-11-21)
TCS has no "watch link" setting. Each build turns on the links it can use, and the watch finds whichever one answers.

| TCS build | Garmin Connect link (Garmin SDK) | Gadgetbridge link (Intent push + localhost server) |
|---|---|---|
| F-Droid (`foss`) | Not included (the SDK isn't open source) | Yes |
| GitHub / Play (`garmin`) | Yes | Yes (it is plain open code, so both builds get it) |

**On the phone:**
- Garmin link: started at app launch. If Garmin Connect isn't installed, the SDK finds nothing and stays idle.
- Gadgetbridge link: the localhost server runs only while TrackService or AnchorService is running (TCS is alive
  anyway then), and only if Gadgetbridge is installed. TCS checks the package; the manifest needs a `<queries>`
  entry for `nodomain.freeyourgadget.gadgetbridge`.
- Nothing is sent to any watch until that watch says `HELLO`.

**On the watch, at start-up:**
1. Send `HELLO` by `transmit` (Garmin Connect). If TCS answers within 5 s, use this link.
2. Otherwise send `HELLO` as a web request to `http://127.0.0.1:52741/tcs` (fixed port; 52742 as the fallback).
3. TCS answers that request with "token sent" and pushes a random token to the watch through Gadgetbridge's Intent
   API. Only the watch paired in Gadgetbridge can receive it. From then on, every web request carries the token.
   Another app on the phone can trigger the push but never sees the token, so it can't send fake commands.
4. If neither link answers: "No phone" as now. The watch tries again every 60 s, which the protocol already does.

**Setup help:** if Gadgetbridge is installed and a track or anchor watch has run, but no watch has ever said
hello, TCS's Help page shows the three Gadgetbridge steps. It's a Help-page item, not a pop-up.
