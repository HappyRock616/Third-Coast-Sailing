import Toybox.Lang;
import Toybox.Math;

// Navigation maths. A line-for-line port of reference/logic.js (tested in Node: node --test reference/logic.test.js).
// Formulas match the TCS phone app (Route.kt distNm / bearingDeg, Polars.kt boatSpeed) so both show the same numbers.
module Nav {
    const EARTH_NM = 3440.065;
    const FT_PER_NM = 6076.12;
    const POINT_REACHED_NM = 0.1;
    const OUTLIER_ARCMIN = 2.5;

    function rad(d as Numeric) as Float { return d.toFloat() * Math.PI / 180.0; }
    function deg(r as Numeric) as Float { return r.toFloat() * 180.0 / Math.PI; }

    function norm360(a as Numeric) as Float {
        var x = a.toFloat();
        x = x - 360.0 * Math.floor(x / 360.0);
        return x >= 360.0 ? 0.0 : x;
    }

    // Signed difference b - a, -180..180
    function angDiff(a as Numeric, b as Numeric) as Float {
        var d = norm360(b - a);
        return d > 180.0 ? d - 360.0 : d;
    }

    // Great-circle distance (nm) between [lat, lon] pairs in degrees
    function distNm(a as Array<Float>, b as Array<Float>) as Float {
        var p1 = rad(a[0]), p2 = rad(b[0]);
        var dp = p2 - p1, dl = rad(b[1] - a[1]);
        var h = Math.pow(Math.sin(dp / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(dl / 2), 2);
        if (h < 0.0) { h = 0.0; }
        if (h > 1.0) { h = 1.0; }
        return (2.0 * EARTH_NM * Math.asin(Math.sqrt(h))).toFloat();
    }

    // Initial true bearing from a to b, 0..360
    function bearingDeg(a as Array<Float>, b as Array<Float>) as Float {
        var p1 = rad(a[0]), p2 = rad(b[0]), dl = rad(b[1] - a[1]);
        var y = Math.sin(dl) * Math.cos(p2);
        var x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return norm360(deg(Math.atan2(y, x)));
    }

    function toMagnetic(trueDeg as Numeric, magVar as Numeric) as Float { return norm360(trueDeg - magVar); }

    // "047": three digits, never "360"
    function deg3(d as Numeric) as String {
        var n = Math.round(norm360(d)).toNumber() % 360;
        return n.format("%03d");
    }

    function vmc(sogKn as Float, cogDeg as Float, brgDeg as Float) as Float {
        return sogKn * Math.cos(rad(angDiff(cogDeg, brgDeg))).toFloat();
    }

    // Minutes to go, or null when not closing (VMC <= 0.05 kt)
    function minutesToGo(dist as Float, vmcKn as Float) as Float? {
        return vmcKn > 0.05 ? dist / vmcKn * 60.0 : null;
    }

    // ---------------------------------------------------------------- polar
    function foldAngle(a as Numeric) as Float {
        var x = norm360(a);
        return x > 180.0 ? 360.0 - x : x;
    }

    // [i0, i1, fraction] around v in a sorted axis (clamped)
    function bracket(axis as Array<Numeric>, v as Float) as Array<Numeric> {
        var n = axis.size() - 1;
        if (v <= axis[0]) { return [0, 0, 0.0]; }
        if (v >= axis[n]) { return [n, n, 0.0]; }
        var i = 0;
        for (var k = 0; k <= n; k++) { if (axis[k] <= v) { i = k; } }
        return [i, i + 1, (v - axis[i]) / (axis[i + 1] - axis[i]).toFloat()];
    }

    // Polar as sent in POLAR: tws[] and twa[] in tenths, speed[twa][tws] in tenths of a knot
    function boatSpeed(tws as Array<Number>, twa as Array<Number>, spd as Array<Array<Number>>, twsKn as Float, twaDeg as Float) as Float {
        var a = foldAngle(twaDeg) * 10.0;
        var bi = bracket(twa, a), bj = bracket(tws, twsKn * 10.0);
        var i0 = bi[0] as Number, i1 = bi[1] as Number, fa = bi[2].toFloat();
        var j0 = bj[0] as Number, j1 = bj[1] as Number, fs = bj[2].toFloat();
        var top = spd[i0][j0] * (1 - fs) + spd[i0][j1] * fs;
        var bot = spd[i1][j0] * (1 - fs) + spd[i1][j1] * fs;
        var v = (top * (1 - fa) + bot * fa) / 10.0;
        return v > 0.0 ? v.toFloat() : 0.0;
    }

    // Lite: 13-point curve at TWA 0, 15 .. 180 (tenths of a knot)
    function curveSpeed(curve as Array<Number>, twaDeg as Float) as Float {
        var a = foldAngle(twaDeg) / 15.0;
        var i = Math.floor(a).toNumber();
        if (i > 11) { i = 11; }
        var f = a - i;
        return ((curve[i] * (1 - f) + curve[i + 1] * f) / 10.0).toFloat();
    }

    // ---------------------------------------------------------------- anchor
    // Boat offset (east, north feet) from the anchor -> [distance ft, true bearing boat->anchor]
    function toAnchor(dx as Numeric, dy as Numeric) as Array<Float> {
        var x = dx.toFloat(), y = dy.toFloat();
        return [Math.sqrt(x * x + y * y).toFloat(), norm360(deg(Math.atan2(-x, -y)))];
    }

    // Offset (east, north feet) of point p from the anchor (both [lat, lon])
    function toOffsetFt(anchor as Array<Float>, p as Array<Float>) as Array<Number> {
        var n = (p[0] - anchor[0]) * 60.0 * FT_PER_NM;
        var e = (p[1] - anchor[1]) * 60.0 * FT_PER_NM * Math.cos(rad(anchor[0]));
        return [Math.round(e).toNumber(), Math.round(n).toNumber()];
    }

    // px per ft so the alarm circle fills rPx, stretching if the boat is outside it
    function plotScale(radiusFt as Numeric, boatFt as Numeric, rPx as Numeric) as Float {
        var m = radiusFt.toFloat();
        var b = boatFt.toFloat() * 1.08;
        return rPx.toFloat() / (b > m ? b : m);
    }

    // ---------------------------------------------------------------- scroll drums
    function stepOf(v as Number) as Number { return v < 100 ? 5 : (v < 200 ? 10 : 25); }
    function drumUp(v as Number, max as Number) as Number { var n = v + stepOf(v); return n > max ? max : n; }
    function drumDown(v as Number, min as Number) as Number { var n = v - stepOf(v - 1); return n < min ? min : n; }

    // ---------------------------------------------------------------- heading source
    const MODE_BOAT = 0;
    const MODE_WATCH = 1;
    const MODE_NORTH = 2;

    // Returns [mode, upDeg]. prev = last mode (hysteresis: into boat above 1.2 kt, out below 0.8 kt).
    function headingMode(sogKn as Float?, cogDeg as Float?, phoneHdg as Float?, watchHdg as Float?, prev as Number) as Array<Numeric> {
        var sog = sogKn == null ? 0.0 : sogKn;
        var fast = prev == MODE_BOAT ? sog >= 0.8 : sog >= 1.2;
        if (fast && cogDeg != null) { return [MODE_BOAT, cogDeg]; }
        if (phoneHdg != null) { return [MODE_BOAT, phoneHdg]; }
        if (watchHdg != null) { return [MODE_WATCH, watchHdg]; }
        return [MODE_NORTH, 0.0];
    }

    // ---------------------------------------------------------------- sights
    // shots: Array of [tSec, ms, body, hsTenths or null]. Line through those with an angle (only `body`).
    // Returns [meanT, meanA, slope per second] with t in seconds relative to t0, or null.
    function lineFit(shots as Array, body as Number, skip as Number) as Array<Float>? {
        var n = 0, st = 0.0, sa = 0.0;
        var t0 = firstT(shots, body);
        for (var i = 0; i < shots.size(); i++) {
            var s = shots[i] as Array;
            if (i == skip || s[2] != body || s[3] == null) { continue; }
            n++; st += tOf(s, t0); sa += (s[3] as Number) / 10.0;
        }
        if (n < 2) { return null; }
        var mt = st / n, ma = sa / n, sxx = 0.0, sxy = 0.0;
        for (var i = 0; i < shots.size(); i++) {
            var s = shots[i] as Array;
            if (i == skip || s[2] != body || s[3] == null) { continue; }
            var dt = tOf(s, t0) - mt;
            sxx += dt * dt; sxy += dt * ((s[3] as Number) / 10.0 - ma);
        }
        return [mt, ma, sxx > 0 ? sxy / sxx : 0.0];
    }

    function firstT(shots as Array, body as Number) as Number {
        for (var i = 0; i < shots.size(); i++) { var s = shots[i] as Array; if (s[2] == body) { return s[0] as Number; } }
        return 0;
    }
    function tOf(s as Array, t0 as Number) as Float { return ((s[0] as Number) - t0) + (s[1] as Number) / 1000.0; }
    function fitAt(f as Array<Float>, t as Float) as Float { return f[1] + f[2] * (t - f[0]); }

    // Residual (arc-min) of shot i against the line through the other shots of its body (3+ needed to judge)
    function residual(shots as Array, i as Number) as Float {
        var s = shots[i] as Array;
        if (s[3] == null) { return 0.0; }
        var body = s[2] as Number;
        var f = lineFit(shots, body, i);
        if (f == null) { return 0.0; }
        return (s[3] as Number) / 10.0 - fitAt(f, tOf(s, firstT(shots, body)));
    }

    function countAngles(shots as Array, body as Number) as Number {
        var n = 0;
        for (var i = 0; i < shots.size(); i++) { var s = shots[i] as Array; if (s[2] == body && s[3] != null) { n++; } }
        return n;
    }

    function isOutlier(shots as Array, i as Number) as Boolean {
        var s = shots[i] as Array;
        if (s[3] == null || countAngles(shots, s[2] as Number) < 3) { return false; }
        return residual(shots, i).abs() > OUTLIER_ARCMIN;
    }

    // Tenths of an arc-minute -> "32°14.6'" (rounded first, so never 60.0')
    function fmtHs(tenths as Number) as String {
        var d = tenths / 600;
        var m = tenths - d * 600;
        return d.format("%d") + "°" + (m / 10).format("%02d") + "." + (m % 10).format("%d") + "'";
    }

    // (seconds mod 10,000,000) x 10 + body: fits a 32-bit Number, unique for ~115 days (PROTOCOL.md)
    function shotId(tSec as Number, body as Number) as Number { return (tSec % 10000000) * 10 + body; }
}
