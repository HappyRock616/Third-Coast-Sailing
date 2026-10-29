package com.thirdcoastsailing.app.celestial

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Maths for the 3D star finder, shared by the OpenGL renderer and the text overlay drawn on top of it (so labels
 * sit exactly on the stars). Pure Kotlin, no Android, so it's tested on a PC.
 *
 * World frame: east-north-up (ENU), true north. Sky directions are unit vectors; the boat scene is in feet with the
 * boat at the origin.
 *
 * Two cameras share the screen:
 *  - SKY: looks exactly where the back of the phone points (rotation-vector sensor + magnetic declination), so
 *    stars are in their real directions. Rough pointing only: phone compasses are good to a few degrees.
 *  - BOAT: a fixed view from behind and above the boat, turned to the same compass direction as the phone, drawn in
 *    the lower part of the screen. The boat lies on its last course; the compass ring is fixed to north.
 */

/** 3-vector. */
data class V3(val x: Double, val y: Double, val z: Double) {
    /** Vector sum. */
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    /** Vector difference. */
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    /** Vector scaled by k. */
    operator fun times(k: Double) = V3(x * k, y * k, z * k)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    /** Length of the vector. */
    fun length() = sqrt(this dot this)
    /** The same direction with length 1 (straight up for a zero vector). */
    fun unit(): V3 { val l = length(); return if (l > 1e-12) this * (1.0 / l) else V3(0.0, 0.0, 1.0) }

    companion object {
        /** Unit vector toward altitude / true azimuth (degrees). */
        fun altAz(altDeg: Double, azDeg: Double): V3 {
            val a = altDeg * DEG; val z = azDeg * DEG
            return V3(cos(a) * sin(z), cos(a) * cos(z), sin(a))
        }
    }
}

/** Altitude (degrees) of a direction. */
fun V3.altDeg(): Double = asin(z.coerceIn(-1.0, 1.0)) * RAD
/** True azimuth (degrees) of a direction. */
fun V3.azDeg(): Double = norm360(atan2(x, y) * RAD)

/** 4x4 matrix, column-major (OpenGL order): m[col * 4 + row]. */
class Mat4(val m: DoubleArray = DoubleArray(16)) {
    /** Element at row, col. */
    operator fun get(row: Int, col: Int) = m[col * 4 + row]
    /** Set the element at row, col. */
    operator fun set(row: Int, col: Int, v: Double) { m[col * 4 + row] = v }

    /** Matrix product (this × o). */
    operator fun times(o: Mat4): Mat4 {
        val r = Mat4()
        for (i in 0..3) for (j in 0..3) { var s = 0.0; for (k in 0..3) s += this[i, k] * o[k, j]; r[i, j] = s }
        return r
    }

    /** Transform a point (w = 1); returns x, y, z, w. */
    fun transform(p: V3, w: Double = 1.0): DoubleArray = DoubleArray(4) { i -> this[i, 0] * p.x + this[i, 1] * p.y + this[i, 2] * p.z + this[i, 3] * w }

    /** The matrix as 16 floats, column-major, for OpenGL. */
    fun toFloats(): FloatArray = FloatArray(16) { m[it].toFloat() }

    /** General inverse (Gauss-Jordan); identity if singular. */
    fun inverse(): Mat4 {
        val a = Array(4) { r -> DoubleArray(8) { c -> if (c < 4) this[r, c] else if (c - 4 == r) 1.0 else 0.0 } }
        for (c in 0..3) {
            var p = c; for (r in c + 1..3) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12) return identity()
            val t = a[p]; a[p] = a[c]; a[c] = t
            val d = a[c][c]; for (k in 0..7) a[c][k] /= d
            for (r in 0..3) if (r != c) { val f = a[r][c]; if (f != 0.0) for (k in 0..7) a[r][k] -= f * a[c][k] }
        }
        val r = Mat4(); for (i in 0..3) for (j in 0..3) r[i, j] = a[i][j + 4]; return r
    }

    companion object {
        fun identity() = Mat4().also { for (i in 0..3) it[i, i] = 1.0 }

        /** Perspective projection; fovY in degrees. */
        fun perspective(fovYDeg: Double, aspect: Double, near: Double, far: Double): Mat4 {
            val f = 1.0 / tan(fovYDeg * DEG / 2.0)
            return Mat4().also {
                it[0, 0] = f / aspect; it[1, 1] = f
                it[2, 2] = (far + near) / (near - far); it[2, 3] = 2 * far * near / (near - far)
                it[3, 2] = -1.0
            }
        }

        /** Camera at `eye` looking at `target` with `up` roughly up. */
        fun lookAt(eye: V3, target: V3, up: V3): Mat4 {
            val f = (target - eye).unit()
            val s = (f cross up).unit()
            val u = s cross f
            return Mat4().also {
                it[0, 0] = s.x; it[0, 1] = s.y; it[0, 2] = s.z; it[0, 3] = -(s dot eye)
                it[1, 0] = u.x; it[1, 1] = u.y; it[1, 2] = u.z; it[1, 3] = -(u dot eye)
                it[2, 0] = -f.x; it[2, 1] = -f.y; it[2, 2] = -f.z; it[2, 3] = f dot eye
                it[3, 3] = 1.0
            }
        }

        /** Rotation about the vertical (z) axis taking the boat's bow (+x in the model) to a true heading. */
        fun boatHeading(headingDeg: Double): Mat4 {
            // model axes: x forward, y to port, z up -> ENU: forward = (sin h, cos h, 0), port = (-cos h, sin h, 0)
            val h = headingDeg * DEG
            return identity().also {
                it[0, 0] = sin(h); it[1, 0] = cos(h)
                it[0, 1] = -cos(h); it[1, 1] = sin(h)
            }
        }
    }
}

/** Where the phone points: forward = out of the back of the phone, up = toward the top of the screen (true ENU). */
data class PhonePose(val forward: V3, val up: V3) {
    val azDeg: Double get() = forward.azDeg()
    val altDeg: Double get() = forward.altDeg()

    companion object {
        /** Looking at the horizon in a given direction (drag mode / phones without a compass). */
        fun look(azDeg: Double, altDeg: Double): PhonePose {
            val f = V3.altAz(altDeg, azDeg)
            val up = V3.altAz(altDeg + 90.0, azDeg)
            return PhonePose(f, up)
        }

        /**
         * From the rotation-vector sensor's quaternion (x, y, z, w: device -> magnetic ENU), the screen's rotation
         * (0, 90, 180, 270 degrees) and the magnetic declination (degrees, east +).
         */
        fun fromQuaternion(qx: Double, qy: Double, qz: Double, qw: Double, displayRotationDeg: Int, declinationDeg: Double): PhonePose {
            val r = quatToMatrix(qx, qy, qz, qw)   // row-major 3x3, device -> world
            fun dev(v: V3) = V3(r[0] * v.x + r[1] * v.y + r[2] * v.z, r[3] * v.x + r[4] * v.y + r[5] * v.z, r[6] * v.x + r[7] * v.y + r[8] * v.z)
            // the top of the screen in device axes, for the way the screen is turned
            val upDev = when (((displayRotationDeg % 360) + 360) % 360) {
                90 -> V3(-1.0, 0.0, 0.0)
                180 -> V3(0.0, -1.0, 0.0)
                270 -> V3(1.0, 0.0, 0.0)
                else -> V3(0.0, 1.0, 0.0)
            }
            val f = toTrue(dev(V3(0.0, 0.0, -1.0)), declinationDeg)
            val u = toTrue(dev(upDev), declinationDeg)
            return PhonePose(f.unit(), u.unit())
        }

        /** Magnetic ENU -> true ENU: turn clockwise (seen from above) by the declination. */
        fun toTrue(v: V3, declinationDeg: Double): V3 {
            val d = declinationDeg * DEG
            return V3(v.x * cos(d) + v.y * sin(d), -v.x * sin(d) + v.y * cos(d), v.z)
        }

        /** Unit quaternion -> row-major 3x3 rotation matrix (same as SensorManager.getRotationMatrixFromVector). */
        fun quatToMatrix(x: Double, y: Double, z: Double, w: Double): DoubleArray {
            val n = sqrt(x * x + y * y + z * z + w * w).takeIf { it > 1e-9 } ?: 1.0
            val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
            return doubleArrayOf(
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
            )
        }

        /** Smooth sensor jitter: normalized blend of two quaternions (t = share of the new one), shortest way round. */
        fun blend(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
            val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
            val s = if (dot < 0) -1.0 else 1.0
            val r = DoubleArray(4) { a[it] * (1 - t) + b[it] * s * t }
            val n = sqrt(r.sumOf { it * it }).takeIf { it > 1e-9 } ?: 1.0
            return DoubleArray(4) { r[it] / n }
        }
    }
}

/** A point on screen: x, y in pixels from the top left; visible = in front of the camera and on screen. */
data class ScreenPt(val x: Float, val y: Float, val inFront: Boolean, val onScreen: Boolean)

/**
 * The two cameras for one frame.
 * @param widthPx, heightPx  screen size
 * @param fovYDeg            sky camera's vertical field of view (pinch to zoom)
 * @param yawDeg             compass direction for the boat camera (the phone's, held when pointing straight up/down)
 */
class FinderCameras(val pose: PhonePose, val widthPx: Int, val heightPx: Int, val fovYDeg: Double, val yawDeg: Double) {

    val skyView: Mat4 = Mat4.lookAt(V3(0.0, 0.0, 0.0), pose.forward, pose.up)
    val skyProj: Mat4 = Mat4.perspective(fovYDeg, widthPx.toDouble() / heightPx.coerceAtLeast(1), 0.1, 10.0)
    val skyViewProj: Mat4 = skyProj * skyView

    // Boat viewport: the lower part of the screen (same layout as the approved mock-up)
    val boatViewportHeight: Int = (heightPx * BOAT_VIEW_FRACTION).toInt()
    val boatViewportBottom: Int = -(heightPx * BOAT_VIEW_DROP).toInt()     // GL y, from the bottom; slightly off screen
    private val dh = V3.altAz(0.0, yawDeg)
    val boatEye: V3 = dh * -BOAT_CAM_BACK + V3(0.0, 0.0, BOAT_CAM_UP)
    val boatView: Mat4 = Mat4.lookAt(boatEye, V3(0.0, 0.0, BOAT_CAM_LOOK_Z), V3(0.0, 0.0, 1.0))
    val boatProj: Mat4 = Mat4.perspective(BOAT_FOV_DEG, widthPx.toDouble() / boatViewportHeight.coerceAtLeast(1), 1.0, 400.0)
    val boatViewProj: Mat4 = boatProj * boatView

    /** Screen position of a sky direction. */
    fun projectSky(dir: V3): ScreenPt {
        val c = skyViewProj.transform(dir, 0.0)    // direction: no translation (camera at the center of the sky)
        return toScreen(c, 0, heightPx, heightPx)
    }

    /** Screen position of a point in the boat scene (feet, ENU, boat at the origin). */
    fun projectBoat(p: V3): ScreenPt {
        val c = boatViewProj.transform(p)
        // viewport: GL y from boatViewportBottom, height boatViewportHeight -> screen y from the top
        val topPx = heightPx - (boatViewportBottom + boatViewportHeight)
        return toScreen(c, topPx, boatViewportHeight, heightPx)
    }

    /** Clip-space coordinates to a screen point inside a viewport of vpH pixels starting topPx from the top (points behind the camera are pushed far off-screen). */
    private fun toScreen(c: DoubleArray, topPx: Int, vpH: Int, screenH: Int): ScreenPt {
        val w = c[3]
        val inFront = w > 1e-6
        val nx = if (inFront) c[0] / w else c[0] * 1e6
        val ny = if (inFront) c[1] / w else c[1] * 1e6
        val x = ((nx + 1.0) / 2.0 * widthPx).toFloat()
        val y = (topPx + (1.0 - ny) / 2.0 * vpH).toFloat()
        val on = inFront && x in 0f..widthPx.toFloat() && y in 0f..screenH.toFloat()
        return ScreenPt(x, y, inFront, on)
    }

    /** Direction in the sky under a screen point (for tapping). */
    fun unprojectSky(xPx: Float, yPx: Float): V3 {
        val nx = xPx / widthPx.toDouble() * 2.0 - 1.0
        val ny = 1.0 - yPx / heightPx.toDouble() * 2.0
        val inv = skyViewProj.inverse()
        val p = inv.transform(V3(nx, ny, 1.0))
        return V3(p[0] / p[3], p[1] / p[3], p[2] / p[3]).unit()
    }

    companion object {
        const val BOAT_VIEW_FRACTION = 0.625      // boat view height as a share of the screen
        const val BOAT_VIEW_DROP = 0.042          // pushed this far below the bottom edge
        const val BOAT_CAM_BACK = 78.0            // ft behind the boat
        const val BOAT_CAM_UP = 34.0              // ft above the water
        const val BOAT_CAM_LOOK_Z = 8.0           // looks at this height over the boat
        const val BOAT_FOV_DEG = 46.0
        const val RING_RADIUS_FT = 28.0           // compass ring around the boat
    }
}

/**
 * Stylized sky colors for the Sun's altitude (degrees): the uniforms for the sky shader. The approved look: blue
 * day sky, an orange-to-red glow hugging the horizon under the Sun through twilight, the pink "Belt of Venus"
 * opposite the Sun just after sunset / before sunrise, and a near-black night sky. Colors are RGB 0..1.
 */
data class SkyShade(
    val zenith: FloatArray,
    val horizon: FloatArray,
    val glow: FloatArray,
    val glowStrength: Float,
    val glowWidthDeg: Float,      // how far either side of the Sun's bearing the glow spreads
    val glowHeightDeg: Float,     // how high it reaches
    val beltStrength: Float,
    val limitingMag: Float,       // faintest star shown
    val artAlpha: Float,          // constellation lines / names: 0 in daylight, 1 when dark
) {
    companion object {
        private val ZENITH = listOf(15.0 to floatArrayOf(0.22f, 0.45f, 0.80f), 0.0 to floatArrayOf(0.18f, 0.33f, 0.60f),
            -4.0 to floatArrayOf(0.10f, 0.17f, 0.36f), -8.0 to floatArrayOf(0.05f, 0.08f, 0.19f),
            -12.0 to floatArrayOf(0.025f, 0.04f, 0.09f), -18.0 to floatArrayOf(0.010f, 0.016f, 0.035f))
        private val HORIZON = listOf(15.0 to floatArrayOf(0.68f, 0.80f, 0.90f), 0.0 to floatArrayOf(0.55f, 0.60f, 0.72f),
            -4.0 to floatArrayOf(0.28f, 0.27f, 0.40f), -8.0 to floatArrayOf(0.10f, 0.11f, 0.21f),
            -12.0 to floatArrayOf(0.035f, 0.05f, 0.10f), -18.0 to floatArrayOf(0.014f, 0.022f, 0.045f))
        private val GLOW = listOf(15.0 to floatArrayOf(1.0f, 0.95f, 0.85f), 3.0 to floatArrayOf(1.0f, 0.75f, 0.40f),
            0.0 to floatArrayOf(1.0f, 0.55f, 0.22f), -4.0 to floatArrayOf(0.95f, 0.40f, 0.20f),
            -8.0 to floatArrayOf(0.60f, 0.22f, 0.25f), -12.0 to floatArrayOf(0.25f, 0.10f, 0.18f),
            -18.0 to floatArrayOf(0.0f, 0.0f, 0.0f))

        fun forSun(sunAltDeg: Double): SkyShade = SkyShade(
            keys(ZENITH, sunAltDeg), keys(HORIZON, sunAltDeg), keys(GLOW, sunAltDeg),
            interp(sunAltDeg, doubleArrayOf(-18.0, -13.0, -9.0, -5.0, -1.0, 3.0, 10.0, 30.0), doubleArrayOf(0.0, 0.04, 0.35, 0.75, 0.9, 0.7, 0.35, 0.25)).toFloat(),
            interp(sunAltDeg, doubleArrayOf(-12.0, -6.0, 0.0, 10.0), doubleArrayOf(70.0, 60.0, 45.0, 35.0)).toFloat(),
            interp(sunAltDeg, doubleArrayOf(-12.0, -6.0, 0.0, 10.0), doubleArrayOf(9.0, 14.0, 18.0, 25.0)).toFloat(),
            interp(sunAltDeg, doubleArrayOf(-7.0, -5.0, -2.0, 0.0, 1.0), doubleArrayOf(0.0, 0.25, 0.35, 0.15, 0.0)).toFloat(),
            limitingMag(sunAltDeg).toFloat(),
            interp(sunAltDeg, doubleArrayOf(-12.0, -6.0, 0.0), doubleArrayOf(1.0, 0.5, 0.0)).toFloat(),
        )

        /** Faintest star the eye picks out for the Sun's altitude. */
        fun limitingMag(sunAltDeg: Double): Double =
            interp(sunAltDeg, doubleArrayOf(-18.0, -15.0, -12.0, -9.0, -6.0, -3.0, 0.0, 5.0), doubleArrayOf(4.6, 4.5, 4.0, 3.2, 2.2, 1.0, -1.0, -4.0))

        /** How visible a star of magnitude `mag` is (0..1) against this sky. */
        fun starAlpha(mag: Double, limitingMag: Double): Double = ((limitingMag - mag) + 0.15).coerceIn(0.0, 1.0)

        /** Piecewise-linear interpolation; xs ascending. */
        fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
            if (x <= xs.first()) return ys.first()
            if (x >= xs.last()) return ys.last()
            for (i in 1 until xs.size) if (x <= xs[i]) {
                val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]); return ys[i - 1] + t * (ys[i] - ys[i - 1])
            }
            return ys.last()
        }

        /** Color keyframes listed from high Sun to low Sun. */
        private fun keys(k: List<Pair<Double, FloatArray>>, h: Double): FloatArray {
            val hc = min(max(h, k.last().first), k.first().first)
            for (i in 0 until k.size - 1) {
                val (h0, c0) = k[i]; val (h1, c1) = k[i + 1]
                if (hc <= h0 && hc >= h1) {
                    val t = ((hc - h0) / (h1 - h0)).toFloat()
                    return FloatArray(3) { c0[it] * (1 - t) + c1[it] * t }
                }
            }
            return k.last().second.copyOf()
        }

        /**
         * The sky color in one direction (CPU copy of the shader, for tests and previews).
         * Same formula as the GLSL in the renderer.
         */
        fun color(shade: SkyShade, viewAltDeg: Double, viewAzDeg: Double, sunAltDeg: Double, sunAzDeg: Double): DoubleArray {
            fun sky(alt: Double): DoubleArray {
                val t = exp(-max(alt, 0.0) / 14.0)
                val col = DoubleArray(3) { shade.zenith[it] * (1 - t) + shade.horizon[it] * t }
                val daz = abs(norm180(viewAzDeg - sunAzDeg))
                val g = shade.glowStrength * exp(-(daz / shade.glowWidthDeg) * (daz / shade.glowWidthDeg)) * exp(-max(alt, 0.0) / shade.glowHeightDeg)
                for (i in 0..2) col[i] = col[i] * (1 - g) + shade.glow[i] * g
                val anti = 180.0 - daz
                val b = shade.beltStrength * exp(-(anti / 80.0) * (anti / 80.0)) * exp(-((alt - 8.0) / 6.0) * ((alt - 8.0) / 6.0))
                val belt = doubleArrayOf(0.62, 0.42, 0.55)
                for (i in 0..2) col[i] = col[i] * (1 - b) + belt[i] * b
                if (sunAltDeg > -1.0) {
                    val gam = acosDeg(V3.altAz(alt, viewAzDeg) dot V3.altAz(sunAltDeg, sunAzDeg))
                    val sun = doubleArrayOf(1.0, 0.95, 0.8)
                    for (i in 0..2) col[i] += sun[i] * exp(-(gam / 2.5) * (gam / 2.5)) * 1.2
                }
                return DoubleArray(3) { col[it].coerceIn(0.0, 1.0) }
            }
            if (viewAltDeg < 0) {
                val h = sky(0.5); val k = 0.28 + 0.12 * exp(viewAltDeg / 6.0)
                return DoubleArray(3) { h[it] * k }
            }
            return sky(viewAltDeg)
        }

        private fun acosDeg(c: Double) = kotlin.math.acos(c.coerceIn(-1.0, 1.0)) * RAD
    }
}
