package com.thirdcoastsailing.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

/*
 * Barograph logging. The pressure sensor is read in short bursts (about 10 readings over 2 seconds, keeping the
 * median), every 5 minutes, or every minute while the weather is turning. Between bursts the sensor is off.
 * The bursts are timed by Android's "allow while idle" alarm, so it works the same on any phone and keeps running
 * with the screen off (in deep sleep Android may stretch the 5 minutes to about 10). Android only lets an app read
 * the sensor in the background from a foreground service, so logging shows a small notification, like the trip track.
 * The log is kept on the phone until it is reset (hold the reset arrow), like the trip log; readings older than
 * 14 days are dropped.
 */
object Baro {
    private const val PREFS = "tcs_baro"
    private const val KEEP_MS = 14 * 24 * BaroMath.HOUR
    private const val BURST_MS = 2_000L

    /** Changes whenever the log does (Compose reads it to redraw). */
    var version by mutableIntStateOf(0)
        private set

    private var cache: MutableList<BaroPoint>? = null
    /** The barograph log file: one "time,hPa" line per reading. */
    private fun file(c: Context) = File(c.filesDir, "baro_log.csv")
    /** Barograph settings and warning state. */
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the phone has a pressure sensor (many phones don't). */
    fun hasSensor(c: Context): Boolean = (c.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    /** True when the user has switched barograph logging on. */
    fun isOn(c: Context) = prefs(c).getBoolean("on", false)

    /** All readings, oldest first (station pressure at the phone). */
    @Synchronized
    fun log(c: Context): List<BaroPoint> {
        cache?.let { return it.toList() }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val pts = runCatching {
            file(c).readLines().mapNotNull { l ->
                val p = l.split(','); if (p.size < 2) null else BaroPoint(p[0].toLong(), p[1].toDouble())
            }
        }.getOrDefault(emptyList()).filter { it.t >= cutoff }.sortedBy { it.t }.toMutableList()
        cache = pts
        return pts.toList()
    }

    /** Add one reading, dropping readings older than KEEP_MS (the file is rewritten only now and then). */
    @Synchronized
    fun add(c: Context, p: BaroPoint) {
        val pts = cache ?: log(c).toMutableList().also { cache = it }
        pts += p
        val cutoff = System.currentTimeMillis() - KEEP_MS
        if (pts.first().t < cutoff) {                               // drop old readings, rewrite the file now and then
            pts.removeAll { it.t < cutoff }
            runCatching { file(c).writeText(pts.joinToString("") { "${it.t},${"%.2f".format(java.util.Locale.US, it.hPa)}\n" }) }
        } else runCatching { file(c).appendText("${p.t},${"%.2f".format(java.util.Locale.US, p.hPa)}\n") }
        version++
    }

    /** Clear the log and the warning state (hold-to-reset on the barograph page). */
    @Synchronized
    fun reset(c: Context) {
        cache = mutableListOf(); runCatching { file(c).delete() }
        prefs(c).edit().remove("turningUntil").putInt("warnEpisode", 0).putLong("calmSince", 0L).apply()
        version++
    }

    /** Lake surface height for sea-level pressure, from the last known position. */
    fun elevationM(c: Context) = prefs(c).getFloat("elevM", 176f).toDouble()
    /** Set the lake surface elevation (m) for the boat's position, used to convert the phone's reading to sea-level pressure. */
    fun setPosition(c: Context, lat: Double, lon: Double) {
        val e = BaroMath.lakeElevationM(lat, lon).toFloat()
        if (e != prefs(c).getFloat("elevM", 176f)) { prefs(c).edit().putFloat("elevM", e).apply(); version++ }
    }

    /** Switch logging on or off (starts or stops the background service). */
    fun setOn(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("on", on).apply()
        if (on) BaroService.start(c) else BaroService.stop(c)
        version++
    }

    /** Next burst in 1 minute while the weather is turning (and for an hour after), otherwise 5 minutes. */
    fun nextIntervalMs(c: Context): Long {
        val now = System.currentTimeMillis()
        val clean = BaroMath.clean(log(c))
        val p = prefs(c)
        if (BaroMath.turning(clean, now)) p.edit().putLong("turningUntil", now + BaroMath.TURNING_KEEP_MS).apply()
        return if (now < p.getLong("turningUntil", 0L)) BaroMath.FAST_EVERY_MS else BaroMath.NORMAL_EVERY_MS
    }

    /**
     * One burst: sensor on, about 10 readings over 2 seconds, sensor off; `done` gets the median (hPa) or null.
     * A short wake lock keeps the phone awake for the 2 seconds.
     */
    fun burst(c: Context, done: (Double?) -> Unit) {
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: run { done(null); return }
        val wake = (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThirdCoast:baro").apply { acquire(BURST_MS + 3_000L) }
        val values = ArrayList<Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { synchronized(values) { values += e.values[0] } }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, sensor, 200_000)                  // about 5 per second
        Handler(Looper.getMainLooper()).postDelayed({
            sm.unregisterListener(listener)
            val v = synchronized(values) { values.filter { it in 300f..1100f }.sorted() }
            if (wake.isHeld) wake.release()
            done(if (v.isEmpty()) null else v[v.size / 2].toDouble())
        }, BURST_MS)
    }

    /** Take one burst now and log it (the Barograph page does this when it opens). */
    fun sampleNow(c: Context) {
        if (!hasSensor(c)) return
        burst(c.applicationContext) { v -> if (v != null) add(c.applicationContext, BaroPoint(System.currentTimeMillis(), v)) }
    }

    // ---------------------------------------------------------------- warning (anti-nag)

    /**
     * Should the yellow box show now? Only when the held warning level is above the highest level already shown in
     * this weather episode. The episode ends (and a new warning becomes possible) only after the pressure has been
     * steady or rising (3 h change above -1.5 hPa) for 2 hours straight.
     */
    fun warningToShow(c: Context, t: BaroTrend, nowMs: Long): Int {
        val p = prefs(c)
        var episode = p.getInt("warnEpisode", 0)
        val r3 = t.rate3h
        if (r3 != null && r3 > -1.5) {
            val since = p.getLong("calmSince", 0L).takeIf { it > 0 } ?: nowMs.also { p.edit().putLong("calmSince", it).apply() }
            if (episode > 0 && nowMs - since >= 2 * BaroMath.HOUR) { episode = 0; p.edit().putInt("warnEpisode", 0).apply() }
        } else p.edit().putLong("calmSince", 0L).apply()
        return if (t.heldLevel > episode) t.heldLevel else 0
    }

    /** The yellow box was shown and dismissed at this level: don't show it again unless it gets worse. */
    fun dismissWarning(c: Context, level: Int) {
        val p = prefs(c)
        if (level > p.getInt("warnEpisode", 0)) p.edit().putInt("warnEpisode", level).apply()
    }
}

/** Runs while barograph logging is on: a burst at each alarm, then the next alarm. */
class BaroService : Service() {
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Baro.isOn(this) || !Baro.hasSensor(this)) { stopSelf(); return START_NOT_STICKY }
        Baro.burst(this) { v ->
            if (v != null) Baro.add(this, BaroPoint(System.currentTimeMillis(), v))
            schedule(Baro.nextIntervalMs(this))
        }
        return START_NOT_STICKY
    }

    /** Wake the service again in inMs (an inexact alarm, allowed while the phone is idle). */
    private fun schedule(inMs: Long) {
        // allowed while the phone is idle, no special permission; Android may delay it a little, fine for weather
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + inMs, pending())
    }

    /** The PendingIntent the alarm uses to restart this service. */
    private fun pending(): PendingIntent = PendingIntent.getService(this, 7, Intent(this, BaroService::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    override fun onTaskRemoved(rootIntent: Intent?) {          // app swiped away: logging pauses (the log is kept)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { alarms.cancel(pending()) }
        super.onDestroy()
    }

    /** The low-priority ongoing "Barograph logging" notification (creates its channel the first time); tapping it opens the app. */
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Barograph", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Barograph logging")
            .setContentText("Pressure every 5 minutes (every minute when the weather turns)")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "barograph"
        private const val NOTE_ID = 3

        fun start(c: Context) { runCatching { c.startForegroundService(Intent(c, BaroService::class.java)) } }
        fun stop(c: Context) {
            runCatching {
                c.getSystemService(AlarmManager::class.java).cancel(PendingIntent.getService(c, 7, Intent(c, BaroService::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            c.stopService(Intent(c, BaroService::class.java))
        }
    }
}
