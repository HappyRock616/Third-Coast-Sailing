package com.thirdcoastsailing.app

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.thirdcoastsailing.app.celestial.BoatMesh
import com.thirdcoastsailing.app.celestial.FinderCameras
import com.thirdcoastsailing.app.celestial.Mat4
import com.thirdcoastsailing.app.celestial.PhonePose
import com.thirdcoastsailing.app.celestial.Sky3D
import com.thirdcoastsailing.app.celestial.SkyObjectKind
import com.thirdcoastsailing.app.celestial.SkyShade
import com.thirdcoastsailing.app.celestial.V3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/*
 * OpenGL ES 2.0 renderer for the 3D star finder (no 3D library).
 *
 *  1. Sky: one full-screen quad; the fragment shader works out the direction of each pixel and colors it with the
 *     stylized twilight model (SkyShade: blue day, horizon glow under the Sun, Belt of Venus, dark night, sea below
 *     the horizon). Same formula as SkyShade.color on the CPU.
 *  2. Constellation lines and the horizon line, then stars and planets as round points, faded by the sky's
 *     limiting magnitude (stars come out as twilight deepens).
 *  3. The boat scene in the lower part of the screen, from its own camera: compass ring (fixed to north), the boat
 *     on its last course with the hull decal (logo + "Third Coast"), rigging, and glowing navigation lights.
 *
 * Text (names, compass numbers, the bearing box) is drawn by the Compose overlay using the same FinderCameras.
 * Draws only when asked (RENDERMODE_WHEN_DIRTY): the screen calls requestRender() when the phone moves.
 */
class StarFinderRenderer(private val context: Context) : GLSurfaceView.Renderer {

    /** What to draw; set from the UI thread, read on the GL thread. */
    data class View(val pose: PhonePose, val fovYDeg: Double, val yawDeg: Double, val boatHeadingDeg: Double)

    @Volatile var view: View = View(PhonePose.look(0.0, 15.0), 65.0, 0.0, 0.0)
    @Volatile private var sky: Sky3D? = null
    @Volatile private var skyVersion = 0
    private var uploadedVersion = -1

    fun setSky(s: Sky3D) { sky = s; skyVersion++ }

    private var width = 1
    private var height = 1

    // programs
    private var skyProg = 0; private var pointProg = 0; private var lineProg = 0; private var boatProg = 0; private var glowProg = 0

    // sky data (client-side arrays, rebuilt every 30 s)
    private var starBuf: FloatBuffer? = null; private var starCount = 0          // dir xyz, mag, rgb, isMoon
    private var lineBuf: FloatBuffer? = null; private var lineVerts = 0
    private val horizonBuf: FloatBuffer = floats(FloatArray(361 * 3) { i ->
        val a = (i / 3) * Math.PI / 180.0
        when (i % 3) { 0 -> sin(a).toFloat(); 1 -> cos(a).toFloat(); else -> 0f }
    })
    private val quadBuf: FloatBuffer = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    // boat (VBOs)
    private var mesh: BoatMesh? = null
    private var boatVbo = 0; private var boatIbo = 0; private var boatTex = 0
    private var rigBuf: FloatBuffer? = null; private var rigVerts = 0
    private var lightBuf: FloatBuffer? = null
    private var ringBuf: FloatBuffer? = null; private var ringVerts = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.06f, 0.12f, 1f)
        skyProg = program(SKY_VS, SKY_FS)
        pointProg = program(POINT_VS, POINT_FS)
        lineProg = program(LINE_VS, LINE_FS)
        boatProg = program(BOAT_VS, BOAT_FS)
        glowProg = program(GLOW_VS, GLOW_FS)
        loadBoat()
        ringBuf = floats(ringTriangles()).also { ringVerts = it.capacity() / 3 }
        uploadedVersion = -1                                       // new GL context: rebuild the sky arrays
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val v = view
        val cam = FinderCameras(v.pose, width, height, v.fovYDeg, v.yawDeg)
        val s = sky
        if (s != null && skyVersion != uploadedVersion) { buildSkyArrays(s); uploadedVersion = skyVersion }
        val sunAlt = s?.sunAltDeg ?: -30.0
        val sunAz = s?.sunAzDeg ?: 0.0
        val shade = SkyShade.forSun(sunAlt)
        val scale = width / 720f                                    // sizes were designed on a 720 px wide screen

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        // ---- 1. sky
        GLES20.glUseProgram(skyProg)
        uniformMat(skyProg, "uInv", cam.skyViewProj.inverse())
        GLES20.glUniform3fv(loc(skyProg, "uZen"), 1, shade.zenith, 0)
        GLES20.glUniform3fv(loc(skyProg, "uHor"), 1, shade.horizon, 0)
        GLES20.glUniform3fv(loc(skyProg, "uGlowCol"), 1, shade.glow, 0)
        GLES20.glUniform1f(loc(skyProg, "uGlow"), shade.glowStrength)
        GLES20.glUniform1f(loc(skyProg, "uWidth"), shade.glowWidthDeg)
        GLES20.glUniform1f(loc(skyProg, "uHeight"), shade.glowHeightDeg)
        GLES20.glUniform1f(loc(skyProg, "uBelt"), shade.beltStrength)
        GLES20.glUniform1f(loc(skyProg, "uSunAlt"), sunAlt.toFloat())
        val sun = V3.altAz(sunAlt, sunAz)
        GLES20.glUniform3f(loc(skyProg, "uSun"), sun.x.toFloat(), sun.y.toFloat(), sun.z.toFloat())
        attrib(skyProg, "aPos", quadBuf, 2, 2, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        disable(skyProg, "aPos")

        // ---- 2. lines, then points (premultiplied alpha)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(lineProg)
        uniformMat(lineProg, "uVP", cam.skyViewProj)
        GLES20.glLineWidth((1.6f * scale).coerceIn(1f, 4f))
        lineBuf?.let { lb ->
            val a = 0.55f * shade.artAlpha
            if (a > 0.01f && lineVerts > 0) {
                GLES20.glUniform4f(loc(lineProg, "uCol"), 0.47f * a, 0.65f * a, 0.80f * a, a)
                attrib(lineProg, "aDir", lb, 3, 3, 0)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineVerts)
            }
        }
        val ha = 0.6f
        GLES20.glUniform4f(loc(lineProg, "uCol"), 0.49f * ha, 0.69f * ha, 0.85f * ha, ha)
        attrib(lineProg, "aDir", horizonBuf, 3, 3, 0)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, 361)
        disable(lineProg, "aDir")

        starBuf?.let { sb ->
            GLES20.glUseProgram(pointProg)
            uniformMat(pointProg, "uVP", cam.skyViewProj)
            GLES20.glUniform1f(loc(pointProg, "uLim"), shade.limitingMag)
            GLES20.glUniform1f(loc(pointProg, "uScale"), scale * (65f / v.fovYDeg.toFloat()).coerceIn(0.7f, 1.8f))
            attrib(pointProg, "aDir", sb, 3, 8, 0)
            attrib(pointProg, "aMag", sb, 1, 8, 3)
            attrib(pointProg, "aCol", sb, 3, 8, 4)
            attrib(pointProg, "aMoon", sb, 1, 8, 7)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
            for (n in listOf("aDir", "aMag", "aCol", "aMoon")) disable(pointProg, n)
        }

        // ---- 3. boat scene, lower part of the screen
        GLES20.glViewport(0, cam.boatViewportBottom, width, cam.boatViewportHeight)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        val model = Mat4.boatHeading(v.boatHeadingDeg)
        val mvp = cam.boatViewProj * model
        val dim = (0.78 + 0.22 * ((sunAlt + 12.0) / 12.0).coerceIn(0.0, 1.0)).toFloat()   // a little darker at night
        // light from behind the viewer, to the right and above, so the side of the boat you see is always lit
        val dh = V3.altAz(0.0, v.yawDeg); val right = V3(dh.y, -dh.x, 0.0)
        val light = (dh * -0.6 + right * 0.4 + V3(0.0, 0.0, 0.75)).unit()

        // compass ring (flat, world-fixed) as solid orange triangles
        GLES20.glUseProgram(lineProg)
        uniformMat(lineProg, "uVP", cam.boatViewProj)
        GLES20.glUniform4f(loc(lineProg, "uCol"), 0.949f, 0.549f, 0.157f, 1f)
        ringBuf?.let { rb ->
            attrib(lineProg, "aDir", rb, 3, 3, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, ringVerts)
            disable(lineProg, "aDir")
        }

        // hull, deck, cabin, mast...
        val m = mesh
        if (m != null && boatVbo != 0) {
            GLES20.glUseProgram(boatProg)
            uniformMat(boatProg, "uMVP", mvp)
            val r = model.m
            val n3 = floatArrayOf(r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[4].toFloat(), r[5].toFloat(), r[6].toFloat(),
                r[8].toFloat(), r[9].toFloat(), r[10].toFloat())
            GLES20.glUniformMatrix3fv(loc(boatProg, "uNormal"), 1, false, n3, 0)
            GLES20.glUniform3f(loc(boatProg, "uLight"), light.x.toFloat(), light.y.toFloat(), light.z.toFloat())
            GLES20.glUniform1f(loc(boatProg, "uDim"), dim)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, boatTex)
            GLES20.glUniform1i(loc(boatProg, "uTex"), 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, boatVbo)
            val stride = BoatMesh.FLOATS * 4
            vboAttrib(boatProg, "aPos", 3, stride, 0)
            vboAttrib(boatProg, "aNorm", 3, stride, 3)
            vboAttrib(boatProg, "aCol", 3, stride, 6)
            vboAttrib(boatProg, "aUv", 2, stride, 9)
            vboAttrib(boatProg, "aFlag", 1, stride, 11)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, boatIbo)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indices.size, GLES20.GL_UNSIGNED_SHORT, 0)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            for (n in listOf("aPos", "aNorm", "aCol", "aUv", "aFlag")) disable(boatProg, n)

            // rigging
            rigBuf?.let { rb ->
                GLES20.glUseProgram(lineProg)
                uniformMat(lineProg, "uVP", mvp)
                GLES20.glUniform4f(loc(lineProg, "uCol"), 0.69f * dim, 0.76f * dim, 0.84f * dim, 1f)
                GLES20.glLineWidth((1.3f * scale).coerceIn(1f, 3f))
                attrib(lineProg, "aDir", rb, 3, 3, 0)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, rigVerts)
                disable(lineProg, "aDir")
            }

            // navigation lights: soft glowing points, added on top
            lightBuf?.let { lb ->
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
                GLES20.glDepthMask(false)
                GLES20.glUseProgram(glowProg)
                uniformMat(glowProg, "uMVP", mvp)
                GLES20.glUniform1f(loc(glowProg, "uSize"), 30f * scale)
                attrib(glowProg, "aPos", lb, 3, 6, 0)
                attrib(glowProg, "aCol", lb, 3, 6, 3)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, m.lightCount)
                disable(glowProg, "aPos"); disable(glowProg, "aCol")
                GLES20.glDepthMask(true)
            }
        }
        GLES20.glViewport(0, 0, width, height)
    }

    // ---------------------------------------------------------------- data

    /** Star / planet / Moon points and constellation lines from the latest sky. */
    private fun buildSkyArrays(s: Sky3D) {
        val pts = s.objects.filter { it.kind != SkyObjectKind.SUN }
        val a = FloatArray(pts.size * 8)
        pts.forEachIndexed { i, o ->
            val d = V3.altAz(o.altDeg, o.azDeg); val k = i * 8
            a[k] = d.x.toFloat(); a[k + 1] = d.y.toFloat(); a[k + 2] = d.z.toFloat(); a[k + 3] = o.mag.toFloat()
            val c = when (o.kind) {
                SkyObjectKind.STAR -> floatArrayOf(0.75f, 0.80f, 0.87f)
                SkyObjectKind.NAV_STAR -> floatArrayOf(0.97f, 0.96f, 0.93f)
                SkyObjectKind.PLANET -> floatArrayOf(1.0f, 0.84f, 0.59f)
                else -> floatArrayOf(0.92f, 0.92f, 0.89f)
            }
            a[k + 4] = c[0]; a[k + 5] = c[1]; a[k + 6] = c[2]
            a[k + 7] = if (o.kind == SkyObjectKind.MOON) 1f else 0f
        }
        starBuf = floats(a); starCount = pts.size
        val segs = s.segments.filter { it.alt1 > 0.0 && it.alt2 > 0.0 }
        val l = FloatArray(segs.size * 6)
        segs.forEachIndexed { i, g ->
            val p = V3.altAz(g.alt1, g.az1); val q = V3.altAz(g.alt2, g.az2); val k = i * 6
            l[k] = p.x.toFloat(); l[k + 1] = p.y.toFloat(); l[k + 2] = p.z.toFloat()
            l[k + 3] = q.x.toFloat(); l[k + 4] = q.y.toFloat(); l[k + 5] = q.z.toFloat()
        }
        lineBuf = floats(l); lineVerts = segs.size * 2
    }

    /** Boat mesh into vertex / index buffers, hull decal into a mipmapped texture. */
    private fun loadBoat() {
        val m = mesh ?: runCatching { BoatMesh.decode(context.assets.open("finder/boat.tcsm.gz").use { it.readBytes() }) }.getOrNull()
        mesh = m ?: return
        val ids = IntArray(2); GLES20.glGenBuffers(2, ids, 0)
        boatVbo = ids[0]; boatIbo = ids[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, boatVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.vertices.size * 4, floats(m.vertices), GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, boatIbo)
        val ib: ShortBuffer = ByteBuffer.allocateDirect(m.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(m.indices).position(0)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 2, ib, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        rigBuf = floats(m.lines); rigVerts = m.lines.size / 3
        lightBuf = floats(m.lights)

        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); boatTex = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, boatTex)
        val bmp = runCatching { context.assets.open("finder/hull_decal.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
        if (bmp != null) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /** Compass ring around the boat: a band, a tick every 10° and longer ticks every 30° (feet, ENU, flat). */
    private fun ringTriangles(): FloatArray {
        val out = ArrayList<Float>()
        val r = FinderCameras.RING_RADIUS_FT; val z = 0.3f; val band = 0.45
        fun p(rad: Double, deg: Double) = V3.altAz(0.0, deg) * rad
        fun quad(a: V3, b: V3, c: V3, d: V3) {
            for (v in listOf(a, b, c, a, c, d)) { out += v.x.toFloat(); out += v.y.toFloat(); out += z }
        }
        for (k in 0 until 120) {
            val a0 = k * 3.0; val a1 = a0 + 3.0
            quad(p(r - band, a0), p(r + band, a0), p(r + band, a1), p(r - band, a1))
        }
        for (k in 0 until 36) {
            val deg = k * 10.0; val big = k % 3 == 0
            val len = if (big) 3.5 else 1.8; val w = if (big) 0.22 else 0.15      // half width, degrees of arc
            val dw = Math.toDegrees(w / r)
            quad(p(r, deg - dw), p(r + len, deg - dw), p(r + len, deg + dw), p(r, deg + dw))
        }
        return out.toFloatArray()
    }

    // ---------------------------------------------------------------- GL helpers

    private fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

    private fun loc(p: Int, name: String) = GLES20.glGetUniformLocation(p, name)

    private fun uniformMat(p: Int, name: String, m: Mat4) = GLES20.glUniformMatrix4fv(loc(p, name), 1, false, m.toFloats(), 0)

    private fun attrib(p: Int, name: String, buf: FloatBuffer, size: Int, strideFloats: Int, offsetFloats: Int) {
        val a = GLES20.glGetAttribLocation(p, name); if (a < 0) return
        buf.position(offsetFloats)
        GLES20.glVertexAttribPointer(a, size, GLES20.GL_FLOAT, false, strideFloats * 4, buf)
        GLES20.glEnableVertexAttribArray(a)
        buf.position(0)
    }

    private fun vboAttrib(p: Int, name: String, size: Int, strideBytes: Int, offsetFloats: Int) {
        val a = GLES20.glGetAttribLocation(p, name); if (a < 0) return
        GLES20.glVertexAttribPointer(a, size, GLES20.GL_FLOAT, false, strideBytes, offsetFloats * 4)
        GLES20.glEnableVertexAttribArray(a)
    }

    private fun disable(p: Int, name: String) {
        val a = GLES20.glGetAttribLocation(p, name); if (a >= 0) GLES20.glDisableVertexAttribArray(a)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("StarFinder", "shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) android.util.Log.e("StarFinder", "link: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val PRECISION = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
        """

        // ---- sky: per-pixel direction -> color (same model as SkyShade.color)
        private const val SKY_VS = """
            attribute vec2 aPos;
            varying vec2 vNdc;
            void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }
        """
        private const val SKY_FS = PRECISION + """
            varying vec2 vNdc;
            uniform mat4 uInv;
            uniform vec3 uZen; uniform vec3 uHor; uniform vec3 uGlowCol; uniform vec3 uSun;
            uniform float uGlow; uniform float uWidth; uniform float uHeight; uniform float uBelt; uniform float uSunAlt;
            const float RAD = 57.29578;
            vec3 sky(float alt, float daz, float gam) {
                float t = exp(-max(alt, 0.0) / 14.0);
                vec3 col = mix(uZen, uHor, t);
                float w = daz / uWidth;
                float g = uGlow * exp(-w * w) * exp(-max(alt, 0.0) / uHeight);
                col = mix(col, uGlowCol, g);
                float an = (180.0 - daz) / 80.0; float bb = (alt - 8.0) / 6.0;
                float b = uBelt * exp(-an * an) * exp(-bb * bb);
                col = mix(col, vec3(0.62, 0.42, 0.55), b);
                if (uSunAlt > -1.0) { float s = gam / 2.5; col += vec3(1.0, 0.95, 0.8) * exp(-s * s) * 1.2; }
                return clamp(col, 0.0, 1.0);
            }
            void main() {
                vec4 p = uInv * vec4(vNdc, 1.0, 1.0);
                vec3 d = normalize(p.xyz / p.w);
                float alt = asin(clamp(d.z, -1.0, 1.0)) * RAD;
                vec2 h = d.xy; vec2 sh = uSun.xy;
                float daz = 0.0;
                if (length(h) > 1e-4 && length(sh) > 1e-4) daz = acos(clamp(dot(normalize(h), normalize(sh)), -1.0, 1.0)) * RAD;
                if (alt < 0.0) {
                    vec3 dh = vec3(normalize(h + vec2(1e-6, 0.0)) * 0.99996, 0.0087);
                    float gam = acos(clamp(dot(dh, uSun), -1.0, 1.0)) * RAD;
                    gl_FragColor = vec4(sky(0.5, daz, gam) * (0.28 + 0.12 * exp(alt / 6.0)), 1.0);
                } else {
                    float gam = acos(clamp(dot(d, uSun), -1.0, 1.0)) * RAD;
                    gl_FragColor = vec4(sky(alt, daz, gam), 1.0);
                }
            }
        """

        // ---- stars, planets, Moon: round soft points faded by the limiting magnitude, hidden below the horizon
        private const val POINT_VS = """
            attribute vec3 aDir; attribute float aMag; attribute vec3 aCol; attribute float aMoon;
            uniform mat4 uVP; uniform float uLim; uniform float uScale;
            varying float vA; varying vec3 vC;
            void main() {
                gl_Position = uVP * vec4(aDir, 1.0);
                float a = aMoon > 0.5 ? 1.0 : clamp(uLim - aMag + 0.15, 0.0, 1.0);
                if (aDir.z < -0.002) a = 0.0;
                vA = a; vC = aCol;
                float d = aMoon > 0.5 ? 28.0 : max(3.0, (4.8 - aMag) * 4.2);
                gl_PointSize = d * uScale;
            }
        """
        private const val POINT_FS = """
            precision mediump float;
            varying float vA; varying vec3 vC;
            void main() {
                float r = length(gl_PointCoord - vec2(0.5)) * 2.0;
                float a = (1.0 - smoothstep(0.55, 1.0, r)) * vA;
                gl_FragColor = vec4(vC * a, a);
            }
        """

        // ---- plain lines / triangles in one color (constellations, horizon, compass ring, rigging)
        private const val LINE_VS = """
            attribute vec3 aDir; uniform mat4 uVP;
            void main() { gl_Position = uVP * vec4(aDir, 1.0); }
        """
        private const val LINE_FS = """
            precision mediump float;
            uniform vec4 uCol;
            void main() { gl_FragColor = uCol; }
        """

        // ---- the boat: per-vertex color, soft lighting, hull decal (two layouts: starboard / port)
        private const val BOAT_VS = """
            attribute vec3 aPos; attribute vec3 aNorm; attribute vec3 aCol; attribute vec2 aUv; attribute float aFlag;
            uniform mat4 uMVP; uniform mat3 uNormal; uniform vec3 uLight;
            varying vec3 vCol; varying vec2 vUv; varying float vFlag; varying float vShade;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
                vec3 n = normalize(uNormal * aNorm);
                vShade = 0.5 + 0.5 * max(dot(n, uLight), 0.0);
                vCol = aCol; vUv = aUv; vFlag = aFlag;
            }
        """
        private const val BOAT_FS = PRECISION + """
            varying vec3 vCol; varying vec2 vUv; varying float vFlag; varying float vShade;
            uniform sampler2D uTex; uniform float uDim;
            void main() {
                vec3 col = vCol;
                if (vFlag > 0.5) {
                    float inside = step(0.0, vUv.x) * step(vUv.x, 1.0) * step(0.0, vUv.y) * step(vUv.y, 1.0);
                    float row = vFlag > 1.5 ? 0.5 : 0.0;
                    vec4 t = texture2D(uTex, vec2(clamp(vUv.x, 0.0, 1.0), clamp(vUv.y, 0.0, 1.0) * 0.5 + row));
                    col = col * (1.0 - t.a * inside) + t.rgb * inside;     // texture is premultiplied
                }
                gl_FragColor = vec4(col * vShade * uDim, 1.0);
            }
        """

        // ---- navigation lights: glowing points
        private const val GLOW_VS = """
            attribute vec3 aPos; attribute vec3 aCol; uniform mat4 uMVP; uniform float uSize;
            varying vec3 vC;
            void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uSize; vC = aCol; }
        """
        private const val GLOW_FS = """
            precision mediump float;
            varying vec3 vC;
            void main() {
                float r = length(gl_PointCoord - vec2(0.5)) * 2.0;
                float core = 1.0 - smoothstep(0.12, 0.28, r);
                float halo = (1.0 - smoothstep(0.2, 1.0, r)) * 0.55;
                gl_FragColor = vec4(vC * max(core, halo) + vec3(core * 0.6), 1.0);
            }
        """
    }
}
