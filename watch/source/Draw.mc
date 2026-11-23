import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;

// Shared drawing. Layouts are designed on a 390-unit square (the vívoactive 5 screen, as in the renders) and scaled.
// Angles are compass style: 0 = top of the screen, clockwise.
module Draw {
    // Pixel frame for one draw call. On portrait rectangles the design square sits at the top and the leftover
    // strip below (stripY .. h) is free for extra numbers.
    class Frame {
        var w as Number;
        var h as Number;
        var size as Number;
        var cx as Number;
        var cy as Number;
        var s as Float;
        var stripY as Number;
        function initialize(dc as Dc) {
            w = dc.getWidth();
            h = dc.getHeight();
            size = w < h ? w : h;
            cx = w / 2;
            cy = h > w ? size / 2 : h / 2;
            s = size / 390.0;
            stripY = h > w ? size : h;
        }
        function x(v as Numeric) as Number { return (cx + (v - 195) * s).toNumber(); }
        function y(v as Numeric) as Number { return (cy + (v - 195) * s).toNumber(); }
        function d(v as Numeric) as Number { var r = (v * s).toNumber(); return r < 1 ? 1 : r; }
        function small() as Boolean { return size < 240; }
        // Pixel point at design radius r and compass angle a around the centre
        function pt(r as Numeric, a as Numeric) as Array<Number> {
            var t = Nav.rad(a);
            return [(cx + r * s * Math.sin(t)).toNumber(), (cy - r * s * Math.cos(t)).toNumber()];
        }
    }

    const TEXT_FONTS = [Graphics.FONT_LARGE, Graphics.FONT_MEDIUM, Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY];
    const NUM_FONTS = [Graphics.FONT_NUMBER_THAI_HOT, Graphics.FONT_NUMBER_HOT, Graphics.FONT_NUMBER_MEDIUM, Graphics.FONT_NUMBER_MILD];
    const CENTER = Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER;

    function aa(dc as Dc) as Void { if (dc has :setAntiAlias) { dc.setAntiAlias(true); } }

    function clear(dc as Dc) as Void {
        dc.setColor(Theme.BG, Theme.BG);
        dc.clear();
    }

    // Largest font whose height fits px (Garmin fonts include some padding, hence 1.3)
    function fontFor(dc as Dc, px as Number, nums as Boolean) as Graphics.FontType {
        var list = nums ? NUM_FONTS : TEXT_FONTS;
        for (var i = 0; i < list.size(); i++) {
            var fnt = list[i] as Graphics.FontType;
            if (dc.getFontHeight(fnt) <= px * 1.3) { return fnt; }
        }
        return nums ? fontFor(dc, px, false) : Graphics.FONT_XTINY;
    }

    // Text centred on (x, y), designed `px` pixels tall, shrunk to fit maxW if given
    // Width available for text centred on (x, y): the round screen's chord at that height, less a margin.
    // Rectangular screens: the full width.
    function fitWidth(dc as Dc, x as Number, y as Number) as Number {
        var w = dc.getWidth(), h = dc.getHeight();
        if (System.getDeviceSettings().screenShape == System.SCREEN_SHAPE_RECTANGLE) { return w - 8; }
        var r = (w < h ? w : h) / 2.0, cy = (h > w ? w : h) / 2.0, dy = y - cy;
        if (dy * dy >= r * r) { return 0; }
        var half = Math.sqrt(r * r - dy * dy);
        var left = x - (w / 2.0 - half), right = (w / 2.0 + half) - x;
        var avail = 2 * (left < right ? left : right) - 12;
        return avail > 0 ? avail.toNumber() : 0;
    }

    // Text centred on (x, y), designed `px` pixels tall, shrunk to fit maxW (if given) and always the round screen
    function text(dc as Dc, x as Number, y as Number, s as String, px as Number, color as Number, maxW as Number) as Void {
        var fnt = fontFor(dc, px, false);
        var i = TEXT_FONTS.indexOf(fnt);
        var fit = fitWidth(dc, x, y);
        if (fit > 0 && (maxW <= 0 || fit < maxW)) { maxW = fit; }
        while (maxW > 0 && i < TEXT_FONTS.size() - 1 && dc.getTextWidthInPixels(s, fnt) > maxW) {
            i++; fnt = TEXT_FONTS[i] as Graphics.FontType;
        }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x, y, fnt, s, CENTER);
    }

    // A number in a number font (digits, ':', '.', '-'), with an optional degree mark drawn as a small ring
    function num(dc as Dc, x as Number, y as Number, s as String, px as Number, color as Number, degMark as Boolean) as Void {
        var fnt = fontFor(dc, px, true);
        var tw = dc.getTextWidthInPixels(s, fnt);
        var dr = degMark ? (px / 9 > 2 ? px / 9 : 2) : 0;
        var x0 = x - (tw + dr * 3) / 2;
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(x0, y, fnt, s, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        if (degMark) {
            dc.setPenWidth(dr > 2 ? dr / 2 + 1 : 1);
            dc.drawCircle(x0 + tw + dr * 2, y - px / 3, dr);
        }
    }

    function setPen(dc as Dc, w as Number) as Void { dc.setPenWidth(w < 1 ? 1 : w); }

    // Clockwise arc from compass angle a0 to a1 at design radius r
    function arc(dc as Dc, f as Frame, r as Numeric, a0 as Float, a1 as Float, width as Numeric, color as Number) as Void {
        if (a1 - a0 < 0.4) { return; }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        setPen(dc, f.d(width));
        if (a1 - a0 >= 359.6) { dc.drawCircle(f.cx, f.cy, f.d(r)); return; }
        dc.drawArc(f.cx, f.cy, f.d(r), Graphics.ARC_CLOCKWISE, 90.0 - a0, 90.0 - a1);
    }

    function circle(dc as Dc, f as Frame, r as Numeric, width as Numeric, color as Number) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        setPen(dc, f.d(width));
        dc.drawCircle(f.cx, f.cy, f.d(r));
    }

    // Rounded box in pixels
    function box(dc as Dc, x as Number, y as Number, w as Number, h as Number, r as Number, fill as Number, stroke as Number, sw as Number) as Void {
        dc.setColor(fill, Graphics.COLOR_TRANSPARENT);
        dc.fillRoundedRectangle(x, y, w, h, r);
        if (sw > 0) {
            dc.setColor(stroke, Graphics.COLOR_TRANSPARENT);
            setPen(dc, sw);
            dc.drawRoundedRectangle(x, y, w, h, r);
        }
    }

    // Pill button in design units, label centred
    function pill(dc as Dc, f as Frame, x as Numeric, y as Numeric, w as Numeric, h as Numeric, label as String, fill as Number, ink as Number) as Void {
        var hp = f.d(h);
        box(dc, f.x(x), f.y(y), f.d(w), hp, hp / 2, fill, fill, 0);
        text(dc, f.x(x + w / 2), f.y(y + h / 2), label, hp * 45 / 100, ink, f.d(w - 12));
    }

    // Polygon from design-unit points around (ox, oy) pixels, rotated by rot degrees (compass) and scaled by f.s * k
    function poly(dc as Dc, f as Frame, ox as Number, oy as Number, pts as Array, rot as Numeric, k as Float, color as Number) as Array {
        var t = Nav.rad(rot), c = Math.cos(t), sn = Math.sin(t), out = [];
        for (var i = 0; i < pts.size(); i++) {
            var p = pts[i] as Array;
            var px = (p[0] as Numeric) * f.s * k, py = (p[1] as Numeric) * f.s * k;
            out.add([(ox + px * c - py * sn).toNumber(), (oy + px * sn + py * c).toNumber()]);
        }
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.fillPolygon(out);
        return out;
    }

    function outline(dc as Dc, pts as Array, width as Number, color as Number) as Void {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        setPen(dc, width);
        for (var i = 0; i < pts.size(); i++) {
            var a = pts[i] as Array, b = pts[(i + 1) % pts.size()] as Array;
            dc.drawLine(a[0], a[1], b[0], b[1]);
        }
    }

    // Wind / wave / MOB triangle pointing at the centre, at design radius r and compass angle a. Returns its centre.
    function triangle(dc as Dc, f as Frame, r as Numeric, a as Numeric, k as Float, fill as Number, edge as Number) as Array<Number> {
        var c = f.pt(r, a);
        var p = poly(dc, f, c[0], c[1], [[0, 26], [-24, -18], [24, -18]], a, k, fill);
        outline(dc, p, f.d(2.5), edge);
        return c;
    }

    // Severity triangle: colour on colour screens, fill style on mono (outline / half / solid)
    function sevTriangle(dc as Dc, f as Frame, r as Numeric, a as Numeric, k as Float, level as Number) as Array<Number> {
        if (!Theme.MONO) { return triangle(dc, f, r, a, k, Theme.sev(level), Theme.FOAM); }
        var c = f.pt(r, a);
        if (level == 0) {
            var p = poly(dc, f, c[0], c[1], [[0, 26], [-24, -18], [24, -18]], a, k, Theme.BG);
            outline(dc, p, f.d(3), Theme.FOAM);
        } else if (level == 1) {
            var p = poly(dc, f, c[0], c[1], [[0, 26], [-24, -18], [24, -18]], a, k, Theme.BG);
            outline(dc, p, f.d(3), Theme.FOAM);
            poly(dc, f, c[0], c[1], [[0, 26], [-12, 4], [12, 4]], a, k, Theme.FOAM);
        } else {
            poly(dc, f, c[0], c[1], [[0, 26], [-24, -18], [24, -18]], a, k, Theme.FOAM);
        }
        return c;
    }

    // Overhead sailboat (bow up before rotation), centred at (x, y) pixels; len and beam in design units
    function hull(dc as Dc, f as Frame, x as Number, y as Number, len as Numeric, beam as Numeric, rot as Numeric, fill as Number, edge as Number) as Void {
        var t = -len * 0.55, b = len * 0.45, pts = [];
        // right side: cubic from bow (0,t) via (.55b, t+.25L), (.5b, .2L) to (.38b, b); mirrored for the left
        for (var i = 0; i <= 6; i++) {
            var u = i / 6.0, v = 1 - u;
            var px = 3 * v * v * u * beam * 0.55 + 3 * v * u * u * beam * 0.5 + u * u * u * beam * 0.38;
            var py = v * v * v * t + 3 * v * v * u * (t + len * 0.25) + 3 * v * u * u * len * 0.2 + u * u * u * b;
            pts.add([px, py]);
        }
        for (var i = 6; i >= 0; i--) { var p = pts[i] as Array; pts.add([-(p[0] as Float), p[1]]); }
        var out = poly(dc, f, x, y, pts, rot, 1.0, fill);
        outline(dc, out, f.d(2.5), edge);
    }

    // Anchor symbol, `sz` design units tall, centred at (x, y) pixels
    function anchor(dc as Dc, f as Frame, x as Number, y as Number, sz as Numeric, color as Number) as Void {
        var k = sz * f.s / 60.0;
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        setPen(dc, (6 * k).toNumber());
        dc.drawCircle(x, y - (24 * k).toNumber(), (6 * k).toNumber());
        dc.drawLine(x, y - (18 * k).toNumber(), x, y + (28 * k).toNumber());
        dc.drawLine(x - (14 * k).toNumber(), y - (8 * k).toNumber(), x + (14 * k).toNumber(), y - (8 * k).toNumber());
        dc.drawArc(x, y + (2 * k).toNumber(), (26 * k).toNumber(), Graphics.ARC_COUNTER_CLOCKWISE, 200, 340);
        dc.drawLine(x - (31 * k).toNumber(), y + (12 * k).toNumber(), x - (26 * k).toNumber(), y + (4 * k).toNumber());
        dc.drawLine(x + (31 * k).toNumber(), y + (12 * k).toNumber(), x + (26 * k).toNumber(), y + (4 * k).toNumber());
    }

    // Lifebuoy: white ring with four red quarters
    function buoy(dc as Dc, f as Frame, x as Number, y as Number, r as Numeric) as Void {
        var rp = f.d(r), w = f.d(r * 0.45);
        dc.setColor(Theme.FOAM, Graphics.COLOR_TRANSPARENT);
        setPen(dc, w);
        dc.drawCircle(x, y, rp);
        if (Theme.MONO) { return; }
        dc.setColor(0xC62828, Graphics.COLOR_TRANSPARENT);
        for (var q = 0; q < 4; q++) { dc.drawArc(x, y, rp, Graphics.ARC_CLOCKWISE, 112 - q * 90, 67 - q * 90); }
    }

    var dim as Boolean = false;          // draw the compass ring dimmed (no reliable heading)

    // Compass ring at the screen edge. up = true bearing at the top; northUp labels N E S W instead of numbers.
    function compass(dc as Dc, f as Frame, up as Numeric, northUp as Boolean) as Void {
        circle(dc, f, 167, 36, Theme.NAVY);
        circle(dc, f, 185, 2, Theme.LINE);
        circle(dc, f, 149, 2, Theme.LINE);
        var stepT = f.small() ? 10 : 5;
        for (var d = 0; d < 360; d += stepT) {
            var a = Nav.norm360(d - up), big = d % 30 == 0;
            var len = big ? 11 : (d % 10 == 0 ? 7 : 4);
            var p0 = f.pt(184, a), p1 = f.pt(184 - len, a);
            dc.setColor(dim ? (big ? Theme.MIST : Theme.FADE) : (big ? Theme.FOAM : Theme.MIST), Graphics.COLOR_TRANSPARENT);
            setPen(dc, f.d(big ? 2.2 : 1.2));
            dc.drawLine(p0[0], p0[1], p1[0], p1[1]);
            if (big) {
                var lab = d == 0 ? "N" : (northUp ? (d == 90 ? "E" : (d == 180 ? "S" : (d == 270 ? "W" : ""))) : (f.small() && d % 90 != 0 ? "" : d.format("%03d")));
                if (lab.length() > 0) {
                    var c = f.pt(164, a);
                    text(dc, c[0], c[1], lab, f.d(d == 0 ? 17 : 13), dim ? Theme.MIST : (d == 0 ? Theme.ORANGE : Theme.FOAM), 0);
                }
            }
        }
    }

    // Heading box over the top of the ring, with the pointer below. value = digits; degMark adds the ring.
    function headBox(dc as Dc, f as Frame, value as String, suffix as String, y as Numeric, w as Numeric) as Void {
        box(dc, f.x(195 - w / 2), f.y(y), f.d(w), f.d(46), f.d(11), Theme.NAVY, Theme.ORANGE, f.d(3));
        if (value.equals("--")) { text(dc, f.x(195), f.y(y + 23), "--", f.d(26), Theme.MIST, 0); }   // number fonts draw "--" badly
        else { num(dc, f.x(195) - (suffix.length() > 0 ? f.d(10) : 0), f.y(y + 23), value, f.d(30), Theme.FOAM, true); }
        if (suffix.length() > 0) { text(dc, f.x(195 + w / 2 - 16), f.y(y + 26), suffix, f.d(15), Theme.MIST, 0); }
        poly(dc, f, f.x(195), f.y(y + 46), [[-8, 0], [8, 0], [0, 9]], 0, 1.0, Theme.ORANGE);
    }

    // Orange ring filling clockwise while a hold is in progress (0..1)
    function holdRing(dc as Dc, f as Frame, frac as Float) as Void {
        if (frac > 0.0) { arc(dc, f, 188, 0.0, 360.0 * frac, 8, Theme.ORANGE); }
    }

    // Hold-to-confirm bar (design units), fill grows left to right
    function holdBar(dc as Dc, f as Frame, bx as Numeric, y as Numeric, bw as Numeric, label as String, frac as Float, edge as Number) as Void {
        var x = f.x(bx), w = f.d(bw), h = f.d(38);
        box(dc, x, f.y(y), w, h, h / 2, Theme.ALARM_BG, edge, f.d(2));
        if (frac > 0.0) {
            var fw = (w * frac).toNumber();
            box(dc, x, f.y(y), fw < h ? h : fw, h, h / 2, Theme.ALARM_FILL, edge, 0);
        }
        text(dc, f.x(bx + bw / 2), f.y(y + 19), label, f.d(15), Theme.MONO && frac > 0.5 ? Theme.INK : Theme.FOAM, f.d(bw - 10));
    }

    // Semicircle side panels (Just Go 5K style) clipped by the screen edge
    function sidePanels(dc as Dc, f as Frame, lLab as String, lVal as String, rLab as String, rVal as String, unit as String) as Void {
        var r = f.d(12);
        box(dc, f.x(28), f.y(146), f.d(86), f.d(102), r, Theme.NAVY, Theme.NAVY, 0);
        box(dc, f.x(276), f.y(146), f.d(86), f.d(102), r, Theme.NAVY, Theme.NAVY, 0);
        text(dc, f.x(80), f.y(176), lLab, f.d(13), Theme.MIST, 0);
        num(dc, f.x(80), f.y(208), lVal, f.d(30), Theme.FOAM, false);
        text(dc, f.x(80), f.y(234), unit, f.d(12), Theme.MIST, 0);
        text(dc, f.x(310), f.y(176), rLab, f.d(13), Theme.MIST, 0);
        num(dc, f.x(310), f.y(208), rVal, f.d(30), Theme.FOAM, false);
        text(dc, f.x(310), f.y(234), unit, f.d(12), Theme.MIST, 0);
    }

    // Value with a faded neighbour above and below, in a box (scroll drums). Design units. v = [value, above, below]
    // (older CIQ allows at most 9 arguments per function)
    function drum(dc as Dc, f as Frame, x as Numeric, y as Numeric, w as Numeric, label as String, v as Array<String>, unit as String, focus as Boolean) as Void {
        var val = v[0], up = v[1], dn = v[2];
        text(dc, f.x(x + w / 2), f.y(y - 12), up, f.d(20), Theme.FADE, 0);
        box(dc, f.x(x), f.y(y), f.d(w), f.d(80), f.d(13), Theme.NAVY, focus ? Theme.ORANGE : Theme.LINE, f.d(focus ? 3 : 2));
        if (label.length() > 0) { text(dc, f.x(x + w / 2), f.y(y + 16), label, f.d(11), Theme.MIST, 0); }
        num(dc, f.x(x + w / 2) - (unit.length() > 0 ? f.d(12) : 0), f.y(y + (label.length() > 0 ? 50 : 42)), val, f.d(44), Theme.FOAM, false);
        if (unit.length() > 0) { text(dc, f.x(x + w - 22), f.y(y + 54), unit, f.d(15), Theme.MIST, 0); }
        text(dc, f.x(x + w / 2), f.y(y + 98), dn, f.d(20), Theme.FADE, 0);
    }

    // Hit test in design units
    function hit(f as Frame, px as Number, py as Number, x as Numeric, y as Numeric, w as Numeric, h as Numeric) as Boolean {
        return px >= f.x(x) && px <= f.x(x + w) && py >= f.y(y) && py <= f.y(y + h);
    }

    // Distance in design units of a pixel point from the centre
    function radiusOf(f as Frame, px as Number, py as Number) as Float {
        var dx = (px - f.cx) / f.s, dy = (py - f.cy) / f.s;
        return Math.sqrt(dx * dx + dy * dy).toFloat();
    }

    // Compass angle of a pixel point around the centre
    function angleOf(f as Frame, px as Number, py as Number) as Float {
        return Nav.norm360(Nav.deg(Math.atan2(px - f.cx, f.cy - py)));
    }
    // Anchor button (HappyRock's design): orange circle = it's a button; the anchor is green when set, red when not
    function anchorButton(dc as Dc, f as Frame, x as Number, y as Number, set as Boolean) as Void {
        var r = f.d(33);
        dc.setColor(Theme.BG, Graphics.COLOR_TRANSPARENT);
        dc.fillCircle(x, y, r);
        dc.setColor(Theme.ORANGE, Graphics.COLOR_TRANSPARENT);
        setPen(dc, f.d(4));
        dc.drawCircle(x, y, r);
        anchor(dc, f, x, y, 40, set ? Theme.GREEN : Theme.ALARM);
    }

    // Data box (navy, orange edge) centred on (x, y) pixels; clock = an alarm-clock glyph before the value
    function dataBox(dc as Dc, f as Frame, x as Number, y as Number, s as String, clock as Boolean, edge as Number) as Void {
        var w = f.d(92), h = f.d(40);
        box(dc, x - w / 2, y - h / 2, w, h, f.d(10), Theme.NAVY, edge, f.d(3));
        if (clock) {
            var cx = x - f.d(28), cy = y + f.d(1), r = f.d(10);
            dc.setColor(Theme.ALARM, Graphics.COLOR_TRANSPARENT);
            setPen(dc, f.d(2.5));
            dc.drawCircle(cx, cy, r);
            dc.drawLine(cx, cy, cx, cy - f.d(6));
            dc.drawLine(cx, cy, cx + f.d(5), cy + f.d(3));
            dc.drawLine(cx - f.d(9), cy - f.d(12), cx - f.d(5), cy - f.d(15));
            dc.drawLine(cx + f.d(9), cy - f.d(12), cx + f.d(5), cy - f.d(15));
            text(dc, x + f.d(12), y, s, f.d(19), Theme.FOAM, f.d(56));
        } else {
            text(dc, x, y, s, f.d(20), Theme.FOAM, f.d(84));
        }
    }

    // Number + small unit, centred together on (x, y): the unit sits right after the digits, never on them
    function numUnit(dc as Dc, f as Frame, x as Number, y as Number, val as String, px as Number, unit as String, upx as Number, color as Number) as Void {
        var nf = fontFor(dc, px, true), uf = fontFor(dc, upx, false);
        var nw = dc.getTextWidthInPixels(val, nf), uw = unit.length() > 0 ? dc.getTextWidthInPixels(unit, uf) + f.d(4) : 0;
        var left = x - (nw + uw) / 2;
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(left, y, nf, val, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER);
        if (uw > 0) { dc.drawText(left + nw + f.d(4), y + f.d(4), uf, unit, Graphics.TEXT_JUSTIFY_LEFT | Graphics.TEXT_JUSTIFY_VCENTER); }
    }
}
