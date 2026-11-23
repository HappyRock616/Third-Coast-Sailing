import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;

// Phone link status (PROTOCOL.md section 9): a small phone icon in the same place on every page, coloured by the link,
// and the link screen it opens, where all status messages live.
module LinkUi {
    const NOSUPPORT = 0; const LINKED = 1; const WAIT = 2; const DENIED = 3; const CONNECTING = 4; const OFFLINE = 5;
    // design units: 2 o'clock, straddling the compass ring's inner edge (r 149 at 57 deg), radius 30: half on the
    // ring, half inside (2026-11-22). Clear of the 6 o'clock label, the Next mark SOG panel and the MOB time.
    const ICON_X = 320;
    const ICON_Y = 114;
    const ICON_R = 30;

    function status() as Number {
        if (!Net.supported) { return NOSUPPORT; }
        if (Link.trusted || Model.linked) { return LINKED; }
        if (Link.pairState == Link.PAIR_DENIED) { return DENIED; }
        if (Link.pairState == Link.PAIR_WAIT) { return WAIT; }
        if (Net.fails >= 2) { return OFFLINE; }
        return CONNECTING;
    }

    // green connected, yellow slow or a recent failure, orange pairing / connecting, red offline
    function color() as Number {
        var s = status();
        if (s == LINKED) { return (Net.fails > 0 || Net.latency > 1500) ? Theme.CAUTION : Theme.GREEN; }
        if (s == WAIT || s == CONNECTING) { return Theme.ORANGE; }
        return Theme.ALARM;
    }

    // A phone: body, screen, speaker slot, home button, in the status colour inside a ring of the same colour
    function drawIcon(dc as Dc, f as Draw.Frame) as Void {
        var x = f.x(ICON_X), y = f.y(ICON_Y), r = f.d(ICON_R), c = color();
        dc.setColor(Theme.BG, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(x, y, r + f.d(3));
        dc.setColor(c, Graphics.COLOR_TRANSPARENT);
        Draw.setPen(dc, f.d(3));
        dc.drawCircle(x, y, r);
        var w = f.d(24), h = f.d(40), top = y - h / 2, left = x - w / 2;
        dc.fillRoundedRectangle(left, top, w, h, f.d(5));                          // body
        dc.setColor(Theme.NAVY, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(left + f.d(3), top + f.d(7), w - f.d(6), h - f.d(15));   // screen
        dc.setColor(Theme.BG, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x - f.d(5), top + f.d(3), f.d(10), f.d(2), f.d(1)); // speaker slot
        dc.fillCircle(x, top + h - f.d(4), f.d(2));                                 // home button
        dc.setColor(c, Graphics.COLOR_TRANSPARENT);                                 // signal bars on the screen
        var bx = left + f.d(6), by = top + h - f.d(11);
        for (var i = 0; i < 3; i++) { var bh = f.d(4 + i * 4); dc.fillRectangle(bx + i * f.d(4), by - bh, f.d(2), bh); }
    }
    function iconHit(f as Draw.Frame, x as Number, y as Number) as Boolean {
        var dx = x - f.x(ICON_X), dy = y - f.y(ICON_Y), rr = f.d(ICON_R + 22);
        return dx * dx + dy * dy <= rr * rr;
    }
}

// Link screen: opened by tapping the phone icon on any page, or Menu on the Gauge.
// Shows what the link is doing, the pairing code, or "Connected" with "Forget phone".
class LinkPage extends Page {
    function initialize() { Page.initialize(); }
    function tickMs() as Number { return 1000; }
    function showIcon() as Boolean { return false; }

    function line(dc as Dc, f as Draw.Frame, y as Numeric, s as String, px as Number, c as Number) as Void {
        Draw.text(dc, f.cx, f.y(y), s, f.d(px), c, f.d(290));
    }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        var s = LinkUi.status(), c = LinkUi.color();
        // big phone at the top, in the status colour
        dc.setColor(c, Graphics.COLOR_TRANSPARENT);
        Draw.setPen(dc, f.d(4));
        dc.drawRoundedRectangle(f.x(177), f.y(40), f.d(36), f.d(58), f.d(7));
        dc.fillRectangle(f.x(191), f.y(88), f.d(8), f.d(3));
        if (s == LinkUi.NOSUPPORT) {
            line(dc, f, 150, "This watch can't", 20, Theme.FOAM);
            line(dc, f, 178, "link to TCS", 20, Theme.FOAM);
        } else if (s == LinkUi.LINKED) {
            line(dc, f, 140, "Connected to TCS", 22, Theme.FOAM);
            line(dc, f, 170, Net.latency > 0 ? "reply " + (Net.latency / 100 / 10.0).format("%.1f") + " s" : "", 15, Theme.MIST);
            if (host != null && (host as Host).holdLen > 0) {
                Draw.holdBar(dc, f, 105, 262, 180, "Forget phone", (host as Host).holdFrac(), Theme.FOAM);
            } else {
                Draw.box(dc, f.x(105), f.y(262), f.d(180), f.d(38), f.d(19), Theme.BG, Theme.FOAM, f.d(2));
                line(dc, f, 281, "Forget phone", 15, Theme.FOAM);
                if (!f.small()) { line(dc, f, 318, "hold", 13, Theme.MIST); }
            }
        } else if (s == LinkUi.WAIT) {
            line(dc, f, 128, "Waiting for Allow", 18, Theme.FOAM);
            Draw.num(dc, f.cx, f.y(190), Model.pairCode.format("%04d"), f.d(66), Theme.ORANGE, false);
            line(dc, f, 250, "Tap Allow in TCS", 16, Theme.POLAR);
            line(dc, f, 274, "on the phone", 16, Theme.POLAR);
        } else if (s == LinkUi.DENIED) {
            line(dc, f, 140, "Not allowed", 22, Theme.FOAM);
            line(dc, f, 170, "on the phone", 16, Theme.MIST);
            Draw.pill(dc, f, 125, 240, 140, 40, "Try again", Theme.ORANGE, Theme.ON_ORANGE);
        } else if (s == LinkUi.OFFLINE) {
            line(dc, f, 136, "Can't reach TCS", 22, Theme.FOAM);
            line(dc, f, 172, "Open TCS on the phone", 15, Theme.POLAR);
            line(dc, f, 196, "and check Gadgetbridge", 15, Theme.POLAR);
            line(dc, f, 220, "is connected", 15, Theme.POLAR);
            if (!f.small()) { line(dc, f, 252, "Setup: TCS > Watch", 13, Theme.MIST); }
        } else {
            line(dc, f, 150, "Connecting to TCS...", 20, Theme.FOAM);
        }
        if (s != LinkUi.WAIT) { Log.draw(dc, f); }       // link test build only
    }

    function select() as Boolean {
        if (LinkUi.status() == LinkUi.DENIED) { Link.pairState = -1; Link.hello(); return true; }
        return false;
    }
    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean {
        if (LinkUi.status() == LinkUi.DENIED && Draw.hit(f, x, y, 125, 240, 140, 40)) { return select(); }
        return true;
    }

    // Forget: touch hold on the button, or hold Start, 1.5 s
    function holdAt(f as Draw.Frame, x as Number, y as Number) as Number {
        return LinkUi.status() == LinkUi.LINKED && Draw.hit(f, x, y, 105, 252, 180, 60) ? 1500 : 0;
    }
    function keyHoldMs() as Number { return LinkUi.status() == LinkUi.LINKED ? 1500 : 0; }
    function holdDone() as Void {
        Haptics.play(Haptics.HOLD);
        Model.setToken(null);
        Link.trusted = false; Link.pairState = -1;
        Link.hello();                   // starts a new pairing (new code)
    }
}
