import Toybox.Lang;
import Toybox.Math;
import Toybox.Position;
import Toybox.Sensor;
import Toybox.System;
import Toybox.Time;

// Watch GPS, compass and a millisecond clock. GPS runs only while a page needs it (COMPATIBILITY / PROTOCOL 4).
module Sensors {
    var gpsOn as Boolean = false;
    var secBase as Number = 0;     // Time.now() second at which timerBase was taken
    var timerBase as Number = 0;   // System.getTimer() at that second boundary
    var fakeHeading as Float? = null;   // demo tour only: stands in for the compass
    // GPS speed is noisy at rest (0.2-0.4 kt): speed is shown only above 0.6 kt for 3 s (off below 0.5 kt), course
    // only above 1.2 kt for 3 s (off below 0.8 kt), on a 5-sample average
    var spd as Array<Float> = [];
    var sogOk as Boolean = false;
    var cogOk as Boolean = false;
    var sogSince as Number = 0;
    var cogSince as Number = 0;

    function setGps(on as Boolean) as Void {
        if (on == gpsOn || Tour.on()) { return; }      // the demo tour keeps its sample positions
        gpsOn = on;
        if (on) {
            Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, new Lang.Method(Sensors, :onPosition));
        } else {
            Position.enableLocationEvents(Position.LOCATION_DISABLE, null);
        }
    }

    function onPosition(info as Position.Info) as Void {
        var p = info.position;
        if (p != null && info.accuracy != null && info.accuracy >= Position.QUALITY_POOR) {
            var d = p.toDegrees();
            Model.pos = [d[0].toFloat(), d[1].toFloat()];
            Model.posTime = Model.now();
        }
        if (info.speed == null) { return; }
        spd.add((info.speed as Float) * 1.943844);
        if (spd.size() > 5) { spd = spd.slice(1, null); }
        var avg = 0.0;
        for (var i = 0; i < spd.size(); i++) { avg += spd[i]; }
        avg = avg / spd.size();
        var t = System.getTimer();
        sogOk = gate(sogOk, avg, 0.6, 0.5, t, true);
        cogOk = gate(cogOk, avg, 1.2, 0.8, t, false);
        Model.sogKn = sogOk ? avg : null;
        if (cogOk && info.heading != null) {
            Model.cogDeg = Nav.norm360(Nav.deg(info.heading as Float));
            Model.lastCog = Model.cogDeg;
        } else {
            Model.cogDeg = null;
        }
    }

    // Hysteresis: on after the value has stayed at or above on for 3 s, off as soon as it drops below off
    function gate(ok as Boolean, v as Float, on as Float, off as Float, t as Number, isSog as Boolean) as Boolean {
        if (ok) { return v >= off; }
        if (v < on) { if (isSog) { sogSince = 0; } else { cogSince = 0; } return false; }
        var since = isSog ? sogSince : cogSince;
        if (since == 0) { if (isSog) { sogSince = t; } else { cogSince = t; } return false; }
        return t - since >= 3000;
    }

    // Poll the compass (call from a page timer). Sets Model.hasCompass the first time a heading arrives.
    function readCompass() as Void {
        if (fakeHeading != null) { Model.compass = fakeHeading; Model.hasCompass = true; return; }
        var i = Sensor.getInfo();
        if (i has :heading && i.heading != null) {
            Model.compass = Nav.norm360(Nav.deg(i.heading as Float));
            Model.hasCompass = true;
        } else {
            Model.compass = null;
        }
    }

    // Millisecond sight times without fast polling: calibrate() is called every 50 ms for at most ~1.1 s (Sight page
    // shown) until the UTC second changes; from then on the time is the boundary plus System.getTimer(), so the page
    // only needs a 1 s tick. Recalibrated each time the page opens (the two clocks drift a few ppm).
    var calibrated as Boolean = false;
    var calLast as Number = -1;
    function startCalibration() as Void { calibrated = false; calLast = -1; }
    function calibrate() as Void {
        var s = Time.now().value();
        if (calLast >= 0 && s != calLast) { secBase = s; timerBase = System.getTimer(); calibrated = true; }
        calLast = s;
    }
    function tickClock() as Void { if (!calibrated) { calibrate(); } }

    // [seconds UTC, milliseconds 0-999] for a mark
    function markTime() as Array<Number> {
        if (!calibrated) { var s = Time.now().value(); return [s, 0]; }      // not calibrated yet: whole seconds
        var d = System.getTimer() - timerBase;
        return [secBase + d / 1000, d % 1000];
    }
    // current UTC second, from the calibrated clock when there is one
    function nowSec() as Number { return calibrated ? secBase + (System.getTimer() - timerBase) / 1000 : Time.now().value(); }
}
