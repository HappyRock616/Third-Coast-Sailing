import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;

// Page 5: MOB. Last in the loop, so one step "previous" from the Gauge. Ready: a big red button, one tap marks.
// Active: time in the water, true bearing and distance to the pin, hold 5 s to reset. The background and the red
// arrow follow the heading source (DESIGN 3.5): boat up (silhouette), watch up (watch outline, watch compass),
// or north up.
class MobPage extends Page {
    var mode as Number = Nav.MODE_NORTH;

    function initialize() { Page.initialize(); }
    function id() as Number { return 5; }
    function gps() as Boolean { return true; }
    function tickMs() as Number { return Model.mobOn ? (Model.hasCompass ? 250 : 1000) : 0; }

    function select() as Boolean { if (!Model.mobOn) { mark(); } return true; }
    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        if (!Model.mobOn) { mark(); }
        return true;
    }
    function holdAt(f as Draw.Frame, x as Number, y as Number) as Number {
        return Model.mobOn && Draw.hit(f, x, y, 80, 268, 230, 58) ? 5000 : 0;
    }
    function keyHoldMs() as Number { return Model.mobOn ? 5000 : 0; }   // buttons: hold Start 5 s to reset
    function holdDone() as Void {
        Link.sendReliable(Link.MOB_RESET, [Model.mobTime]);
        Model.mobOn = false; Model.mobPos = null; Model.saveMob();
        if (host != null) { (host as Host).restartTimer(); }
    }

    // The watch's own position goes with the MOB only if its GPS fix is fresh (the watch GPS runs only on some
    // pages, so Model.pos can be old). The phone uses its own fix when it has a fresh one.
    function mark() as Void {
        var t = Model.now(), age = t - Model.posTime;
        var p = (Model.pos != null && age <= 10) ? Model.pos : null;
        Model.mobOn = true; Model.mobTime = t; Model.mobPos = p;
        Model.saveMob();
        Haptics.play(Haptics.MOB);
        Link.sendReliable(Link.MOB_MARK, [t, p == null ? null : ((p as Array<Float>)[0] * 1e6).toNumber(),
                                          p == null ? null : ((p as Array<Float>)[1] * 1e6).toNumber(), p == null ? null : age]);
        Sensors.setGps(true);
        if (host != null) { (host as Host).restartTimer(); }
    }

    function tick() as Void { Sensors.readCompass(); }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        if (!Model.mobOn) {
            dc.setColor(Theme.ALARM, Graphics.COLOR_TRANSPARENT); dc.fillCircle(f.cx, f.cy, f.d(170));
            Draw.circle(dc, f, 170, 12, Theme.ALARM_DK);
            Draw.buoy(dc, f, f.cx, f.y(112), 30);
            Draw.text(dc, f.cx, f.y(232), "MOB", f.d(80), Theme.MONO ? Theme.INK : Theme.FOAM, 0);
            Draw.text(dc, f.cx, f.y(286), System.getDeviceSettings().isTouchScreen ? "tap to mark" : "press START", f.d(20), Theme.MONO ? Theme.INK : Theme.FOAM, 0);
            return;
        }
        var m = Nav.headingMode(Model.sogKn, Model.cogDeg, null, Model.compass, mode);
        mode = m[0] as Number;
        var up = m[1].toFloat();
        var pos = Model.pos, pin = Model.mobPos;
        if (pin == null && pos != null && Model.now() - Model.posTime <= 10) {
            pin = pos; Model.mobPos = pos; Model.saveMob();          // no fix at the mark: use the first fresh one
        }
        var brg = null, dist = null;
        if (pos != null && pin != null) { brg = Nav.bearingDeg(pos as Array<Float>, pin as Array<Float>); dist = Nav.distNm(pos as Array<Float>, pin as Array<Float>); }

        if (mode == Nav.MODE_WATCH) {
            // watch outline: case, bezel, lugs and buttons
            dc.setColor(Theme.MIST, Graphics.COLOR_TRANSPARENT);
            Draw.setPen(dc, f.d(4));
            dc.drawCircle(f.cx, f.cy, f.d(142));
            Draw.setPen(dc, f.d(2));
            dc.drawCircle(f.cx, f.cy, f.d(124));
            Draw.outline(dc, [[f.x(150), f.y(62)], [f.x(163), f.y(26)], [f.x(227), f.y(26)], [f.x(240), f.y(62)]], f.d(3), Theme.MIST);
            Draw.outline(dc, [[f.x(150), f.y(328)], [f.x(163), f.y(364)], [f.x(227), f.y(364)], [f.x(240), f.y(328)]], f.d(3), Theme.MIST);
        } else {
            Draw.hull(dc, f, f.cx, f.y(205), 300, 118, 0, Theme.NAVY, mode == Nav.MODE_BOAT ? Theme.MIST : Theme.FADE);
            if (mode == Nav.MODE_NORTH) { Draw.text(dc, f.cx, f.y(40), "N", f.d(20), Theme.ORANGE, 0); }
        }
        Draw.circle(dc, f, 186, 8, Theme.ALARM_FILL);
        if (mode != Nav.MODE_NORTH) { Draw.buoy(dc, f, f.cx, f.y(72), 14); }
        // one short line at the top, only when it matters: the phone hasn't got the MOB yet, or "hold flat"
        if (Link.hasPending(Link.MOB_MARK)) { Draw.text(dc, f.cx, f.y(108), "NOT SENT", f.d(16), Theme.ALARM, 0); }
        else if (mode == Nav.MODE_WATCH) { Draw.text(dc, f.cx, f.y(108), "hold flat", f.d(16), Theme.MIST, 0); }

        var el = Model.now() - Model.mobTime;
        var tstr = el >= 3600 ? (el / 3600).format("%d") + ":" + ((el % 3600) / 60).format("%02d") + ":" + (el % 60).format("%02d")
                              : (el / 60).format("%02d") + ":" + (el % 60).format("%02d");
        Draw.num(dc, f.cx, f.y(150), tstr, f.d(64), Theme.FOAM, false);
        if (brg != null) {
            Draw.num(dc, f.cx, f.y(204), Nav.deg3(brg), f.d(40), Theme.ALARM, true);
            Draw.numUnit(dc, f, f.cx, f.y(244), (dist as Float).format("%.2f"), f.d(34), "nm", f.d(18), Theme.FOAM);
        } else {
            Draw.text(dc, f.cx, f.y(224), "waiting for GPS", f.d(18), Theme.MIST, 0);
        }
        // big RESET button: hold 5 s (touch) or hold Start
        var frac = host == null ? 0.0 : (host as Host).holdFrac();
        var bx = f.x(80), by = f.y(270), bw = f.d(230), bh = f.d(56);
        Draw.box(dc, bx, by, bw, bh, bh / 2, Theme.ALARM_BG, Theme.ALARM, f.d(3));
        if (frac > 0.0) { var fw = (bw * frac).toNumber(); Draw.box(dc, bx, by, fw < bh ? bh : fw, bh, bh / 2, Theme.ALARM_FILL, Theme.ALARM, 0); }
        Draw.text(dc, f.cx, f.y(298), "RESET", f.d(26), Theme.MONO && frac > 0.5 ? Theme.INK : Theme.FOAM, 0);
        arrowRel = brg == null ? null : Nav.norm360((brg as Float) - up);
    }

    // The red arrow to the person is drawn last, over the phone icon, so it can never be hidden
    var arrowRel as Float? = null;
    function drawTop(dc as Dc, f as Draw.Frame) as Void {
        if (!Model.mobOn || arrowRel == null) { return; }
        var rel = arrowRel as Float;
        var c = f.pt(168, rel);
        var tri = Draw.poly(dc, f, c[0], c[1], [[0, -26], [21, 10], [-21, 10]], rel, 1.0, Theme.ALARM);
        Draw.outline(dc, tri, f.d(3), Theme.FOAM);
    }
}
