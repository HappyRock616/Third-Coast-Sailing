package com.thirdcoastsailing.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper

/** GPS interval while the trip track records with the app in the background or the screen off. */
private const val BACKGROUND_FIX_MS = 5 * 60_000L

/** Notification channel for the trip-track notification. */
private const val CHANNEL_ID = "trip_track"
/** Id of the trip-track notification (one per app). */
private const val NOTE_ID = 1

/**
 * Fixes taken by [TrackService] while the app isn't on screen. The app drains them into the trip track
 * when it comes back. Same process, so a simple shared list is enough.
 */
object BackgroundTrack {
    private val fixes = mutableListOf<BoatFix>()

    @Synchronized fun add(f: BoatFix) { fixes.add(f) }

    @Synchronized fun drain(): List<BoatFix> = fixes.toList().also { fixes.clear() }
}

/**
 * Low-power position sampling for the trip track: one GPS fix every 5 minutes while Trip track is on.
 * Android only allows this with a visible notification (a "foreground service"). It runs only while
 * Trip track is switched on, and stops when the switch goes off or the app is swiped away.
 * While the app is on screen it keeps its own 1-second GPS; this service just adds the slow samples.
 */
class TrackService : Service() {

    private val lm by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    private val listener = LocationListener { loc ->
        val sogKn = if (loc.hasSpeed()) loc.speed * 1.943844f else null
        BackgroundTrack.add(BoatFix(loc.latitude, loc.longitude, null, sogKn, loc.time))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        val ok = runCatching {
            lm.removeUpdates(listener)                       // in case of a repeated start
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, BACKGROUND_FIX_MS, 0f, listener, Looper.getMainLooper())
        }.isSuccess
        if (!ok) stopSelf()                                  // no location permission or no GPS: nothing to do
        return START_NOT_STICKY                              // the track lives in memory; never restart on its own
    }

    override fun onTaskRemoved(rootIntent: Intent?) {        // app swiped away: stop tracking
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { lm.removeUpdates(listener) }
        super.onDestroy()
    }

    /** The ongoing "Recording trip track" notification (creates its channel the first time); tapping it opens the app. */
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Trip track", NotificationManager.IMPORTANCE_LOW))
        }
        // Tapping the notification brings the app back
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trip track recording")
            .setContentText("Position every 5 minutes while the app is in the background")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TrackService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackService::class.java))
        }
    }
}
