package com.thirdcoastsailing.app.celestial

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/*
 * The star finder's 3D boat (assets/finder/boat.tcsm.gz): an indexed triangle mesh, quantized and gzipped
 * (about 60 KB instead of 750 KB), plus the rigging lines and the three navigation lights.
 * The hull decal (round logo + "Third Coast") is a separate full-resolution texture (assets/finder/hull_decal.png)
 * so the lettering stays sharp.
 *
 * Model axes, feet: x toward the bow, y to port, z up; waterline at z = 0.
 * Vertex layout after decoding, 12 floats: position xyz, normal xyz, color rgb, decal uv, decal flag
 * (0 = none, 1 = starboard layout = top half of the texture, 2 = port layout = bottom half).
 */
class BoatMesh(
    val vertices: FloatArray,      // 12 floats per vertex
    val indices: ShortArray,       // triangles
    val lines: FloatArray,         // rigging: x1 y1 z1 x2 y2 z2 per line
    val lights: FloatArray,        // navigation lights: x y z r g b each
) {
    val vertexCount: Int get() = vertices.size / FLOATS
    val lightCount: Int get() = lights.size / 6

    companion object {
        const val FLOATS = 12

        fun decode(gz: ByteArray): BoatMesh {
            val raw = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
            val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "TCSM") { "not a boat mesh" }
            require(b.int == 1) { "unknown boat mesh version" }
            val nv = b.int; val ni = b.int; val nl = b.int; val nlt = b.int
            val ps = b.float; val us = b.float
            val v = FloatArray(nv * FLOATS)
            for (i in 0 until nv) {
                val o = i * FLOATS
                v[o] = b.short * ps; v[o + 1] = b.short * ps; v[o + 2] = b.short * ps
                v[o + 3] = b.get() / 127f; v[o + 4] = b.get() / 127f; v[o + 5] = b.get() / 127f
                v[o + 11] = (b.get().toInt() and 0xFF).toFloat()
                v[o + 6] = (b.get().toInt() and 0xFF) / 255f; v[o + 7] = (b.get().toInt() and 0xFF) / 255f
                v[o + 8] = (b.get().toInt() and 0xFF) / 255f
                b.get()                                                           // padding
                v[o + 9] = b.short * us; v[o + 10] = b.short * us
            }
            val idx = ShortArray(ni) { b.short }
            val lines = FloatArray(nl * 6) { b.float }
            val lights = FloatArray(nlt * 6) { b.float }
            return BoatMesh(v, idx, lines, lights)
        }
    }
}
