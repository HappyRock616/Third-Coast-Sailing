import Toybox.Lang;
import Toybox.Math;
import Toybox.StringUtil;

// Wire format of the direct Bluetooth link (PROTOCOL.md section 6). Line-for-line port of reference/wire.js; the
// test vectors in reference/wire.test.js define the exact bytes.
module Wire {
    const T_NULL = 0; const T_FALSE = 1; const T_TRUE = 2; const T_I8 = 3; const T_I16 = 4; const T_I32 = 5;
    const T_STR = 6; const T_ARR = 7;
    const CHUNK = 20; const FIRST = 0x80; const LAST = 0x40;
    const COMPANY = 0xFFFF;

    var pos as Number = 0;          // decode cursor

    function encode(v as Object?) as ByteArray {
        var out = []b;
        enc(v, out);
        return out;
    }

    function enc(v as Object?, out as ByteArray) as Void {
        if (v == null) { out.add(T_NULL); return; }
        if (v instanceof Boolean) { out.add(v ? T_TRUE : T_FALSE); return; }
        if (v instanceof Float || v instanceof Double) { v = Math.round(v).toNumber(); }
        if (v instanceof Long) { v = v.toNumber(); }
        if (v instanceof Number) {
            var n = v as Number;
            if (n >= -128 && n <= 127) { out.add(T_I8); out.add(n & 0xFF); }
            else if (n >= -32768 && n <= 32767) { out.add(T_I16); out.add(n & 0xFF); out.add((n >> 8) & 0xFF); }
            else { out.add(T_I32); out.add(n & 0xFF); out.add((n >> 8) & 0xFF); out.add((n >> 16) & 0xFF); out.add((n >> 24) & 0xFF); }
            return;
        }
        if (v instanceof String) {
            var b = (v as String).toUtf8Array();
            var len = b.size() > 255 ? 255 : b.size();
            out.add(T_STR); out.add(len);
            for (var i = 0; i < len; i++) { out.add(b[i]); }
            return;
        }
        if (v instanceof Array) {
            var a = v as Array;
            var len = a.size() > 255 ? 255 : a.size();
            out.add(T_ARR); out.add(len);
            for (var i = 0; i < len; i++) { enc(a[i], out); }
            return;
        }
        out.add(T_NULL);            // unsupported type: sent as null
    }

    // Decodes one value; null on malformed input (the caller treats a non-Array as "not a message")
    function decode(b as ByteArray) as Object? {
        pos = 0;
        var v = val(b);
        return pos == b.size() ? v : null;
    }

    function u8(b as ByteArray) as Number {
        if (pos >= b.size()) { pos = b.size() + 1; return 0; }
        var x = b[pos] as Number; pos += 1; return x;
    }

    function val(b as ByteArray) as Object? {
        var t = u8(b);
        if (t == T_NULL) { return null; }
        if (t == T_FALSE) { return false; }
        if (t == T_TRUE) { return true; }
        if (t == T_I8) { var x = u8(b); return x > 127 ? x - 256 : x; }
        if (t == T_I16) { var x = u8(b) | (u8(b) << 8); return x > 32767 ? x - 65536 : x; }
        if (t == T_I32) { return u8(b) | (u8(b) << 8) | (u8(b) << 16) | (u8(b) << 24); }
        if (t == T_STR) {
            var n = u8(b);
            if (pos + n > b.size()) { pos = b.size() + 1; return null; }
            var s = str(b, pos, pos + n);
            pos += n;
            return s;
        }
        if (t == T_ARR) {
            var n = u8(b), a = [];
            for (var i = 0; i < n && pos <= b.size(); i++) { a.add(val(b)); }
            return a;
        }
        pos = b.size() + 1;         // bad tag
        return null;
    }

    // UTF-8 bytes [from, to) to a String
    function str(b as ByteArray, from as Number, to as Number) as String {
        var a = [] as Array<Number>;
        for (var i = from; i < to; i++) { a.add(b[i] as Number); }
        var s = StringUtil.utf8ArrayToString(a);
        return s == null ? "" : s as String;
    }

    // Splits an encoded message into chunks of at most CHUNK bytes, each starting with the header byte
    function frame(m as ByteArray, counter as Number) as Array<ByteArray> {
        var pay = CHUNK - 1, out = [] as Array<ByteArray>;
        var n = (m.size() + pay - 1) / pay;
        if (n < 1) { n = 1; }
        for (var k = 0; k < n; k++) {
            var h = (k == 0 ? FIRST : 0) | (k == n - 1 ? LAST : 0) | (counter & 0x3F);
            var c = [h]b;
            var end = (k + 1) * pay > m.size() ? m.size() : (k + 1) * pay;
            if (end > k * pay) { c.addAll(m.slice(k * pay, end)); }
            out.add(c);
        }
        return out;
    }

    class Reassembler {
        var buf as ByteArray? = null;
        var id as Number = -1;
        function initialize() {}
        // Returns the decoded message when the last chunk arrives, else null. A broken sequence is dropped.
        function push(c as ByteArray) as Object? {
            if (c.size() < 1) { return null; }
            var h = c[0] as Number, i = h & 0x3F;
            if ((h & FIRST) != 0) { buf = []b; id = i; }
            else if (buf == null || i != id) { buf = null; return null; }
            if (c.size() > 1) { (buf as ByteArray).addAll(c.slice(1, null)); }
            if ((h & LAST) != 0) {
                var b = buf as ByteArray;
                buf = null;
                return decode(b);
            }
            return null;
        }
    }

    // TCS beacon (manufacturer data, company 0xFFFF): 'T' 'C' 1 boatId(int32 LE) name(UTF-8 <= 14 bytes).
    // Returns [boatId, label] or null. Accepts the data with the 2 company-id bytes (FF FF) in front too: Garmin's
    // docs don't say which form getManufacturerSpecificData() returns.
    function parseAdvert(b as ByteArray?) as Array? {
        if (b == null) { return null; }
        var o = (b.size() >= 9 && b[0] == 0xFF && b[1] == 0xFF && b[2] == 0x54) ? 2 : 0;
        if (b.size() < o + 7 || b[o] != 0x54 || b[o + 1] != 0x43 || b[o + 2] != 1) { return null; }
        var id = (b[o + 3] as Number) | ((b[o + 4] as Number) << 8) | ((b[o + 5] as Number) << 16) | ((b[o + 6] as Number) << 24);
        var name = b.size() > o + 7 ? str(b, o + 7, b.size()) : "";
        if (name == null || (name as String).length() == 0) { name = "TCS " + (id & 0xFFFF).format("%04X"); }
        return [id, name];
    }

    function bars(rssi as Number) as Number { return rssi >= -60 ? 3 : (rssi >= -75 ? 2 : (rssi >= -90 ? 1 : 0)); }
}
