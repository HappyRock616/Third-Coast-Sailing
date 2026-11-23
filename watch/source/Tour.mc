import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Demo tour for simulator screenshots: steps through every page and state every STEP_MS, with sample data, so
// scripts/sim.sh --tour can capture each one (as the renders in docs/renders.html). Only compiled by test.jungle
// (annotation :tour); normal and release builds get the stubs below.
(:tour)
module Tour {
    const STEP_MS = 4000;
    const NAMES = ["gauge", "gauge-outside", "mark", "mark-approach", "anchor", "anchor-alarm", "set-anchor",
                   "set-anchor-drop", "conditions", "conditions-sca", "sight", "sight-angle", "sight-review",
                   "mob", "mob-boat-up", "mob-watch-up", "mob-north-up", "gauge-nolink", "link-pick", "link-code", "link-connected"];
    var step as Number = -1;
    var timer as Timer.Timer? = null;
    var pushed as Boolean = false;
    var host as Host? = null;

    function on() as Boolean { return true; }

    function start(h as Host) as Void {
        host = h;
        timer = new Timer.Timer();
        (timer as Timer.Timer).start(new Lang.Method(Tour, :next), STEP_MS, true);
        next();
    }

    function next() as Void {
        if (pushed) { WatchUi.popView(WatchUi.SLIDE_IMMEDIATE); pushed = false; }
        step = (step + 1) % NAMES.size();
        System.println("TOUR " + step.format("%02d") + " " + NAMES[step]);
        Model.demo();
        Model.outside = false; Model.alarm = 0; Model.sca = false; Model.mobOn = false;
        Sensors.fakeHeading = null; Model.hasCompass = false;
        Link.trusted = true; Link.pairState = 0; Ble.supported = true; Ble.state = Ble.OFF; Ble.boats = [];
        Model.boatId = 0x7F3A1C02; Model.boatName = "Third Wind"; Model.boatToken = [1, 2, 3, 4]; Model.pairCode = 0;
        var h = host as Host;
        var s = step;
        if (s == 0) { h.goTo(0); }
        else if (s == 1) { Model.outside = true; h.goTo(0); }
        else if (s == 2) { h.goTo(1); }
        else if (s == 3) { Model.pos = [43.1480, -86.2685]; h.goTo(1); }
        else if (s == 4) { h.goTo(2); }
        else if (s == 5) { Model.alarm = 1; for (var i = 0; i < 20; i++) { Model.addFix(-60 - i * 4, -90 - i * 3); } h.goTo(2); }
        else if (s == 6) { Sensors.fakeHeading = 30.0; Model.hasCompass = true; h.goTo(2); push(new SetAnchorPage()); }
        else if (s == 7) { Model.anchorOn = false; h.goTo(2); push(new SetAnchorPage()); }
        else if (s == 8) { h.goTo(3); }
        else if (s == 9) { Model.tws = 24.0; Model.gust = 31.0; Model.waveHt = 5.2; Model.sca = true; h.goTo(3); }
        else if (s == 10) { h.goTo(4); }
        else if (s == 11) { h.goTo(4); pushed = TourSight.angle(); }
        else if (s == 12) { h.goTo(4); pushed = TourSight.review(); }
        else if (s == 13) { h.goTo(5); }
        else if (s >= 17) {
            // direct Bluetooth link screens (no phone in the simulator: states are faked)
            Model.linked = s == 20; Link.trusted = s == 20;
            if (s == 17 || s == 18) {
                Model.boatId = null; Model.boatName = null; Model.boatToken = null; Ble.state = Ble.SCANNING;
                Model.tws = null; Model.twd = null; Model.depthFt = null; Model.sogKn = null;
                var t = System.getTimer();
                Ble.boats = [[0x7F3A1C02, "Third Wind", -58, null, t], [0x12347F3A, "TCS 7F3A", -86, null, t]];
            } else if (s == 19) {
                Ble.state = Ble.READY; Model.boatToken = null; Model.pairCode = 4821; Link.pairState = 2;
            } else { Ble.state = Ble.READY; }
            h.goTo(0);
            if (s >= 18) { push(new LinkPage()); }
        }
        else {
            Model.mobOn = true; Model.mobTime = Model.now() - 222; Model.mobPos = [43.1010, -86.2560];
            if (s == 14) { Model.sogKn = 3.1; Model.cogDeg = 34.0; }
            else if (s == 15) { Model.sogKn = 0.3; Sensors.fakeHeading = 120.0; Model.hasCompass = true; }
            else { Model.sogKn = 0.3; }
            h.goTo(5);
        }
        WatchUi.requestUpdate();
    }

    function push(p as Page) as Void { Ui.push(p); pushed = true; }
}

// Lite / Mono devices have no Sight pages: the tour skips them there
(:lite)
module TourSight {
    function angle() as Boolean { return false; }
    function review() as Boolean { return false; }
}

(:notour)
module Tour {
    function on() as Boolean { return false; }
    function start(h as Host) as Void {}
}
