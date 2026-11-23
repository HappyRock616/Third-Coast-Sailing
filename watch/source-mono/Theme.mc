import Toybox.Lang;

// Monochrome devices (Instinct 2 / 3 Solar / E / Crossover, Descent G1): white on black.
// Same names as source-color/Theme.mc. Severity is shown by fill style instead of colour (Draw.sevTriangle).
module Theme {
    const MONO = true;
    const BG = 0x000000;
    const NAVY = 0x000000;
    const NAVY_L = 0x000000;
    const LINE = 0xFFFFFF;
    const ORANGE = 0xFFFFFF;
    const ON_ORANGE = 0x000000;
    const FOAM = 0xFFFFFF;
    const MIST = 0xFFFFFF;
    const FADE = 0xFFFFFF;
    const BLUE = 0xFFFFFF;
    const POLAR = 0xFFFFFF;
    const GREEN = 0xFFFFFF;
    const RED = 0xFFFFFF;
    const ALARM = 0xFFFFFF;
    const ALARM_DK = 0xFFFFFF;
    const ALARM_BG = 0x000000;
    const ALARM_FILL = 0xFFFFFF;
    const CAUTION = 0xFFFFFF;
    const INK = 0x000000;
    const START = 0xFFFFFF;

    function sev(level as Number) as Number { return FOAM; }
    function onSev(level as Number) as Number { return level == 0 ? FOAM : INK; }
}
