// Survey every Connect IQ device the installed SDK knows, for the TCS companion's compatibility report.
// Usage: node tools/device_survey.js [out.csv]   (reads %APPDATA%/Garmin/ConnectIQ/Devices, read-only)
const fs = require("fs");
const path = require("path");
const dir = path.join(process.env.APPDATA, "Garmin", "ConnectIQ", "Devices");
const out = process.argv[2];
const rows = [];
for (const id of fs.readdirSync(dir).sort()) {
  let c, s;
  try {
    c = JSON.parse(fs.readFileSync(path.join(dir, id, "compiler.json"), "utf8"));
    s = JSON.parse(fs.readFileSync(path.join(dir, id, "simulator.json"), "utf8"));
  } catch (e) { continue; }
  const app = (c.appTypes || []).find(a => a.type === "watchApp");
  const vers = (c.partNumbers || []).map(p => p.connectIQVersion).filter(Boolean);
  const cmp = (a, b) => { const x = a.split(".").map(Number), y = b.split(".").map(Number); for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0); return 0; };
  vers.sort(cmp);
  const keys = (s.keys || []).filter(k => !k.isHold).map(k => k.id);
  const d = s.display || {};
  rows.push({
    id, name: (c.displayName || id).replace(/®|™/g, ""), family: c.deviceFamily || "",
    shape: d.shape || "", w: (c.resolution || {}).width || d.location?.width, h: (c.resolution || {}).height || d.location?.height,
    display: c.displayType || "", bpp: c.bitsPerPixel || "", touch: !!d.isTouch,
    buttons: keys.length, keyIds: keys.join(" "),
    ciqMin: vers[0] || "", ciqMax: vers[vers.length - 1] || "",
    memKB: app ? Math.round(app.memoryLimit / 1024) : 0,
    group: c.deviceGroup || "",
    subscreen: !!(d.subscreen || c.subscreen || s.subscreen),
  });
}
const cols = Object.keys(rows[0]);
const csv = [cols.join(",")].concat(rows.map(r => cols.map(k => `"${String(r[k]).replace(/"/g, "'")}"`).join(","))).join("\n");
if (out) fs.writeFileSync(out, csv);
console.log(JSON.stringify(rows));
