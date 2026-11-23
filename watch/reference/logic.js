// TCS Companion: reference logic, tested in Node (reference/logic.test.js), then ported line for line to Monkey C
// (source/Nav.mc, Haptics.mc, Sight.mc, HeadingMode.mc). Formulas match the TCS phone app where it has them
// (Route.kt distNm/bearingDeg, Polars.kt boatSpeed/bracket/foldAngle) so both devices show the same numbers.
"use strict";

const EARTH_NM = 3440.065;            // Route.kt
const FT_PER_NM = 6076.12;
const POINT_REACHED_NM = 0.1;         // Route.kt
const rad = d => d * Math.PI / 180, deg = r => r * 180 / Math.PI;
const norm360 = a => ((a % 360) + 360) % 360;
/** Signed difference b - a in -180..180. */
const angDiff = (a, b) => { let d = norm360(b - a); return d > 180 ? d - 360 : d; };

// ------------------------------------------------------------------ geometry (Route.kt)
function distNm(a, b) {
  const p1 = rad(a.lat), p2 = rad(b.lat), dp = p2 - p1, dl = rad(b.lon - a.lon);
  const h = Math.sin(dp / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) ** 2;
  return 2 * EARTH_NM * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
}
function bearingDeg(a, b) {
  const p1 = rad(a.lat), p2 = rad(b.lat), dl = rad(b.lon - a.lon);
  const y = Math.sin(dl) * Math.cos(p2), x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
  return norm360(deg(Math.atan2(y, x)));
}
/** True to magnetic with the variation the phone sends (east positive), as Route.kt toMagnetic. */
const toMagnetic = (trueDeg, magVar) => norm360(trueDeg - magVar);
/** "047": three digits, 359.6 shows as 000, never 360 (Route.kt deg3). */
const deg3 = d => String(norm360(Math.round(d)) % 360).padStart(3, "0");

/** Velocity made good toward a bearing. */
const vmc = (sogKn, cogDeg, brgDeg) => sogKn * Math.cos(rad(angDiff(cogDeg, brgDeg)));
/** Minutes to go at the VMC, or null when not closing (≤ 0.05 kt). */
const minutesToGo = (distNm, vmcKn) => (vmcKn > 0.05 ? distNm / vmcKn * 60 : null);

// ------------------------------------------------------------------ polar (Polars.kt)
function foldAngle(a) { let x = norm360(a); if (x > 180) x = 360 - x; return x; }
function bracket(axis, v) {
  if (v <= axis[0]) return [0, 0, 0];
  const n = axis.length - 1;
  if (v >= axis[n]) return [n, n, 0];
  let i = 0; for (let k = 0; k <= n; k++) if (axis[k] <= v) i = k;
  return [i, i + 1, (v - axis[i]) / (axis[i + 1] - axis[i])];
}
/** polar = {tws:[], twa:[], speed:[[...per tws]...per twa]} */
function boatSpeed(polar, twsKn, twaDeg) {
  const a = foldAngle(twaDeg), [i0, i1, fa] = bracket(polar.twa, a), [j0, j1, fs] = bracket(polar.tws, twsKn);
  const s = polar.speed, top = s[i0][j0] * (1 - fs) + s[i0][j1] * fs, bot = s[i1][j0] * (1 - fs) + s[i1][j1] * fs;
  return Math.max(0, top * (1 - fa) + bot * fa);
}
/** Lite devices: the 13-point curve (TWA 0,15..180) the phone sends as POLAR_CURVE, read back by interpolation. */
function curveSpeed(curve, twaDeg) {
  const a = foldAngle(twaDeg) / 15, i = Math.min(11, Math.floor(a)), f = a - i;
  return curve[i] * (1 - f) + curve[i + 1] * f;
}

// ------------------------------------------------------------------ route ring (Next mark page)
/**
 * Ring segments for the whole route: one per leg, starting at 12 o'clock, clockwise, proportional to leg length.
 * legsNm: leg lengths; cur: index of the leg being sailed; togoNm: distance to its mark.
 * Returns {segs:[{a0,a1,state}], boatAngle, marks:[angle]} with state "done" | "travelled" | "remaining" | "future".
 */
function routeRing(legsNm, cur, togoNm, gapDeg = 2.5) {
  const n = legsNm.length, total = legsNm.reduce((s, l) => s + l, 0);
  const k = total > 0 ? (360 - n * gapDeg) / total : 0;
  const segs = [], marks = []; let a = gapDeg / 2, boatAngle = 0;
  for (let i = 0; i < n; i++) {
    const a0 = a, a1 = a + legsNm[i] * k;
    if (i < cur) segs.push({ a0, a1, state: "done" });
    else if (i > cur) segs.push({ a0, a1, state: "future" });
    else {
      const done = Math.min(legsNm[i], Math.max(0, legsNm[i] - togoNm));
      const m = a0 + done * k; boatAngle = m;
      if (m > a0) segs.push({ a0, a1: m, state: "travelled" });
      segs.push({ a0: m, a1, state: "remaining" });
    }
    marks.push(a1 + gapDeg / 2);
    a = a1 + gapDeg;
  }
  return { segs, boatAngle, marks };
}

// ------------------------------------------------------------------ next-mark haptics
/**
 * Fires each leg event once: quarters of the leg done, approaching (3 × the reached radius), reached.
 * A new leg (different index) resets it. GPS jitter can't fire an event twice, because each fires only once per leg.
 */
class LegHaptics {
  constructor() { this.leg = -1; this.fired = new Set(); this.legLen = 0; }
  update(legIndex, legLenNm, togoNm) {
    const ev = [];
    if (legIndex !== this.leg) {
      if (this.leg >= 0 && legIndex > this.leg) ev.push("reached");   // moved on to a later leg = mark reached
      this.leg = legIndex; this.legLen = legLenNm; this.fired = new Set();
      // a leg that starts already inside a threshold doesn't fire it
      for (const q of [0.75, 0.5, 0.25]) if (togoNm <= q * legLenNm) this.fired.add("q" + q);
      if (togoNm <= 3 * POINT_REACHED_NM) this.fired.add("approach");
      return ev;
    }
    for (const q of [0.75, 0.5, 0.25]) {
      // short legs (≤ 0.4 nm): quarters are skipped, only approach and reached buzz
      if (this.legLen > 0.4 && togoNm <= q * this.legLen && !this.fired.has("q" + q)) { this.fired.add("q" + q); ev.push("quarter"); }
    }
    if (togoNm <= 3 * POINT_REACHED_NM && !this.fired.has("approach")) { this.fired.add("approach"); ev.push("approach"); }
    return ev.length > 1 ? [ev[ev.length - 1]] : ev;   // never two buzzes at once: keep the most urgent
  }
  finished() { return ["finish"]; }
}
const HAPTIC_PATTERNS = {   // [on ms, off ms, ...]; strength 0..100 on the first item
  quarter: [[60, 150]], approach: [[60, 120], [60, 0]], reached: [[400, 150], [60, 0]],
  finish: [[400, 200], [400, 200], [400, 0]], mob: [[1200, 0]], mark: [[40, 0]],
  hold: [[50, 0]], deleted: [[60, 120], [60, 0]], alarm: [[500, 250], [500, 1200]],
};

// ------------------------------------------------------------------ anchor
/** Local east/north offsets in feet from the anchor (equirectangular: plenty within a few hundred feet). */
function toOffsetFt(anchor, p) {
  const n = (p.lat - anchor.lat) * 60 * FT_PER_NM;
  const e = (p.lon - anchor.lon) * 60 * FT_PER_NM * Math.cos(rad(anchor.lat));
  return [Math.round(e), Math.round(n)];
}
function fromOffsetFt(anchor, dx, dy) {
  return { lat: anchor.lat + dy / (60 * FT_PER_NM), lon: anchor.lon + dx / (60 * FT_PER_NM * Math.cos(rad(anchor.lat))) };
}
/** Pack a trace for ANCHOR_TRACE: flat [dx, dy, ...] chunks of 60 points. */
function packTrace(anchor, pts, perChunk = 60) {
  const flat = pts.map(p => toOffsetFt(anchor, p));
  const chunks = [];
  for (let i = 0; i < flat.length; i += perChunk) chunks.push([].concat(...flat.slice(i, i + perChunk)));
  return chunks.length ? chunks : [[]];
}
/** Distance (ft) and true bearing from the boat TO the anchor, given the boat's offset from the anchor. */
function toAnchor(dx, dy) { return { ft: Math.hypot(dx, dy), brg: norm360(deg(Math.atan2(-dx, -dy))) }; }
/** Where the phone puts the anchor for ANCHOR_SET: aheadFt along the true bearing from its fix. */
function anchorAhead(fix, aheadFt, bearingT) {
  const b = rad(bearingT);
  return { lat: fix.lat + aheadFt * Math.cos(b) / (60 * FT_PER_NM), lon: fix.lon + aheadFt * Math.sin(b) / (60 * FT_PER_NM * Math.cos(rad(fix.lat))) };
}
/** Plot scale (px per ft) so the alarm circle fills rPx, stretching if the boat is outside it. */
const plotScale = (radiusFt, boatFt, rPx) => rPx / Math.max(radiusFt, boatFt * 1.08);

// ------------------------------------------------------------------ scroll drums
const stepOf = v => (v < 100 ? 5 : v < 200 ? 10 : 25);
const drumUp = (v, max) => Math.min(max, v + stepOf(v));
const drumDown = (v, min) => Math.max(min, v - stepOf(v - 1));

// ------------------------------------------------------------------ heading source (MOB arrow, anchor boat)
/**
 * Picks what "up" means: "boat" (GPS course, or the phone compass when mounted), "watch" (watch compass), or "north".
 * Hysteresis on speed: into boat mode above 1.2 kt, out below 0.8 kt, so it doesn't flicker around 1 kt.
 * prev = the mode last time. Returns {mode, upDeg} where upDeg is the true bearing at the top of the screen.
 */
function headingMode(s, prev) {
  const fast = prev === "boat" ? s.sogKn >= 0.8 : s.sogKn >= 1.2;
  if (fast && s.cogDeg != null) return { mode: "boat", upDeg: s.cogDeg };
  if (s.phoneMounted && s.phoneHdg != null) return { mode: "boat", upDeg: s.phoneHdg };
  if (s.watchHdg != null) return { mode: "watch", upDeg: s.watchHdg };
  return { mode: "north", upDeg: 0 };
}
/** Screen angle (clockwise from the top) of a true bearing, for the given "up". */
const screenAngle = (trueDeg, upDeg) => norm360(trueDeg - upDeg);

// ------------------------------------------------------------------ sights
/** Least-squares line through shots with an angle; times in ms, altitudes in arc-minutes. */
function lineFit(shots) {
  const p = shots.filter(s => s.hs != null);
  if (p.length < 2) return null;
  const n = p.length, mt = p.reduce((a, s) => a + s.t, 0) / n, ma = p.reduce((a, s) => a + s.hs, 0) / n;
  let sxx = 0, sxy = 0; for (const s of p) { sxx += (s.t - mt) ** 2; sxy += (s.t - mt) * (s.hs - ma); }
  const k = sxx ? sxy / sxx : 0;
  return { at: t => ma + k * (t - mt), perMin: k * 60000 };
}
/** Residual of one shot against the line through the OTHER shots (needs 3+ shots with angles), in arc-minutes. */
function residual(shots, s) {
  if (s.hs == null) return 0;
  const others = shots.filter(q => q !== s && q.hs != null);
  if (others.length < 2) { const f = lineFit(shots); return f ? s.hs - f.at(s.t) : 0; }
  return s.hs - lineFit(others).at(s.t);
}
const OUTLIER_ARCMIN = 2.5;
const isOutlier = (shots, s) => shots.filter(q => q.hs != null).length >= 3 && Math.abs(residual(shots, s)) > OUTLIER_ARCMIN;
/** Shot id as in PROTOCOL.md: (mark seconds mod 10,000,000) × 10 + body. Fits a 32-bit Number; unique for ~115 days. */
const shotId = (tMs, body) => (Math.floor(tMs / 1000) % 10000000) * 10 + body;
/** Hs arc-minutes to "32°14.6'". */
function fmtHs(am) {
  am = Math.round(am * 10) / 10;            // round first: 59.97' → 33°00.0', never 32°60.0'
  const d = Math.floor(am / 60), m = am - d * 60;
  return d + "°" + m.toFixed(1).padStart(4, "0") + "'";
}

module.exports = {
  EARTH_NM, FT_PER_NM, POINT_REACHED_NM, norm360, angDiff, distNm, bearingDeg, toMagnetic, deg3, vmc, minutesToGo,
  foldAngle, bracket, boatSpeed, curveSpeed, routeRing, LegHaptics, HAPTIC_PATTERNS, toOffsetFt, fromOffsetFt,
  packTrace, toAnchor, anchorAhead, plotScale, stepOf, drumUp, drumDown, headingMode, screenAngle, lineFit, residual,
  OUTLIER_ARCMIN, isOutlier, shotId, fmtHs,
};
