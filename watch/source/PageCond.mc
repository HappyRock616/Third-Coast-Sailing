import Toybox.Graphics;
import Toybox.Lang;

// Page 4: wind and waves at the boat. North-up ring; wind triangle on the ring, wave triangle just inside it,
// both pointing from where they come, filled by severity. Thresholds are placeholders (DESIGN 9).
class CondPage extends Page {
    const WIND_YELLOW = 15.0;
    const WIND_RED = 22.0;
    const WAVE_YELLOW = 3.0;
    const WAVE_RED = 4.5;

    function initialize() { Page.initialize(); }
    function id() as Number { return 3; }

    function level(v as Float, y as Float, r as Float) as Number { return v >= r ? 2 : (v >= y ? 1 : 0); }

    function draw(dc as Dc, f as Draw.Frame) as Void {
        Draw.compass(dc, f, 0, true);
        var tws = Model.tws, twd = Model.twd, wh = Model.waveHt, wd = Model.waveDir;
        if (tws == null || twd == null) {
            Draw.text(dc, f.cx, f.y(190), "No forecast yet", f.d(22), Theme.FOAM, f.d(260));
            return;
        }
        Draw.anchor(dc, f, f.cx, f.y(116), 34, Theme.MIST);
        Draw.text(dc, f.cx, f.y(162), "WIND", f.d(15), Theme.MIST, 0);
        var g = Model.gust == null ? "" : " G" + (Model.gust as Float).format("%.0f");
        Draw.text(dc, f.cx, f.y(196), (tws as Float).format("%.0f") + g + " kt", f.d(32), Theme.FOAM, f.d(220));
        Draw.text(dc, f.cx, f.y(232), "WAVES", f.d(15), Theme.MIST, 0);
        var wtxt = wh == null ? "--" : (wh as Float).format("%.1f") + " ft" + (Model.wavePer == null ? "" : " · " + (Model.wavePer as Float).format("%.0f") + " s");
        Draw.text(dc, f.cx, f.y(266), wtxt, f.d(28), Theme.FOAM, f.d(220));
        if (Model.sca) { Draw.text(dc, f.cx, f.y(302), "SMALL CRAFT ADVISORY", f.d(13), Theme.RED, f.d(220)); }
    }

    // Wind and wave triangles sit on the ring: drawn over the phone icon so they never hide
    function drawTop(dc as Dc, f as Draw.Frame) as Void {
        var tws = Model.tws, twd = Model.twd, wh = Model.waveHt, wd = Model.waveDir;
        if (tws == null || twd == null) { return; }
        var wl = Model.sca ? 2 : level(tws as Float, WIND_YELLOW, WIND_RED);
        var c = Draw.sevTriangle(dc, f, 160, twd, 1.0, wl);
        Draw.text(dc, c[0], c[1], (tws as Float).format("%.0f"), f.d(16), Theme.onSev(wl), 0);
        if (wh != null && wd != null) {
            var vl = level(wh as Float, WAVE_YELLOW, WAVE_RED);
            var c2 = Draw.sevTriangle(dc, f, 118, wd, 1.0, vl);
            Draw.text(dc, c2[0], c2[1], (wh as Float).format("%.1f"), f.d(15), Theme.onSev(vl), 0);
        }
    }
}
