package com.thirdcoastsailing.app

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/*
 * App-wide plumbing: the main settings file and its keys (one list instead of bare strings), the process-level
 * coroutine scope for work that must outlive a screen (chart downloads, route calculation), and the network check.
 */

/** Name of the app's main settings file (SharedPreferences). */
const val PREFS = "tcs"

/** The app's main settings file. */
fun appPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** Keys in the main settings file. */
object PrefKeys {
    const val NIGHT = "night"
    const val OFFLINE = "offline"
    const val GAUGE = "gauge"
    const val GAUGE_X = "gaugeX"
    const val GAUGE_Y = "gaugeY"
    const val GAUGE_SCALE = "gaugeScale"
    const val WX_COLOR = "wxColor"            // "wind", "gust" or "none"
    const val WX_BARBS = "wxBarbs"
    const val WX_WAVES = "wxWaves"
    const val WX_CURRENTS = "wxCurrents"
    const val ROUTE_ON_BOAT = "routeOnBoat"
    const val ROUTE_PIN_NEXT = "routePinNext"
    const val CHARTS_SETUP_DONE = "chartsSetupDone"
    const val DISCLAIMER_ACCEPTED = "disclaimerAccepted"
    /** Location has been asked for once (after a permanent denial the app opens its settings page instead). */
    const val LOCATION_ASKED = "locationAsked"
}

/**
 * Coroutine scope for the whole app process (main thread), for work that must not stop when the screen is rebuilt:
 * chart downloads and imports, route calculations. Heavy work inside switches to Dispatchers.IO / Default.
 */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** True when the phone has a working internet connection. */
fun online(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
