package com.thirdcoastsailing.app

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thirdcoastsailing.app.celestial.Coach
import com.thirdcoastsailing.app.celestial.FinderCameras
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.PhonePose
import com.thirdcoastsailing.app.celestial.Sky3D
import com.thirdcoastsailing.app.celestial.Sky3DObject
import com.thirdcoastsailing.app.celestial.SkyBody
import com.thirdcoastsailing.app.celestial.SkyObjectKind
import com.thirdcoastsailing.app.celestial.SightReduction
import com.thirdcoastsailing.app.celestial.SkyShade
import com.thirdcoastsailing.app.celestial.StarCatalog
import com.thirdcoastsailing.app.celestial.StarFinder
import com.thirdcoastsailing.app.celestial.V3
import com.thirdcoastsailing.app.celestial.formatLat
import com.thirdcoastsailing.app.celestial.formatLon
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * 3D star finder: hold the phone up on deck and the sky on screen turns with it (rotation-vector sensor + magnetic
 * declination, true bearings). For a rough look only, not measurement: phone compasses are good to a few degrees.
 * No camera is used; the sky is shaded for the real time and place.
 *
 *  - The boat (lower part of the screen) lies on its last course inside a compass ring fixed to north, seen from
 *    behind in the direction the phone faces. A vertical line runs from the boat up to the bearing box.
 *  - Every star, the constellation lines and the names are shown day or night (a learning aid, not the real sky).
 *    Below the horizon is solid ground: nothing is drawn through it.
 *  - An orange arrow points to the target body at every level: the body the finder was opened for, or one tapped.
 *    On screen it points at the body; off screen it points from the middle toward it, worked out from bearing and
 *    altitude so it never flips, with the body's name, bearing and altitude.
 *  - Tap a star, planet, Moon or Sun to select it; "Use <name>" sends it back to the workbook.
 *  - Pinch to zoom. Phones without a compass sensor: drag to look around.
 *  - ✕ (top left) or Back returns to the workbook.
 */

/** Star finder catalog, loaded once from assets. */
object FinderCatalog {
    @Volatile private var cached: StarCatalog? = null
    fun load(context: Context): StarCatalog = cached ?: synchronized(this) {
        cached ?: StarCatalog.parse(
            context.assets.open("celestial/stars.csv").bufferedReader().use { it.readText() },
            context.assets.open("celestial/constellation_lines.csv").bufferedReader().use { it.readText() },
            context.assets.open("celestial/constellations.csv").bufferedReader().use { it.readText() },
        ).also { cached = it }
    }
}

@Composable
fun StarFinderScreen(
    position: LatLon,
    positionIsGps: Boolean,
    boatHeadingDeg: Double?,           // last course; null = not known yet (boat drawn heading north)
    suggested: SkyBody?,               // orange arrow to this body (any level); a tapped body replaces it
    pickLabel: String?,                // e.g. "Use" (null = no picking, just looking)
    onPick: (SkyBody) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val androidView = LocalView.current
    val renderer = remember { StarFinderRenderer(context) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

    // ---- position frozen when the screen opens: every GPS fix is a new LatLon, and keying on it restarted the sky
    // build on each fix, so on a phone with steady fixes it never finished (no stars). The boat barely moves while
    // the finder is open, so one position is plenty; the rest of the app keeps its live GPS.
    val openPos = remember { position }
    val openIsGps = remember { positionIsGps }

    // ---- sky drawn once per opening (stars drift about 4° in 15 min, fine for finding them)
    var sky by remember { mutableStateOf<Sky3D?>(null) }
    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.Default) {
            runCatching { StarFinder.sky3d(FinderCatalog.load(context), openPos, System.currentTimeMillis()) }
                .onFailure { android.util.Log.e("StarFinder", "sky: $it") }.getOrNull()
        }
        if (s != null) { sky = s; renderer.setSky(s); glView?.requestRender() }
    }

    // ---- where the phone points
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val rotSensor = remember { sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    val declination = remember {
        GeomagneticField(openPos.lat.toFloat(), openPos.lon.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()
    }
    var pose by remember { mutableStateOf(PhonePose.look(boatHeadingDeg ?: 0.0, 15.0)) }
    var yaw by remember { mutableStateOf(boatHeadingDeg ?: 0.0) }
    var fov by remember { mutableStateOf(65.0) }
    var compassPoor by remember { mutableStateOf(false) }
    var dragAz by remember { mutableStateOf(boatHeadingDeg ?: 0.0) }
    var dragAlt by remember { mutableStateOf(15.0) }

    val heading by rememberUpdatedState(boatHeadingDeg)              // the GPS course can change while open
    fun push() {
        // boat camera follows the phone's compass direction; hold it when the phone points almost straight up or down
        if (abs(pose.forward.z) < 0.95) yaw = pose.azDeg
        renderer.view = StarFinderRenderer.View(pose, fov, yaw, heading ?: 0.0)
        glView?.requestRender()
    }

    DisposableEffect(rotSensor, declination) {
        if (rotSensor == null) return@DisposableEffect onDispose { }
        var q: DoubleArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values
                val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
                val w = if (v.size > 3) v[3].toDouble() else kotlin.math.sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
                val nq = doubleArrayOf(x, y, z, w)
                val sq = q?.let { PhonePose.blend(it, nq, 0.25) } ?: nq          // smooth the jitter
                q = sq
                val rot = when (androidView.display?.rotation) {
                    Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
                }
                pose = PhonePose.fromQuaternion(sq[0], sq[1], sq[2], sq[3], rot, declination)
                push()
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
                compassPoor = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
            }
        }
        sm.registerListener(listener, rotSensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    LaunchedEffect(boatHeadingDeg) { push() }

    // ---- GLSurfaceView follows the screen's lifecycle
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, glView) {
        val gv = glView ?: return@DisposableEffect onDispose { }
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) gv.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) gv.onPause()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); gv.onPause() }
    }

    BackHandler(onBack = onClose)
    var selected by remember { mutableStateOf<Sky3DObject?>(null) }
    // the arrow's body: a tapped one, else the one the finder was opened for; position worked out directly so it's
    // known even below the horizon (the sky list leaves out bodies well below it)
    val arrowBody: SkyBody? = selected?.body ?: suggested
    val arrowAltAz = remember(arrowBody, sky) {
        arrowBody?.let { b -> runCatching { SightReduction.altAz(b, openPos, System.currentTimeMillis()) }.getOrNull() }
    }
    val currentSky by rememberUpdatedState(sky)
    val measurer = rememberTextMeasurer()

    Box(Modifier.fillMaxSize().background(Color(0xFF0A1B2E))) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }.also { glView = it; push() }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- overlay: names, compass numbers, reference line, bearing box, beginner arrow, selection
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(rotSensor) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        fov = (fov / zoom).coerceIn(30.0, 100.0)
                        if (rotSensor == null) {                    // no compass: drag to look around
                            val degPerPx = fov / size.height
                            dragAz = (dragAz - pan.x * degPerPx + 360.0) % 360.0
                            dragAlt = (dragAlt + pan.y * degPerPx).coerceIn(-30.0, 89.0)
                            pose = PhonePose.look(dragAz, dragAlt)
                        }
                        push()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val s = currentSky ?: return@detectTapGestures
                        val cam = FinderCameras(pose, size.width, size.height, fov, yaw)
                        val hit = s.objects.filter { it.body != null && it.altDeg > -1.0 }.mapNotNull { o ->
                            val p = cam.projectSky(V3.altAz(o.altDeg, o.azDeg))
                            if (!p.onScreen) null else o to hypot(p.x - tap.x, p.y - tap.y)
                        }.filter { it.second < 44f * density }.minByOrNull { it.second }?.first
                        selected = hit
                    }
                }
        ) {
            val s = sky
            val cam = FinderCameras(pose, size.width.toInt(), size.height.toInt(), fov, yaw)
            val shade = SkyShade.forSun(s?.sunAltDeg ?: -30.0)
            val k = size.width / 720f
            if (s != null) drawSkyLabels(s, cam, shade, measurer, k)
            drawRingLabels(cam, measurer, k)
            // vertical reference line: from the boat up to the bearing box
            val base = cam.projectBoat(V3(0.0, 0.0, 4.0))
            val boxBottom = bearingBoxTop(k) + 90f * k
            drawLine(Foam.copy(alpha = 0.9f), Offset(size.width / 2, base.y), Offset(size.width / 2, boxBottom), 2f * k.coerceAtLeast(1f))
            // arrow to the target body (any level)
            if (arrowBody != null && arrowAltAz != null) drawTargetArrow(cam, arrowBody.name, arrowAltAz.hcDeg, arrowAltAz.znDeg, measurer, k)
            selected?.let { o ->
                val p = cam.projectSky(V3.altAz(o.altDeg, o.azDeg))
                if (p.onScreen) drawCircle(SignalOrange, 24f * k, Offset(p.x, p.y), style = Stroke(3f * k))
            }
            drawBearingBox(cam, measurer, k, boatHeadingDeg)
        }

        // ✕: back to the workbook
        Box(
            Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp).size(48.dp)
                .shadow(6.dp, CircleShape).clip(CircleShape).background(Navy).border(2.dp, SignalOrange, CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("✕", color = SignalOrange, fontSize = 22.sp, fontWeight = FontWeight.Bold) }

        // status notes under the bearing box
        Column(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 124.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (rotSensor == null) Note("No compass on this device: drag to look around")
            else if (compassPoor) Note("Compass needs calibrating: wave the phone in a figure 8")
            if (!openIsGps) Note("No GPS: sky for ${formatLat(openPos.lat)} ${formatLon(openPos.lon)}")
        }

        // selected body: name, where it is, and "Use <name>"
        selected?.let { o ->
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).widthIn(max = 360.dp)
                    .shadow(8.dp, RoundedCornerShape(14.dp)).background(Navy, RoundedCornerShape(14.dp))
                    .border(1.dp, SignalOrange, RoundedCornerShape(14.dp)).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(o.name ?: "", color = Foam, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Altitude %.0f° · bearing %03.0f° (%s) · mag %.1f".format(o.altDeg, o.azDeg, Coach.compassPoint(o.azDeg), o.mag),
                    color = Mist, fontSize = 13.sp)
                if (o.altDeg < 0) Text("Below the horizon now", color = Mist, fontSize = 13.sp)
                val b = o.body
                if (pickLabel != null && b != null && o.altDeg > 0) Button(
                    onClick = { onPick(b) },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("$pickLabel ${b.name}", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = Foam, fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp).background(Navy.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Names next to navigational stars, planets, Moon and Sun; constellation names (fading in as the sky darkens). */
private fun DrawScope.drawSkyLabels(s: Sky3D, cam: FinderCameras, shade: SkyShade, m: TextMeasurer, k: Float) {
    // constellation names: always shown (a learning aid), stronger when the sky is dark
    val art = 0.7f + 0.3f * shade.artAlpha
    for (n in s.names) {
        if (n.altDeg < 2.0) continue
        val p = cam.projectSky(V3.altAz(n.altDeg, n.azDeg)); if (!p.onScreen) continue
        val a = art * (if (n.nav) 0.8f else 0.55f)
        val t = m.measure(n.name.uppercase(), TextStyle(fontSize = textSp(17 * k, 10f), color = Color(0xFF78A5CD).copy(alpha = a), letterSpacing = 1.sp))
        drawText(t, topLeft = Offset(p.x - t.size.width / 2, p.y - t.size.height / 2))
    }
    for (o in s.objects) {
        if (o.name == null || o.altDeg < 0) continue
        val p = cam.projectSky(V3.altAz(o.altDeg, o.azDeg)); if (!p.onScreen) continue
        // every named body (navigation stars, planets, Moon, Sun) gets a floating label on a soft backing, day or night
        val col = when (o.kind) { SkyObjectKind.PLANET -> Color(0xFFFFD696); else -> Foam }
        val r = when (o.kind) { SkyObjectKind.MOON -> 14f; SkyObjectKind.SUN -> 18f; else -> ((4.8 - o.mag) * 2.1).toFloat().coerceAtLeast(1.5f) } * k
        val t = m.measure(o.name, TextStyle(fontSize = textSp(22 * k, 13f), color = col))
        val tl = Offset(p.x + r + 8f * k, p.y - t.size.height / 2)
        drawRoundRect(Color(0xFF0A1B2E).copy(alpha = 0.6f), tl - Offset(5f * k, 1f * k),
            Size(t.size.width + 10f * k, t.size.height + 2f * k), CornerRadius(7f * k))
        drawText(t, topLeft = tl)
    }
}

/** Compass ring numbers around the boat: N E S W bold, every 30° in between. */
private fun DrawScope.drawRingLabels(cam: FinderCameras, m: TextMeasurer, k: Float) {
    for (deg in 0 until 360 step 30) {
        val p = cam.projectBoat(V3.altAz(0.0, deg.toDouble()) * 36.0 + V3(0.0, 0.0, 0.3))
        if (!p.inFront || p.y > size.height + 40 || p.y < 0) continue
        val cardinal = deg % 90 == 0
        val txt = when (deg) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> "%03d".format(deg) }
        val t = m.measure(txt, TextStyle(fontSize = textSp((if (cardinal) 28 else 22) * k, 12f),
            color = Foam, fontWeight = if (cardinal) FontWeight.Bold else FontWeight.Normal))
        drawText(t, topLeft = Offset(p.x - t.size.width / 2, p.y - t.size.height / 2))
    }
}

/** Top of the bearing box: below the status bar. */
private fun DrawScope.bearingBoxTop(k: Float) = 40.dp.toPx() + 30f * k

/** Bearing box at the top: where the phone points (true) and its altitude, and the boat's heading. */
private fun DrawScope.drawBearingBox(cam: FinderCameras, m: TextMeasurer, k: Float, boatHeadingDeg: Double?) {
    val w = 340f * k; val h = 90f * k
    val left = size.width / 2 - w / 2; val top = bearingBoxTop(k)
    drawRoundRect(Color(0xFF0F2742), Offset(left, top), Size(w, h), CornerRadius(16f * k))
    drawRoundRect(SignalOrange, Offset(left, top), Size(w, h), CornerRadius(16f * k), style = Stroke(3f * k))
    val az = cam.pose.azDeg; val alt = cam.pose.altDeg
    val big = m.measure("%03.0f°  %s".format(az, Coach.compassPoint(az)),
        TextStyle(fontSize = textSp(28 * k, 18f), color = Foam, fontWeight = FontWeight.Bold))
    drawText(big, topLeft = Offset(size.width / 2 - big.size.width / 2, top + h * 0.36f - big.size.height / 2))
    val sub = "altitude %.0f°  ·  boat heading %s".format(alt, boatHeadingDeg?.let { "%03.0f°".format(it) } ?: "—")
    val small = m.measure(sub, TextStyle(fontSize = textSp(17 * k, 11f), color = Color(0xFFAFC2D6)))
    drawText(small, topLeft = Offset(size.width / 2 - small.size.width / 2, top + h * 0.76f - small.size.height / 2))
}

/**
 * Orange arrow to the target body. On screen: a ring round it and an arrow pointing at it. Off screen: an arrow from
 * the middle of the sky toward it, with its name, bearing and altitude. The direction comes from where the body is
 * relative to the phone's own right and up axes (not from the screen projection), so it never flips when the body is
 * behind you. Below the horizon the label says so.
 */
private fun DrawScope.drawTargetArrow(cam: FinderCameras, name: String, altDeg: Double, azDeg: Double, m: TextMeasurer, k: Float) {
    val dir = V3.altAz(altDeg, azDeg)
    val p = cam.projectSky(dir)
    val orange = SignalOrange
    if (p.onScreen && altDeg > 0.0) {
        val c = Offset(p.x, p.y)
        drawCircle(orange, 26f * k, c, style = Stroke(4f * k))
        val tip = c + Offset(-33f * k, 33f * k)
        drawLine(orange, c + Offset(-75f * k, 75f * k), tip, 8f * k, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(c.x - 28f * k, c.y + 28f * k); lineTo(c.x - 52f * k, c.y + 34f * k); lineTo(c.x - 34f * k, c.y + 52f * k); close()
        }
        drawPath(head, orange)
        return
    }
    // off screen: which way to turn, from the body's direction against the phone's right and up axes (screen y is down)
    val rightAxis = (cam.pose.forward cross cam.pose.up).unit()
    var dx = (dir dot rightAxis).toFloat(); val dy = -(dir dot cam.pose.up).toFloat()
    if (abs(dx) < 1e-3 && abs(dy) < 1e-3) dx = 1f                     // straight behind: point sideways
    val cx = size.width / 2; val cy = size.height * 0.35f
    val ang = atan2(dy, dx)
    val r = minOf(size.width, size.height) * 0.36f
    val tip = Offset(cx + cos(ang) * r, cy + sin(ang) * r)
    val tail = Offset(cx + cos(ang) * (r - 70f * k), cy + sin(ang) * (r - 70f * k))
    drawLine(orange, tail, tip, 9f * k, cap = StrokeCap.Round)
    val left = ang + Math.PI.toFloat() * 0.8f; val right = ang - Math.PI.toFloat() * 0.8f
    val head = Path().apply {
        moveTo(tip.x + cos(ang) * 12f * k, tip.y + sin(ang) * 12f * k)
        lineTo(tip.x + cos(left) * 30f * k, tip.y + sin(left) * 30f * k)
        lineTo(tip.x + cos(right) * 30f * k, tip.y + sin(right) * 30f * k); close()
    }
    drawPath(head, orange)
    // label behind the arrow: name, bearing, altitude (or below the horizon)
    val txt = if (altDeg < 0.0) "$name · below the horizon now" else "%s  %03.0f°  %.0f° up".format(name, azDeg, altDeg)
    val t = m.measure(txt, TextStyle(fontSize = textSp(18 * k, 12f), color = Foam, fontWeight = FontWeight.Bold))
    val lx = (tail.x - cos(ang) * 24f * k - t.size.width / 2).coerceIn(8f * k, size.width - t.size.width - 8f * k)
    val ly = (tail.y - sin(ang) * 34f * k - t.size.height / 2).coerceIn(8f * k, size.height - t.size.height - 8f * k)
    drawRoundRect(Navy, Offset(lx - 8f * k, ly - 4f * k), Size(t.size.width + 16f * k, t.size.height + 8f * k), CornerRadius(8f * k))
    drawRoundRect(orange, Offset(lx - 8f * k, ly - 4f * k), Size(t.size.width + 16f * k, t.size.height + 8f * k), CornerRadius(8f * k), style = Stroke(2f * k))
    drawText(t, topLeft = Offset(lx, ly))
}

/**
 * Text size for `px` pixels (sizes were designed on a 720 px wide screen and scaled by k), never smaller than
 * `minSp` so it stays readable on small screens.
 */
private fun DrawScope.textSp(px: Float, minSp: Float): androidx.compose.ui.unit.TextUnit =
    maxOf(px / (density * fontScale), minSp).sp
