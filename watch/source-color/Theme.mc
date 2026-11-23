import Toybox.Lang;

// Third Coast Sailing palette (TCS app ui/theme/Color.kt and Gauge.kt). Colour devices.
// 64-colour MIP screens map these to the nearest colour themselves; check on the simulator (COMPATIBILITY.md 3.2).
module Theme {
    const MONO = false;
    const BG = 0x000000;
    const NAVY = 0x0F2742;       // ring band, boxes
    const NAVY_L = 0x1B3B5F;     // finished / drained ring
    const LINE = 0x2C5078;       // ring outlines
    const ORANGE = 0xF28C28;     // heading box, hull, target dot, anything tappable
    const ON_ORANGE = 0x0A1B2E;  // text on orange
    const FOAM = 0xF4F1EA;       // main text
    const MIST = 0xAFC2D6;       // secondary text
    const FADE = 0x4F5F70;       // drum neighbours
    const BLUE = 0x2F6FA3;       // wind triangle, depth box, legs to come
    const POLAR = 0x8FBCE0;      // polar, current leg, anchor line
    const GREEN = 0x3DBE6B;      // starboard arc, full sail
    const RED = 0xE5484D;        // port arc, alarm circle, outliers
    const ALARM = 0xFF5A4F;      // MOB, alarm ring
    const ALARM_DK = 0xB3261E;   // MOB border
    const ALARM_BG = 0x3A0F0F;   // hold bar background
    const ALARM_FILL = 0x7A1C17; // hold bar fill
    const CAUTION = 0xF2C14E;    // anchor box, first reef
    const INK = 0x1B1F23;        // text on yellow / green
    const START = 0x2E9E4F;      // route start dot

    // Severity fill for the wind and wave triangles: 0 green, 1 yellow, 2 red
    function sev(level as Number) as Number { return level == 0 ? GREEN : (level == 1 ? CAUTION : RED); }
    function onSev(level as Number) as Number { return level == 2 ? FOAM : INK; }
}
