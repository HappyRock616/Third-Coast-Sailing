package com.thirdcoastsailing.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Notification channel of the anchor watch (silent: the app plays the bell itself). */
private const val ANCHOR_CHANNEL = "anchor_watch"
/** Channel of the "anchor watch stopped" warning: its own channel, so it sounds and vibrates like any urgent notification. */
private const val STOPPED_CHANNEL = "anchor_stopped_alarm"   // alarm sound (a channel's sound can't change later)
/** Id of the anchor watch notification. */
private const val ANCHOR_NOTE_ID = 2
/** Id of the "anchor watch stopped" notification (kept after the service has gone). */
private const val STOPPED_NOTE_ID = 4          // (1 = trip track, 2 = anchor watch, 3 = barograph)
/** Notification action: silence the bell for SNOOZE_MS. */
private const val ACTION_SILENCE = "com.thirdcoastsailing.app.ANCHOR_SILENCE"
/** Notification action: switch the watch off. */
private const val ACTION_STOP = "com.thirdcoastsailing.app.ANCHOR_STOP"
/** Alarm action: take a fix and check the radius. */
private const val ACTION_CHECK = "com.thirdcoastsailing.app.ANCHOR_CHECK"
/** Longest wait for a fresh fix when a borderline fix is rechecked; no fix by then = alarm. */
private const val RECHECK_MS = 30_000L

/**
 * Keeps the anchor watch running with the screen off and after the app is swiped away: one GPS fix every 2 minutes,
 * the radius checked on each, a timer for "GPS fix lost", and the bell. Android requires a visible notification for
 * this ("foreground service"). If Android stops it while the watch is on, a notification says so.
 */
class AnchorService : Service() {

    private val lm by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private var bell: ShipsBell? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastFixMs = 0L
    private var foregrounded = false                 // startForeground done (once per service instance)
    private var listening = false                    // GPS updates requested
    private var rechecking = false
    private var recheckWake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    /** Recheck got no fresh fix in time: take the borderline fix as real and alarm. */
    private val recheckTimeout = Runnable { endRecheck(); evaluate() }
    // (a recheck asks for 1-second fixes on the same listener; onStartCommand never re-registers while `listening`,
    // so an app resume during a recheck can't put it back to 2 minutes)

    private val listener = LocationListener { loc -> onLocation(loc) }

    /** A GPS fix: a borderline one is checked again with a fresh fix (see [AnchorRules.onFix]), others decide now. */
    private fun onLocation(loc: Location) {
        if (!AnchorWatch.state.armed) return                 // switched off; a fix already queued must not ring
        lastFixMs = System.currentTimeMillis()
        AnchorWatch.onFix(this, loc.toBoatFix())
        val s = AnchorWatch.state
        val errFt = if (loc.hasAccuracy()) loc.accuracy * FT_PER_M else 0.0      // no accuracy given: taken as exact
        val verdict = AnchorRules.onFix(s.toAnchor()?.first, errFt, s.radiusFt, rechecking, s.alarming,
            snoozed = lastFixMs < s.snoozedUntilMs)
        if (verdict == AnchorRules.OnFix.RECHECK) { startRecheck(); return }
        if (rechecking) endRecheck()                                           // this fresh fix decides
        evaluate()
    }

    /** Ask for fixes every second (GPS is still warm) and keep the phone awake until one arrives or 30 s pass. */
    @SuppressLint("MissingPermission")
    private fun startRecheck() {
        rechecking = true
        recheckWake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThirdCoast:anchorRecheck").apply { acquire(RECHECK_MS + 5_000L) }
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
        handler.postDelayed(recheckTimeout, RECHECK_MS)
        updateNotification()
    }

    /** Back to one fix every 2 minutes. */
    @SuppressLint("MissingPermission")
    private fun endRecheck() {
        if (!rechecking) return
        rechecking = false
        handler.removeCallbacks(recheckTimeout)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, AnchorWatch.FIX_MS, 0f, listener, Looper.getMainLooper()) }
        recheckWake?.let { if (it.isHeld) it.release() }; recheckWake = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AnchorWatch.load(this)
        if (intent?.action == ACTION_STOP) {
            // (a service started with startForegroundService must still call startForeground before it stops)
            if (!foregrounded) runCatching { startForeground(ANCHOR_NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
            AnchorWatch.disarm(this); stopSelf(); return START_NOT_STICKY
        }
        // startForeground first, once per service instance (a service started with startForegroundService must call
        // it). Android can refuse it, e.g. when it restarts the service by itself with the app in the background:
        // then say so. Later intents (timer, Silence) reach the running service and don't repeat the call.
        if (!foregrounded) {
            foregrounded = hasFineLocation(this) && runCatching {
                startForeground(ANCHOR_NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            }.isSuccess
            if (!foregrounded) { notifyAnchorStopped(this); stopSelf(); return START_NOT_STICKY }
        }
        if (!AnchorWatch.state.armed || AnchorWatch.state.anchor == null) { stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).cancel(STOPPED_NOTE_ID)
        // GPS, whatever started this instance (the switch, Android's restart, the timer or a notification button):
        // the lost-fix clock starts now, so a fresh instance never counts the time before it as "no fix"
        if (!listening) {
            lastFixMs = System.currentTimeMillis()
            listening = runCatching {
                lm.removeUpdates(listener)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, AnchorWatch.FIX_MS, 0f, listener, Looper.getMainLooper())
            }.isSuccess
            if (!listening) { notifyAnchorStopped(this); stopSelf(); return START_NOT_STICKY }   // no permission or no GPS
        }
        when (intent?.action) {
            ACTION_SILENCE -> silence()
            else -> evaluate()                    // the switch, a restart, or the timer (ACTION_CHECK)
        }
        return START_STICKY                       // killed by Android while armed: it tries to restart the service
    }

    /**
     * Mark the fix lost after 10 minutes without one, sound the alarm if outside the circle or the fix is lost
     * (unless silenced), set the next timer and refresh the notification.
     */
    private fun evaluate() {
        if (!AnchorWatch.state.armed) return
        // the anchor was dropped again (or the watch re-armed) while the bell rang: the app cleared the alarm in the
        // saved state, so stop the bell here (it re-starts below if the boat is still outside the new circle)
        if (!AnchorWatch.state.alarming && bell != null) stopAlarm()
        if (AnchorRules.fixLost(System.currentTimeMillis(), lastFixMs) && !AnchorWatch.state.fixLost)
            AnchorWatch.update(this) { it.copy(fixLost = true) }
        val s = AnchorWatch.state
        if (AnchorRules.shouldAlarm(s.outside, rechecking, s.fixLost, snoozed = System.currentTimeMillis() < s.snoozedUntilMs))
            startAlarm()
        scheduleCheck()
        updateNotification()
    }

    /**
     * Next timer check (lost fix, or the end of a silence). With "Alarms & reminders" allowed (Settings; the anchor
     * screen links to it) it is an exact timer, delivered on time in deep sleep; otherwise an inexact one that sleep
     * may delay by some minutes. (Not an "alarm clock": that would show a clock icon and a next-alarm time all night.)
     */
    private fun scheduleCheck() {
        val at = AnchorRules.nextCheckMs(System.currentTimeMillis(), lastFixMs, AnchorWatch.state.snoozedUntilMs)
        val op = servicePending(ACTION_CHECK)
        if (canScheduleExact(this) && runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op) }.isSuccess) return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
    }

    /** Start the bell and vibration, and keep the phone awake while it rings. */
    private fun startAlarm() {
        if (bell != null) return                             // already ringing (never a second bell)
        if (!AnchorWatch.state.alarming) AnchorWatch.update(this) { it.copy(alarming = true) }
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ThirdCoast:anchor").apply { acquire(AnchorWatch.SNOOZE_MS) }
        bell = ShipsBell(this).also { it.start() }
    }

    /** Stop the bell and let the phone sleep again. */
    private fun stopAlarm() {
        bell?.stop(); bell = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
    }

    /**
     * Volume button, yellow box or notification: quiet for 15 minutes, then re-arm. Only while the bell is ringing,
     * so a stray tap on the notification can't mute an alarm that hasn't happened yet.
     */
    private fun silence() {
        if (!AnchorWatch.state.alarming) { updateNotification(); return }
        stopAlarm()
        AnchorWatch.update(this) { it.copy(alarming = false, snoozedUntilMs = System.currentTimeMillis() + AnchorWatch.SNOOZE_MS) }
        scheduleCheck()
        updateNotification()
    }

    // App swiped away: the watch keeps running (manifest: stopWithTask="false"). Switch it off in the app or with
    // the notification's "Stop Anchor Watch".

    override fun onDestroy() {
        runCatching { lm.removeUpdates(listener) }
        runCatching { alarms.cancel(servicePending(ACTION_CHECK)) }
        handler.removeCallbacks(recheckTimeout)                    // stop a recheck in progress
        recheckWake?.let { if (it.isHeld) it.release() }
        stopAlarm()
        if (AnchorWatch.state.armed) notifyAnchorStopped(this)     // stopped without being switched off
        super.onDestroy()
    }


    /** PendingIntent that sends an action to this service (notification buttons, alarms). */
    private fun servicePending(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, AnchorService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Refresh the notification text (distance to the anchor, status). */
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(ANCHOR_NOTE_ID, notification())
    }

    /**
     * The anchor watch notification (high priority, silent: the app plays the bell itself); tapping it opens the app.
     * While the bell rings it alerts again on each update, so it pops up over the lock screen.
     */
    private fun notification(): Notification {
        ensureChannel(this, ANCHOR_CHANNEL, "Anchor watch", NotificationManager.IMPORTANCE_HIGH, silent = true)
        val s = AnchorWatch.state
        val d = s.toAnchor()
        val text = when {
            s.fixLost -> "GPS fix lost"
            d == null -> "Waiting for GPS"
            else -> "%d ft · anchor %03.0f°T · radius %d ft".format(d.first.roundToInt(), d.second, s.radiusFt.roundToInt())
        }
        val title = when {
            s.alarming -> "⚓ ANCHOR ALARM"
            s.snoozedUntilMs > System.currentTimeMillis() -> "⚓ Anchor watch · silenced"
            else -> "⚓ Anchor watch"
        }
        return Notification.Builder(this, ANCHOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(openAppIntent(this)).setOngoing(true).setOnlyAlertOnce(!s.alarming)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(Notification.Action.Builder(null, "Silence 15 min", servicePending(ACTION_SILENCE)).build())
            .addAction(Notification.Action.Builder(null, "⚓ Stop Anchor Watch", servicePending(ACTION_STOP)).build())
            .build()
    }

    companion object {
        fun start(context: Context) {
            if (!hasFineLocation(context)) return
            runCatching { context.startForegroundService(Intent(context, AnchorService::class.java)) }
        }
        /** Stop the service (the watch was switched off) and clear a "watch stopped" warning still showing. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AnchorService::class.java))
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(STOPPED_NOTE_ID) }
        }
        /** Silence the alarm for 15 minutes (volume buttons, the yellow box). */
        fun silence(context: Context) {
            runCatching { context.startService(Intent(context, AnchorService::class.java).setAction(ACTION_SILENCE)) }
        }
    }
}

/**
 * "Third Coast Ships Bell": the anchor alarm sound, made in code (no sound file): each strike is a sum of bell
 * partials with their own decays, struck twice close together ("two bells") then a pause, looped. Played on the alarm
 * channel at full alarm volume (put back afterwards, also if the app was closed mid-alarm), with vibration in time
 * with the strikes.
 */
class ShipsBell(private val context: Context) {
    private var track: AudioTrack? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else context.getSystemService(Vibrator::class.java)                     // Android 10–11

    /** Ring the bell at full alarm volume (the previous volume is restored on stop), with vibration. */
    fun start() {
        runCatching {
            val saved = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            prefs(context).edit().putInt(KEY_VOLUME, saved).apply()             // so a crash can't leave it at max
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val pcm = PCM
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
            if (Build.VERSION.SDK_INT >= 33)
                vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else                                                                // Android 10–12: same alarm usage
                vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    /** Stop ringing and vibrating, and put the alarm volume back. */
    fun stop() {
        runCatching { track?.stop(); track?.release() }; track = null
        runCatching { vibrator?.cancel() }
        restoreVolume(context)
    }

    companion object {
        private const val RATE = 22050
        private const val LOOP_S = 2.6                               // one "two bells" pattern
        private val STRIKES = doubleArrayOf(0.0, 0.47)               // seconds into the pattern
        private const val F0 = 660.0                                 // strike tone (Hz)
        private const val KEY_VOLUME = "bellSavedVolume"
        // bell partials: ratio to F0, loudness, decay time (s): hum, prime, tierce, quint, nominal and above
        private val PARTIALS = arrayOf(
            doubleArrayOf(0.5, 0.30, 2.4), doubleArrayOf(1.0, 0.55, 1.8), doubleArrayOf(1.19, 0.38, 1.3),
            doubleArrayOf(1.5, 0.22, 1.1), doubleArrayOf(2.0, 0.50, 1.0), doubleArrayOf(2.51, 0.18, 0.6),
            doubleArrayOf(2.66, 0.14, 0.5), doubleArrayOf(3.01, 0.12, 0.4), doubleArrayOf(4.07, 0.08, 0.25),
        )

        /** The bell sound, made once (about a million sin/exp calls) and reused. */
        private val PCM: ShortArray by lazy { synth() }

        /** Make the sound in the background now (watch switched on), so an alarm doesn't wait for it. */
        fun prepare() { Thread { PCM }.start() }

        private fun prefs(c: Context) = c.getSharedPreferences("tcs_anchor", Context.MODE_PRIVATE)

        /** Put the alarm volume back if the bell left it at full (normal stop, or after the app was closed mid-alarm). */
        fun restoreVolume(c: Context) {
            val p = prefs(c)
            val saved = p.getInt(KEY_VOLUME, -1)
            if (saved < 0) return
            runCatching { c.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
            p.edit().remove(KEY_VOLUME).apply()
        }

        private fun synth(): ShortArray {
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


/**
 * A loud notification that the watch is not running although it is switched on; tapping it opens the app (which
 * restarts the watch). Its channel plays the alarm sound on the alarm stream, so it is heard with the phone on silent
 * or vibrate. Used by the service when Android stops it, and after a reboot or app update (AnchorBootReceiver).
 */
fun notifyAnchorStopped(context: Context) {
    AnchorWatch.update(context) { it.copy(alarming = false) }
    val nm = context.getSystemService(NotificationManager::class.java)
    runCatching { nm.deleteNotificationChannel("anchor_stopped") }     // earlier test builds' channel, no alarm sound
    if (nm.getNotificationChannel(STOPPED_CHANNEL) == null) {
        nm.createNotificationChannel(android.app.NotificationChannel(STOPPED_CHANNEL, "Anchor watch stopped",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        })
    }
    val n = Notification.Builder(context, STOPPED_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("⚓ Anchor watch STOPPED")
        .setContentText("The phone stopped the anchor watch. Open the app to restart it.")
        .setContentIntent(openAppIntent(context)).setAutoCancel(true)
        .setCategory(Notification.CATEGORY_ALARM)
        .build()
    runCatching { nm.notify(STOPPED_NOTE_ID, n) }
}

/**
 * After a reboot or an app update nothing of the app runs, so a watch that was switched on is silently gone (and
 * Android won't let it restart location in the background): warn loudly so the skipper opens the app.
 */
class AnchorBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AnchorWatch.load(context)
        if (AnchorWatch.state.armed) notifyAnchorStopped(context)
    }
}
