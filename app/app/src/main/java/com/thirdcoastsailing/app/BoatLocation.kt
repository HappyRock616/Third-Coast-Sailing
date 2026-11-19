package com.thirdcoastsailing.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A fix older than this is not used to mark MOB or drop the anchor (it may be from before the app was opened). */
const val FRESH_FIX_MS = 15_000L

/**
 * Boat fix as the app uses it. `time` is the fix's GPS time (UTC ms); `elapsedMs` is when the phone got it on its
 * monotonic clock (SystemClock.elapsedRealtime), 0 when unknown. The age comes from `elapsedMs` because the phone's
 * wall clock and GPS time can differ.
 */
data class BoatFix(val lat: Double, val lon: Double, val cogDeg: Float?, val sogKn: Float?, val time: Long,
                   val elapsedMs: Long = 0L) {
    /** How old the fix is (ms). */
    fun ageMs(): Long =
        if (elapsedMs > 0L) SystemClock.elapsedRealtime() - elapsedMs else System.currentTimeMillis() - time

    /** True when the fix is recent enough to mark a position with (MOB, anchor). */
    val fresh: Boolean get() = ageMs() in 0..FRESH_FIX_MS
}

/** This Android location as a [BoatFix] (speed in knots; `cog` passed in because callers treat it differently). */
fun Location.toBoatFix(cog: Float? = null): BoatFix =
    BoatFix(latitude, longitude, cog, if (hasSpeed()) (speed * MS_TO_KN).toFloat() else null, time,
        elapsedRealtimeNanos / 1_000_000L)

/**
 * GPS through Android's own LocationManager (no Google Play Services fused location),
 * so it works on GrapheneOS and LineageOS. Caller must hold ACCESS_FINE_LOCATION.
 */
class BoatLocation(
    private val context: Context,
    private val onFix: (BoatFix) -> Unit,
    private val onSatellites: (used: Int, inView: Int) -> Unit = { _, _ -> },
) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var lastCog: Float? = null

    private val listener = LocationListener { loc: Location ->
        val f = loc.toBoatFix()
        // Course over ground is only meaningful when moving; keep the last good one when nearly stopped
        if (loc.hasBearing() && (f.sogKn ?: 0f) > 0.5f) lastCog = loc.bearing
        onFix(f.copy(cogDeg = lastCog))
    }

    // Satellite count, so a slow first fix (no Google assistance) is visibly progressing
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            onSatellites(used, status.satelliteCount)
        }
    }

    /** True when the phone's Location switch is on. */
    fun locationEnabled(): Boolean = lm.isLocationEnabled

    /** Start GPS updates (once a second) and satellite-status callbacks. Call when the app becomes visible. */
    @SuppressLint("MissingPermission")
    fun start() {
        // Register GPS even if Location is currently off: updates begin as soon as it is switched on
        if (LocationManager.GPS_PROVIDER in lm.allProviders) {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { listener.onLocationChanged(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            // Android 10 only has the Handler form of the satellite callback
            if (Build.VERSION.SDK_INT >= 30) lm.registerGnssStatusCallback(context.mainExecutor, gnss)
            else lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
        }
        // GPS only: no network (cell / Wi-Fi) location. It is rough (often 100 m+ off), useless offshore, and mixed
        // in with GPS it made the trip track zigzag.
    }

    /** Stop GPS updates and satellite callbacks. Call when the app goes to the background. */
    fun stop() {
        lm.removeUpdates(listener)
        lm.unregisterGnssStatusCallback(gnss)
    }

    companion object {
        /** Boat marker: orange arrow pointing up (north); the map rotates it to course over ground. */
        fun arrowBitmap(sizePx: Int = 64): Bitmap {
            val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val s = sizePx.toFloat()
            val path = Path().apply {
                moveTo(s * 0.5f, s * 0.06f)
                lineTo(s * 0.86f, s * 0.92f)
                lineTo(s * 0.5f, s * 0.72f)
                lineTo(s * 0.14f, s * 0.92f)
                close()
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 140, 40) })
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(27, 31, 35); style = Paint.Style.STROKE; strokeWidth = s * 0.05f
            })
            return b
        }
    }
}
