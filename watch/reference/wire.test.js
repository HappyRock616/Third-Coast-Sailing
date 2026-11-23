// Run: node --test reference/
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const W = require("./wire.js");

// Test vectors shared with the watch (source/Wire.mc) and the phone (Kotlin). Change only together.
const HELLO = [1, 101, 7, 1, "006-B4426-00", 19, 1795000000, -123456789, false, 4821];
// HMAC vector cross-checked against .NET HMACSHA256
const PROOF_VECTOR = [2097231789, 341873637];
const HELLO_HEX = "07 0a 03 01 03 65 03 07 03 01 06 0c 3030362d42343432362d3030 03 13 05 c086fd6a 05 eb32a4f8 01 04 d512".replace(/ /g, "");

test("value encoding matches the vector and round-trips", () => {
  const b = W.encode(HELLO);
  assert.equal(W.hex(b), HELLO_HEX);
  assert.deepEqual(W.decode(b), HELLO);
});

test("integer sizes and edges", () => {
  for (const n of [0, 1, -1, 127, -128, 128, -129, 32767, -32768, 32768, -32769, 2147483647, -2147483648]) {
    assert.equal(W.decode(W.encode(n)), n, String(n));
  }
  assert.equal(W.encode(127).length, 2);
  assert.equal(W.encode(128).length, 3);
  assert.equal(W.encode(32768).length, 5);
  assert.equal(W.decode(W.encode(12.6)), 13, "floats are rounded");
});

test("nested arrays, strings, booleans", () => {
  const route = [1, 6, 300, 2, [[43063100, -86228400], [43038900, -87906500]]];
  assert.deepEqual(W.decode(W.encode(route)), route);
  assert.deepEqual(W.decode(W.encode([true, false, null, "Þriðji"])), [true, false, null, "Þriðji"]);
  assert.throws(() => W.decode([7, 2, 3, 1]), /trailing|bad|undefined/);
});

test("framing splits into 20-byte chunks and reassembles", () => {
  const msg = W.encode(HELLO);                       // 41 bytes -> 3 chunks
  const ch = W.frame(msg, 5);
  assert.equal(ch.length, 3);
  assert.ok(ch.every(c => c.length <= W.CHUNK));
  assert.equal(ch[0][0], 0x85); assert.equal(ch[1][0], 0x05); assert.equal(ch[2][0], 0x45);
  const r = new W.Reassembler();
  assert.equal(r.push(ch[0]), null);
  assert.equal(r.push(ch[1]), null);
  assert.deepEqual(r.push(ch[2]), HELLO);
  // single chunk: first + last
  const one = W.frame(W.encode([1, 13, 9, 5, 0]), 63);
  assert.equal(one.length, 1); assert.equal(one[0][0], 0xff);
  assert.deepEqual(r.push(one[0]), [1, 13, 9, 5, 0]);
  // a lost middle chunk drops the message, and the next one still arrives
  assert.equal(r.push(ch[0]), null);
  assert.equal(r.push(ch[2]), null);
  const ch2 = W.frame(msg, 6);
  r.push(ch2[0]); r.push(ch2[1]);
  assert.deepEqual(r.push(ch2[2]), HELLO);
});

test("advertising data", () => {
  const ad = W.advert(0x7f3a1c02, "Third Wind");
  assert.equal(W.hex(ad), "544301021c3a7f" + Buffer.from("Third Wind").toString("hex"));
  assert.deepEqual(W.parseAdvert(ad), { boatId: 0x7f3a1c02, name: "Third Wind" });
  assert.deepEqual(W.parseAdvert(W.advert(0x12347f3a, "")), { boatId: 0x12347f3a, name: "TCS 7F3A" });
  assert.equal(W.advert(1, "A very long boat name").length, 7 + 14);
  assert.equal(W.parseAdvert([0x54, 0x43, 2, 0, 0, 0, 0]), null, "unknown version");
  assert.deepEqual(W.parseAdvert([0xff, 0xff, ...ad]), { boatId: 0x7f3a1c02, name: "Third Wind" }, "with company id in front");
  assert.equal(W.bars(-55), 3); assert.equal(W.bars(-80), 1); assert.equal(W.bars(-95), 0);
});

test("pairing proof (HMAC-SHA256, first 8 bytes as two int32)", () => {
  const token = [0x01020304, -2, 0x7fffffff, 12345], nonce = [0x11223344, -559038737], watchId = 987654321;
  const p = W.proof(token, nonce, watchId);
  assert.equal(p.length, 2);
  assert.deepEqual(p, PROOF_VECTOR);
  assert.notDeepEqual(W.proof(token, [nonce[0] + 1, nonce[1]], watchId), p, "a new nonce gives a new proof");
  assert.notDeepEqual(W.proof([1, 2, 3, 4], nonce, watchId), p, "another token gives another proof");
});

test("section 9.3 request / push auth (HMAC-SHA256 over nonce + watchId + n)", () => {
  // vector cross-checked against .NET HMACSHA256
  const token = [0x01020304, -2, 0x7fffffff, 12345], nonce = [0x11223344, -559038737], watchId = 987654321;
  assert.deepEqual(W.auth(token, nonce, watchId, 4242), [-1417096384, -436526076]);
  assert.notDeepEqual(W.auth(token, nonce, watchId, 4243), W.auth(token, nonce, watchId, 4242), "n changes it");
});
