import Toybox.Application;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// Settings (resources/settings): editable on the watch through Garmin Connect.
module Prefs {
    function num(key as String, dflt as Number) as Number {
        var v = Application.Properties.getValue(key);
        return v instanceof Number ? v : dflt;
    }
    function holdMs() as Number { var v = num("holdMs", 600); return v < 500 ? 500 : (v > 750 ? 750 : v); }
    function sightDelay() as Number { var v = num("sightDelay", 0); return v < 0 ? 0 : (v > 5 ? 5 : v); }
    function celestialOn() as Boolean { return num("celestial", 0) == 1 || Model.celestial == 1; }
}

class TcsApp extends Application.AppBase {
    var host as Host? = null;
    var timer as Timer.Timer? = null;

    function initialize() { AppBase.initialize(); }

    function onStart(state as Dictionary?) as Void {
        Model.load();
        Model.demo();                 // debug builds only: sample data
        Sensors.readCompass();
        Link.start();
        timer = new Timer.Timer();
        (timer as Timer.Timer).start(method(:onBeat), 1000, true);      // the app's only timer besides the page's
    }

    function onStop(state as Dictionary?) as Void {
        if (timer != null) { (timer as Timer.Timer).stop(); }
        Sensors.setGps(false);
        Link.stop();
    }

    // Loop order: Gauge, Next mark, Anchor, [Sight], MOB (last, one step "previous" from the Gauge).
    // Conditions page removed (2026-11-22): the Gauge already shows the wind.
    function loopPages() as Array<Page> {
        var list = [new GaugePage(), new MarkPage(), new AnchorPage()] as Array<Page>;
        var s = sightPage();
        if (s != null && Prefs.celestialOn()) { list.add(s); }
        list.add(new MobPage());
        return list;
    }
    (:sight) function sightPage() as Page? { return new SightPage(); }
    (:sight) function hasSight() as Boolean { return true; }
    (:lite) function hasSight() as Boolean { return false; }
    (:lite) function sightPage() as Page? { return null; }

    function getInitialView() as [WatchUi.Views] or [WatchUi.Views, WatchUi.InputDelegates] {
        var h = new Host(loopPages(), true);
        host = h;
        if (Model.mobOn) { h.index = h.pages.size() - 1; }    // an active MOB opens on the MOB page
        if (Tour.on()) { Tour.start(h); }
        return [h, new HostDelegate(h)];
    }

    // Every second: the link's polls; every 10 s: resend unacknowledged messages and follow the celestial setting
    var beats as Number = 0;
    function onBeat() as Void {
        Net.onTimer();
        beats++;
        if (beats % 10 != 0) { return; }
        Link.resend();
        var h = host;
        if (h != null) {
            var want = (hasSight() && Prefs.celestialOn()) ? 5 : 4;
            if (want != h.pages.size()) { h.setPages(loopPages()); }
        }
    }

    function onSettingsChanged() as Void {
        var h = host;
        if (h != null) { h.setPages(loopPages()); }
        WatchUi.requestUpdate();
    }
}
