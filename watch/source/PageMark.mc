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
        var blink = togo < 3 * Nav.POINT_REACHED_NM && (System.getTimer() / 250) % 2 == 0;
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
        Draw.text(dc, f.cx, f.y(64), n == 0 ? "TO START" : "MARK " + n.format("%d") + " of " + cnt.format("%d"), f.d(17), Theme.MIST, 0);
        Draw.box(dc, f.x(124), f.y(78), f.d(142), f.d(60), f.d(13), Theme.NAVY, Theme.ORANGE, f.d(3));
        Draw.num(dc, f.x(189), f.y(108), Nav.deg3(Nav.toMagnetic(brgT, Model.magVar)), f.d(40), Theme.FOAM, true);
        Draw.text(dc, f.x(250), f.y(116), "M", f.d(16), Theme.MIST, 0);
        Draw.num(dc, f.cx, f.y(196), togo < 10 ? togo.format("%.2f") : togo.format("%.1f"), f.d(56), Theme.FOAM, false);
        Draw.text(dc, f.cx, f.y(234), "nm", f.d(16), Theme.MIST, 0);
        var mins = Nav.minutesToGo(togo, v);
        var tg = "--:--";
        if (mins != null) { var mm = Math.round(mins as Float).toNumber(); tg = (mm / 60).format("%d") + ":" + (mm % 60).format("%02d"); }
        Draw.num(dc, f.x(170), f.y(278), tg, f.d(28), Theme.FOAM, false);
        Draw.text(dc, f.x(250), f.y(280), "to go", f.d(18), Theme.MIST, 0);
        if (mins != null && !f.small()) {
            var eta = Gregorian.info(Time.now().add(new Time.Duration(((mins as Float) * 60).toNumber())), Time.FORMAT_SHORT);
            Draw.text(dc, f.cx, f.y(312), "ETA " + eta.hour.format("%02d") + ":" + eta.min.format("%02d"), f.d(18), Theme.MIST, 0);
        }
        Draw.holdRing(dc, f, host == null ? 0.0 : (host as Host).holdFrac());
    }
}
