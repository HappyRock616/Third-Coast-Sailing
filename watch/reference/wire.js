// TCS direct Bluetooth link: wire format (docs/PROTOCOL.md section "Direct Bluetooth link").
// Reference implementation, tested in reference/wire.test.js; ported to Monkey C (source/Wire.mc) and to Kotlin
// on the phone. Both must produce the exact bytes of the test vectors.
"use strict";

// ---- value encoding: tag byte, then the value (little-endian)
const T_NULL = 0, T_FALSE = 1, T_TRUE = 2, T_I8 = 3, T_I16 = 4, T_I32 = 5, T_STR = 6, T_ARR = 7;

function encode(v, out = []) {
  if (v === null || v === undefined) { out.push(T_NULL); }
  else if (v === false) { out.push(T_FALSE); }
  else if (v === true) { out.push(T_TRUE); }
  else if (typeof v === "number") {
    const n = Math.round(v);                         // floats are never sent; round defensively
    if (n >= -128 && n <= 127) { out.push(T_I8, n & 0xff); }
    else if (n >= -32768 && n <= 32767) { out.push(T_I16, n & 0xff, (n >> 8) & 0xff); }
    else { out.push(T_I32, n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff); }
  } else if (typeof v === "string") {
    const b = Buffer.from(v, "utf8");
    if (b.length > 255) throw new Error("string too long");
    out.push(T_STR, b.length, ...b);
  } else if (Array.isArray(v)) {
    if (v.length > 255) throw new Error("array too long");
    out.push(T_ARR, v.length);
    for (const x of v) encode(x, out);
  } else throw new Error("unsupported value " + v);
  return out;
}

function decode(bytes) {
  let i = 0;
  const s8 = b => (b << 24) >> 24;
  function val() {
    const t = bytes[i++];
    switch (t) {
      case T_NULL: return null;
      case T_FALSE: return false;
      case T_TRUE: return true;
      case T_I8: return s8(bytes[i++]);
      case T_I16: { const n = bytes[i] | (bytes[i + 1] << 8); i += 2; return (n << 16) >> 16; }
      case T_I32: { const n = bytes[i] | (bytes[i + 1] << 8) | (bytes[i + 2] << 16) | (bytes[i + 3] << 24); i += 4; return n; }
      case T_STR: { const n = bytes[i++]; const s = Buffer.from(bytes.slice(i, i + n)).toString("utf8"); i += n; return s; }
      case T_ARR: { const n = bytes[i++]; const a = []; for (let k = 0; k < n; k++) a.push(val()); return a; }
      default: throw new Error("bad tag " + t + " at " + (i - 1));
    }
  }
  const v = val();
  if (i !== bytes.length) throw new Error("trailing bytes");
  return v;
}

// ---- framing: each GATT write / notification is at most CHUNK bytes; byte 0 is a header
//   bit 7 = first chunk of a message, bit 6 = last chunk, bits 0-5 = message counter (0-63, rolling)
const CHUNK = 20, FIRST = 0x80, LAST = 0x40;

function frame(msgBytes, counter) {
  const pay = CHUNK - 1, out = [];
  const n = Math.max(1, Math.ceil(msgBytes.length / pay));
  for (let k = 0; k < n; k++) {
    const h = (k === 0 ? FIRST : 0) | (k === n - 1 ? LAST : 0) | (counter & 0x3f);
    out.push([h, ...msgBytes.slice(k * pay, (k + 1) * pay)]);
  }
  return out;
}

/** Collects chunks; push() returns the decoded message when one completes, else null. Bad sequences are dropped. */
class Reassembler {
  constructor() { this.buf = null; this.id = -1; }
  push(chunk) {
    const h = chunk[0], id = h & 0x3f;
    if (h & FIRST) { this.buf = []; this.id = id; }
    else if (this.buf === null || id !== this.id) { this.buf = null; return null; }
    this.buf.push(...chunk.slice(1));
    if (h & LAST) {
      const b = this.buf; this.buf = null;
      try { return decode(b); } catch (e) { return null; }
    }
    return null;
  }
}

// ---- advertising (phone -> watch discovery): manufacturer-specific data, company id 0xFFFF (test/unregistered)
//   bytes: 'T' 'C' 0x01(version) boatId(uint32 LE) name(UTF-8, up to 14 bytes)
const COMPANY = 0xffff, AD_MAGIC = [0x54, 0x43], AD_VER = 1, NAME_MAX = 14;

function advert(boatId, name) {
  let nb = Buffer.from(name || "", "utf8");
  while (nb.length > NAME_MAX) { name = name.slice(0, -1); nb = Buffer.from(name, "utf8"); }   // whole characters only
  return [...AD_MAGIC, AD_VER, boatId & 0xff, (boatId >> 8) & 0xff, (boatId >> 16) & 0xff, (boatId >>> 24) & 0xff, ...nb];
}

/** Parses the manufacturer data; null if it isn't a TCS beacon. Label falls back to "TCS XXXX" (boat id hex).
 *  Accepts the data with or without the 2 company-id bytes in front (FF FF), since scanners differ. */
function parseAdvert(b) {
  if (!b) return null;
  if (b.length >= 9 && b[0] === 0xff && b[1] === 0xff && b[2] === 0x54) b = b.slice(2);
  if (b.length < 7 || b[0] !== 0x54 || b[1] !== 0x43 || b[2] !== AD_VER) return null;
  const boatId = b[3] | (b[4] << 8) | (b[5] << 16) | (b[6] << 24);       // signed 32-bit, as on the watch
  let name = Buffer.from(b.slice(7)).toString("utf8");
  if (!name) name = "TCS " + (boatId & 0xffff).toString(16).toUpperCase().padStart(4, "0");
  return { boatId, name };
}

/** RSSI to 0-3 signal bars on the picker. */
const bars = rssi => rssi >= -60 ? 3 : rssi >= -75 ? 2 : rssi >= -90 ? 1 : 0;

// ---- pairing proof (PROTOCOL 8.4): the token never travels after pairing
//   token = 4 int32 words (128 bits), nonce = 2 int32 words from the phone, fresh per connection.
//   proof = first 8 bytes of HMAC-SHA256(key = token as 16 bytes LE, msg = nonce (8 bytes LE) + watchId (4 bytes LE)),
//   sent as 2 int32 words (LE, signed).
const le32 = n => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff];
const word = (b, o) => b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24);
function proof(token, nonce, watchId) {
  const crypto = require("crypto");
  const key = Buffer.from(token.flatMap(le32));
  const msg = Buffer.from([...nonce.flatMap(le32), ...le32(watchId)]);
  const h = crypto.createHmac("sha256", key).update(msg).digest();
  return [word(h, 0), word(h, 4)];
}

/** Section 9.3: auth for a request or push = first 8 bytes of HMAC-SHA256(token, nonce + watchId + n), n = the seq of
 *  the (first) message, or the poll counter "c" for an empty request. All words little-endian. */
function auth(token, nonce, watchId, n) {
  const crypto = require("crypto");
  const key = Buffer.from(token.flatMap(le32));
  const msg = Buffer.from([...nonce.flatMap(le32), ...le32(watchId), ...le32(n)]);
  const h = crypto.createHmac("sha256", key).update(msg).digest();
  return [word(h, 0), word(h, 4)];
}

const hex = a => a.map(x => x.toString(16).padStart(2, "0")).join("");

module.exports = { encode, decode, frame, Reassembler, CHUNK, advert, parseAdvert, COMPANY, NAME_MAX, bars, proof, auth, hex };
