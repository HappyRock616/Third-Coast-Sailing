package com.thirdcoastsailing.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/*
 * Shared helpers for the three foreground services (TrackService, AnchorService, BaroService) and the screens that
 * start them: permission checks, notification channels, the "open the app" intent, and the phone settings that can
 * stop a background watch (notifications off, battery optimisation, exact timers).
 */

/**
 * True when the app may use location (fine or approximate). Android 14+ refuses to start a location foreground
 * service without it (SecurityException), so the track and anchor services check this before starting.
 */
fun hasLocationPermission(context: Context): Boolean =
    hasFineLocation(context) ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * True when the app has precise location. The anchor watch and MOB need it: Android's "approximate" location is
 * rounded to about 2 km, so a dragging anchor might never move the fix.
 */
fun hasFineLocation(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** True when the app's notifications are allowed (Android 13+ asks; they can also be switched off in Settings). */
fun notificationsEnabled(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

/** True when Android's battery optimisation may stop or delay the app in the background. */
fun batteryOptimized(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false

/**
 * True when the app may set exact timers, needed for on-time anchor checks. Android 12+ asks for this in Settings
 * ("Alarms & reminders"); Android 10 and 11 always allow it.
 */
fun canScheduleExact(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

/** Create a notification channel the first time it is needed. `silent`: no notification sound (the app makes its own). */
fun ensureChannel(context: Context, id: String, name: String, importance: Int, silent: Boolean = false) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(id) != null) return
    nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { if (silent) setSound(null, null) })
}

/** PendingIntent that brings the app back to the front (tapping a notification). */
fun openAppIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
