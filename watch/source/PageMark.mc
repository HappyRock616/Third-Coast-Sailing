import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;

// Page 2: heading, distance and time to the next route point. Ring = the whole route, one segment per leg, the
// current leg draining; VMC and SOG in the side panels; haptics at quarters, approach and arrival.
class MarkPage extends Page {
    var legs as Haptics.LegWatch;
    var finished as Boolean = false;

    function initialize() { Page.initialize(); legs = new Haptics.LegWatch(); }
    function id() as Number { return 1; }
    function gps() as Boolean { return true; }
    function tickMs() as Number { return 1000; }
    // Redraw when the boat, link or ETA minute changed; every tick while the ring blinks (under 30 s to the mark)
    var blinking as Boolean = false;
    function sig() as String? { return blinking ? null : Model.sigBase() + "/" + (Model.now() / 60); }
    function holdAt(f as Draw.Frame, x as Number, y as Number) as Number { return hasRoute() ? 1500 : 0; }   // deliberate: skips a mark
    function holdMs() as Number { return hasRoute() ? 1 : 0; }
    function holdDone() as Void {
        if (!hasRoute()) { return; }
        Link.sendReliable(Link.NEXT_MARK, [Model.next]);
        Haptics.play(Haptics.HOLD);
    }

    // next = the pin being sailed to; 0 = heading for the start pin (no leg yet)
    function hasRoute() as Boolean { return Model.pins.size() >= 2 && Model.next >= 0 && Model.next < Model.pins.size(); }

    // Haptics run every second whatever is on screen while this page is open
    function tick() as Void {
        var pos = Model.pos;
        if (Model.pins.size() >= 2 && Model.next >= Model.pins.size()) {
            if (!finished) { finished = true; Haptics.play(Haptics.FINISH); }
            return;
        }
        finished = false;
        if (!hasRoute() || pos == null || Model.next == 0) { return; }     // no leg haptics on the way to the start
        var n = Model.next;
        var len = Nav.distNm(Model.pins[n - 1], Model.pins[n]);
        var ev = legs.update(n, len, Nav.distNm(pos as Array<Float>, Model.pins[n]));
        if (ev >= 0) { Haptics.play(ev); }
    }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        var pos = Model.pos;
        if (!hasRoute() || pos == null) {
            Draw.circle(dc, f, 180, 16, Theme.NAVY_L);
            var msg = Model.pins.size() >= 2 && Model.next >= Model.pins.size() ? "Route finished" : (pos == null ? "Waiting for GPS" : "No route");
            Draw.text(dc, f.cx, f.y(180), msg, f.d(26), Theme.FOAM, f.d(300));
            Draw.text(dc, f.cx, f.y(220), pos == null ? "" : "lay one in on the phone", f.d(15), Theme.MIST, f.d(280));
            return;
        }
        var p = pos as Array<Float>, n = Model.next, cnt = Model.pins.size() - 1;
        var togo = Nav.distNm(p, Model.pins[n]);
        var brgT = Nav.bearingDeg(p, Model.pins[n]);
        var sog = Model.sogKn == null ? 0.0 : Model.sogKn as Float;
        var cog = Model.cogDeg == null ? brgT : Model.cogDeg as Float;
        var v = Nav.vmc(sog, cog, brgT);

        // route ring (Nav reference: routeRing)
        var total = 0.0, lens = [];
        for (var i = 1; i <= cnt; i++) { var l = Nav.distNm(Model.pins[i - 1], Model.pins[i]); lens.add(l); total += l; }
        var gap = 2.5, k = total > 0 ? (360.0 - cnt * gap) / total : 0.0, a = gap / 2, boatA = 0.0;
        var mins = Nav.minutesToGo(togo, v);
        blinking = mins != null && (mins as Float) * 60 < 30;               // under 30 s to the mark: slow blink
        var blink = blinking && (System.getTimer() / 1000) % 2 == 0;
        for (var i = 0; i < cnt; i++) {
            var a0 = a, a1 = a + (lens[i] as Float) * k, leg = i + 1;
            if (leg < n) { Draw.arc(dc, f, 180, a0, a1, 16, Theme.NAVY_L); }
            else if (leg > n) { Draw.arc(dc, f, 180, a0, a1, 16, Theme.BLUE); }
            else {
                var done = (lens[i] as Float) - togo;
                if (done < 0) { done = 0.0; }
                var m = a0 + done * k;
                boatA = m;
                Draw.arc(dc, f, 180, a0, m, 16, Theme.NAVY_L);
                if (!blink) { Draw.arc(dc, f, 180, m, a1, 16, Theme.POLAR); }
            }
            var mp = f.pt(180, a1 + gap / 2);
            dc.setColor(Theme.FOAM, Graphics.COLOR_TRANSPARENT); dc.fillCircle(mp[0], mp[1], f.d(4));
            a = a1 + gap;
        }
        var sp = f.pt(180, 0);
        dc.setColor(Theme.START, Graphics.COLOR_TRANSPARENT); dc.fillCircle(sp[0], sp[1], f.d(5));
        var bp = f.pt(180, boatA);
        dc.setColor(Theme.BG, Graphics.COLOR_TRANSPARENT); dc.fillCircle(bp[0], bp[1], f.d(12));
        dc.setColor(Theme.ORANGE, Graphics.COLOR_TRANSPARENT); dc.fillCircle(bp[0], bp[1], f.d(9));
        // steer bug: bearing to the mark relative to the course
        var rel = Nav.angDiff(cog, brgT), bug = f.pt(166, rel);
        Draw.poly(dc, f, bug[0], bug[1], [[0, -9], [9, 6], [-9, 6]], rel, 1.0, Theme.ORANGE);

        Draw.sidePanels(dc, f, "VMC", v.format("%.1f"), "SOG", sog.format("%.1f"), "kt");
        // one tall orange box, four fields for the next mark: which mark, bearing, distance, time to go + ETA
        Draw.box(dc, f.x(122), f.y(58), f.d(146), f.d(264), f.d(14), Theme.NAVY, Theme.ORANGE, f.d(3));
        dc.setColor(Theme.FADE, Graphics.COLOR_TRANSPARENT);
        Draw.setPen(dc, f.d(2));
        var dv = [98, 160, 240];
        for (var i = 0; i < dv.size(); i++) { dc.drawLine(f.x(132), f.y(dv[i]), f.x(258), f.y(dv[i])); }
        Draw.text(dc, f.cx, f.y(78), n == 0 ? "START" : "MARK " + n.format("%d") + "/" + cnt.format("%d"), f.d(17), Theme.MIST, f.d(132));
        Draw.num(dc, f.cx, f.y(129), Nav.deg3(Nav.toMagnetic(brgT, Model.magVar)), f.d(40), Theme.FOAM, true);
        Draw.num(dc, f.cx, f.y(192), togo < 10 ? togo.format("%.2f") : togo.format("%.1f"), f.d(48), Theme.FOAM, false);
        Draw.text(dc, f.cx, f.y(225), "nm", f.d(14), Theme.MIST, 0);
        var tg = "--:--";
        // time to go: m:ss under an hour, else h:mm:ss (number fonts have no letters)
        if (mins != null) {
            var sec = Math.round((mins as Float) * 60).toNumber();
            tg = sec < 3600 ? (sec / 60).format("%d") + ":" + (sec % 60).format("%02d") : (sec / 3600).format("%d") + ":" + (sec % 3600 / 60).format("%02d") + ":" + (sec % 60).format("%02d");
        }
        Draw.num(dc, f.cx, f.y(266), tg, f.d(28), Theme.FOAM, false);
        if (mins != null) {
            var eta = Gregorian.info(Time.now().add(new Time.Duration(((mins as Float) * 60).toNumber())), Time.FORMAT_SHORT);
            Draw.text(dc, f.cx, f.y(298), "ETA " + eta.hour.format("%02d") + ":" + eta.min.format("%02d"), f.d(15), Theme.MIST, f.d(136));
        }
        Draw.holdRing(dc, f, hostOf() == null ? 0.0 : (hostOf() as Host).holdFrac());
    }
}
