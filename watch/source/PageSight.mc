import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;

// Sight pages (celestial, Training mode only; compiled out on Lite and Mono devices). DESIGN 3.7.
// Touch: tap a body slot to switch, tap it again to review; tap MARK; hold 3 s to clear all bodies.
// Buttons: Start = MARK, Menu = next body, hold Start 1 s = review.

(:sight)
module Sight {
    var body as Number = 1;

    function count(b as Number) as Number {
        var n = 0;
        for (var i = 0; i < Model.shots.size(); i++) { if ((Model.shots[i] as Array)[2] == b) { n++; } }
        return n;
    }
    function lastOf(b as Number) as Array? {
        for (var i = Model.shots.size() - 1; i >= 0; i--) { var s = Model.shots[i] as Array; if (s[2] == b) { return s; } }
        return null;
    }
    function utc(t as Number) as String {
        var g = Gregorian.utcInfo(new Time.Moment(t), Time.FORMAT_SHORT);
        return g.hour.format("%02d") + ":" + g.min.format("%02d") + ":" + g.sec.format("%02d");
    }
    function send(s as Array) as Void {
        Link.sendReliable(Link.SHOT, [Nav.shotId(s[0] as Number, s[2] as Number), s[2], [s[0], s[1]], s[3], null]);   // clockOffsetMs: not measured
    }
    function clearAll() as Void {
        var ids = [];
        for (var i = 0; i < Model.shots.size(); i++) { var s = Model.shots[i] as Array; ids.add(Nav.shotId(s[0] as Number, s[2] as Number)); }
        Link.sendReliable(Link.SESSION_CLEAR, [ids]);
        Model.shots = []; Model.saveShots();
        Haptics.play(Haptics.DELETED);
    }
}

(:sight)
class SightPage extends Page {
    var pending as Number = 0;   // 1 = clear (touch hold), 2 = review (Start hold)
    var pressT as Array<Number>? = null;     // mark time taken when Start went down (the press acts on release)
    var pressAt as Number = 0;
    var fullAt as Number = -10000;           // "body full" message

    function initialize() { Page.initialize(); }
    function id() as Number { return 4; }
    // 50 ms only while finding the second boundary (about 1 s after the page opens), then 1 s
    function tickMs() as Number { return Sensors.calibrated ? 1000 : 50; }
    var drawnSec as Number = 0;
    function shown() as Void { Sensors.startCalibration(); }
    function tick() as Void {
        var was = Sensors.calibrated;
        Sensors.tickClock();
        if (!was && Sensors.calibrated && hostOf() != null) { (hostOf() as Host).restartTimer(); }
    }
    function redrawOnTick() as Boolean { return Sensors.nowSec() != drawnSec; }

    function chipAt(f as Draw.Frame, x as Number, y as Number) as Number {
        for (var b = 1; b <= 3; b++) { if (Draw.hit(f, x, y, 79 + (b - 1) * 72, 78, 64, 44)) { return b; } }
        return 0;
    }

    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        var b = chipAt(f, x, y);
        if (b > 0) {
            if (b == Sight.body && Sight.count(b) > 0) { Ui.push(new ReviewPage()); } else { Sight.body = b; }
            return true;
        }
        if (Draw.radiusOf(f, x, y) < 90) { mark(); return true; }
        return true;
    }
    function select() as Boolean { mark(); return true; }
    function menu() as Boolean { Sight.body = Sight.body % 3 + 1; return true; }   // Menu: next body
    function holdDone() as Void {
        if (pending == 1) { Sight.clearAll(); }
        else if (pending == 2 && Sight.count(Sight.body) > 0) { Ui.push(new ReviewPage()); }
        pending = 0;
    }
    function holdAt(f as Draw.Frame, x as Number, y as Number) as Number { pending = 1; return 3000; }
    function keyHoldMs() as Number { return 1000; }
    function keyDown() as Void { pending = 2; pressT = Sensors.markTime(); pressAt = System.getTimer(); }

    function mark() as Void {
        if (Sight.count(Sight.body) >= Limits.MAX_SHOTS_PER_BODY) {
            fullAt = System.getTimer(); Haptics.play(Haptics.DELETED); return;    // review and delete some first
        }
        var t = (pressT != null && System.getTimer() - pressAt < 2000) ? pressT as Array<Number> : Sensors.markTime();
        pressT = null;
        var delay = Prefs.sightDelay();
        if (delay > 0) { t[0] = t[0] - delay; }
        Haptics.play(Haptics.MARK);
        Ui.push(new AnglePage(t[0], t[1]));
    }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        var now = Model.now();
        drawnSec = Sensors.nowSec();
        // twilight ring: time left in the nautical twilight window, draining
        Draw.circle(dc, f, 180, 16, Theme.NAVY_L);
        if (Model.nautEnd > now && Model.nautEnd > Model.nautStart) {
            var fr = (Model.nautEnd - (now > Model.nautStart ? now : Model.nautStart)).toFloat() / (Model.nautEnd - Model.nautStart);
            Draw.arc(dc, f, 180, 360.0 * (1 - fr), 360.0, 16, Theme.BLUE);
        }
        Draw.text(dc, f.cx, f.y(62), "SIGHT", f.d(15), Theme.MIST, 0);
        for (var b = 1; b <= 3; b++) {
            var x = 79 + (b - 1) * 72, on = b == Sight.body, n = Sight.count(b);
            Draw.box(dc, f.x(x), f.y(78), f.d(64), f.d(44), f.d(12), on ? Theme.NAVY : Theme.NAVY_L, on ? Theme.ORANGE : Theme.LINE, f.d(on ? 3 : 1.5));
            Draw.text(dc, f.x(x + 32), f.y(96), b.format("%d"), f.d(17), on ? Theme.FOAM : Theme.MIST, 0);
            var k = n > 6 ? 6 : n;
            dc.setColor(on ? Theme.ORANGE : Theme.MIST, Graphics.COLOR_TRANSPARENT);
            for (var j = 0; j < k; j++) { dc.fillCircle(f.x(x + 32 - (k - 1) * 3.5 + j * 7), f.y(112), f.d(2.6)); }
        }
        dc.setColor(Theme.NAVY, Graphics.COLOR_TRANSPARENT); dc.fillCircle(f.cx, f.y(204), f.d(72));
        // body full (the chip dots show the count): the MARK ring turns red for 2.5 s, with a buzz
        var full = System.getTimer() - fullAt < 2500;
        dc.setColor(full ? Theme.RED : Theme.ORANGE, Graphics.COLOR_TRANSPARENT); Draw.setPen(dc, f.d(6)); dc.drawCircle(f.cx, f.y(204), f.d(72));
        Draw.text(dc, f.cx, f.y(204), "MARK", f.d(36), Theme.FOAM, 0);
        // the only text at the bottom: the time, UTC
        Draw.num(dc, f.x(178), f.y(318), Sight.utc(drawnSec), f.d(32), Theme.FOAM, false);
        Draw.text(dc, f.x(272), f.y(322), "UTC", f.d(14), Theme.MIST, 0);
        Draw.holdRing(dc, f, hostOf() == null ? 0.0 : (hostOf() as Host).holdFrac());
    }
}

// Sextant reading: degrees and minutes drums, starting at this body's last reading
(:sight)
class AnglePage extends Page {
    var tSec as Number;
    var tMs as Number;
    var deg as Number = 30;
    var mnt as Number = 0;       // tenths of an arc-minute, 0..599
    var selMin as Boolean = true;
    var dragY as Number? = null;
    var acc as Number = 0;

    function initialize(t as Number, ms as Number) {
        Page.initialize();
        tSec = t; tMs = ms;
        var l = Sight.lastOf(Sight.body);
        if (l != null && l[3] != null) { var v = l[3] as Number; deg = v / 600; mnt = v % 600; }
    }
    function wantsDrag() as Boolean { return true; }
    function showIcon() as Boolean { return false; }

    function nudge(dir as Number) as Void {
        if (!selMin) { deg = deg + dir; if (deg < 0) { deg = 0; } if (deg >= 90) { deg = 90; mnt = 0; } return; }
        mnt += dir;
        if (mnt >= 600) { mnt = 0; deg++; }
        if (mnt < 0) { if (deg > 0) { mnt = 599; deg--; } else { mnt = 0; } }
        if (deg >= 90) { deg = 90; mnt = 0; }                    // Hs never above 90°00.0'
    }
    function upDown(dir as Number) as Boolean { nudge(-dir); return true; }
    function save(withAngle as Boolean) as Void {
        var s = [tSec, tMs, Sight.body, withAngle ? deg * 600 + mnt : null];
        Model.shots.add(s);
        Model.saveShots();
        Sight.send(s);
        Ui.pop();
    }
    function select() as Boolean { if (!selMin) { selMin = true; } else { save(true); } return true; }
    function holdMs() as Number { return 1; }
    function holdDone() as Void { save(false); }      // Menu = skip (time only)

    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        if (Draw.hit(f, x, y, 66, 138, 124, 88)) { selMin = false; }
        else if (Draw.hit(f, x, y, 198, 138, 132, 88)) { selMin = true; }
        else if (Draw.hit(f, x, y, 120, 278, 150, 46)) { save(true); }
        return true;
    }
    function dragStart(f as Draw.Frame, x as Number, y as Number) as Void { dragY = y; acc = 0; }
    function drag(f as Draw.Frame, x as Number, y as Number) as Void {
        if (dragY == null) { return; }
        acc += (dragY as Number) - y; dragY = y;
        var stp = f.d(12);
        while (acc.abs() >= stp) { var s = acc > 0 ? 1 : -1; nudge(s); acc -= stp * s; }
    }
    function dragEnd(f as Draw.Frame, x as Number, y as Number) as Void { dragY = null; }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        // Hs = sextant altitude (as read, before corrections), degrees and minutes to 0.1'
        Draw.text(dc, f.cx, f.y(100), "Hs", f.d(30), Theme.ORANGE, 0);
        var dm = deg > 0 ? (deg - 1).format("%d") : "", dp = deg < 90 ? (deg + 1).format("%d") : "";
        Draw.drum(dc, f, 66, 140, 124, "", [deg.format("%d"), dm, dp], "°", !selMin);
        var m0 = (mnt + 599) % 600, m1 = (mnt + 1) % 600;
        Draw.drum(dc, f, 198, 140, 132, "", [(mnt / 10).format("%d") + "." + (mnt % 10).format("%d"),
                  (m0 / 10).format("%d") + "." + (m0 % 10).format("%d"), (m1 / 10).format("%d") + "." + (m1 % 10).format("%d")], "'", selMin);
        Draw.pill(dc, f, 120, 278, 150, 46, "ENTER", Theme.ORANGE, Theme.ON_ORANGE);
    }
}

// Altitude against time for the body, trend line, outliers red; select a shot and hold to delete it
(:sight)
class ReviewPage extends Page {
    var sel as Number = -1;      // index into Model.shots

    function initialize() {
        Page.initialize();
        var idx = indices();
        sel = idx.size() > 0 ? idx[idx.size() - 1] : -1;
    }
    function indices() as Array<Number> {
        var out = [];
        for (var i = 0; i < Model.shots.size(); i++) { if ((Model.shots[i] as Array)[2] == Sight.body) { out.add(i); } }
        return out;
    }
    function upDown(dir as Number) as Boolean {
        var idx = indices(), k = idx.indexOf(sel) + dir;
        if (k >= 0 && k < idx.size()) { sel = idx[k]; }
        return true;
    }
    function showIcon() as Boolean { return false; }
    // Delete is a tap (or Start). Back (swipe or the Back button) returns to the Sight page; it never exits the app.
    function select() as Boolean { del(); return true; }
    function del() as Void {
        if (sel < 0) { return; }
        var s = Model.shots[sel] as Array;
        Link.sendReliable(Link.SHOT_DELETE, [Nav.shotId(s[0] as Number, s[2] as Number)]);
        Model.shots.remove(s);
        Model.saveShots();
        Haptics.play(Haptics.DELETED);
        var idx = indices();
        sel = idx.size() > 0 ? idx[idx.size() - 1] : -1;
        if (sel < 0) { Ui.pop(); }
    }

    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        if (Draw.hit(f, x, y, 115, 270, 160, 48)) { del(); return true; }
        if (Draw.hit(f, x, y, 62, 78, 266, 128)) {
            var idx = indices(), best = -1, bd = 99999;
            for (var k = 0; k < idx.size(); k++) { var dx = (xOf(f, idx[k]) - x).abs(); if (dx < bd) { bd = dx; best = idx[k]; } }
            if (best >= 0) { sel = best; }
        }
        return true;
    }

    var tMin as Number = 0;
    var tSpan as Float = 1.0;
    function xOf(f as Draw.Frame, i as Number) as Number {
        var s = Model.shots[i] as Array;
        return f.x(84 + (Nav.tOf(s, tMin) / tSpan) * 222);
    }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        var idx = indices();
        if (idx.size() == 0) { Draw.text(dc, f.cx, f.y(190), "No shots", f.d(20), Theme.MIST, 0); return; }
        var sh = Model.shots;
        Draw.text(dc, f.cx, f.y(60), "BODY " + Sight.body.format("%d") + " · " + idx.size().format("%d") + (idx.size() == 1 ? " shot" : " shots"), f.d(16), Theme.FOAM, 0);
        Draw.box(dc, f.x(62), f.y(78), f.d(266), f.d(128), f.d(12), Theme.NAVY, Theme.NAVY, 0);
        tMin = (sh[idx[0]] as Array)[0] as Number;
        var tMax = Nav.tOf(sh[idx[idx.size() - 1]] as Array, tMin);
        tSpan = tMax > 0.5 ? tMax : 1.0;
        var aMin = 99999.0, aMax = -99999.0;
        for (var k = 0; k < idx.size(); k++) {
            var s = sh[idx[k]] as Array;
            if (s[3] != null) { var a = (s[3] as Number) / 10.0; if (a < aMin) { aMin = a; } if (a > aMax) { aMax = a; } }
        }
        if (aMax < aMin) { aMin = 0.0; aMax = 1.0; }
        aMin -= 3.0; aMax += 3.0;
        var fit = Nav.lineFit(sh, Sight.body, -1);
        if (fit != null) {
            var y0 = f.y(190 - (Nav.fitAt(fit, 0.0) - aMin) / (aMax - aMin) * 96), y1 = f.y(190 - (Nav.fitAt(fit, tSpan) - aMin) / (aMax - aMin) * 96);
            dc.setColor(Theme.POLAR, Graphics.COLOR_TRANSPARENT); Draw.setPen(dc, f.d(2.5));
            dc.drawLine(f.x(84), y0, f.x(306), y1);
        }
        for (var k = 0; k < idx.size(); k++) {
            var i = idx[k], s = sh[i] as Array, x = xOf(f, i);
            var y = s[3] == null ? f.y(190) : f.y(190 - ((s[3] as Number) / 10.0 - aMin) / (aMax - aMin) * 96);
            if (i == sel) { dc.setColor(Theme.ORANGE, Graphics.COLOR_TRANSPARENT); Draw.setPen(dc, f.d(3)); dc.drawCircle(x, y, f.d(12)); }
            var bad = Nav.isOutlier(sh, i);
            dc.setColor(bad ? Theme.RED : Theme.FOAM, Graphics.COLOR_TRANSPARENT);
            if (s[3] == null) { Draw.setPen(dc, f.d(2)); dc.drawCircle(x, y, f.d(5)); }
            else if (bad && Theme.MONO) { dc.drawRectangle(x - f.d(6), y - f.d(6), f.d(12), f.d(12)); }
            else { dc.fillCircle(x, y, f.d(6)); }
        }
        if (sel >= 0) { Draw.pill(dc, f, 125, 276, 140, 42, "Delete", Theme.RED, Theme.FOAM); }
    }
}

// Demo tour hooks (Tour.mc); unused in normal builds
(:sight)
module TourSight {
    function angle() as Boolean { var t = Sensors.markTime(); Ui.push(new AnglePage(t[0], t[1])); return true; }
    function review() as Boolean { Ui.push(new ReviewPage()); return true; }
}
