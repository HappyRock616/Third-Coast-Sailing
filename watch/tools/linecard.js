// Line card: every simulator tour screenshot of one device on one page, numbered for HappyRock's notes.
// node tools/linecard.js <device> <label>   -> docs/linecard/linecard_<label>.html (render to PNG with Edge)
const fs = require("fs");
const dev = process.argv[2] || "vivoactive5", label = process.argv[3] || "v0.1.1";
const dir = `docs/sim/${dev}`;
const files = fs.readdirSync(dir).filter(f => /^\d\d-.*\.png$/.test(f)).sort();
const desc = { "gauge": "Gauge", "gauge-outside": "Gauge · outside forecast area", "gauge-slow": "Gauge · too slow (no SOG/COG)",
  "mark": "Next mark", "mark-approach": "Next mark · under 30 s (ring blinks)", "anchor": "Anchor · fix good",
  "anchor-stale": "Anchor · fix stale", "anchor-lost": "Anchor · fix lost (alarm)", "anchor-alarm": "Anchor · ALARM",
  "set-anchor": "Set anchor · 0 ft (drop here)", "set-anchor-off": "Set anchor · anchor not set",
  "dial-distance": "Dial · distance", "dial-radius": "Dial · alarm radius", "sight": "Sight · MARK",
  "sight-angle": "Sight · enter Hs", "sight-review": "Sight · review shots", "mob": "MOB", "mob-boat-up": "MOB · boat up",
  "mob-watch-up": "MOB · watch up", "mob-north-up": "MOB · north up", "link-connected": "Link · connected",
  "link-wait": "Link · waiting for Allow", "link-offline": "Link · can't reach TCS", "link-timedout": "Link · no answer (3 min)",
  "paused": "Paused (10 min idle)" };
const cells = files.map(f => {
  const n = f.slice(0, 2), k = f.slice(3, -4), b64 = fs.readFileSync(`${dir}/${f}`).toString("base64");
  return `<div class=c><div class=w><img src="data:image/png;base64,${b64}"></div><div class=l><b>${n}</b> ${desc[k] || k}</div></div>`;
}).join("");
// screen area in each simulator window screenshot: [x, y, w, h, window width]
const CROP = { vivoactive5: [84, 262, 436, 436, 604], fr55: [92, 188, 226, 226, 410], fenix6s: [52, 172, 256, 256, 358],
  venusq2: [70, 245, 450, 470, 591], edge1040: [58, 148, 298, 482, 411], gpsmap67: [62, 96, 290, 512, 410] };
const c = CROP[dev] || [0, 0, 600, 1000, 600], sc = Math.min(320 / c[2], 320 / c[3]);
const imgCss = `.w img{position:absolute;left:${Math.round(-c[0] * sc + (320 - c[2] * sc) / 2)}px;top:${Math.round(-c[1] * sc + (320 - c[3] * sc) / 2)}px;width:${Math.round(c[4] * sc)}px}`;
const html = `<!doctype html><meta charset=utf-8><style>body{margin:0;background:#1b1d21;font-family:Segoe UI,Arial;color:#eee;width:2040px}
h1{font-size:30px;margin:24px 30px 6px}p{margin:0 30px 18px;color:#9ab;font-size:18px}
.g{display:grid;grid-template-columns:repeat(6,320px);gap:16px;padding:0 30px 30px}
.c{background:#000;border-radius:14px;padding:10px 0 12px;text-align:center}
.w{width:320px;height:320px;overflow:hidden;position:relative;margin:auto}
${imgCss}
.l{font-size:17px;margin-top:6px}.l b{color:#F28C28;margin-right:6px}</style>
<h1>TCS Watch Companion ${label} · screen line card</h1><p>${dev} simulator, demo data · ${new Date().toISOString().slice(0, 10)} · numbers for your notes</p><div class=g>${cells}</div>`;
fs.mkdirSync("docs/linecard", { recursive: true });
fs.writeFileSync(`docs/linecard/linecard_${label}.html`, html);
console.log(files.length, "screens", Math.ceil(files.length / 6), "rows");
