// Run: node --test reference/
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const L = require("./logic.js");
const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg || ""} ${a} vs ${b} (±${tol})`);

test("distance and bearing match known values", () => {
  // Grand Haven to Milwaukee (approx. 75 nm, bearing ~ 279°)
  const gh = { lat: 43.0631, lon: -86.2284 }, mke = { lat: 43.0389, lon: -87.9065 };
  near(L.distNm(gh, mke), 73.6, 0.6, "dist");
  near(L.bearingDeg(gh, mke), 268.9, 1.0, "brg");
  near(L.distNm(gh, gh), 0, 1e-9);
  // one minute of latitude = 1 nm (within the sphere's error)
  near(L.distNm({ lat: 43, lon: -86 }, { lat: 43 + 1 / 60, lon: -86 }), 1.0, 0.001, "1 minute");
  near(L.bearingDeg({ lat: 43, lon: -86 }, { lat: 44, lon: -86 }), 0, 1e-9);
  near(L.bearingDeg({ lat: 43, lon: -86 }, { lat: 43, lon: -85 }), 90, 0.5);
});

test("deg3 and magnetic", () => {
  assert.equal(L.deg3(359.6), "000");
  assert.equal(L.deg3(4.4), "004");
  assert.equal(L.deg3(-1), "359");
  near(L.toMagnetic(72, -4.5), 76.5, 1e-9);   // west variation adds
  near(L.toMagnetic(2, 5), 357, 1e-9);
});

test("VMC and time to go", () => {
  near(L.vmc(6, 47, 72), 6 * Math.cos(25 * Math.PI / 180), 1e-9);
  near(L.vmc(6, 350, 10), 6 * Math.cos(20 * Math.PI / 180), 1e-9, "across north");
  assert.ok(L.vmc(6, 0, 180) < 0);
  near(L.minutesToGo(1.84, 5.6), 19.714, 0.01);
  assert.equal(L.minutesToGo(1, 0), null);
  assert.equal(L.minutesToGo(1, -2), null);
});

test("polar interpolation matches Polars.kt behaviour", () => {
  const p = { tws: [6, 10, 16], twa: [0, 45, 90, 180], speed: [[0, 0, 0], [4, 5.5, 6.5], [5, 6.8, 7.6], [3, 5, 6.5]] };
  near(L.boatSpeed(p, 10, 90), 6.8, 1e-9);
  near(L.boatSpeed(p, 8, 90), 5.9, 1e-9, "between tws");
  near(L.boatSpeed(p, 10, 270), 6.8, 1e-9, "port = starboard");
  near(L.boatSpeed(p, 30, 90), 7.6, 1e-9, "clamped above");
  near(L.boatSpeed(p, 10, 67.5), (5.5 + 6.8) / 2, 1e-9, "between twa");
  const curve = [0, 2, 4, 5, 6, 6.5, 6.8, 6.9, 6.8, 6.6, 6.2, 5.8, 5.4];
  near(L.curveSpeed(curve, 90), 6.8, 1e-9);
  near(L.curveSpeed(curve, 180), 5.4, 1e-9);
  near(L.curveSpeed(curve, 97.5), 6.85, 1e-9);
});

test("route ring geometry", () => {
  const r = L.routeRing([2.1, 3.4, 1.6, 2.9], 1, 1.84);
  const total = r.segs.reduce((s, g) => s + (g.a1 - g.a0), 0);
  near(total, 360 - 4 * 2.5, 1e-9, "arcs + gaps = 360");
  assert.deepEqual(r.segs.map(s => s.state), ["done", "travelled", "remaining", "future", "future"]);
  const k = (360 - 10) / 10;
  near(r.boatAngle, 1.25 + 2.1 * k + 2.5 + (3.4 - 1.84) * k, 1e-9, "boat angle");
  near(r.marks[3], 360 - 1.25 + 0, 1.3, "finish mark near 12 o'clock");
  // togo larger than the leg (boat behind the start) clamps to the leg start
  const r2 = L.routeRing([2, 2], 0, 5);
  assert.deepEqual(r2.segs.map(s => s.state), ["remaining", "future"]);
});

test("leg haptics fire once each, in order", () => {
  const h = new L.LegHaptics(), out = [];
  h.update(1, 3.4, 3.4);
  for (let d = 3.4; d >= 0.11; d -= 0.01) out.push(...h.update(1, 3.4, d).map(e => e + "@" + d.toFixed(2)));
  assert.deepEqual(out.map(e => e.split("@")[0]), ["quarter", "quarter", "quarter", "approach"]);
  // GPS jitter back and forth across a threshold does not re-fire
  assert.deepEqual(h.update(1, 3.4, 0.35), []);
  assert.deepEqual(h.update(1, 3.4, 0.29), []);
  // next leg = reached
  assert.deepEqual(h.update(2, 1.6, 1.6), ["reached"]);
  // short leg: no quarters
  const s = new L.LegHaptics(); s.update(0, 0.35, 0.35);
  const ev = []; for (let d = 0.35; d > 0; d -= 0.01) ev.push(...s.update(0, 0.35, d));
  assert.deepEqual(ev, ["approach"]);
  // a leg that begins already close doesn't buzz approach straight away
  const c = new L.LegHaptics(); assert.deepEqual(c.update(0, 2, 0.2), []); assert.deepEqual(c.update(0, 2, 0.15), []);
});

test("anchor offsets, packing and distance", () => {
  const a = { lat: 43.06, lon: -86.25 };
  const p = L.fromOffsetFt(a, 30, -40);
  assert.deepEqual(L.toOffsetFt(a, p), [30, -40]);
  const t = L.toAnchor(30, -40);
  near(t.ft, 50, 1e-9); near(t.brg, 323.13, 0.01, "boat SE of anchor → anchor bears NW");
  near(L.toAnchor(0, -80).brg, 0, 1e-9, "boat south → anchor north");
  const pts = Array.from({ length: 150 }, (_, i) => L.fromOffsetFt(a, i % 50, -i));
  const ch = L.packTrace(a, pts);
  assert.equal(ch.length, 3); assert.equal(ch[0].length, 120); assert.equal(ch[2].length, 60);
  assert.deepEqual(ch[2].slice(0, 2), [120 % 50, -120]);
  // packed size: 150 points as JSON well under 1 KB per chunk
  assert.ok(JSON.stringify(ch[0]).length < 1024);
  // anchorAhead then back again
  const fix = { lat: 43.06, lon: -86.25 }, an = L.anchorAhead(fix, 60, 30), off = L.toOffsetFt(an, fix);
  near(Math.hypot(...off), 60, 1, "60 ft ahead"); near(L.toAnchor(...off).brg, 30, 1, "on bearing 030");
  near(L.plotScale(100, 62, 150), 1.5, 1e-9); near(L.plotScale(100, 140, 150), 150 / (140 * 1.08), 1e-9);
});

test("scroll drum steps", () => {
  let v = 0; const seen = [];
  for (let i = 0; i < 40; i++) { v = L.drumUp(v, 300); seen.push(v); }
  assert.deepEqual(seen.slice(0, 22), [5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 110, 120]);
  assert.equal(seen[seen.length - 1], 300);
  // down retraces the same values
  const down = []; for (let i = 0; i < 40; i++) { v = L.drumDown(v, 0); down.push(v); }
  assert.ok(down.includes(200) && down.includes(100) && down.includes(95)); assert.equal(down[down.length - 1], 0);
  assert.equal(L.drumDown(225, 0), 200); assert.equal(L.drumDown(110, 0), 100); assert.equal(L.drumDown(100, 0), 95);
});

test("heading mode with hysteresis", () => {
  let m = L.headingMode({ sogKn: 3, cogDeg: 40 }, "north"); assert.equal(m.mode, "boat"); assert.equal(m.upDeg, 40);
  m = L.headingMode({ sogKn: 1.0, cogDeg: 40, watchHdg: 100 }, "boat"); assert.equal(m.mode, "boat", "stays boat at 1.0");
  m = L.headingMode({ sogKn: 0.7, cogDeg: 40, watchHdg: 100 }, "boat"); assert.equal(m.mode, "watch");
  m = L.headingMode({ sogKn: 1.0, cogDeg: 40, watchHdg: 100 }, "watch"); assert.equal(m.mode, "watch", "not back until 1.2");
  m = L.headingMode({ sogKn: 0.2, phoneMounted: true, phoneHdg: 15, watchHdg: 100 }, "watch"); assert.equal(m.mode, "boat");
  m = L.headingMode({ sogKn: 0.2 }, "watch"); assert.deepEqual(m, { mode: "north", upDeg: 0 });
  near(L.screenAngle(214, 34), 180, 1e-9); near(L.screenAngle(10, 350), 20, 1e-9);
});

test("sight trend, outliers, ids", () => {
  const t0 = 1_763_700_000_000;
  const sh = [[0, 1930.2], [40, 1936.0], [85, 1943.1], [120, 1951.0]].map(([s, a]) => ({ t: t0 + s * 1000, hs: a }));
  const f = L.lineFit(sh.slice(0, 3));
  near(f.perMin, 9.11, 0.01, "rate ′/min");
  assert.equal(L.isOutlier(sh, sh[3]), true, "last shot is off the trend");
  assert.equal(L.isOutlier(sh, sh[1]), false);
  near(L.residual(sh, sh[3]), 1951.0 - f.at(sh[3].t), 1e-9);
  assert.equal(L.isOutlier(sh.slice(0, 2), sh[1]), false, "two shots never flagged");
  const withBlank = sh.concat([{ t: t0 + 150000, hs: null }]);
  assert.equal(L.isOutlier(withBlank, withBlank[4]), false, "time-only shot never flagged");
  assert.equal(L.shotId(t0 + 999, 2), (Math.floor(t0 / 1000) % 10000000) * 10 + 2); assert.ok(L.shotId(t0, 3) < 2 ** 31);
  assert.equal(L.fmtHs(1934.6), "32°14.6'"); assert.equal(L.fmtHs(1925.06), "32°05.1'"); assert.equal(L.fmtHs(1979.97), "33°00.0'");
});
