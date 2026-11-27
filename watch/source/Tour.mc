import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Demo tour for simulator screenshots: steps through every page and state every STEP_MS, with sample data, so
// scripts/sim.sh --tour can capture each one (as the renders in docs/renders.html). Only compiled by test.jungle
// (annotation :tour); normal and release builds get the stubs below.
(:tour)
module Tour {
    const STEP_MS = 8000;           // long steps: the simulator log can lag a few seconds behind the screen
    const NAMES = ["gauge", "gauge-outside", "gauge-slow", "mark", "mark-approach", "anchor", "anchor-stale",
                   "anchor-lost", "anchor-alarm", "set-anchor", "set-anchor-off", "dial-distance", "dial-radius",
                   "sight", "sight-angle", "sight-review", "mob", "mob-boat-up", "mob-watch-up", "mob-north-up",
                   "link-connected", "link-wait", "link-offline", "link-timedout", "paused"];
    var step as Number = -1;
    var held as Boolean = false;        // the first step runs two periods (the simulator log is slowest at start)
    var timer as Timer.Timer? = null;
    var pushed as Number = 0;           // screens pushed by the current step
    var host as Host? = null;

    function on() as Boolean { return true; }

    function start(h as Host) as Void {
        host = h;
        timer = new Timer.Timer();
        (timer as Timer.Timer).start(new Lang.Method(Tour, :next), STEP_MS, true);
        next();
    }

    function next() as Void {
        if (step == 0 && !held) { held = true; return; }
        while (pushed > 0) { WatchUi.popView(WatchUi.SLIDE_IMMEDIATE); pushed--; }
        step = (step + 1) % NAMES.size();
        var st = System.getSystemStats();
        System.println("TOUR " + step.format("%02d") + " " + NAMES[step] + " mem " + st.usedMemory + "/" + st.totalMemory);
        Model.demo();
        Model.outside = false; Model.alarm = 0; Model.sca = false; Model.mobOn = false;
        Sensors.fakeHeading = null; Model.hasCompass = false;
        Link.trusted = true; Link.pairState = 0; Net.fails = 0; Net.gaveUp = false; Idle.asleep = false; Model.fixState = 0;
        Model.boatToken = [1, 2, 3, 4]; Model.pairCode = 0;
        var h = host as Host;
        var n = NAMES[step] as String;
        if (n.equals("gauge")) { h.goTo(0); }
        else if (n.equals("gauge-outside")) { Model.outside = true; h.goTo(0); }
        else if (n.equals("gauge-slow")) { Model.sogKn = null; Model.cogDeg = null; Model.lastCog = 47.0; h.goTo(0); }
        else if (n.equals("mark")) { h.goTo(1); }
        else if (n.equals("mark-approach")) { Model.pos = [43.1297, -86.2404]; Model.sogKn = 6.0; Model.cogDeg = 45.0; h.goTo(1); }
        else if (n.equals("anchor")) { h.goTo(2); }
        else if (n.equals("anchor-stale")) { Model.fixState = 1; h.goTo(2); }
        else if (n.equals("anchor-lost")) { Model.fixState = 2; h.goTo(2); }
        else if (n.equals("anchor-alarm")) { Model.alarm = 1; for (var i = 0; i < 20; i++) { Model.addFix(-60 - i * 4, -90 - i * 3); } h.goTo(2); }
        else if (n.equals("set-anchor")) { Sensors.fakeHeading = 30.0; Model.hasCompass = true; h.goTo(2); push(new SetAnchorPage()); }
        else if (n.equals("set-anchor-off")) { Model.anchorOn = false; h.goTo(2); push(new SetAnchorPage()); }
        else if (n.equals("dial-distance") || n.equals("dial-radius")) {
            Model.anchorOn = false; h.goTo(2); var sp = new SetAnchorPage(); sp.ahead = 120; sp.radius = 170;
            push(sp); push(new DialPage(sp, n.equals("dial-radius")));
        }
        else if (n.equals("sight")) { h.goTo(4); }
        else if (n.equals("sight-angle")) { h.goTo(4); if (TourSight.angle()) { pushed++; } }
        else if (n.equals("sight-review")) { h.goTo(4); if (TourSight.review()) { pushed++; } }
        else if (n.find("mob") == 0) {
            Model.mobOn = true; Model.mobTime = Model.now() - 222; Model.mobPos = [43.1010, -86.2560];
            if (n.equals("mob-boat-up")) { Model.sogKn = 3.1; Model.cogDeg = 34.0; }
            else if (n.equals("mob-watch-up")) { Model.sogKn = 0.3; Sensors.fakeHeading = 120.0; Model.hasCompass = true; }
            else if (n.equals("mob-north-up")) { Model.sogKn = 0.3; }
            h.goTo(5);
        }
        else if (n.find("link") == 0) {
            // link screen states (no phone in the simulator: states are faked)
            if (!n.equals("link-connected")) { Link.trusted = false; Model.linked = false; }
            if (n.equals("link-wait")) { Model.boatToken = null; Model.pairCode = 4821; Link.pairState = Link.PAIR_WAIT; }
            else if (n.equals("link-offline")) { Link.pairState = -1; Net.fails = 3; }
            else if (n.equals("link-timedout")) { Model.boatToken = null; Model.pairCode = 4821; Link.pairState = Link.PAIR_WAIT; Net.gaveUp = true; }
            h.goTo(0); push(new LinkPage());
            if (n.equals("link-offline")) { Net.fails = 3; }      // opening the link screen retries at once; show the offline state
        }
        else { Model.anchorOn = false; Model.pins = []; h.goTo(0); Idle.asleep = true; }
        WatchUi.requestUpdate();
    }

    function push(p as Page) as Void { Ui.push(p); pushed++; }
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
