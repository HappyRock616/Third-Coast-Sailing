package com.thirdcoastsailing.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
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
 * Low-power position sampling for the trip track: one GPS fix every 5 minutes while Trip track is on.
 * Android only allows this with a visible notification (a "foreground service"). It runs only while
 * Trip track is switched on, and stops when the switch goes off or the app is swiped away.
 * While the app is on screen it keeps its own 1-second GPS; this service just adds the slow samples
 * (handed to [TripTrack], which keeps the track for the whole app process).
 */
class TrackService : Service() {

    private val lm by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }

    // recorded at once (on the main thread, where the listener runs), so a swipe-away, reboot or flat battery can't
    // lose hours of background fixes; outside the charted region nothing is drawn, as with live fixes
    private val listener = LocationListener { loc ->
        val f = loc.toBoatFix()
        if (GreatLakes.contains(f.lat, f.lon)) TripTrack.record(f)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground first, always: a service started with startForegroundService must call it, or Android
        // crashes the app (e.g. permission revoked between start() and here); then stop if there is nothing to record
        val started = runCatching {
            startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
        if (!started || !hasLocationPermission(this)) { stopSelf(); return START_NOT_STICKY }
        val ok = runCatching {
            lm.removeUpdates(listener)                       // in case of a repeated start
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, BACKGROUND_FIX_MS, 0f, listener, Looper.getMainLooper())
        }.isSuccess
        if (!ok) stopSelf()                                  // no location permission or no GPS: nothing to do
        return START_NOT_STICKY                              // never restart on its own (the switch decides)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {        // app swiped away: stop tracking
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { lm.removeUpdates(listener) }
        super.onDestroy()
    }

    /** The ongoing "Recording trip track" notification; tapping it opens the app. */
    private fun notification(): Notification {
        ensureChannel(this, CHANNEL_ID, "Trip track", NotificationManager.IMPORTANCE_LOW)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Trip track recording")
            .setContentText("Position every 5 minutes while the app is in the background")
            .setContentIntent(openAppIntent(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Start the background sampling; false when Android refused (no permission, or not allowed right now). */
        fun start(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            return runCatching { context.startForegroundService(Intent(context, TrackService::class.java)) }.isSuccess
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackService::class.java))
        }
    }
}
