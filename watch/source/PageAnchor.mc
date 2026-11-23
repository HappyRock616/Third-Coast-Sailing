import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;

// Anchor pages (redesign, 2026-11-22). Every anchor icon is a button: an orange circle with the anchor in
// it, green when an anchor is set, red when not.
//   AnchorPage (main): north-up plot from the phone's fixes. The red alarm ring, the boat swinging live with its
//     trace, and the live distance to the anchor on the line. Tap the anchor: settings. Hold it: lift the anchor.
//   SetAnchorPage (settings): the compass ring aims the bearing as before. The boat sits at the bottom with the
//     distance box on the line and the alarm box inside the dashed red ring. Tap a box: its dial. Tap the anchor: set.
//     Hold it: lift.
//   DialPage: the distance or the alarm radius on a drum, with DONE.
module AnchorUi {
    const BTN_R = 33;
    function anchorHit(f as Draw.Frame, x as Number, y as Number) as Boolean { return Draw.radiusOf(f, x, y) <= BTN_R + 12; }
    // Lift the anchor (ANCHOR_RESET with tCmd: the phone refuses it after 60 s)
    function lift() as Boolean {
        if (!Model.linked) { return false; }
        Link.sendReliable(Link.ANCHOR_RESET, [Model.now()]);
        Model.anchorOn = false; Model.alarm = 0;
        Haptics.play(Haptics.SET);
        return true;
    }
}

class AnchorPage extends Page {
    function initialize() { Page.initialize(); }
    function id() as Number { return 2; }
    function tickMs() as Number { return Model.alarm == 1 ? 500 : 0; }

    function openSettings() as Void { Haptics.play(Haptics.HOLD); Ui.push(new SetAnchorPage()); }

    // touch: tap = silence an alarm, or the anchor button opens settings; hold the anchor 2 s = lift
    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        if (Model.alarm == 1) { silence(); return true; }
        if (AnchorUi.anchorHit(f, x, y) || !Model.anchorOn) { openSettings(); return true; }
        return true;
    }
    function holdAt(f as Draw.Frame, x as Number, y as Number) as Number {
        return Model.anchorOn && AnchorUi.anchorHit(f, x, y) ? 2000 : 0;
    }
    function holdDone() as Void { AnchorUi.lift(); if (host != null) { (host as Host).restartTimer(); } }
    // buttons: Start = silence or settings; hold Start 2 s = lift; Menu = settings
    function select() as Boolean {
        if (Model.alarm == 1) { silence(); return true; }
        openSettings(); return true;
    }
    function keyHoldMs() as Number { return Model.anchorOn && Model.alarm != 1 ? 2000 : 0; }
    function menu() as Boolean { openSettings(); return true; }

    function silence() as Void {
        Model.alarm = 2;
        Model.silencedUntil = Model.now() + 900;
        Link.sendReliable(Link.ANCHOR_SILENCE, [Model.now()]);     // tCmd: the phone ignores a silence over 60 s old
        if (host != null) { (host as Host).restartTimer(); }
    }
    function tick() as Void { if (Model.alarm == 1 && (System.getTimer() / 2000) % 2 == 0) { Haptics.play(Haptics.ALARM); } }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        var hold = host == null ? 0.0 : (host as Host).holdFrac();
        Draw.text(dc, f.cx, f.y(34), "N", f.d(20), Theme.ORANGE, 0);
        if (!Model.anchorOn || Model.anchorPos == null) {
            Draw.anchorButton(dc, f, f.cx, f.cy, false);
            Draw.text(dc, f.cx, f.y(270), "Anchor watch off", f.d(22), Theme.FOAM, 0);
            Draw.text(dc, f.cx, f.y(300), "tap the anchor to set", f.d(15), Theme.MIST, 0);
            return;
        }
        var n = Model.trDx.size();
        var bx = n > 0 ? Model.trDx[n - 1] : 0, by = n > 0 ? Model.trDy[n - 1] : 0;
        var ta = Nav.toAnchor(bx, by);
        var alarm = Model.alarm == 1;
        var sc = Nav.plotScale(Model.radiusFt, ta[0], 140) * f.s;   // px per ft
        dc.setColor(Theme.RED, Graphics.COLOR_TRANSPARENT);
        Draw.setPen(dc, f.d(4));
        dc.drawCircle(f.cx, f.cy, (Model.radiusFt * sc).toNumber());

        // swing trace: dotted line of past fixes
        dc.setColor(Theme.MIST, Graphics.COLOR_TRANSPARENT);
        Draw.setPen(dc, f.d(2));
        for (var i = 1; i < n; i++) {
            if (i % 2 == 0) { continue; }
            dc.drawLine(f.cx + (Model.trDx[i - 1] * sc).toNumber(), f.cy - (Model.trDy[i - 1] * sc).toNumber(),
                        f.cx + (Model.trDx[i] * sc).toNumber(), f.cy - (Model.trDy[i] * sc).toNumber());
        }
        var px = f.cx + (bx * sc).toNumber(), py = f.cy - (by * sc).toNumber();
        dc.setColor(Theme.POLAR, Graphics.COLOR_TRANSPARENT);
        Draw.setPen(dc, f.d(4));
        dc.drawLine(f.cx, f.cy, px, py);
        // the bow points at the anchor when lying back on it, else into the wind
        var bow = ta[1];
        if (ta[0] < Model.radiusFt * 0.5 && Model.twd != null) { bow = Model.twd as Float; }
        Draw.hull(dc, f, px, py, 50, 20, bow, Theme.ORANGE, Theme.NAVY);

        // live distance to the anchor, in a data box beside the middle of the line
        var mx = (f.cx + px) / 2, my = (f.cy + py) / 2;
        var off = f.d(38);
        var ux = (py - f.cy), uy = -(px - f.cx), ul = Math.sqrt(ux * ux + uy * uy);
        if (ul > 1) { mx += (off * ux / ul).toNumber(); my += (off * uy / ul).toNumber(); } else { my += off; }
        Draw.dataBox(dc, f, mx, my, Model.gpsLost ? "no fix" : ta[0].format("%.0f") + " ft", false, alarm ? Theme.ALARM : Theme.ORANGE);

        if (alarm) {
            if ((System.getTimer() / 500) % 2 == 0) { Draw.circle(dc, f, 186, 12, Theme.ALARM); }
            Draw.text(dc, f.cx, f.y(92), "ALARM", f.d(30), Theme.ALARM, 0);
            Draw.text(dc, f.cx, f.y(332), "tap to silence", f.d(15), Theme.FOAM, 0);
        } else if (Model.alarm == 2) {
            var left = Model.silencedUntil - Model.now();
            if (left < 0) { left = 0; }
            Draw.text(dc, f.cx, f.y(332), "silenced " + (left / 60).format("%d") + ":" + (left % 60).format("%02d"), f.d(15), Theme.MIST, 0);
        } else if (!f.small()) {
            var age = (Model.now() - Model.fixTime) / 60;
            Draw.text(dc, f.cx, f.y(332), "fix " + age.format("%d") + " min ago", f.d(15), Theme.MIST, 0);
        }
        Draw.anchorButton(dc, f, f.cx, f.cy, true);
        if (hold > 0.0) { Draw.holdRing(dc, f, hold); }
    }
}

// Settings: compass ring for the bearing (aim with the watch compass, tap the ring to lock, drag 1 deg at a time),
// boat at the bottom, distance box on the line, alarm box inside the dashed red ring, anchor button in the middle.
class SetAnchorPage extends Page {
    var ahead as Number = 0;
    var radius as Number;
    var radiusSet as Boolean = false;   // false: the alarm follows distance + 50 ft
    var editRadius as Boolean = false;  // buttons: Up/Down change this value (Menu switches)
    var locked as Boolean = false;
    var bear as Number = 0;
    var state as Number = 0;          // 0 editing, 1 sending, 2 set, 3 no fix, 4 no phone, 5 too late, 6 queued
    var stateAt as Number = 0;
    var dragA as Float? = null;
    var dragAcc as Float = 0.0;
    var probeUntil as Number = 0;
    var touched as Boolean = false;
    var lifting as Boolean = false;

    function initialize() {
        Page.initialize();
        radius = Model.radiusFt;
        radiusSet = Model.anchorOn;
        if (!radiusSet) { radius = ahead + 50; }
        Sensors.readCompass();
        probeUntil = System.getTimer() + 2000;
        if (!Model.hasCompass) {
            locked = true;           // no compass: start locked, into the wind, ready to dial
            bear = Model.twd == null ? 0 : Math.round(Model.twd as Float).toNumber() % 360;
        }
    }
    function id() as Number { return 6; }
    function tickMs() as Number { return locked && state == 0 && System.getTimer() > probeUntil ? 0 : 250; }
    function wantsDrag() as Boolean { return true; }

    function aim() as Number {
        Sensors.readCompass();
        return Model.compass == null ? bear : Math.round(Model.compass as Float).toNumber() % 360;
    }

    function tick() as Void {
        if (!Model.hasCompass && System.getTimer() < probeUntil) {
            Sensors.readCompass();
            if (Model.hasCompass && !touched) { locked = false; }
        }
        var wait = state == 1 ? 15000 : 1500;
        if (state != 0 && System.getTimer() - stateAt > wait) {
            if (state == 2) { Ui.pop(); return; }
            state = state == 1 ? 6 : 0;
            stateAt = System.getTimer();
            if (host != null) { (host as Host).restartTimer(); }
        }
    }

    // Called by the dial
    function setAhead(v as Number) as Void {
        ahead = v; touched = true;
        if (!radiusSet) { radius = ahead + 50 > 500 ? 500 : ahead + 50; }
    }
    function setRadius(v as Number) as Void { radius = v; radiusSet = true; touched = true; }

    function valueUp(dir as Number) as Void {
        touched = true;
        if (editRadius) { setRadius(dir > 0 ? Nav.drumUp(radius, 500) : Nav.drumDown(radius, 20)); }
        else { setAhead(dir > 0 ? Nav.drumUp(ahead, 300) : Nav.drumDown(ahead, 0)); }
    }

    // buttons: Up/Down = bearing once locked (buttons-only watches), else the value Menu chose
    function upDown(dir as Number) as Boolean {
        if (locked && !System.getDeviceSettings().isTouchScreen) { bear = (bear - dir + 360) % 360; }
        else { valueUp(-dir); }
        return true;
    }
    function select() as Boolean {
        if (!locked && ahead > 0) { lock(); return true; }
        send();
        return true;
    }
    function holdMs() as Number { return 1; }
    function holdDone() as Void {
        if (lifting) { lifting = false; if (AnchorUi.lift()) { Ui.pop(); } else { state = 4; stateAt = System.getTimer(); } return; }
        editRadius = !editRadius;            // Menu: Up/Down now change the other value
    }
    function holdAt(f as Draw.Frame, x as Number, y as Number) as Number {
        lifting = Model.anchorOn && AnchorUi.anchorHit(f, x, y);
        return lifting ? 2000 : 0;
    }
    function keyHoldMs() as Number { return Model.anchorOn ? 2000 : 0; }
    function keyDown() as Void { lifting = Model.anchorOn; }

    function lock() as Void {
        locked = !locked;
        if (locked) { bear = aim(); Haptics.play(Haptics.HOLD); }
        if (host != null) { (host as Host).restartTimer(); }
    }

    function send() as Void {
        if (state != 0) { return; }              // never a second command while one is on its way
        if (Link.hasPending(Link.ANCHOR_SET)) { state = 6; stateAt = System.getTimer(); return; }
        if (!Model.linked) { state = 4; stateAt = System.getTimer(); return; }
        state = 1; stateAt = System.getTimer();
        Link.pendingSet = method(:onResult);
        if (Model.anchorOn && ahead == 0 && radius != Model.radiusFt) {
            Link.sendReliable(Link.ANCHOR_RADIUS, [radius]);
            Link.pendingSet = null; state = 2;
        } else {
            Link.sendReliable(Link.ANCHOR_SET, [radius, ahead, ahead == 0 ? 0 : bear * 10, Model.now()]);   // + tCmd
        }
        if (host != null) { (host as Host).restartTimer(); }
    }

    function onResult(res as Number) as Void {
        state = res == Link.ACK_OK ? 2 : (res == Link.ACK_NO_FIX ? 3 : (res == Link.ACK_TOO_OLD ? 5 : 4));
        stateAt = System.getTimer();
        if (res == 0) { Haptics.play(Haptics.SET); Model.radiusFt = radius; Model.anchorOn = true; }
        update();
    }

    // geometry (design units): anchor at the centre, boat at the bottom, boxes as in the mockup
    const BOAT_Y = 300;
    const DIST_Y = 255;
    const ALARM_X = 100;

    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        if (AnchorUi.anchorHit(f, x, y)) { send(); return true; }
        if (Draw.hit(f, x, y, 149, DIST_Y - 20, 92, 40)) { Ui.push(new DialPage(self, false)); return true; }
        if (Draw.hit(f, x, y, ALARM_X - 46, 175, 92, 40)) { Ui.push(new DialPage(self, true)); return true; }
        if (Draw.radiusOf(f, x, y) > 146) { lock(); return true; }   // ring tap (a drag along it is handled below)
        return true;
    }

    function dragStart(f as Draw.Frame, x as Number, y as Number) as Void {
        dragAcc = 0.0;
        dragA = Draw.radiusOf(f, x, y) > 146 ? Draw.angleOf(f, x, y) : null;
    }
    function drag(f as Draw.Frame, x as Number, y as Number) as Void {
        if (dragA == null || !locked) { return; }
        var a = Draw.angleOf(f, x, y), d = Nav.angDiff(dragA as Float, a);
        dragA = a; dragAcc += d;
        while (dragAcc.abs() >= 3.0) {
            var s = dragAcc > 0 ? 1 : -1;
            bear = (bear + s + 360) % 360; dragAcc -= 3.0 * s;
        }
    }
    function dragEnd(f as Draw.Frame, x as Number, y as Number) as Void { dragA = null; }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        var drop = ahead == 0;
        var h = locked ? bear : aim();
        Draw.dim = drop;                 // dropping at the boat: no bearing, so the ring is shown dimmed
        Draw.compass(dc, f, h, false);
        Draw.dim = false;
        Draw.headBox(dc, f, drop ? "--" : h.format("%03d"), drop ? "" : "T", 22, 104);

        // dashed red alarm ring (schematic, not to scale)
        for (var a = 0; a < 360; a += 12) { Draw.arc(dc, f, 138, a.toFloat(), (a + 7).toFloat(), 4, Theme.RED); }
        // line anchor -> boat, boat at the bottom pointing up at the anchor
        dc.setColor(Theme.POLAR, Graphics.COLOR_TRANSPARENT);
        Draw.setPen(dc, f.d(4));
        dc.drawLine(f.cx, f.y(195 + AnchorUi.BTN_R), f.cx, f.y(BOAT_Y - 22));
        Draw.hull(dc, f, f.cx, f.y(BOAT_Y), 46, 18, 0, Theme.ORANGE, Theme.NAVY);
        Draw.dataBox(dc, f, f.cx, f.y(DIST_Y), drop ? "at boat" : ahead.format("%d") + " ft", false, !editRadius ? Theme.ORANGE : Theme.LINE);
        Draw.dataBox(dc, f, f.x(ALARM_X), f.y(195), radius.format("%d"), true, editRadius ? Theme.ORANGE : Theme.LINE);

        var msg = state == 1 ? "sending..." : (state == 2 ? "set" : (state == 3 ? "no GPS fix on the phone" :
                  (state == 4 ? "no phone link" : (state == 5 ? "too late: try again" : (state == 6 ? "queued: waiting for the phone" :
                  (drop ? "tap anchor: drop here" : (locked ? "tap anchor: set · hold: lift" : "aim · tap the ring to lock")))))));
        Draw.text(dc, f.cx, f.y(345), msg, f.d(13), state >= 3 ? Theme.ALARM : Theme.MIST, 0);
        Draw.anchorButton(dc, f, f.cx, f.cy, Model.anchorOn);
        var hf = host != null && lifting ? (host as Host).holdFrac() : 0.0;
        if (hf > 0.0) { Draw.holdRing(dc, f, hf); }
    }
}

// Dial for the distance to the anchor or the alarm radius: drum with neighbours, swipe / Up/Down / tap halves, DONE
class DialPage extends Page {
    var owner as SetAnchorPage;
    var isRadius as Boolean;
    var v as Number;
    var dragY as Number? = null;
    var acc as Number = 0;

    function initialize(o as SetAnchorPage, r as Boolean) {
        Page.initialize();
        owner = o; isRadius = r;
        v = r ? o.radius : o.ahead;
    }
    function wantsDrag() as Boolean { return true; }
    function showIcon() as Boolean { return false; }
    function maxV() as Number { return isRadius ? 500 : 300; }
    function minV() as Number { return isRadius ? 20 : 0; }
    function step(dir as Number) as Void { v = dir > 0 ? Nav.drumUp(v, maxV()) : Nav.drumDown(v, minV()); }
    function upDown(dir as Number) as Boolean { step(-dir); return true; }
    function done() as Void {
        if (isRadius) { owner.setRadius(v); } else { owner.setAhead(v); }
        Ui.pop();
    }
    function select() as Boolean { done(); return true; }
    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        if (Draw.hit(f, x, y, 135, 290, 120, 42)) { done(); return true; }
        if (y < f.y(188)) { step(1); } else if (y < f.y(285)) { step(-1); }
        return true;
    }
    function dragStart(f as Draw.Frame, x as Number, y as Number) as Void { dragY = y; acc = 0; }
    function drag(f as Draw.Frame, x as Number, y as Number) as Void {
        if (dragY == null) { return; }
        acc += (dragY as Number) - y; dragY = y;
        var stp = f.d(20);
        while (acc.abs() >= stp) { var s = acc > 0 ? 1 : -1; step(s); acc -= stp * s; }
    }
    function dragEnd(f as Draw.Frame, x as Number, y as Number) as Void { dragY = null; }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        Draw.text(dc, f.cx, f.y(70), isRadius ? "Alarm radius" : "Distance to anchor", f.d(17), Theme.MIST, 0);
        var up = Nav.drumUp(v, maxV()), dn = Nav.drumDown(v, minV());
        if (up != v) { Draw.text(dc, f.cx, f.y(124), up.format("%d"), f.d(24), Theme.FADE, 0); }
        Draw.box(dc, f.x(110), f.y(148), f.d(170), f.d(80), f.d(14), Theme.NAVY, Theme.ORANGE, f.d(3));
        Draw.num(dc, f.cx, f.y(188), v.format("%d"), f.d(48), Theme.FOAM, false);
        Draw.text(dc, f.x(262), f.y(196), "ft", f.d(15), Theme.MIST, 0);
        if (dn != v) { Draw.text(dc, f.cx, f.y(262), dn.format("%d"), f.d(24), Theme.FADE, 0); }
        Draw.pill(dc, f, 135, 290, 120, 42, "DONE", Theme.ORANGE, Theme.ON_ORANGE);
    }
}
