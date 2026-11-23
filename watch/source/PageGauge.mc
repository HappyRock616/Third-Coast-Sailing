import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;

// Page 1: the TCS wind gauge (Gauge.kt) on the watch. Heading-up compass ring, heading box, no-go arcs, polar for
// the forecast wind with the target dot, wind triangle on the ring, boat with SOG and depth boxes.
class GaugePage extends Page {
    function initialize() { Page.initialize(); }
    function id() as Number { return 0; }
    function gps() as Boolean { return true; }
    function tickMs() as Number { return 1000; }
    // Start or Menu opens the link screen (touch watches also tap the phone icon)
    function select() as Boolean { Ui.push(new LinkPage()); return true; }
    function menu() as Boolean { Ui.push(new LinkPage()); return true; }

    // Polar speed at a TWA for the given TWS, from the full table or the Lite curve; null without a polar
    function speedAt(tws as Float, twa as Float) as Float? {
        if (Model.polTws != null && Model.polTwa != null && Model.polSpd != null) {
            return Nav.boatSpeed(Model.polTws as Array<Number>, Model.polTwa as Array<Number>, Model.polSpd as Array<Array<Number>>, tws, twa);
        }
        if (Model.polCurve != null) { return Nav.curveSpeed(Model.polCurve as Array<Number>, twa); }
        return null;
    }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        // course only when fast enough to be reliable (Sensors); otherwise the ring holds the last one, dimmed
        var moving = Model.cogDeg != null;
        var h = moving ? Model.cogDeg as Float : (Model.lastCog == null ? 0.0 : Model.lastCog as Float);
        var out = Model.outside;
        Draw.dim = !moving;
        Draw.compass(dc, f, h, false);
        Draw.dim = false;
        Draw.arc(dc, f, 116, 330.0, 358.5, 8, Theme.RED);
        Draw.arc(dc, f, 116, 1.5, 30.0, 8, Theme.GREEN);

        var tws = Model.tws, twd = Model.twd;
        var w = tws == null ? 10.0 : tws as Float;      // polar shape preview until forecast wind exists
        var rP = 104.0;
        if (speedAt(w, 90.0) != null) {
            var vmax = 0.5;
            for (var a = 0; a <= 180; a += 10) { var v = speedAt(w, a.toFloat()) as Float; if (v > vmax) { vmax = v; } }
            var step = f.small() ? 15 : 8, pts = [], c = out || tws == null ? Theme.MIST : Theme.POLAR;
            for (var a = -180; a <= 180; a += step) {
                pts.add(f.pt(rP * (speedAt(w, a.abs().toFloat()) as Float) / vmax, a));
            }
            Draw.outline(dc, pts, f.d(5), Theme.NAVY);
            Draw.outline(dc, pts, f.d(2.5), c);
            if (!out && tws != null && twd != null) {
                var ws = Nav.norm360((twd as Float) - h);
                var twa = Nav.foldAngle(ws);
                var target = speedAt(tws as Float, twa) as Float;
                // dashed wind line from the centre
                dc.setColor(Theme.BLUE, Graphics.COLOR_TRANSPARENT);
                Draw.setPen(dc, f.d(3));
                for (var r = 8; r < 145; r += 16) {
                    var p0 = f.pt(r, ws), p1 = f.pt(r + 9, ws);
                    dc.drawLine(p0[0], p0[1], p1[0], p1[1]);
                }
                var dot = f.pt(rP * target / vmax, ws);
                dc.setColor(Theme.NAVY, Graphics.COLOR_TRANSPARENT); dc.fillCircle(dot[0], dot[1], f.d(9.5));
                dc.setColor(Theme.ORANGE, Graphics.COLOR_TRANSPARENT); dc.fillCircle(dot[0], dot[1], f.d(7));
                if (!f.small()) {
                    Draw.text(dc, dot[0] + (dot[0] > f.cx ? -f.d(34) : f.d(34)), dot[1] + f.d(24), "target " + target.format("%.1f"), f.d(13), Theme.FOAM, 0);
                }
            }
        }

        // boat, SOG on deck, depth at the stern
        Draw.hull(dc, f, f.cx, f.cy, 112, 44, 0, Theme.ORANGE, Theme.NAVY);
        Draw.box(dc, f.x(129), f.y(181), f.d(132), f.d(54), f.d(9), Theme.NAVY, Theme.FOAM, f.d(2));
        Draw.num(dc, f.x(183), f.y(208), Model.sogKn == null ? "--" : (Model.sogKn as Float).format("%.1f"), f.d(40), Theme.FOAM, false);
        Draw.text(dc, f.x(238), f.y(214), "kt", f.d(16), Theme.MIST, 0);
        var dep = Model.depthFt;
        Draw.box(dc, f.x(163), f.y(251), f.d(64), f.d(26), f.d(6), out ? Theme.FADE : Theme.BLUE, Theme.FOAM, f.d(1.5));
        Draw.text(dc, f.x(195), f.y(264), out || dep == null ? "-- ft" : ((dep as Number) < 6 ? "<6 ft" : dep.format("%d") + " ft"), f.d(15), Theme.FOAM, 0);

        windUp = h;
        if ((out || twd == null || tws == null) && !out && !f.small()) {
            Draw.text(dc, f.cx, f.y(330), "no wind forecast yet", f.d(14), Theme.MIST, 0);
        }
        Draw.headBox(dc, f, moving ? Nav.deg3(h) : "--", "", 22, 96);
    }

    // The wind triangle sits on the ring: drawn over the phone icon so it never hides
    var windUp as Float = 0.0;
    function drawTop(dc as Dc, f as Draw.Frame) as Void {
        var twd = Model.twd, tws = Model.tws;
        if (Model.outside || twd == null || tws == null) { return; }
        var c = Draw.triangle(dc, f, 160, Nav.norm360((twd as Float) - windUp), 1.0, Theme.BLUE, Theme.FOAM);
        Draw.text(dc, c[0], c[1], (tws as Float).format("%.0f"), f.d(16), Theme.FOAM, 0);
    }
}
