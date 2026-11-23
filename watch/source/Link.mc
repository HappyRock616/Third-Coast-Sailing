import Toybox.Application;
import Toybox.Cryptography;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.WatchUi;

// Phone link (docs/PROTOCOL.md). Messages are Arrays [ver, type, seq, ...], carried by the Gadgetbridge transport
// (Net.mc, PROTOCOL section 9). Ble.mc (the direct link, section 8) is kept but not started. Reliable (★) messages wait in an outbox kept in Storage and are resent every 10 s until the phone
// ACKs their seq. Until the phone has allowed this watch on this connection (PAIR 0), only HELLO and PROVE are sent
// and only PAIR is accepted. After pairing the token never travels again: the watch proves it with an HMAC.
module Link {
    const VER = 1;
    // phone -> watch
    const STATE = 1; const WX = 2; const POLAR = 3; const POLAR_CURVE = 4; const DEPTH = 5; const ROUTE = 6;
    const ROUTE_NEXT = 7; const ANCHOR = 8; const ANCHOR_TRACE = 9; const ANCHOR_FIX = 10; const MOB = 11;
    const TWILIGHT = 12; const ACK = 13; const SHOTS_DELETED = 14; const PAIR = 15; const PULL = 16;
    // watch -> phone
    const HELLO = 101; const PAGE = 102; const MOB_MARK = 103; const MOB_RESET = 104; const ANCHOR_SET = 105;
    const ANCHOR_RADIUS = 106; const ANCHOR_RESET = 107; const ANCHOR_SILENCE = 108; const NEXT_MARK = 109;
    const SHOT = 110; const SHOT_DELETE = 111; const SESSION_CLEAR = 112; const BYE = 113; const PROVE = 114;
    // PAIR results
    const PAIR_OK = 0; const PAIR_DENIED = 1; const PAIR_WAIT = 2; const PAIR_UNKNOWN = 3; const PAIR_PROVE = 4;
    // ACK results
    const ACK_OK = 0; const ACK_NO_FIX = 1; const ACK_NOT_NOW = 2; const ACK_UNKNOWN = 3; const ACK_TOO_OLD = 4;

    var seq as Number = 0;
    var outbox as Array<Array> = [];       // [seq, message]
    var seen as Array<Number> = [];        // last 16 phone seqs (this connection)
    var pendingSet as Method? = null;      // callback(result) for ANCHOR_SET
    var trusted as Boolean = false;        // the phone has allowed this watch on this connection
    var pairState as Number = -1;          // last PAIR result (-1 none yet)
    var lastHello as Number = 0;           // seconds
    var nonce as Array? = null;            // from PAIR 4; signs every request and push (section 9.3)

    function start() as Void {
        var ob = Application.Storage.getValue("outbox");
        if (ob instanceof Array) { outbox = ob as Array<Array>; }
        var sq = Application.Storage.getValue("seq");
        if (sq instanceof Number) { seq = sq; }
        Log.start();
        Net.start();
    }

    function stop() as Void { Net.stop(); }

    // The phone refused a request (403): start the handshake again
    function onRefused() as Void { trusted = false; nonce = null; Model.linked = false; hello(); }
    // TCS not reachable (not running, or Gadgetbridge not set up)
    function onUnreachable() as Void { trusted = false; nonce = null; Model.linked = false; }

    // Bluetooth connected and notifications on: introduce ourselves
    function onBleReady() as Void { trusted = false; pairState = -1; seen = []; hello(); }
    function onBleDown() as Void { trusted = false; Model.linked = false; WatchUi.requestUpdate(); }

    // The watch can prove a token only with Garmin's HMAC (API 3.0+; every supported watch, checked at run time)
    function canProve() as Boolean {
        return (Toybox has :Cryptography) && (Toybox.Cryptography has :HashBasedMessageAuthenticationCode);
    }

    function nextSeq() as Number {
        seq = (seq + 1) % 65536;
        Application.Storage.setValue("seq", seq);
        return seq;
    }

    function raw(msg as Array) as Void {
        var t = msg[1];
        if (!trusted && t != HELLO && t != PROVE) { return; }     // reliable messages wait in the outbox
        Net.send(msg);
    }

    // Latest-wins message
    function send(type as Number, fields as Array) as Void {
        raw([VER, type, nextSeq()].addAll(fields));
    }

    // Reliable message: kept until ACKed
    function sendReliable(type as Number, fields as Array) as Number {
        var s = nextSeq();
        var msg = [VER, type, s].addAll(fields);
        Log.p("tx " + type + " seq " + s);
        outbox.add([s, msg]);
        trimOutbox();
        Application.Storage.setValue("outbox", outbox);
        raw(msg);
        return s;
    }

    // Outbox full: drop sight messages first, then other commands; never a MOB message
    function trimOutbox() as Void {
        while (outbox.size() > Limits.OUTBOX_MAX) {
            var victim = -1;
            for (var pass = 0; pass < 2 && victim < 0; pass++) {
                for (var i = 0; i < outbox.size(); i++) {
                    var t = ((outbox[i] as Array)[1] as Array)[1] as Number;
                    var sight = t == SHOT || t == SHOT_DELETE || t == SESSION_CLEAR;
                    var mob = t == MOB_MARK || t == MOB_RESET;
                    if ((pass == 0 && sight) || (pass == 1 && !mob)) { victim = i; break; }
                }
            }
            if (victim < 0) { victim = 0; }          // only MOB messages left (can't happen in practice)
            outbox.remove(outbox[victim]);
        }
    }

    // A reliable message of this type is still waiting for the phone's ACK
    function hasPending(type as Number) as Boolean {
        for (var i = 0; i < outbox.size(); i++) {
            if (((outbox[i] as Array)[1] as Array)[1] == type) { return true; }
        }
        return false;
    }

    // Called every 10 s by the app timer: resend what the phone hasn't ACKed; a fresh handshake every 30 min
    function resend() as Void {
        if (!trusted) { return; }
        for (var i = 0; i < outbox.size(); i++) { raw((outbox[i] as Array)[1] as Array); }
        if (Model.now() - lastHello >= 1800) { hello(); }
    }

    function hello() as Void {
        var caps = (Model.hasCompass ? 1 : 0) | (System.getDeviceSettings().isTouchScreen ? 2 : 0) |
                   (Theme.MONO ? 4 : 0) | (Limits.LITE ? 8 : 0) | ((Toybox has :Attention) && (Toybox.Attention has :vibrate) ? 16 : 0);
        var known = Model.boatToken != null;
        if (!known && Model.pairCode == 0) { Model.pairCode = 1000 + (Model.rand32() & 0x7FFFFFFF) % 9000; }
        lastHello = Model.now();
        seen = [];                      // the phone's seq may have restarted (there's no connection to reset it)
        nonce = null; trusted = false;  // PAIR 4 -> PROVE -> PAIR 0 again; signed traffic waits until then
        Log.p("tx HELLO known=" + known + " code=" + Model.pairCode);
        send(HELLO, [1, System.getDeviceSettings().partNumber, caps, Model.now(), Model.watchId(), known, known ? null : Model.pairCode]);
    }

    // Answer the phone's challenge: first 8 bytes of HMAC-SHA256(token, nonce + watchId) as two int32 words
    // (reference/wire.js proof(), with test vectors).
    function prove(nonce as Array) as Void {
        var tok = Model.boatToken;
        if (tok == null || nonce.size() < 2 || !canProve()) { return; }
        var key = []b, msg = []b;
        for (var i = 0; i < 4; i++) { key.addAll(le32((tok as Array<Number>)[i])); }
        msg.addAll(le32(nonce[0] as Number)); msg.addAll(le32(nonce[1] as Number)); msg.addAll(le32(Model.watchId()));
        var h = new Cryptography.HashBasedMessageAuthenticationCode({ :algorithm => Cryptography.HASH_SHA256, :key => key });
        h.update(msg);
        var d = h.digest();
        Log.p("tx PROVE");
        send(PROVE, [word(d, 0), word(d, 4)]);
    }
    // Section 9.3: first 8 bytes of HMAC-SHA256(token, nonce + watchId + n), as two int32 words
    function auth(n as Number) as Array<Number>? {
        var tok = Model.boatToken, nc = nonce;
        if (tok == null || nc == null || nc.size() < 2 || !canProve()) { return null; }
        var key = []b, msg = []b;
        for (var i = 0; i < 4; i++) { key.addAll(le32((tok as Array<Number>)[i])); }
        msg.addAll(le32(nc[0] as Number)); msg.addAll(le32(nc[1] as Number)); msg.addAll(le32(Model.watchId())); msg.addAll(le32(n));
        var h = new Cryptography.HashBasedMessageAuthenticationCode({ :algorithm => Cryptography.HASH_SHA256, :key => key });
        h.update(msg);
        var d = h.digest();
        return [word(d, 0), word(d, 4)];
    }
    function authOk(a as Object?, n as Number) as Boolean {
        if (!(a instanceof Array) || (a as Array).size() < 2) { return false; }
        var want = auth(n);
        return want != null && want[0] == (a as Array)[0] && want[1] == (a as Array)[1];
    }
    function le32(n as Number) as ByteArray { return [n & 0xFF, (n >> 8) & 0xFF, (n >> 16) & 0xFF, (n >> 24) & 0xFF]b; }
    function word(b as ByteArray, o as Number) as Number {
        return (b[o] as Number) | ((b[o + 1] as Number) << 8) | ((b[o + 2] as Number) << 16) | ((b[o + 3] as Number) << 24);
    }

    // Allowed (PAIR 0): tell the phone the page on screen, then flush the outbox
    function afterPair() as Void {
        var app = Application.getApp() as TcsApp;
        if (app.host != null) { var p = (app.host as Host).page(); if (p.id() >= 0) { page(p.id()); } }
        for (var i = 0; i < outbox.size(); i++) { raw((outbox[i] as Array)[1] as Array); }
    }

    function page(p as Number) as Void { if (trusted) { send(PAGE, [p, true]); } }

    // ---------------------------------------------------------------- receive
    function tenths(v as Number?) as Float? { return v == null ? null : (v as Number) / 10.0; }
    function e6(v as Number?) as Float { return (v as Number) / 1e6; }

    function onArray(a as Array) as Void {
        if (a.size() < 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) { return; }
        var type = a[1] as Number, s = a[2] as Number;
        Log.p("rx " + type + (type == PAIR && a.size() > 3 ? " r" + a[3] : (type == ACK && a.size() > 4 ? " seq " + a[3] + " res " + a[4] : "")));
        if (seen.indexOf(s) >= 0 && type != ACK) { return; }
        seen.add(s);
        if (seen.size() > 16) { seen = seen.slice(1, null); }
        Model.lastRx = Model.now();
        apply(type, a);
        Model.linked = trusted;
        WatchUi.requestUpdate();
    }

    function isNumArray(v as Object?, n as Number) as Boolean {
        if (!(v instanceof Array) || (v as Array).size() != n) { return false; }
        for (var i = 0; i < n; i++) { if (!((v as Array)[i] instanceof Number)) { return false; } }
        return true;
    }

    // POLAR must be a proper table, else Nav.boatSpeed would index out of range
    function polarOk(tws as Object?, twa as Object?, spd as Object?) as Boolean {
        if (!(tws instanceof Array) || !(twa instanceof Array) || !(spd instanceof Array)) { return false; }
        var nw = (tws as Array).size(), na = (twa as Array).size();
        if (nw < 2 || na < 2 || !isNumArray(tws, nw) || !isNumArray(twa, na) || (spd as Array).size() != na) { return false; }
        for (var i = 0; i < na; i++) { if (!isNumArray((spd as Array)[i], nw)) { return false; } }
        return true;
    }

    function apply(type as Number, a as Array) as Void {
        if (type == PAIR && a.size() >= 4) {
            var r = a[3] as Number;
            pairState = r;
            if (r == PAIR_OK) {
                if (a.size() >= 5 && isNumArray(a[4], 4)) {
                    // a new pairing: keep the token, then prove it like any reconnect to get a nonce (section 9.3)
                    Model.setToken(a[4] as Array<Number>); Model.pairCode = 0;
                    Log.p("paired: token kept");
                    hello();
                    return;
                }
                if (nonce == null) { hello(); return; }      // can't sign requests without a nonce
                trusted = true; Model.pairCode = 0;
                Log.p("trusted");
                afterPair();
            } else if (r == PAIR_PROVE) {
                if (a.size() >= 5 && a[4] instanceof Array) { nonce = a[4] as Array; prove(nonce as Array); }
            } else if (r == PAIR_UNKNOWN) {
                trusted = false; Model.setToken(null); hello();         // the phone forgot us: show a new code
            } else {
                trusted = false;                                       // waiting for Allow, or denied
            }
            return;
        }
        if (!trusted) { return; }
        if (type == STATE && a.size() >= 11) {
            Model.units = a[4] as Number;
            Model.magVar = a[5] == null ? 0.0 : tenths(a[5]) as Float;
            Model.celestial = a[6] as Number;
            // a watch command still on its way wins over the phone's older view
            if (!hasPending(ANCHOR_SET) && !hasPending(ANCHOR_RESET)) { Model.anchorOn = a[7] as Boolean; }
            if (!hasPending(MOB_MARK) && !hasPending(MOB_RESET)) { Model.mobOn = a[8] as Boolean; }
            Model.outside = a[10] as Boolean;
        } else if (type == WX && a.size() >= 11) {
            Model.twd = tenths(a[3]); Model.tws = tenths(a[4]); Model.gust = tenths(a[5]);
            Model.waveHt = tenths(a[6]); Model.waveDir = tenths(a[7]); Model.wavePer = tenths(a[8]);
            Model.sca = (a[9] == true) as Boolean; Model.wxTime = a[10] as Number;
        } else if (type == POLAR && a.size() >= 6) {
            if (polarOk(a[3], a[4], a[5])) {
                Model.polTws = a[3] as Array<Number>; Model.polTwa = a[4] as Array<Number>; Model.polSpd = a[5] as Array<Array<Number>>;
            }
        } else if (type == POLAR_CURVE && a.size() >= 5) {
            if (isNumArray(a[4], 13)) { Model.polCurve = a[4] as Array<Number>; }      // TWA 0, 15 ... 180
        } else if (type == DEPTH && a.size() >= 4) {
            Model.depthFt = a[3] as Number?;
        } else if (type == ROUTE && a.size() >= 5) {
            Model.next = a[3] as Number;
            var p = a[4] as Array, pins = [] as Array<Array<Float>>;
            for (var i = 0; i < p.size(); i++) { var q = p[i] as Array; pins.add([e6(q[0]), e6(q[1])]); }
            Model.pins = pins;
        } else if (type == ROUTE_NEXT && a.size() >= 4) {
            Model.next = a[3] as Number;
        } else if (type == ANCHOR && a.size() >= 10) {
            var was = Model.alarm;
            if (!hasPending(ANCHOR_SET) && !hasPending(ANCHOR_RESET)) { Model.anchorOn = a[3] as Boolean; }
            Model.anchorPos = a[4] == null ? null : [e6(a[4]), e6(a[5])];
            Model.radiusFt = a[6] as Number; Model.alarm = a[7] as Number;
            Model.silencedUntil = a[8] as Number; Model.gpsLost = (a[9] == true) as Boolean;
            if (!Model.anchorOn) { Model.trDx = []; Model.trDy = []; }
            var app = Application.getApp() as TcsApp;
            if (Model.alarm == 1 && was != 1) {
                // a new alarm: buzz and bring the Anchor page up, where the alarm repeats until silenced
                Haptics.play(Haptics.ALARM);
                if (app.host != null) { (app.host as Host).goTo(2); }
            }
            if (app.host != null) { (app.host as Host).restartTimer(); }
        } else if (type == ANCHOR_TRACE && a.size() >= 8) {
            if ((a[3] as Number) == 0) { Model.trDx = []; Model.trDy = []; }
            var p = a[7] as Array<Number>;
            for (var i = 0; i + 1 < p.size(); i += 2) { Model.addFix(p[i], p[i + 1]); }
        } else if (type == ANCHOR_FIX && a.size() >= 7) {
            Model.addFix(a[4] as Number, a[5] as Number);
            Model.fixTime = (a[3] as Number) - (a[6] as Number);
        } else if (type == MOB && a.size() >= 8) {
            if (!hasPending(MOB_MARK) && !hasPending(MOB_RESET)) {
                Model.mobOn = a[3] as Boolean;
                Model.mobPos = a[4] == null ? null : [e6(a[4]), e6(a[5])];
                Model.mobTime = a[6] as Number;
                Model.saveMob();
            }
        } else if (type == TWILIGHT && a.size() >= 5) {
            Model.nautStart = a[3] as Number; Model.nautEnd = a[4] as Number;
        } else if (type == ACK && a.size() >= 5) {
            var ack = a[3] as Number, res = a[4] as Number;
            for (var i = 0; i < outbox.size(); i++) {
                var o = outbox[i] as Array;
                if (o[0] == ack) {
                    var t = (o[1] as Array)[1] as Number;
                    outbox.remove(o);
                    Application.Storage.setValue("outbox", outbox);
                    if (t == ANCHOR_SET && pendingSet != null) { (pendingSet as Method).invoke(res); pendingSet = null; }
                    break;
                }
            }
        } else if (type == SHOTS_DELETED && a.size() >= 4) {
            var ids = a[3] as Array<Number>;
            for (var i = Model.shots.size() - 1; i >= 0; i--) {
                var sh = Model.shots[i] as Array;
                if (ids.indexOf(Nav.shotId(sh[0] as Number, sh[2] as Number)) >= 0) { Model.shots.remove(sh); }
            }
            Model.saveShots();
        }
    }
}
