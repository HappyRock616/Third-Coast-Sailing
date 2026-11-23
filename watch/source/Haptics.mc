import Toybox.Attention;
import Toybox.Lang;

// Buzz patterns (DESIGN.md 4) and the next-mark leg events (port of LegHaptics in reference/logic.js).
// Devices without a vibration motor (Edge, handhelds) play tones instead (COMPATIBILITY.md 3.5).
module Haptics {
    const QUARTER = 0;
    const APPROACH = 1;
    const REACHED = 2;
    const FINISH = 3;
    const MOB = 4;
    const MARK = 5;
    const HOLD = 6;
    const DELETED = 7;
    const ALARM = 8;
    const SET = 9;

    // [on ms, off ms] pairs
    const PATTERNS = [
        [60],                       // quarter: 1 short
        [60, 120, 60],              // approach: 2 short
        [400, 150, 60],             // reached: 1 long + 1 short
        [400, 200, 400, 200, 400],  // finish: 3 long
        [1200],                     // MOB: 1 very long
        [40],                       // sight mark: 1 sharp short
        [50],                       // hold reached
        [60, 120, 60],              // shot deleted: 2 short
        [500, 250, 500],            // anchor alarm (repeated by the caller)
        [60, 120, 60]               // anchor set by the phone: 2 short
    ];

    var lastEvent as Number = -1;  // for the debug readout

    function play(which as Number) as Void {
        lastEvent = which;
        if (Attention has :vibrate) {
            var p = PATTERNS[which] as Array<Number>, prof = [];
            for (var i = 0; i < p.size(); i++) {
                prof.add(new Attention.VibeProfile(i % 2 == 0 ? 100 : 0, p[i]));
            }
            Attention.vibrate(prof);
        } else if (Attention has :playTone) {
            var tone = Attention.TONE_KEY;
            if (which == ALARM || which == MOB) { tone = Attention.TONE_ALARM; }
            else if (which == REACHED || which == FINISH) { tone = Attention.TONE_LAP; }
            else if (which == APPROACH) { tone = Attention.TONE_ALERT_HI; }
            Attention.playTone(tone);
        }
    }

    // Leg events: each fires once per leg; a new later leg = reached; short legs (<= 0.4 nm) skip the quarters.
    class LegWatch {
        var leg as Number = -1;
        var legLen as Float = 0.0;
        var fired as Number = 0;   // bits: 1 = 75%, 2 = 50%, 4 = 25%, 8 = approach

        function initialize() {}

        // Returns the event to play, or -1
        function update(legIndex as Number, legLenNm as Float, togo as Float) as Number {
            if (legIndex != leg) {
                var ev = (leg >= 0 && legIndex > leg) ? REACHED : -1;
                leg = legIndex; legLen = legLenNm; fired = 0;
                // a leg that starts already inside a threshold doesn't fire it
                if (togo <= 0.75 * legLen) { fired |= 1; }
                if (togo <= 0.5 * legLen) { fired |= 2; }
                if (togo <= 0.25 * legLen) { fired |= 4; }
                if (togo <= 3 * Nav.POINT_REACHED_NM) { fired |= 8; }
                return ev;
            }
            var ev = -1;
            if (legLen > 0.4) {
                if (togo <= 0.75 * legLen && (fired & 1) == 0) { fired |= 1; ev = QUARTER; }
                if (togo <= 0.5 * legLen && (fired & 2) == 0) { fired |= 2; ev = QUARTER; }
                if (togo <= 0.25 * legLen && (fired & 4) == 0) { fired |= 4; ev = QUARTER; }
            }
            if (togo <= 3 * Nav.POINT_REACHED_NM && (fired & 8) == 0) { fired |= 8; ev = APPROACH; }
            return ev;
        }
    }
}
