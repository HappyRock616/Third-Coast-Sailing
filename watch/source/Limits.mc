import Toybox.Lang;

// Buffer sizes per memory tier (COMPATIBILITY.md 3.4). monkey.jungle compiles out (:lite) on Full devices and
// (:full) on Lite and Mono devices.
module Limits {
    (:full) const TRACE_POINTS = 180;   // 6 h of anchor fixes at 2 min
    (:lite) const TRACE_POINTS = 90;    // 3 h
    (:full) const LITE = false;
    (:lite) const LITE = true;
    const MAX_SHOTS_PER_BODY = 8;
    const OUTBOX_MAX = 20;
}
