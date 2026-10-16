package com.thirdcoastsailing.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.thirdcoastsailing.app.celestial.LatLon
import com.thirdcoastsailing.app.celestial.bearing
import com.thirdcoastsailing.app.celestial.distanceNm
import com.thirdcoastsailing.app.celestial.travel
import com.thirdcoastsailing.app.ui.theme.Caution
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyDeep
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Anchor watch.
 *
 *  - Menu: an Anchor button (opens the setup screen) with an on/off switch, and a hold-to-reset arrow while an anchor
 *    point is saved. Switching on with no saved anchor drops the anchor at the boat's GPS position (0 ft offset: mark
 *    it from the bow as the anchor goes down). The setup screen can instead place it ahead: point the top of the
 *    phone at the anchor, type the distance, and the compass bearing places it.
 *  - Chart: the anchor symbol at the anchor point, a bold red circle at the alarm radius (default 100 ft), and a
 *    dashed line of the boat's GPS positions as it swings.
 *  - Top of the chart: a large yellow box with the distance and true bearing to the anchor, "GPS fix lost" when there
 *    has been no fix for 10 minutes, and in an alarm a red border with "Alarm Silence" in bold.
 *  - Alarm: outside the radius, or no GPS fix for 10 minutes. A ship's bell struck in pairs (made in code) on the
 *    alarm sound channel, which sounds even on silent or Do Not Disturb, plus vibration. Silence it with either
 *    volume button while the app is on screen, by tapping the yellow box, or with "Silence 15 min" on the
 *    notification. It re-arms after 15 minutes and sounds again if the boat is still outside the circle.
 *  - Runs all night with the screen off (a foreground service, one GPS fix every 2 minutes). Closing the app (swiping
 *    it away) stops the watch. The anchor point and settings are kept on the phone, so after a dead battery and a
 *    restart the anchor is still marked; switch the watch back on to resume.
 */

/** Feet per nautical mile. */
private const val FT_PER_NM = 6076.12

object AnchorWatch {
    const val DEFAULT_RADIUS_FT = 100.0
    const val FIX_MS = 2 * 60_000L                 // one GPS fix every 2 minutes; the radius is checked on each
    const val LOST_MS = 10 * 60_000L               // no fix for this long = "GPS fix lost" alarm
    const val SNOOZE_MS = 15 * 60_000L             // silence lasts this long, then the alarm re-arms
    private const val MAX_TRACE = 600              // swing trace points kept (20 hours at 2 minutes)
    private const val PREFS = "tcs_anchor"

    data class State(
        val anchor: GeoPt? = null,                 // anchor position (saved on the phone)
        val setMs: Long = 0L,                      // when it was set
        val radiusFt: Double = DEFAULT_RADIUS_FT,  // alarm radius (saved)
        val offsetFt: Double = 0.0,                // distance from the phone to the anchor when it was set (saved)
        val armed: Boolean = false,                // watch running
        val fix: BoatFix? = null,                  // latest GPS fix from the watch
        val trace: List<GeoPt> = emptyList(),      // swing trace (memory only)
        val alarming: Boolean = false,             // bell sounding now
        val fixLost: Boolean = false,              // no fix for LOST_MS
        val snoozedUntilMs: Long = 0L,             // silenced until this time
    ) {
        /** Distance (ft) and true bearing from the boat to the anchor, or null without both. */
        fun toAnchor(): Pair<Double, Double>? {
            val a = anchor ?: return null; val f = fix ?: return null
            val from = LatLon(f.lat, f.lon); val to = LatLon(a.lat, a.lon)
            return distanceNm(from, to) * FT_PER_NM to bearing(from, to)
        }

        /** True when the latest fix is outside the alarm radius. */
        val outside: Boolean get() = (toAnchor()?.first ?: 0.0) > radiusFt
    }

    var state by mutableStateOf(State())
        private set
    private var loaded = false

    /** Saved anchor point and settings, read once per process. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = p.getString("lat", null)?.toDoubleOrNull()
        val lon = p.getString("lon", null)?.toDoubleOrNull()
        val anchor = if (lat != null && lon != null) GeoPt(lat, lon) else null
        state = State(anchor = anchor, setMs = p.getLong("setMs", 0L),
            radiusFt = p.getString("radiusFt", null)?.toDoubleOrNull() ?: DEFAULT_RADIUS_FT,
            offsetFt = p.getString("offsetFt", null)?.toDoubleOrNull() ?: 0.0)
    }

    private fun save(context: Context) {
        val s = state
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (s.anchor != null) {
                putString("lat", s.anchor.lat.toString()); putString("lon", s.anchor.lon.toString())   // full precision
                putLong("setMs", s.setMs)
            } else { remove("lat"); remove("lon"); remove("setMs") }
            putString("radiusFt", s.radiusFt.toString()); putString("offsetFt", s.offsetFt.toString())
        }.apply()
    }

    /** Change the state (main thread only) and save what's kept. */
    fun update(context: Context, f: (State) -> State) { state = f(state); save(context) }

    fun setRadius(context: Context, ft: Double) = update(context) { it.copy(radiusFt = ft.coerceIn(20.0, 2000.0)) }

    /**
     * Drop the anchor `offsetFt` from the boat's position along `bearingDeg` (true) and start the watch.
     * 0 ft = at the boat (marked from the bow during the drop).
     */
    fun drop(context: Context, at: BoatFix, offsetFt: Double, bearingDeg: Double) {
        val p = if (offsetFt <= 0.0) GeoPt(at.lat, at.lon)
        else travel(LatLon(at.lat, at.lon), bearingDeg, offsetFt / FT_PER_NM).let { GeoPt(it.lat, it.lon) }
        update(context) {
            it.copy(anchor = p, setMs = System.currentTimeMillis(), offsetFt = offsetFt.coerceAtLeast(0.0),
                fix = at, trace = listOf(GeoPt(at.lat, at.lon)), alarming = false, fixLost = false, snoozedUntilMs = 0L)
        }
        arm(context, at)
    }

    /**
     * Switch the watch on: with a saved anchor it resumes on that point; with none it drops the anchor at `fix`
     * (0 ft offset). Returns false when there is no anchor and no GPS fix yet.
     */
    fun arm(context: Context, fix: BoatFix?): Boolean {
        if (state.anchor == null) {
            val f = fix ?: return false
            update(context) { it.copy(anchor = GeoPt(f.lat, f.lon), setMs = System.currentTimeMillis(), offsetFt = 0.0,
                fix = f, trace = listOf(GeoPt(f.lat, f.lon))) }
        }
        update(context) { it.copy(armed = true, alarming = false, fixLost = false, snoozedUntilMs = 0L, fix = it.fix ?: fix) }
        AnchorService.start(context)
        return true
    }

    /** Switch the watch off (the anchor point stays saved). */
    fun disarm(context: Context) {
        update(context) { it.copy(armed = false, alarming = false, fixLost = false, snoozedUntilMs = 0L) }
        AnchorService.stop(context)
    }

    /** Hold-to-reset: stop the watch and forget the anchor point and its trace (radius stays). */
    fun reset(context: Context) {
        AnchorService.stop(context)
        update(context) { State(radiusFt = it.radiusFt) }
    }

    /** From the service: a new GPS fix. */
    internal fun onFix(context: Context, f: BoatFix) = update(context) {
        it.copy(fix = f, fixLost = false, trace = (it.trace + GeoPt(f.lat, f.lon)).takeLast(MAX_TRACE))
    }
}

// ============================================================================ the service: GPS, alarm, notification

private const val ANCHOR_CHANNEL = "anchor_watch"
private const val ANCHOR_NOTE_ID = 2
private const val ACTION_SILENCE = "com.thirdcoastsailing.app.ANCHOR_SILENCE"
private const val ACTION_CHECK = "com.thirdcoastsailing.app.ANCHOR_CHECK"

/**
 * Keeps the anchor watch running with the screen off: one GPS fix every 2 minutes, the radius checked on each, a
 * timer for "GPS fix lost", and the bell. Android requires a visible notification for this ("foreground service").
 * Swiping the app away stops it.
 */
class AnchorService : Service() {

    private val lm by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private var bell: ShipsBell? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastFixMs = 0L

    private val listener = LocationListener { loc ->
        val sogKn = if (loc.hasSpeed()) loc.speed * 1.943844f else null
        lastFixMs = System.currentTimeMillis()
        AnchorWatch.onFix(this, BoatFix(loc.latitude, loc.longitude, null, sogKn, loc.time))
        evaluate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AnchorWatch.load(this)
        startForeground(ANCHOR_NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!AnchorWatch.state.armed || AnchorWatch.state.anchor == null) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_SILENCE -> silence()
            ACTION_CHECK -> check()
            else -> {
                if (lastFixMs == 0L) lastFixMs = System.currentTimeMillis()          // the lost-fix clock starts now
                val ok = runCatching {
                    lm.removeUpdates(listener)
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, AnchorWatch.FIX_MS, 0f, listener, Looper.getMainLooper())
                }.isSuccess
                if (!ok) { AnchorWatch.disarm(this); return START_NOT_STICKY }       // no permission or no GPS
                evaluate()
            }
        }
        return START_NOT_STICKY
    }

    /** Sound the alarm if outside the circle or the fix is lost, unless silenced; refresh the notification. */
    private fun evaluate() {
        val s = AnchorWatch.state
        val now = System.currentTimeMillis()
        if ((s.outside || s.fixLost) && now >= s.snoozedUntilMs) startAlarm()
        scheduleCheck()
        updateNotification()
    }

    /** Timer: mark the fix lost after 10 minutes without one, and re-arm after a silence. */
    private fun check() {
        val now = System.currentTimeMillis()
        if (now - lastFixMs >= AnchorWatch.LOST_MS && !AnchorWatch.state.fixLost)
            AnchorWatch.update(this) { it.copy(fixLost = true) }
        evaluate()
    }

    /** Next timer: when the fix would count as lost, or when the silence ends, whichever is first. */
    private fun scheduleCheck() {
        val now = System.currentTimeMillis()
        val lostAt = lastFixMs + AnchorWatch.LOST_MS + 5_000L
        val snoozeEnd = AnchorWatch.state.snoozedUntilMs.takeIf { it > now }
        val at = listOfNotNull(lostAt.takeIf { it > now }, snoozeEnd).minOrNull() ?: (now + AnchorWatch.LOST_MS)
        // allowed while the phone is idle (screen off); Android may delay it a few minutes, fine for these timers
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, servicePending(ACTION_CHECK))
    }

    private fun startAlarm() {
        if (AnchorWatch.state.alarming) return
        AnchorWatch.update(this) { it.copy(alarming = true) }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThirdCoast:anchor").apply { acquire(AnchorWatch.SNOOZE_MS) }
        bell = ShipsBell(this).also { it.start() }
    }

    private fun stopAlarm() {
        bell?.stop(); bell = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
    }

    /** Volume button, yellow box or notification: quiet for 15 minutes, then re-arm. */
    private fun silence() {
        stopAlarm()
        AnchorWatch.update(this) { it.copy(alarming = false, snoozedUntilMs = System.currentTimeMillis() + AnchorWatch.SNOOZE_MS) }
        scheduleCheck()
        updateNotification()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {          // app closed: the watch stops (the anchor stays saved)
        AnchorWatch.disarm(this)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { lm.removeUpdates(listener) }
        runCatching { alarms.cancel(servicePending(ACTION_CHECK)) }
        stopAlarm()
        super.onDestroy()
    }

    private fun servicePending(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, AnchorService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(ANCHOR_NOTE_ID, notification())
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ANCHOR_CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(ANCHOR_CHANNEL, "Anchor watch", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) })                 // the bell is played by the app, not the notification
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val s = AnchorWatch.state
        val d = s.toAnchor()
        val text = when {
            s.fixLost -> "GPS fix lost"
            d == null -> "Waiting for GPS"
            else -> "%d ft · anchor %03.0f°T · radius %d ft".format(d.first.roundToInt(), d.second, s.radiusFt.roundToInt())
        }
        val title = when {
            s.alarming -> "ANCHOR ALARM"
            s.snoozedUntilMs > System.currentTimeMillis() -> "Anchor watch · silenced"
            else -> "Anchor watch"
        }
        return Notification.Builder(this, ANCHOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, "Silence 15 min", servicePending(ACTION_SILENCE)).build())
            .build()
    }

    companion object {
        fun start(context: Context) { runCatching { context.startForegroundService(Intent(context, AnchorService::class.java)) } }
        fun stop(context: Context) { context.stopService(Intent(context, AnchorService::class.java)) }
        /** Silence the alarm for 15 minutes (volume buttons, the yellow box). */
        fun silence(context: Context) {
            runCatching { context.startService(Intent(context, AnchorService::class.java).setAction(ACTION_SILENCE)) }
        }
    }
}

// ============================================================================ the bell

/**
 * A ship's bell, made in code (no sound file): each strike is a sum of bell partials with their own decays, struck
 * twice close together ("two bells") then a pause, looped. Played on the alarm channel at full alarm volume (put
 * back afterwards), with vibration in time with the strikes.
 */
private class ShipsBell(private val context: Context) {
    private var track: AudioTrack? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private var savedVolume = -1

    fun start() {
        runCatching {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val pcm = synth()
        track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                .apply { write(pcm, 0, pcm.size); setLoopPoints(0, pcm.size, -1); play() }
        }.getOrNull()
        runCatching {
            // two pulses with the two strikes, then rest; repeats until stopped
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 350, 120, 350, 1780), intArrayOf(0, 255, 0, 255, 0), 0)
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        }
    }

    fun stop() {
        runCatching { track?.stop(); track?.release() }; track = null
        runCatching { vibrator?.cancel() }
        if (savedVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
    }

    companion object {
        private const val RATE = 22050
        private const val LOOP_S = 2.6                               // one "two bells" pattern
        private val STRIKES = doubleArrayOf(0.0, 0.47)               // seconds into the pattern
        private const val F0 = 660.0                                 // strike tone (Hz)
        // bell partials: ratio to F0, loudness, decay time (s): hum, prime, tierce, quint, nominal and above
        private val PARTIALS = arrayOf(
            doubleArrayOf(0.5, 0.30, 2.4), doubleArrayOf(1.0, 0.55, 1.8), doubleArrayOf(1.19, 0.38, 1.3),
            doubleArrayOf(1.5, 0.22, 1.1), doubleArrayOf(2.0, 0.50, 1.0), doubleArrayOf(2.51, 0.18, 0.6),
            doubleArrayOf(2.66, 0.14, 0.5), doubleArrayOf(3.01, 0.12, 0.4), doubleArrayOf(4.07, 0.08, 0.25),
        )

        fun synth(): ShortArray {
            val n = (RATE * LOOP_S).toInt()
            val buf = DoubleArray(n)
            for (t0 in STRIKES) {
                val start = (t0 * RATE).toInt()
                for (i in start until n) {
                    val t = (i - start).toDouble() / RATE
                    val attack = (t / 0.003).coerceAtMost(1.0)               // 3 ms strike
                    var v = 0.0
                    for (p in PARTIALS) v += p[1] * exp(-t / p[2]) * sin(2 * PI * F0 * p[0] * t)
                    buf[i] += v * attack
                }
            }
            val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
            return ShortArray(n) { (buf[it] / peak * 0.9 * Short.MAX_VALUE).toInt().toShort() }
        }
    }
}

// ============================================================================ chart, banner, menu row, setup screen

/** Anchor symbol, bold red alarm circle and the dashed swing trace over the chart. */
@Composable
fun AnchorOverlay(map: MapLibreMap?, tick: Int, night: Boolean) {
    val s = AnchorWatch.state
    Canvas(Modifier.fillMaxSize()) {
        tick                                                             // read so camera moves redraw
        val m = map ?: return@Canvas
        val a = s.anchor ?: return@Canvas
        fun xy(lat: Double, lon: Double) = m.projection.toScreenLocation(LatLng(lat, lon)).let { Offset(it.x, it.y) }
        val c = xy(a.lat, a.lon)
        // swing trace: dashed line through the watch's GPS fixes
        if (s.trace.size > 1) {
            val path = Path()
            s.trace.forEachIndexed { i, p -> val o = xy(p.lat, p.lon); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, if (night) Foam else Navy, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
        }
        // alarm radius: bold red circle
        val edge = travel(LatLon(a.lat, a.lon), 0.0, s.radiusFt / FT_PER_NM).let { xy(it.lat, it.lon) }
        val r = hypot(edge.x - c.x, edge.y - c.y)
        drawCircle(Color(0xFFE5484D), r, c, style = Stroke(3.5.dp.toPx()))
        // the anchor
        drawAnchorSymbol(c, 13.dp.toPx())
    }
}

/** Orange anchor on a navy disc, centered at `c`, radius `r`. */
private fun DrawScope.drawAnchorSymbol(c: Offset, r: Float) {
    drawCircle(Navy, r, c); drawCircle(SignalOrange, r, c, style = Stroke(r * 0.14f))
    val k = r / 13f; val w = 2.4f * k
    drawCircle(SignalOrange, 2.2f * k, c + Offset(0f, -7f * k), style = Stroke(1.8f * k))
    drawLine(SignalOrange, c + Offset(0f, -4.8f * k), c + Offset(0f, 8f * k), w, StrokeCap.Round)
    drawLine(SignalOrange, c + Offset(-4.5f * k, -2f * k), c + Offset(4.5f * k, -2f * k), w, StrokeCap.Round)
    drawArc(SignalOrange, 20f, 140f, false, c + Offset(-7.5f * k, -2.5f * k),
        androidx.compose.ui.geometry.Size(15f * k, 11f * k), style = Stroke(w, cap = StrokeCap.Round))
}

/**
 * Large yellow box at the top while the watch is on: distance and true bearing to the anchor, or "GPS fix lost".
 * In an alarm the border turns red and it reads "Alarm Silence" (tap it, or press a volume button).
 */
@Composable
fun AnchorBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = AnchorWatch.state
    if (!s.armed) return
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.snoozedUntilMs) { while (now < s.snoozedUntilMs) { delay(15_000); now = System.currentTimeMillis() } }
    val snoozed = s.snoozedUntilMs > now
    val red = s.alarming || snoozed
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Caution, shape)
            .border(if (red) 5.dp else 2.dp, if (red) Color(0xFFE5484D) else NavyDeep, shape)
            .clickable(enabled = s.alarming) { AnchorService.silence(context) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (s.alarming) Text("Alarm Silence", color = NavyDeep, fontSize = 26.sp, fontWeight = FontWeight.Black)
        val d = s.toAnchor()
        when {
            s.fixLost -> Text("GPS fix lost", color = NavyDeep, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            d == null -> Text("Waiting for GPS", color = NavyDeep, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            else -> Text("⚓ %d ft · %03.0f°T".format(d.first.roundToInt(), d.second), color = NavyDeep,
                fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            if (snoozed && !s.alarming) "Silenced · %d min · radius %d ft".format(((s.snoozedUntilMs - now) / 60_000 + 1).toInt(), s.radiusFt.roundToInt())
            else "radius %d ft".format(s.radiusFt.roundToInt()),
            color = NavyDeep, fontSize = 13.sp,
        )
    }
}

/** Menu row: Anchor button (opens setup), hold-to-reset while an anchor is saved, and the watch on/off switch. */
@Composable
fun AnchorMenuRow(onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val s = AnchorWatch.state
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { MenuButton(MenuIcon.ANCHOR, "Anchor", onClick = onOpen) }
        if (s.anchor != null) HoldToResetArrow(onReset = { AnchorWatch.reset(context) })
        Switch(checked = s.armed, onCheckedChange = onToggle, modifier = Modifier.padding(start = 4.dp),
            colors = SwitchDefaults.colors(checkedTrackColor = SignalOrange, checkedThumbColor = Foam,
                uncheckedTrackColor = NavyLight, uncheckedThumbColor = Mist, uncheckedBorderColor = NavyLight))
    }
}

/**
 * Anchor setup: alarm radius, and where the anchor is. "Drop here" marks it at the boat (0 ft, from the bow as it
 * goes down). Or point the top of the phone at the anchor, type the distance, and "Set ahead" uses the compass.
 */
@Composable
fun AnchorScreen(fix: BoatFix?, onClose: () -> Unit) {
    val context = LocalContext.current
    val s = AnchorWatch.state
    var radiusText by remember { mutableStateOf(s.radiusFt.roundToInt().toString()) }
    var offsetText by remember { mutableStateOf(if (s.offsetFt > 0) s.offsetFt.roundToInt().toString() else "") }
    val heading = rememberPhoneHeading(fix)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Navy).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MenuIconView(MenuIcon.ANCHOR, Modifier.size(30.dp))
                Text("Anchor", color = Foam, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp).weight(1f))
                TextButton(onClick = onClose) { Text("Done ✓", color = SignalOrange, fontWeight = FontWeight.Bold) }
            }

            // ---- radius
            Text("ALARM RADIUS", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    val v = ((radiusText.toDoubleOrNull() ?: s.radiusFt) - 25).coerceAtLeast(25.0)
                    radiusText = v.roundToInt().toString(); AnchorWatch.setRadius(context, v)
                }) { Text("−25", color = SignalOrange, fontSize = 18.sp) }
                OutlinedTextField(radiusText, { t -> radiusText = t.filter { it.isDigit() }.take(4); radiusText.toDoubleOrNull()?.let { AnchorWatch.setRadius(context, it) } },
                    singleLine = true, suffix = { Text("ft", color = Mist) }, modifier = Modifier.width(120.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Foam, fontSize = 20.sp))
                OutlinedButton(onClick = {
                    val v = (radiusText.toDoubleOrNull() ?: s.radiusFt) + 25
                    radiusText = v.roundToInt().toString(); AnchorWatch.setRadius(context, v)
                }) { Text("+25", color = SignalOrange, fontSize = 18.sp) }
            }

            // ---- where the anchor is
            Text("ANCHOR POINT", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(if (fix == null) "Waiting for GPS…" else latLonLine(fix.lat, fix.lon), color = Mist, fontSize = 13.sp)
            Button(onClick = { fix?.let { AnchorWatch.drop(context, it, 0.0, 0.0); onClose() } }, enabled = fix != null,
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = Navy),
                modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("⚓  Drop here  (0 ft)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Point phone top at anchor", color = Foam, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(heading?.let { "%03.0f°T".format(it) } ?: "—", color = Foam, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(offsetText, { t -> offsetText = t.filter { it.isDigit() }.take(4) }, singleLine = true,
                    label = { Text("Distance", color = Mist) }, suffix = { Text("ft", color = Mist) }, modifier = Modifier.width(150.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Foam, fontSize = 20.sp))
                val off = offsetText.toDoubleOrNull() ?: 0.0
                OutlinedButton(onClick = { val f = fix; val h = heading; if (f != null && h != null) { AnchorWatch.drop(context, f, off, h); onClose() } },
                    enabled = fix != null && heading != null && off > 0, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text("Set ahead", color = SignalOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (s.anchor != null) Text("Anchor set %s · hold ↺ in the menu to clear it".format(
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.setMs))), color = Mist, fontSize = 12.sp)
        }
    }
}

private fun latLonLine(lat: Double, lon: Double) =
    "%.5f° %s  %.5f° %s".format(kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

/** True heading (°) of the top of the phone from the compass (magnetic variation applied), or null without one. */
@Composable
private fun rememberPhoneHeading(fix: BoatFix?): Double? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Double?>(null) }
    val decl = remember(fix == null) {
        fix?.let { GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble() } ?: 0.0
    }
    DisposableEffect(decl) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)                  // ori[0]: azimuth of the phone's top edge
                val h = (Math.toDegrees(ori[0].toDouble()) + decl + 360.0) % 360.0
                val prev = heading
                // light smoothing across north
                heading = if (prev == null) h else (prev + (((h - prev + 540.0) % 360.0) - 180.0) * 0.2 + 360.0) % 360.0
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return heading
}
