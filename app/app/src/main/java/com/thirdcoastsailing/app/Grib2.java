package com.thirdcoastsailing.app;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Minimal GRIB2 reader for the NOAA weather files Third Coast Sailing uses (no Google services, no native code).
 * Supports exactly what NDFD sector files and the GLWU wave model use:
 *   grid template 3.30 (Lambert conformal), product templates 4.0 / 4.8,
 *   data templates 5.0 (simple packing), 5.2 (complex packing) and 5.3 (complex + spatial differencing),
 *   with or without a bitmap. Missing points come back as NaN.
 * index() is cheap (headers only); decode() unpacks one message on demand.
 */
public final class Grib2 {

    /** One GRIB2 message: what it is, when it is valid, its grid, and where its packed data lives. */
    public static final class Message {
        public int discipline, category, number;    // parameter (e.g. 0/2/1 wind speed)
        public long refTimeMs, validTimeMs;
        public Lambert grid;
        int sec5, sec6, sec7, sec7len;               // byte offsets into the file
        int bitmapOffset = -1;                       // start of bitmap bits, -1 when every point has data
        int numPacked;                               // data points actually packed (= bitmap ones)
    }

    /** Lambert conformal conic grid (spherical earth), point (i, j) with i east and j north from the first point. */
    public static final class Lambert {
        public int nx, ny;
        double la1, lo1, lov, latin1, latin2, dx, dy, radius;
        boolean jNorth = true, iEast = true, alternateRows = false;   // scanning mode bits 0x80, 0x40, 0x10
        private double n, f, x1, y1;

        void init() {
            double p1 = Math.toRadians(latin1), p2 = Math.toRadians(latin2);
            n = Math.abs(latin1 - latin2) < 1e-9 ? Math.sin(p1)
                    : Math.log(Math.cos(p1) / Math.cos(p2)) /
                      Math.log(Math.tan(Math.PI / 4 + p2 / 2) / Math.tan(Math.PI / 4 + p1 / 2));
            f = Math.cos(p1) * Math.pow(Math.tan(Math.PI / 4 + p1 / 2), n) / n;
            double[] xy = xy(la1, lo1);
            x1 = xy[0]; y1 = xy[1];
        }

        private double[] xy(double lat, double lon) {
            double rho = radius * f / Math.pow(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2), n);
            double dl = lon - lov;
            while (dl > 180) dl -= 360;
            while (dl < -180) dl += 360;
            double t = n * Math.toRadians(dl);
            return new double[]{rho * Math.sin(t), -rho * Math.cos(t)};
        }

        /** Fractional grid position {i, j} of a lat/lon (i east, j north from the first grid point). */
        public double[] toGrid(double lat, double lon) {
            double[] p = xy(lat, lon);
            return new double[]{(p[0] - x1) / dx, (p[1] - y1) / dy};
        }

        /** Index into the decoded array for whole grid position (i east, j north), or -1 outside. */
        public int index(int i, int j) {
            if (i < 0 || j < 0 || i >= nx || j >= ny) return -1;
            int ii = iEast ? i : nx - 1 - i;
            int jj = jNorth ? j : ny - 1 - j;
            if (alternateRows && (jj & 1) == 1) ii = nx - 1 - ii;     // every other row stored in reverse
            return jj * nx + ii;
        }

        /** Bilinear sample of a decoded field; NaN outside the grid or next to missing points. */
        public float sample(float[] v, double lat, double lon) {
            double[] g = toGrid(lat, lon);
            int i0 = (int) Math.floor(g[0]), j0 = (int) Math.floor(g[1]);
            double fx = g[0] - i0, fy = g[1] - j0;
            int a = index(i0, j0), b = index(i0 + 1, j0), c = index(i0, j0 + 1), d = index(i0 + 1, j0 + 1);
            if (a < 0 || b < 0 || c < 0 || d < 0) return Float.NaN;
            return (float) ((v[a] * (1 - fx) + v[b] * fx) * (1 - fy) + (v[c] * (1 - fx) + v[d] * fx) * fy);
        }

        /** Nearest grid point value (for directions, where averaging 350 and 10 would give 180); NaN outside. */
        public float sampleNearest(float[] v, double lat, double lon) {
            double[] g = toGrid(lat, lon);
            int k = index((int) Math.round(g[0]), (int) Math.round(g[1]));
            return k < 0 ? Float.NaN : v[k];
        }
    }

    // ------------------------------------------------------------------ indexing

    public static List<Message> index(byte[] b) {
        List<Message> out = new ArrayList<>();
        int pos = 0;
        while (true) {
            int start = find(b, pos);
            if (start < 0 || start + 16 > b.length) break;
            int discipline = b[start + 6] & 0xFF;
            long total = u64(b, start + 8);
            int end = (int) Math.min(b.length, start + total);
            int p = start + 16;
            long ref = 0;
            Lambert grid = null;
            Message pending = null;
            int bitmapPrev = -1;
            while (p + 5 <= end) {
                if (b[p] == '7' && b[p + 1] == '7' && b[p + 2] == '7' && b[p + 3] == '7') break;
                int len = (int) u32(b, p);
                int sec = b[p + 4] & 0xFF;
                if (len <= 0) break;
                switch (sec) {
                    case 1:
                        ref = utc(u16(b, p + 12), b[p + 14] & 0xFF, b[p + 15] & 0xFF, b[p + 16] & 0xFF,
                                b[p + 17] & 0xFF, b[p + 18] & 0xFF);
                        break;
                    case 3:
                        grid = lambert(b, p);
                        break;
                    case 4:
                        pending = new Message();
                        pending.discipline = discipline;
                        pending.refTimeMs = ref;
                        pending.grid = grid;
                        product(b, p, pending);
                        break;
                    case 5:
                        if (pending != null) { pending.sec5 = p; pending.numPacked = (int) u32(b, p + 5); }
                        break;
                    case 6:
                        if (pending != null) {
                            int ind = b[p + 5] & 0xFF;
                            if (ind == 0) { pending.bitmapOffset = p + 6; bitmapPrev = p + 6; }
                            else if (ind == 254) pending.bitmapOffset = bitmapPrev;
                            else pending.bitmapOffset = -1;
                            pending.sec6 = p;
                        }
                        break;
                    case 7:
                        if (pending != null && pending.grid != null) {
                            pending.sec7 = p; pending.sec7len = len;
                            out.add(pending);
                        }
                        pending = null;
                        break;
                    default:
                        break;
                }
                p += len;
            }
            pos = end;
        }
        return out;
    }

    /** Read a grid definition section; only template 3.30 (Lambert conformal, the NDFD and GLWU grids) is supported, otherwise null. */
    private static Lambert lambert(byte[] b, int p) {
        int tmpl = u16(b, p + 12);
        if (tmpl != 30) return null;                       // only Lambert conformal is needed
        Lambert g = new Lambert();
        int shape = b[p + 14] & 0xFF;
        if (shape == 1) g.radius = u32(b, p + 16) / Math.pow(10, b[p + 15] & 0xFF);
        else if (shape == 6) g.radius = 6371229.0;
        else g.radius = 6367470.0;
        g.nx = (int) u32(b, p + 30);
        g.ny = (int) u32(b, p + 34);
        g.la1 = s32(b, p + 38) * 1e-6;
        g.lo1 = lon180(u32(b, p + 42) * 1e-6);
        g.lov = lon180(u32(b, p + 51) * 1e-6);
        g.dx = u32(b, p + 55) * 1e-3;
        g.dy = u32(b, p + 59) * 1e-3;
        int scan = b[p + 64] & 0xFF;
        g.iEast = (scan & 0x80) == 0;
        g.jNorth = (scan & 0x40) != 0;
        g.alternateRows = (scan & 0x10) != 0;
        g.latin1 = s32(b, p + 65) * 1e-6;
        g.latin2 = s32(b, p + 69) * 1e-6;
        g.init();
        return g;
    }

    /** Read a product definition section: the quantity (category, number) and its valid time. */
    private static void product(byte[] b, int p, Message m) {
        int tmpl = u16(b, p + 7);
        m.category = b[p + 9] & 0xFF;
        m.number = b[p + 10] & 0xFF;
        int unit = b[p + 17] & 0xFF;
        long ft = s32(b, p + 18);
        long unitMs;
        switch (unit) {
            case 0: unitMs = 60_000L; break;            // minute
            case 1: unitMs = 3_600_000L; break;         // hour
            case 2: unitMs = 86_400_000L; break;        // day
            case 10: unitMs = 3 * 3_600_000L; break;
            case 11: unitMs = 6 * 3_600_000L; break;
            case 12: unitMs = 12 * 3_600_000L; break;
            case 13: unitMs = 1_000L; break;            // second
            default: unitMs = 3_600_000L;
        }
        m.validTimeMs = m.refTimeMs + ft * unitMs;
        if (tmpl == 8) {                                // statistical interval: valid at the end of the interval
            m.validTimeMs = utc(u16(b, p + 34), b[p + 36] & 0xFF, b[p + 37] & 0xFF, b[p + 38] & 0xFF,
                    b[p + 39] & 0xFF, b[p + 40] & 0xFF);
        }
    }

    // ------------------------------------------------------------------ decoding

    /** Unpack one message to grid values (NaN = missing), in file order (see Lambert.index). */
    public static float[] decode(byte[] b, Message m) {
        int p5 = m.sec5;
        int tmpl = u16(b, p5 + 9);
        float r = Float.intBitsToFloat((int) u32(b, p5 + 11));
        int e = s16(b, p5 + 15);
        int d = s16(b, p5 + 17);
        int nbits = b[p5 + 19] & 0xFF;
        double bscale = Math.pow(2, e), dscale = Math.pow(10, -d);
        int npts = m.grid.nx * m.grid.ny;
        int n = m.numPacked;

        double[] packed = new double[n];          // unpacked integers, NaN where missing
        int dataStart = m.sec7 + 5;
        if (tmpl == 0) {
            BitReader br = new BitReader(b, dataStart);
            for (int i = 0; i < n; i++) packed[i] = nbits == 0 ? 0 : br.read(nbits);
        } else if (tmpl == 2 || tmpl == 3) {
            complex(b, p5, tmpl, nbits, dataStart, n, packed);
        } else {
            throw new IllegalArgumentException("Unsupported GRIB2 data template 5." + tmpl);
        }

        float[] out = new float[npts];
        if (m.bitmapOffset < 0) {
            for (int i = 0; i < npts && i < n; i++)
                out[i] = Double.isNaN(packed[i]) ? Float.NaN : (float) ((r + packed[i] * bscale) * dscale);
        } else {
            BitReader bm = new BitReader(b, m.bitmapOffset);
            int k = 0;
            for (int i = 0; i < npts; i++) {
                if (bm.read(1) == 1 && k < n) {
                    double x = packed[k++];
                    out[i] = Double.isNaN(x) ? Float.NaN : (float) ((r + x * bscale) * dscale);
                } else out[i] = Float.NaN;
            }
        }
        return out;
    }

    /** Complex packing (5.2) and complex packing with spatial differencing (5.3). */
    private static void complex(byte[] b, int p5, int tmpl, int nbits, int dataStart, int n, double[] out) {
        int missingMgmt = b[p5 + 22] & 0xFF;
        int ng = (int) u32(b, p5 + 31);
        int refWidth = b[p5 + 35] & 0xFF;
        int bitsWidth = b[p5 + 36] & 0xFF;
        long refLen = u32(b, p5 + 37);
        int lenInc = b[p5 + 41] & 0xFF;
        long lastLen = u32(b, p5 + 42);
        int bitsLen = b[p5 + 46] & 0xFF;
        int order = 0, extraOctets = 0;
        if (tmpl == 3) { order = b[p5 + 47] & 0xFF; extraOctets = b[p5 + 48] & 0xFF; }

        BitReader br = new BitReader(b, dataStart);
        long ival1 = 0, ival2 = 0, minsd = 0;
        if (tmpl == 3 && order > 0) {
            ival1 = br.readSigned(extraOctets * 8);
            if (order == 2) ival2 = br.readSigned(extraOctets * 8);
            minsd = br.readSigned(extraOctets * 8);
        }
        long[] gref = new long[ng];
        for (int g = 0; g < ng; g++) gref[g] = nbits == 0 ? 0 : br.read(nbits);
        br.align();
        int[] gwidth = new int[ng];
        for (int g = 0; g < ng; g++) gwidth[g] = (int) (refWidth + (bitsWidth == 0 ? 0 : br.read(bitsWidth)));
        br.align();
        long[] glen = new long[ng];
        for (int g = 0; g < ng; g++) glen[g] = refLen + (bitsLen == 0 ? 0 : br.read(bitsLen)) * lenInc;
        if (ng > 0) glen[ng - 1] = lastLen;
        br.align();

        long allOnesRef = nbits == 0 ? 0 : (1L << nbits) - 1;
        int k = 0;
        for (int g = 0; g < ng && k < n; g++) {
            int w = gwidth[g];
            for (long c = 0; c < glen[g] && k < n; c++) {
                if (w == 0) {
                    boolean miss = missingMgmt >= 1 && nbits > 0 &&
                            (gref[g] == allOnesRef || (missingMgmt == 2 && gref[g] == allOnesRef - 1));
                    out[k++] = miss ? Double.NaN : gref[g];
                } else {
                    long x = br.read(w);
                    long ones = (1L << w) - 1;
                    boolean miss = missingMgmt >= 1 && (x == ones || (missingMgmt == 2 && x == ones - 1));
                    out[k++] = miss ? Double.NaN : gref[g] + x;
                }
            }
        }

        // Undo spatial differencing over the non-missing values, in order
        if (tmpl == 3 && order > 0) {
            int cnt = 0;
            double prev1 = 0, prev2 = 0;
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(out[i])) continue;
                double v;
                if (cnt == 0) v = ival1;
                else if (order == 2 && cnt == 1) v = ival2;
                else if (order == 1) v = out[i] + minsd + prev1;
                else v = out[i] + minsd + 2 * prev1 - prev2;
                out[i] = v;
                prev2 = prev1; prev1 = v;
                cnt++;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class BitReader {
        private final byte[] b;
        private long bit;
        BitReader(byte[] b, int byteOffset) { this.b = b; this.bit = byteOffset * 8L; }
        long read(int nbits) {
            long v = 0;
            for (int i = 0; i < nbits; i++) {
                int byteIdx = (int) (bit >>> 3);
                int shift = 7 - (int) (bit & 7);
                v = (v << 1) | ((b[byteIdx] >> shift) & 1);
                bit++;
            }
            return v;
        }
        /** Sign-magnitude integer (GRIB2 convention: top bit is the sign). */
        long readSigned(int nbits) {
            long v = read(nbits);
            long sign = 1L << (nbits - 1);
            return (v & sign) != 0 ? -(v & (sign - 1)) : v;
        }
        void align() { bit = (bit + 7) & ~7L; }
    }

    /** Offset of the next "GRIB" message start at or after `from`, or -1. */
    private static int find(byte[] b, int from) {
        for (int i = from; i + 3 < b.length; i++)
            if (b[i] == 'G' && b[i + 1] == 'R' && b[i + 2] == 'I' && b[i + 3] == 'B') return i;
        return -1;
    }

    /** Unsigned 16-bit big-endian integer at p. */
    private static int u16(byte[] b, int p) { return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); }
    /** Unsigned 32-bit big-endian integer at p. */
    private static long u32(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }
    /** Unsigned 64-bit big-endian integer at p (message lengths). */
    private static long u64(byte[] b, int p) { return (u32(b, p) << 32) | u32(b, p + 4); }
    /** Signed 16-bit integer at p in GRIB's sign-and-magnitude form (top bit = minus). */
    private static int s16(byte[] b, int p) { int v = u16(b, p); return (v & 0x8000) != 0 ? -(v & 0x7FFF) : v; }
    /** Signed 32-bit integer at p in GRIB's sign-and-magnitude form. */
    private static long s32(byte[] b, int p) { long v = u32(b, p); return (v & 0x80000000L) != 0 ? -(v & 0x7FFFFFFFL) : v; }
    /** Longitude moved into -180..180. */
    private static double lon180(double lon) { while (lon > 180) lon -= 360; return lon; }

    /** UTC milliseconds of a date and time. */
    private static long utc(int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }
}
