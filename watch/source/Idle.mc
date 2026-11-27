import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// App version, shown on the link screen and sent in HELLO as major*10000 + minor*100 + patch.
module Version {
    const NAME = "0.1.1";
    const CODE = 101;
}

// A forgotten app must not drain the watch (BATTERY_REVIEW_2026-11-24, B1). Watch apps opened from the apps menu
// never time out on their own, so: after 10 minutes with no touch or button and nothing to watch (no anchor watch,
// MOB or route), the app pauses (GPS off, page timer off, link closed with BYE, no polls) and shows "Paused";
// after 30 minutes it exits. The next touch or button only wakes it.
module Idle {
    const PAUSE_MS = 600000;
    const EXIT_MS = 1800000;
    var last as Number = 0;
    var asleep as Boolean = false;
    var wokeAt as Number = -10000;      // one button press can arrive as several events (key, then select)

    function start() as Void { last = System.getTimer(); asleep = false; }

    // Anything the user should keep seeing or being alerted about
    function watching() as Boolean {
        return Model.anchorOn || Model.mobOn || (Model.pins.size() >= 2 && Model.next < Model.pins.size());
    }

    // Any input. True if it only woke the app, or arrives within 0.7 s of waking (the caller then ignores it)
    function touch() as Boolean {
        last = System.getTimer();
        Net.activity();                 // someone is looking: Garmin Connect polls go back to 15 s
        if (!asleep) { return last - wokeAt < 700; }
        wake();
        return true;
    }

    // Every second, from the app heartbeat
    function check() as Void {
        var t = System.getTimer();
        if (watching()) {
            last = t;
            if (asleep) { wake(); }
            return;
        }
        var idle = t - last;
        if (idle >= EXIT_MS) { Log.p("idle 30 min: exit"); System.exit(); }
        if (idle >= PAUSE_MS && !asleep) { sleep(); }
    }

    function sleep() as Void {
        Log.p("idle 10 min: paused");
        asleep = true;
        var h = Ui.top;
        if (h != null) { (h as Host).leave(); }
        Sensors.setGps(false);
        Link.stop();                    // BYE: the phone stops pushing
        WatchUi.requestUpdate();
    }

    function wake() as Void {
        Log.p("wake");
        asleep = false;
        last = System.getTimer();
        wokeAt = last;
        Net.retryNow();
        Link.hello();                   // the phone closed the session on BYE: prove again
        var h = Ui.top;
        if (h != null) { (h as Host).enter(); }
        WatchUi.requestUpdate();
    }

    function draw(dc as Graphics.Dc, f as Draw.Frame) as Void {
        Draw.text(dc, f.cx, f.y(175), "Paused", f.d(30), Theme.FOAM, f.d(300));
        Draw.text(dc, f.cx, f.y(215), "tap or press to resume", f.d(15), Theme.MIST, f.d(280));
    }
}
