import Toybox.Application;
import Toybox.Cryptography;
import Toybox.System;
import Toybox.Lang;
import Toybox.Math;
import Toybox.Time;
import Toybox.WatchUi;

// Everything the pages show. Filled by Link (phone messages, PROTOCOL.md) and Sensors (watch GPS / compass).
// Units as on the wire: angles in degrees (converted from tenths on arrival), speeds in knots, depths in feet.
module Model {
    // link / phone state
    var linked as Boolean = false;
    var lastRx as Number = 0;              // seconds, last message from the phone
    // the remembered boat (direct Bluetooth link): beacon id, label, and the token the phone gave this watch
    var boatId as Number? = null;
    var boatName as String? = null;
    var boatToken as Array<Number>? = null;    // 4 words (128 bits); never sent again after pairing
    var pairCode as Number = 0;            // 4-digit code shown while the phone hasn't allowed this watch yet
    var units as Number = 0;               // 0 imperial, 1 metric, 2 nautical-ft
    var magVar as Float = 0.0;             // east positive
    var celestial as Number = 0;           // 0 off, 1 training
    var outside as Boolean = false;        // outside the forecast / chart area

    // weather at the boat (null = unknown)
    var twd as Float? = null;
    var tws as Float? = null;
    var gust as Float? = null;
    var waveHt as Float? = null;
    var waveDir as Float? = null;
    var wavePer as Float? = null;
    var sca as Boolean = false;
    var wxTime as Number = 0;

    // polar: full table (tenths) or Lite curve
    var polTws as Array<Number>? = null;
    var polTwa as Array<Number>? = null;
    var polSpd as Array<Array<Number>>? = null;
    var polCurve as Array<Number>? = null;

    var depthFt as Number? = null;

    // route: pins as [lat, lon] (degrees), next = index of the pin being sailed to
    var pins as Array<Array<Float>> = [];
    var next as Number = 1;

    // anchor
    var anchorOn as Boolean = false;
    var anchorPos as Array<Float>? = null;
    var radiusFt as Number = 100;
    var alarm as Number = 0;               // 0 ok, 1 alarm, 2 silenced
    var silencedUntil as Number = 0;
    var gpsLost as Boolean = false;
    var trDx as Array<Number> = [];        // boat offsets from the anchor, east (ft)
    var trDy as Array<Number> = [];        // north (ft)
    var fixTime as Number = 0;

    // MOB
    var mobOn as Boolean = false;
    var mobPos as Array<Float>? = null;
    var mobTime as Number = 0;

    // twilight (seconds UTC)
    var nautStart as Number = 0;
    var nautEnd as Number = 0;

    // sights: [tSec, ms, body 1-3, hs tenths of arc-min or null]
    var shots as Array<Array> = [];

    // watch sensors
    var pos as Array<Float>? = null;
    var posTime as Number = 0;             // seconds, when pos was last updated by the watch GPS
    var sogKn as Float? = null;
    var cogDeg as Float? = null;           // null when too slow for a reliable course (Sensors)
    var lastCog as Float? = null;          // last reliable course, for the dimmed Gauge ring
    var compass as Float? = null;          // true heading of the watch top, degrees
    var hasCompass as Boolean = false;

    function now() as Number { return Time.now().value(); }

    function addFix(dx as Number, dy as Number) as Void {
        trDx.add(dx); trDy.add(dy);
        while (trDx.size() > Limits.TRACE_POINTS) { trDx = trDx.slice(1, null); trDy = trDy.slice(1, null); }
    }

    function setBoat(id as Number?, name as String?) as Void {
        boatId = id; boatName = name; boatToken = null; pairCode = 0;
        saveBoat();
    }
    function setToken(t as Array<Number>?) as Void { boatToken = t; saveBoat(); }
    function saveBoat() as Void {
        if (boatId == null && boatToken == null) { Application.Storage.deleteValue("boat"); }
        else { Application.Storage.setValue("boat", [boatId, boatName, boatToken]); }
    }
    // Random id for this watch install, so the phone can list and remove watches
    function watchId() as Number {
        var w = Application.Storage.getValue("watchId");
        if (!(w instanceof Number)) { w = rand32(); Application.Storage.setValue("watchId", w); }
        return w as Number;
    }

    // Random 32-bit Number: the crypto generator where the watch has it, else Math.rand seeded once (seed()).
    function rand32() as Number {
        if ((Toybox has :Cryptography) && (Toybox.Cryptography has :randomBytes)) {
            var b = Cryptography.randomBytes(4);
            return b.decodeNumber(Lang.NUMBER_FORMAT_SINT32, { :offset => 0, :endianness => Lang.ENDIAN_LITTLE }) as Number;
        }
        return Math.rand();
    }
    function seed() as Void { Math.srand(System.getTimer() ^ now()); }

    function load() as Void {
        seed();
        var bt = Application.Storage.getValue("boat");
        if (bt instanceof Array && bt.size() == 3) {
            boatId = bt[0] as Number?; boatName = bt[1] as String?;
            boatToken = (bt[2] instanceof Array && (bt[2] as Array).size() == 4) ? bt[2] as Array<Number> : null;   // old 32-bit tokens: pair again
        }
        var s = Application.Storage.getValue("shots");
        if (s instanceof Array) { shots = s as Array<Array>; }
        var m = Application.Storage.getValue("mob");
        if (m instanceof Array && m.size() == 3) {
            mobOn = true; mobTime = m[0] as Number;
            mobPos = m[1] == null ? null : [(m[1] as Number) / 1e6, (m[2] as Number) / 1e6];
        }
    }

    function saveShots() as Void { Application.Storage.setValue("shots", shots); }

    function saveMob() as Void {
        if (!mobOn) { Application.Storage.deleteValue("mob"); return; }
        var p = mobPos;
        Application.Storage.setValue("mob", [mobTime, p == null ? null : (p[0] * 1e6).toNumber(), p == null ? null : (p[1] * 1e6).toNumber()]);
    }

    // Debug builds: sample data matching the renders, so every page shows without a phone. Never in release builds.
    (:debug)
    function demo() as Void {
        var t = now();
        linked = true; lastRx = t; magVar = -4.5;
        twd = 300.0; tws = 14.0; gust = 19.0; waveHt = 2.6; waveDir = 314.0; wavePer = 5.0; wxTime = t;
        polTws = [60, 80, 100, 120, 160, 200];
        polTwa = [0, 300, 450, 600, 900, 1200, 1500, 1800];
        polSpd = [[0, 0, 0, 0, 0, 0], [28, 34, 39, 42, 45, 46], [44, 52, 58, 61, 64, 65], [50, 58, 64, 67, 70, 71],
                  [55, 63, 68, 72, 76, 78], [52, 61, 67, 72, 78, 82], [44, 54, 61, 67, 75, 81], [36, 45, 53, 60, 69, 76]];
        depthFt = 38;
        pins = [[43.0631, -86.2284], [43.0900, -86.2600], [43.1300, -86.2400], [43.1500, -86.2700], [43.1200, -86.3100]];
        next = 2;
        pos = [43.1050, -86.2510]; sogKn = 6.2; cogDeg = 47.0; posTime = t;
        anchorOn = true; anchorPos = [43.0631, -86.2284]; radiusFt = 100; trDx = []; trDy = [];
        for (var i = 0; i < 34; i++) {
            var b = Nav.rad(176 + i * 2.1), r = 72 + (i * 37 % 14);
            addFix((r * Math.sin(b)).toNumber(), (r * Math.cos(b)).toNumber());
        }
        fixTime = t - 60;
        nautStart = t - 22 * 60; nautEnd = t + 18 * 60; celestial = 1;
        if (shots.size() == 0) {
            var t0 = t - 300;
            shots = [[t0, 0, 1, 19302], [t0 + 40, 0, 1, 19360], [t0 + 85, 0, 1, 19431], [t0 + 120, 0, 1, 19510],
                     [t0 + 150, 0, 2, 28224], [t0 + 190, 0, 2, 28191]];
        }
    }
    (:release)
    function demo() as Void {}
}
