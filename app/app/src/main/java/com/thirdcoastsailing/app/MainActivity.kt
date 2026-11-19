package com.thirdcoastsailing.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import com.thirdcoastsailing.app.ui.theme.ThirdCoastSailingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

/*
 * Third Coast Sailing: entry point and main screen.
 *
 * MainActivity sets up the map engine and hosts App(), the one Compose screen everything else hangs off: the chart
 * (ChartMap.kt) with its overlays, the right-edge route buttons (MapButtons.kt), the info card (InfoCard.kt) and
 * weather boxes, the side menu (MenuSheet.kt), the chart dialogs (Dialogs.kt) and the full-screen pages.
 *
 * State that must outlive the screen lives in process-level holders, not here: ChartManager (charts and downloads),
 * RoutePlan (route and weather routing), TripTrack, AnchorWatch, MobWatch, Baro. App() keeps only what belongs to the
 * screen (which page is open, switches, the forecast slider) and wires the pieces together.
 */

/** Test builds only: a start position or a simulated boat passed in the start intent (see MainActivity.onCreate). */
object DebugStart {
    /** Camera position to open the chart at, or null. */
    var camera: CameraPosition? = null
    /** A simulated boat instead of GPS, to check the heading lines ashore, or null. */
    var fix: BoatFix? = null
}

/** The app's only activity: starts the map engine and shows [App] full screen. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)          // offline map engine; no API key, no Google services
        if (BuildConfig.DEBUG) intent?.let { i ->
            // test builds only: adb shell am start -n <pkg>/com.thirdcoastsailing.app.MainActivity
            //   --ef lat 41.5 --ef lon -70.7 --ef zoom 9   opens the chart there (release builds ignore it)
            if (i.hasExtra("lat") && i.hasExtra("lon")) DebugStart.camera = CameraPosition.Builder()
                .target(LatLng(i.getFloatExtra("lat", 0f).toDouble(), i.getFloatExtra("lon", 0f).toDouble()))
                .zoom(i.getFloatExtra("zoom", 9f).toDouble()).build()
            // --ef simlat 45.8 --ef simlon -84.9 --ef simcog 90 --ef simsog 5 puts a simulated boat there (GPS fixes
            // are then ignored), so the heading line, current predictor and route times can be checked ashore
            if (i.hasExtra("simlat") && i.hasExtra("simlon")) DebugStart.fix = BoatFix(
                i.getFloatExtra("simlat", 0f).toDouble(), i.getFloatExtra("simlon", 0f).toDouble(),
                i.getFloatExtra("simcog", 0f), i.getFloatExtra("simsog", 0f), System.currentTimeMillis())
        }
        // saved state of the process-level holders (each reads once per process)
        ChartManager.init(this)
        RoutePlan.load(this)
        TripTrack.load(this)
        AnchorWatch.load(this)             // saved anchor point and on/off state survive a restart
        MobWatch.load(this)                // a MOB mark stays until it is reset
        enableEdgeToEdge()
        setContent { ThirdCoastSailingTheme { App() } }
    }

    override fun onStart() {
        super.onStart()
        // the app is on screen: restart background work Android may have stopped while it was switched on
        AnchorWatch.resume(this)
        TripTrack.resume(this)
    }

    /** A volume press silenced the alarm: its key-up is swallowed too, so the volume doesn't change. */
    private var swallowVolumeUp = false

    /** While the anchor alarm sounds, either volume button silences it for 15 minutes (with the app on screen). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && AnchorWatch.state.alarming) {
            AnchorService.silence(this); swallowVolumeUp = true; return true
        }
        if (isVolumeKey(keyCode) && swallowVolumeUp) return true        // the key repeating while still held
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolumeKey(keyCode) && swallowVolumeUp) { swallowVolumeUp = false; return true }
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}

/** Version shown on the About screen and in the menu (versionName in build.gradle.kts). */
private val APP_VERSION = BuildConfig.VERSION_NAME
/** Automatic weather update interval while online and the app is open. */
private const val UPDATE_EVERY_MS = HOUR_MS
/** Forecast time slider range (hours). */
private const val FORECAST_HOURS = 48
/** After a failed weather update, wait this long before trying again. */
private const val RETRY_AFTER_MS = 15 * 60_000L

/** The location permissions asked for together (precise, with approximate as Android requires alongside it). */
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * The whole app on one screen: the chart with its overlays and buttons, the side menu, and the full-screen pages
 * (boat, celestial, barograph, anchor, about) opened over it.
 *
 * In order below: settings and GPS, region and depth, the shallow-water warning, route, charts, barograph, weather
 * and currents, buoys, the trip track, forecast fields, MOB, then the layout (menu, chart, buttons, boxes) and dialogs.
 */
@Composable
private fun App() {
    val context = LocalContext.current
    val prefs = remember { appPrefs(context) }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun closeDrawer() { scope.launch { drawer.close() } }
    BackHandler(enabled = drawer.isOpen) { closeDrawer() }      // Back closes the open menu instead of leaving the app

    var night by remember { mutableStateOf(prefs.getBoolean(PrefKeys.NIGHT, false)) }
    var offline by remember { mutableStateOf(prefs.getBoolean(PrefKeys.OFFLINE, false)) }
    val lakes = ChartManager.lakes
    val lakeIds = remember(lakes) { lakes.map { it.id } }

    // ---- the active boat and its polar (read off the main thread: the polar is refit from the boat's measurements)
    val boatStore = remember { BoatStore(context) }
    var activeBoat by remember { mutableStateOf<Boat?>(null) }
    var activePolar by remember { mutableStateOf<Polar?>(null) }
    var boatVersion by remember { mutableStateOf(0) }           // bumps when the Boat page closes
    var boatLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(boatVersion) {
        val (b, p) = withContext(Dispatchers.IO) {
            val b = runCatching { boatStore.load().let { s -> s.boats.firstOrNull { it.id == s.activeId } } }.getOrNull()
            b to b?.let { runCatching { boatStore.polar(it) }.getOrNull() }
        }
        activeBoat = b; activePolar = p; boatLoaded = true
    }
    // the chart is built once the installed charts and the boat are known (a moment after start), so it opens on the
    // right region with the boat's depth shading, instead of being styled up to three times
    val ready = boatLoaded && !ChartManager.loading
    val depthLimitFt = activeBoat?.minDepthFt ?: DEFAULT_MIN_DEPTH_FT     // active boat's minimum depth

    // ---- which page or dialog is open
    var info by remember { mutableStateOf<Info?>(null) }
    var showBoats by remember { mutableStateOf(false) }
    var showCelestial by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBaro by remember { mutableStateOf(false) }
    var showAnchor by remember { mutableStateOf(false) }
    var removeLake by remember { mutableStateOf<Lake?>(null) }
    var askMetered by remember { mutableStateOf<List<CatalogLake>?>(null) }
    var showCharts by remember { mutableStateOf(false) }        // the Charts screen (kept here so it survives the menu closing)
    var showSetup by remember { mutableStateOf(false) }
    // First launch: the not-for-navigation notice must be accepted before anything else (then the region picker)
    var showDisclaimer by remember { mutableStateOf(!prefs.getBoolean(PrefKeys.DISCLAIMER_ACCEPTED, false)) }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }   // for placing new points relative to the view
    var centerRequest by remember { mutableStateOf(0) }
    var overviewRequest by remember { mutableStateOf(0) }       // hold the center button: show every installed chart
    var showRegion by remember { mutableStateOf<Pair<Int, String>?>(null) }   // Charts → Show: (request no., region id)

    // ---- GPS: permission, then updates while the app is visible
    var fix by remember { mutableStateOf(DebugStart.fix) }
    val speedAvg = remember { SpeedAverage() }                 // 15-minute average SOG for the 1/2/3 h dots
    var avgSog by remember { mutableStateOf(DebugStart.fix?.sogKn?.toDouble()) }
    var sats by remember { mutableStateOf(0 to 0) }
    var hasLocation by remember { mutableStateOf(hasFineLocation(context)) }
    // Notification permission (Android 13+), asked when a background feature is first switched on
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = it[Manifest.permission.ACCESS_FINE_LOCATION] == true
        // denied in the dialog: remembered, so a later tap can tell "denied for good" from "never asked"
        if (!hasLocation && it.isNotEmpty()) prefs.edit().putBoolean(PrefKeys.LOCATION_ASKED, true).apply()
    }
    /**
     * Ask for location. Once it has been denied for good Android shows no dialog at all, so the button would do
     * nothing: then open the app's settings page, where it can be allowed.
     */
    fun askLocation(): Boolean {
        val asked = prefs.getBoolean(PrefKeys.LOCATION_ASKED, false)
        val act = context as? android.app.Activity
        if (asked && act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Status.show("Allow location in the app's settings (Permissions → Location → Precise)")
            openAppSettings(context)
            return true
        }
        permission.launch(LOCATION_PERMISSIONS)
        return false
    }
    // ask for location once the first-run disclaimer is accepted (not over it)
    LaunchedEffect(showDisclaimer) {
        if (!showDisclaimer && !hasLocation) permission.launch(LOCATION_PERMISSIONS)
    }
    val gps = remember {
        BoatLocation(context,
            onFix = { f ->
                if (DebugStart.fix == null) { fix = f; speedAvg.add(f.time, f.sogKn); avgSog = speedAvg.average() }
                MobWatch.onFix(context, f)                     // a MOB pressed before the first fix gets its position
            },
            onSatellites = { u, v -> if (sats.first != u || sats.second != v) sats = u to v })
    }
    var locationOn by remember { mutableStateOf(gps.locationEnabled()) }
    DisposableEffect(hasLocation, lifecycle) {
        // (adding the observer replays ON_RESUME when the app is already on screen, which starts the GPS)
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                locationOn = gps.locationEnabled()
                hasLocation = hasFineLocation(context)           // may have been changed in Settings meanwhile
                if (hasLocation) gps.start()
            }
            if (e == Lifecycle.Event.ON_PAUSE && hasLocation) gps.stop()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); if (hasLocation) runCatching { gps.stop() } }
    }

    // ---- region: outside the charted waters the chart stays put with the logo, and everything worked out from the
    // boat's position on the charts or forecasts is greyed out (see GreatLakes). MOB and the anchor alarm keep working
    // anywhere; while either is on, the chart may follow the boat outside the region.
    val outside = GreatLakes.outside(fix)
    val mobActive = MobWatch.state.active
    val safetyOn = mobActive || AnchorWatch.state.armed
    val navFix = if (outside) null else fix                     // the fix for chart, forecast and route features
    val boatPt = navFix?.let { GeoPt(it.lat, it.lon) }

    // ---- depth at the boat, and the shallow-water warning ahead (current heading) and on the route
    val depthLookup = ChartManager.depth
    var depthFt by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(navFix) {
        val f = navFix ?: run { depthFt = null; return@LaunchedEffect }
        depthFt = withContext(Dispatchers.IO) { runCatching { depthLookup.depthFt(f.lat, f.lon) }.getOrNull() }
    }
    val courseAvg = remember { CourseAverager() }
    LaunchedEffect(fix) { fix?.let { courseAvg.add(it) } }
    val latestFix by rememberUpdatedState(navFix)
    val latestLimit by rememberUpdatedState(depthLimitFt)
    val latestReady by rememberUpdatedState(ready)
    LaunchedEffect(Unit) {
        while (true) {                                          // every 20 s, to save power; it stops at the first hit
            val f = latestFix.takeIf { latestReady }            // (not before the boat's depth limit is known)
            val cog = courseAvg.cog()                           // read here: the averager is filled on the main thread
            CrashWatch.ahead = if (f == null) null else withContext(Dispatchers.IO) {
                runCatching { CrashWatch.scanAhead(depthLookup, f, cog, latestLimit) }.getOrNull()
            }
            delay(CrashWatch.AHEAD_EVERY_MS)
        }
    }

    // ---- route (RoutePlan): the route as sailed, progress along it, and its shallow-water check. Only the route
    // itself is checked (start pin onward), never the boat's way to the start: routes can be planned anywhere.
    // Rechecked when the route or limit changes or a snapped start has moved about 100 m; not while a pin is dragged.
    val routePts = RoutePlan.pins
    val effRoute = RoutePlan.effective(boatPt)
    val navPts = RoutePlan.navPoints(boatPt)
    LaunchedEffect(fix?.time, RoutePlan.startOnBoat) { boatPt?.let { RoutePlan.advance(it) } }
    LaunchedEffect(RoutePlan.checkKey(boatPt), depthLimitFt, lakes, RoutePlan.dragging, mobActive) {
        if (mobActive) { CrashWatch.onRoute = null; return@LaunchedEffect }   // the route is hidden during MOB
        if (RoutePlan.dragging) return@LaunchedEffect
        val pts = effRoute
        CrashWatch.onRoute = withContext(Dispatchers.IO) {
            runCatching { CrashWatch.scanRoute(depthLookup, null, pts, depthLimitFt) }.getOrNull()
        }
    }

    // ---- charts (ChartManager): first-run setup, downloads on mobile data, folder import
    LaunchedEffect(ChartManager.loading) {
        if (!ChartManager.loading && !prefs.getBoolean(PrefKeys.CHARTS_SETUP_DONE, false) && ChartManager.lakes.isEmpty()) showSetup = true
    }
    LaunchedEffect(showSetup) { if (showSetup && ChartManager.catalog.isEmpty()) ChartManager.checkCharts() }
    /** Start downloads, asking first when on mobile data. */
    fun startDownloads(list: List<CatalogLake>) {
        if (list.isEmpty()) return
        if (meteredNetwork(context)) askMetered = list else ChartManager.enqueue(list)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) ChartManager.import(uri)
    }

    // ---- barograph: logging restarts with the app; lake height follows the boat; the yellow box (anti-nag rules in Baro)
    var baroWarn by remember { mutableStateOf<BaroTrend?>(null) }        // the trend shown in the yellow box, or null
    var baroWarnLevel by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { if (Baro.isOn(context) && Baro.hasSensor(context)) BaroService.start(context) }
    LaunchedEffect(fix?.lat?.let { (it * 10).roundToInt() }, fix?.lon?.let { (it * 10).roundToInt() }) {
        fix?.let { Baro.setPosition(context, it.lat, it.lon) }
    }
    LaunchedEffect(Baro.version) {
        val now = System.currentTimeMillis()
        val t = withContext(Dispatchers.Default) { BaroMath.trend(BaroMath.clean(Baro.log(context)), now) }
        val lvl = Baro.warningToShow(context, t, now)
        if (lvl > baroWarnLevel) { baroWarn = t; baroWarnLevel = lvl }         // only ever goes up while shown
        else if (baroWarn != null) baroWarn = t                                 // keep the shown box current
    }
    /** Close the barograph warning; it stays away unless the fall gets worse. */
    fun dismissBaroWarn() { Baro.dismissWarning(context, baroWarnLevel); baroWarn = null; baroWarnLevel = 0 }

    // ---- weather: layer switches (wind and gust color fields are linked so only one is on), forecast time, updates
    val weather = remember { WeatherRepo(context.applicationContext) }   // (a route calculation may outlive the screen)
    var wxColor by remember { mutableStateOf(prefs.getString(PrefKeys.WX_COLOR, "none") ?: "none") }
    var wxBarbs by remember { mutableStateOf(prefs.getBoolean(PrefKeys.WX_BARBS, false)) }
    var wxWaves by remember { mutableStateOf(prefs.getBoolean(PrefKeys.WX_WAVES, false)) }
    var wxCurrents by remember { mutableStateOf(prefs.getBoolean(PrefKeys.WX_CURRENTS, false)) }
    var lastWx by remember { mutableStateOf(weather.lastUpdateMs) }
    var wxVersion by remember { mutableStateOf(0) }                    // bumps after each update so overlays reload
    var updating by remember { mutableStateOf(false) }
    var wxFailed by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var waveRun by remember { mutableStateOf<String?>(null) }          // wave model run in use ("Oct 12 12z")
    LaunchedEffect(wxVersion) { waveRun = withContext(Dispatchers.IO) { runCatching { weather.waveRun() }.getOrNull() } }
    // Forecast slider: starts at the hour of the last update. Its box follows the clock forward from there until a
    // drag or play picks an hour; a new update sends it back to following the clock (= the start, right after it).
    var pickH by remember { mutableStateOf<Int?>(null) }   // hours after the last update's hour; null = follows the clock
    val wxStartMs = ((if (lastWx > 0) lastWx else clock) / HOUR_MS) * HOUR_MS
    val nowSlotH = ((clock - wxStartMs + HOUR_MS / 2) / HOUR_MS).toInt().coerceIn(0, FORECAST_HOURS)   // forecast hour closest to now
    val shownH = (pickH ?: nowSlotH).coerceIn(0, FORECAST_HOURS)
    val aheadH = ((wxStartMs + shownH * HOUR_MS - (clock / HOUR_MS) * HOUR_MS).toFloat() / HOUR_MS).roundToInt()   // hours ahead of the current hour
    val forecastMs = (clock / HOUR_MS) * HOUR_MS + aheadH * HOUR_MS
    LaunchedEffect(lastWx) { pickH = null }
    // route timing and calculation start now while the slider follows the clock (its box shows the nearest forecast
    // hour, which can be up to 30 min ahead); at an hour the user picked, they start then
    val departNow = pickH == null || aheadH <= 0

    // Buoys: stations from the chart packages, colored by NOAA's latest reports (refreshed with weather)
    val buoyRepo = remember { BuoyRepo(context) }
    var buoyVersion by remember { mutableStateOf(0) }
    var buoyStations by remember { mutableStateOf<List<BuoyStation>>(emptyList()) }
    var buoyObs by remember { mutableStateOf<Map<String, BuoyObs>>(emptyMap()) }

    /** One update cycle on a background thread; ignored if one is already running. */
    fun runUpdate() {
        if (updating || lakeIds.isEmpty()) return
        updating = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { buoyRepo.update() }                // buoy reports ride along with each weather update
                runCatching { weather.update(lakeIds) }.getOrElse { "Weather update failed" }
            }
            buoyVersion++
            wxFailed = msg != "Weather updated"            // shown on the forecast strip, no message box
            lastWx = weather.lastUpdateMs
            wxVersion++
            updating = false
        }
    }
    // Automatic: on start, when the region set changes, then hourly - only while the app is open, online and not offline
    LaunchedEffect(offline, lakeIds) {
        var first = true
        var lastTry = 0L
        while (true) {
            val now = System.currentTimeMillis()
            val due = first || (now - weather.lastUpdateMs >= UPDATE_EVERY_MS && now - lastTry >= RETRY_AFTER_MS)
            if (!offline && due && online(context) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                runUpdate()
                first = false
                lastTry = now
            }
            delay(60_000)
            clock = System.currentTimeMillis()
        }
    }

    // ---- Great Lakes surface currents (NOAA GLERL model; triangle positions are in the lake packages, see Currents.kt)
    val currentsRepo = remember { CurrentsRepo(context) }
    var currentField by remember { mutableStateOf<CurrentField?>(null) }
    var currentsUpdating by remember { mutableStateOf(false) }
    var currentsVersion by remember { mutableStateOf(0) }
    var lastCur by remember { mutableStateOf(currentsRepo.lastUpdateMs) }
    val hasCurrentLakes = lakeIds.any { it in WeatherRepo.GREAT_LAKES }
    /** One currents download on a background thread; ignored if one is already running. */
    fun runCurrentsUpdate() {
        if (currentsUpdating || !hasCurrentLakes) return
        currentsUpdating = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { currentsRepo.update(lakes) }.getOrElse { "Currents update failed" } }
            // the menu legend shows the times; only something to act on gets a message
            if (msg != "Currents updated") Status.show(msg)
            lastCur = currentsRepo.lastUpdateMs
            currentsVersion++
            currentsUpdating = false
        }
    }
    // While switched on: download when older than 4 hours (checked every 10 minutes), online and not offline
    LaunchedEffect(wxCurrents, offline, lakeIds) {
        while (wxCurrents && !offline) {
            if (online(context) && System.currentTimeMillis() - currentsRepo.lastUpdateMs > CurrentsRepo.REFRESH_MS) runCurrentsUpdate()
            delay(10 * 60_000L)
        }
    }
    // The field shown: rebuilt from the lake packages' grid and the last download (hidden while MOB is active)
    LaunchedEffect(wxCurrents && !mobActive, lakeIds, currentsVersion) {
        currentField = if (!wxCurrents || mobActive) null
        else withContext(Dispatchers.IO) { runCatching { currentsRepo.field(lakes) }.getOrNull() }
    }

    // Buoy stations for the installed regions, their latest reports, and the map markers built from both
    LaunchedEffect(lakeIds) {
        buoyStations = withContext(Dispatchers.IO) { runCatching { buoyRepo.stations(lakes) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(buoyVersion) { buoyObs = withContext(Dispatchers.IO) { runCatching { buoyRepo.latest() }.getOrDefault(emptyMap()) } }
    val buoyFeatures = remember(buoyStations, buoyObs, clock) { BuoyRepo.features(buoyStations, buoyObs, clock) }

    // Messages at the bottom fade after 10 s
    val status = Status.text
    LaunchedEffect(status) { if (status.isNotEmpty()) { delay(10_000); Status.clear() } }

    // ---- trip track (TripTrack): first any 5-minute fixes taken while the app was in the background, then the live
    // one. Fixes outside the charted waters are dropped: the track only covers charted water.
    LaunchedEffect(fix, TripTrack.on) {
        if (!TripTrack.on || outside) return@LaunchedEffect
        TripTrack.drainBackground().filter { GreatLakes.contains(it.lat, it.lon) }.forEach { TripTrack.record(it) }
        fix?.let { TripTrack.record(it) }
    }
    var tripNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(TripTrack.on) { while (TripTrack.on) { tripNowMs = System.currentTimeMillis(); delay(60_000) } }   // trip clock
    val tripText = TripTrack.label(tripNowMs)

    // ---- planned route: time to each pin and the forecast wind on arrival (from the boat's polar and the forecast)
    var routeEtas by remember { mutableStateOf<List<PinEta>>(emptyList()) }
    val routeDepartMs = if (departNow) (clock / 60_000L) * 60_000L else forecastMs
    // keyed on the route rounded to ~200 m: with the start snapped to the boat, effRoute changes with every GPS fix
    val etaKey = effRoute.map { (it.lat * 500).roundToInt() to (it.lon * 500).roundToInt() }
    LaunchedEffect(etaKey, activePolar, wxVersion, routeDepartMs / (5 * 60_000L), avgSog?.let { (it * 2).roundToInt() }, RoutePlan.dragging) {
        if (RoutePlan.dragging) return@LaunchedEffect            // worked out once the pin is let go
        routeEtas = if (effRoute.size < 2) emptyList() else withContext(Dispatchers.Default) {
            runCatching { RouteTiming.compute(effRoute, routeDepartMs, activePolar, WindLookup(weather), CRUISE_FACTOR, avgSog?.takeIf { it >= 0.5 }) }
                .getOrDefault(emptyList())
        }
    }

    // ---- forecast fields for the selected time (only those the switched-on layers need; none while MOB is active:
    // the chart is cleared then, and everything comes back as it was when MOB is reset)
    val layers = if (mobActive) WxLayers(null, barbs = false, waves = false) else WxLayers(
        colorKind = when (wxColor) { "wind" -> Wx.WIND_SPEED; "gust" -> Wx.GUST; else -> null },
        barbs = wxBarbs, waves = wxWaves,
    )
    var frame by remember { mutableStateOf<WxFrame?>(null) }
    LaunchedEffect(layers, forecastMs, wxVersion) {
        frame = if (!layers.any) null
        else withContext(Dispatchers.IO) { runCatching { WxFrame.load(weather, layers, forecastMs) }.getOrNull() }
    }
    // Forecast wind at the boat, now, for the gauge
    var forecastWind by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(navFix?.lat?.let { (it * 100).roundToInt() }, navFix?.lon?.let { (it * 100).roundToInt() }, wxVersion, clock / HOUR_MS) {
        val f = navFix ?: run { forecastWind = null; return@LaunchedEffect }
        forecastWind = withContext(Dispatchers.IO) { runCatching { weather.wind(System.currentTimeMillis(), f.lat, f.lon) }.getOrNull() }
    }

    // ---- man overboard: mark the boat's position (see MobWatch.activate for a stale or missing fix). While it is
    // active the weather layers and the route are hidden (not deleted), and come back on reset.
    fun activateMob() {
        if (!hasLocation) permission.launch(LOCATION_PERMISSIONS)
        MobWatch.activate(context, fix)
        closeDrawer()
    }
    // forecast wind at the pin for the drift triangle, refreshed with the weather and every hour
    LaunchedEffect(MobWatch.state.pin, wxVersion, clock / HOUR_MS) {
        val pin = MobWatch.state.pin ?: return@LaunchedEffect
        val w = withContext(Dispatchers.IO) { runCatching { weather.wind(System.currentTimeMillis(), pin.lat, pin.lon) }.getOrNull() }
        MobWatch.setWind(context, w)
    }

    // ---- wind gauge: on/off from the menu, position and size from drag / pinch (remembered)
    var gaugeOn by remember { mutableStateOf(prefs.getBoolean(PrefKeys.GAUGE, false)) }
    var gaugeOffset by remember { mutableStateOf(Offset(prefs.getFloat(PrefKeys.GAUGE_X, 40f), prefs.getFloat(PrefKeys.GAUGE_Y, 260f))) }
    var gaugeScale by remember { mutableStateOf(prefs.getFloat(PrefKeys.GAUGE_SCALE, 1f)) }
    LaunchedEffect(gaugeOffset, gaugeScale) {                     // the gauge's place, saved once it stops moving
        delay(600)
        prefs.edit().putFloat(PrefKeys.GAUGE_X, gaugeOffset.x).putFloat(PrefKeys.GAUGE_Y, gaugeOffset.y)
            .putFloat(PrefKeys.GAUGE_SCALE, gaugeScale).apply()
    }

    // Celestial trainer: saved sights; their lines of position and fix are drawn on the chart
    val celestial = remember { CelestialStore(context) }
    val celPlot = rememberCelestialPlot(celestial.state)

    // sun/moon and light animation position: the boat, or the chart's center until there is a fix
    val skyPos = fix?.let { it.lat to it.lon } ?: mapRef?.cameraPosition?.target?.let { it.latitude to it.longitude }
    val topInsets = WindowInsets.statusBars.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))

    val menuActions = MenuActions(
        onImport = { closeDrawer(); picker.launch(null) },
        onCharts = { showCharts = true },
        onLakeCheck = { id, on ->
            if (on) {
                val c = ChartManager.catalog.firstOrNull { it.id == id }
                if (c != null) startDownloads(listOf(c)) else ChartManager.checkCharts()
            } else removeLake = lakes.firstOrNull { it.id == id }
        },
        onUpdateLake = { id -> ChartManager.catalog.firstOrNull { it.id == id }?.let { startDownloads(listOf(it)) } },
        onShowLake = { id -> closeDrawer(); showRegion = (showRegion?.first ?: 0) + 1 to id },
        onBoats = { closeDrawer(); showBoats = true },
        onCelestial = { closeDrawer(); showCelestial = true },
        onAbout = { closeDrawer(); showAbout = true },
        onAnchorOpen = { closeDrawer(); showAnchor = true },
        onAnchorToggle = { on ->
            if (on && !hasLocation) {                                  // needs precise GPS: ask instead of arming
                if (!askLocation()) Status.show("Anchor alarm needs precise location – allow it, then switch on again")
            } else if (on) {
                askNotifications()
                // no saved anchor and no fresh GPS fix yet: open the setup screen, which waits for one
                if (!AnchorWatch.arm(context, fix)) { closeDrawer(); showAnchor = true }
            } else AnchorWatch.disarm(context)
        },
        onMob = { activateMob() }, onMobReset = { MobWatch.reset(context) },
        onBaroOpen = { closeDrawer(); showBaro = true },
        onBaroToggle = { on -> if (on) askNotifications(); Baro.setOn(context, on) },
        onNight = { night = it; prefs.edit().putBoolean(PrefKeys.NIGHT, it).apply() },
        onOffline = { offline = it; prefs.edit().putBoolean(PrefKeys.OFFLINE, it).apply() },
        onGauge = { gaugeOn = it; prefs.edit().putBoolean(PrefKeys.GAUGE, it).apply() },
        onTrip = { on ->
            if (on && !hasLocation) {                                  // needs GPS: ask instead of recording
                if (!askLocation()) Status.show("Trip track needs location – allow it, then switch on again")
            } else {
                if (on) askNotifications()
                TripTrack.setOn(context, on)
                tripNowMs = System.currentTimeMillis()
            }
        },
        onTripReset = { TripTrack.reset() },
        onGpsPermission = { askLocation() },
        onLocationSettings = {
            runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .onFailure { openAppSettings(context) }
        },
    )
    val wxSwitches = WxSwitches(wxColor, wxBarbs, wxWaves, wxCurrents, hasCurrentLakes,
        onColor = { wxColor = it; prefs.edit().putString(PrefKeys.WX_COLOR, it).apply() },
        onBarbs = { wxBarbs = it; prefs.edit().putBoolean(PrefKeys.WX_BARBS, it).apply() },
        onWaves = { wxWaves = it; prefs.edit().putBoolean(PrefKeys.WX_WAVES, it).apply() },
        onCurrents = { wxCurrents = it; prefs.edit().putBoolean(PrefKeys.WX_CURRENTS, it).apply() })

    val menuScroll = androidx.compose.foundation.rememberScrollState()   // menu scroll position, kept while it's closed
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,          // keep swipes for panning the chart
        drawerContent = {
            // built only while the menu is open or opening, so GPS and clock updates don't redraw a hidden menu
            if (drawer.isOpen || drawer.isAnimationRunning || drawer.targetValue == DrawerValue.Open) MenuSheet(
                night = night, offline = offline, gaugeOn = gaugeOn, boatName = activeBoat?.name ?: "—", appVersion = APP_VERSION,
                trackEnabled = !outside, wx = wxSwitches,
                wxInfo = WxMenuInfo(waveRun, currentField?.validMs, lastCur, currentsUpdating, clock) { runCurrentsUpdate() },
                gps = GpsInfo(hasLocation, locationOn, fix, sats, avgSog), skyPos = skyPos, nowMs = clock, actions = menuActions,
                scroll = menuScroll,
            ) else Box(Modifier.size(300.dp, 1.dp))
        }
    ) {
        Box(Modifier.fillMaxSize().background(Navy)) {
            if (ready) ChartMap(
                lakes, night, depthLimitFt,
                ChartContent(
                    track = TripTrack.points, trackText = tripText, trackLive = TripTrack.on,
                    fix = if (outside && !safetyOn) null else fix, avgSog = avgSog,
                    frame = frame, layers = layers, currentField = currentField,
                    routePts = if (mobActive) emptyList() else effRoute, calc = if (mobActive) null else RoutePlan.result,
                    routeEtas = if (mobActive) emptyList() else routeEtas,
                    buoys = buoyFeatures, selected = info?.at, celestial = celPlot,
                    // animated lights: after sunset at the chart's position, or any time in night mode (zoom 13+, see NavLightGlow)
                    lightsAnimate = night || sunIsDown(skyPos, clock),
                    outside = outside, safetyOn = safetyOn,
                ),
                CameraRequests(centerRequest, overviewRequest, showRegion),
                ChartCallbacks(
                    // tapping the celestial fix opens the workbook
                    onTap = { ll ->
                        if (tapsCelestialFix(mapRef, celPlot, ll, 28f * context.resources.displayMetrics.density)) { showCelestial = true; true } else false
                    },
                    onInfo = { info = it },       // (a tap that hits nothing clears the card)
                    onMap = { mapRef = it },
                    onMovePoint = { i, p -> RoutePlan.move(i, p) },
                    onDragEnd = { RoutePlan.endDrag() },
                    onSnapStart = { boatPt?.let { RoutePlan.snapStart(it) } ?: RoutePlan.endDrag() },
                ),
            )

            // Right edge: add route point (+), center on boat, remove last route point (−); hidden during MOB
            Column(
                Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!mobActive) RoundButton("+", armed = false, enabled = routePts.size < MAX_ROUTE_POINTS) {
                    mapRef?.cameraPosition?.target?.let { RoutePlan.add(GeoPt(it.latitude, it.longitude)) }
                }
                // center on the boat: hidden while outside the region, unless MOB or the anchor alarm is on.
                // Hold: zoom out to every installed chart (and the boat), to find charts far from here.
                if (!outside || safetyOn) CenterButton(
                    onTap = { centerRequest++ },
                    onHold = { if (lakes.isEmpty()) Status.show("No charts installed – Menu → Charts") else overviewRequest++ })
                // tap: remove the last pin; hold 1.2 s: clear the whole route
                if (!mobActive) RoundButton("−", armed = false, onHold = { RoutePlan.clear(); Status.show("Route cleared") }) { RoutePlan.removeLast() }
            }

            // Menu button: the Third Coast Sailing logo
            Image(
                painter = painterResource(R.drawable.tcs_logo), contentDescription = "Menu",
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(topInsets).padding(start = 6.dp, top = 1.dp)
                    .size(58.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                    .border(2.dp, SignalOrange, CircleShape)
                    .clickable { scope.launch { drawer.open() } }
            )

            // Top: route box (distance, VMG, time to destination, calculate button) while a route is laid in
            val routeShown = routePts.isNotEmpty() && !mobActive
            if (routeShown) {
                val linked = RoutePlan.startOnBoat && boatPt != null
                val nextPt = if (linked) navPts.getOrNull(RoutePlan.navNext) else null
                RouteBox(
                    routeStats(RoutePlan.result, linked, boatPt, effRoute, fix, avgSog, clock, routeEtas),
                    RoutePlan.calcState, boatLinked = linked,
                    // the leg being sailed: distance and magnetic course from the boat to the next point
                    nextNm = if (nextPt != null && boatPt != null) distNm(boatPt, nextPt) else null,
                    steerDegM = if (nextPt != null && boatPt != null) toMagnetic(bearingDeg(boatPt, nextPt), boatPt) else null,
                    onCalc = { RoutePlan.onCalc(boatPt, activePolar, lakes, depthLimitFt, if (departNow) System.currentTimeMillis() else forecastMs, weather) },
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(topInsets).padding(start = 70.dp, top = 1.dp, end = 6.dp))
            }

            // Man overboard: the same MOB box across the top of the chart (hold 5 s to reset)
            if (mobActive) MobBox(fix, onActivate = {}, onReset = { MobWatch.reset(context) }, large = true,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(topInsets).padding(start = 70.dp, end = 6.dp, top = 4.dp))

            // Anchor watch: large yellow box with distance and bearing to the anchor (red border and "Alarm Silence" in an alarm)
            AnchorBanner(Modifier.align(Alignment.TopCenter).safeDrawingPadding()
                .padding(start = 78.dp, end = 10.dp, top = when {
                    mobActive -> 110.dp
                    routeShown -> 120.dp
                    else -> 10.dp
                }))

            // Wind / heading gauge (transparent, over the chart)
            if (gaugeOn) WindGauge(
                data = GaugeData(
                    headingDeg = fix?.cogDeg?.toDouble(), sogKn = fix?.sogKn?.toDouble(),
                    depthFt = depthFt, twdDeg = forecastWind?.second, twsKn = forecastWind?.first,   // forecast wind at the boat, now
                    polar = activePolar,
                    outside = outside,                          // wind, polar target and depth greyed out
                ),
                offset = gaugeOffset, scale = gaugeScale,
                onTransform = { pan, zoom ->
                    gaugeScale = (gaugeScale * zoom).coerceIn(0.6f, 2.2f)
                    gaugeOffset += pan                          // saved once the gesture settles (below)
                },
            )

            // Bottom: messages and the weather boxes (course and speed are on the gauge; GPS details in the menu)
            Column(
                // close to the bottom edge: side and top insets as usual, but only part of the gesture-bar inset
                Modifier.align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                    .padding(start = 6.dp, end = 6.dp, bottom = 14.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (status.isNotEmpty()) Pill(status)
                if (lakes.isEmpty() && ready)
                    Pill(if (ChartManager.progress.isNotEmpty()) "Downloading charts… (progress in the menu)" else "No charts yet: tap the logo → Charts")
                WeatherPanel(
                    layers = layers, aheadH = shownH, maxH = FORECAST_HOURS, nowH = nowSlotH,
                    onAhead = { pickH = it },          // stays where it's put until the next update
                    forecastLabel = WeatherRepo.clock(forecastMs),        // the box on the slider: forecast time shown
                    noData = frame?.empty == true,
                    lastUpdateMs = lastWx, nowMs = clock, updating = updating, offline = offline, failed = wxFailed,
                    waveRun = waveRun, onUpdate = { runUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Info card for a tapped harbor or buoy
            info?.let { i -> InfoCard(i, Modifier.align(Alignment.BottomEnd), buoyObs, buoyRepo, offline, clock) { info = null } }

            // Barograph warning: yellow box on top of everything; a tap on it opens the barograph, a tap anywhere else
            // clears it (and it stays away unless the fall gets worse)
            baroWarn?.let { t ->
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { dismissBaroWarn() } })
                BaroWarningBox(t, onOpen = { dismissBaroWarn(); showBaro = true },
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(topInsets).padding(start = 70.dp, end = 6.dp, top = 4.dp))
            }
        }
    }

    // ---- full-screen pages
    if (showCharts) ChartsScreen(menuActions, onClose = { showCharts = false })
    if (showCelestial) CelestialScreen(celestial, fix) { showCelestial = false }
    if (showBaro) {
        // the nearest NOAA station reporting pressure (in the last 3 h) gives the cross-check dots
        val near = remember(buoyObs, buoyStations, fix?.lat?.roundToInt(), fix?.lon?.roundToInt(), outside) {
            val at = fix?.let { GeoPt(it.lat, it.lon) } ?: mapRef?.cameraPosition?.target?.let { GeoPt(it.latitude, it.longitude) }
            val now = System.currentTimeMillis()
            if (at == null || outside) null else buoyStations.filter { st ->
                buoyObs[st.id.uppercase()]?.let { it.presHpa != null && now - it.timeMs < 3 * HOUR_MS } == true
            }.minByOrNull { distNm(at, GeoPt(it.lat, it.lon)) }
        }
        BarographScreen(near?.let { it.id.uppercase() to it.name },
            loadBuoy = { near?.let { buoyRepo.history(it.id, network = !offline && online(context)) } }) { showBaro = false }
    }
    if (showBoats) BoatScreen(boatStore) { showBoats = false; boatVersion++ }
    if (showAbout) AboutScreen(APP_VERSION) { showAbout = false }
    if (showAnchor) AnchorScreen(fix) { showAnchor = false }

    // ---- dialogs. First launch: accept the disclaimer, then pick regions to download
    if (showDisclaimer) FirstLaunchDisclaimer {
        prefs.edit().putBoolean(PrefKeys.DISCLAIMER_ACCEPTED, true).apply()
        showDisclaimer = false
    } else if (showSetup) {
        fun done() { showSetup = false; prefs.edit().putBoolean(PrefKeys.CHARTS_SETUP_DONE, true).apply() }
        SetupDialog(
            catalog = ChartManager.catalog, loading = ChartManager.checking,
            error = if (!ChartManager.checking && ChartManager.catalog.isEmpty()) ChartManager.catalogMsg else "",
            onRetry = { ChartManager.checkCharts() },
            onDownload = { picked -> done(); startDownloads(picked) },
            onLater = { done() },
        )
    }
    askMetered?.let { list ->
        MeteredDialog(list, onDownload = { ChartManager.enqueue(list); askMetered = null }, onWait = { askMetered = null })
    }
    ChartManager.pendingImport?.let { s ->
        UnverifiedImportDialog(s, onImport = { ChartManager.commitImport(s) }, onCancel = { ChartManager.discardImport() })
    }
    removeLake?.let { l ->
        RemoveChartsDialog(l, onRemove = { ChartManager.remove(l); removeLake = null }, onCancel = { removeLake = null })
    }
}

/**
 * The route box's numbers. Snapped to the boat: distance, VMG, time and ETA from the boat. A what-if route elsewhere:
 * distance, and after calculation the time it takes (no VMG or ETA, the boat isn't on it). With no time from the
 * boat's speed (not moving, or a what-if route), the planned time from the polar is used.
 */
private fun routeStats(calc: RouteResult?, linked: Boolean, boat: GeoPt?, effRoute: List<GeoPt>, fix: BoatFix?,
                       avgSog: Double?, nowMs: Long, etas: List<PinEta>): RouteStats {
    val s = when {
        calc != null && linked -> calculatedStats(calc, nowMs)
        calc != null -> RouteStats(calc.distanceNm, null, (calc.endMs - calc.startMs) / 3_600_000.0, null)
        linked -> plannedStats(null, listOfNotNull(boat) + effRoute.drop(RoutePlan.pinNext.coerceAtLeast(1)), fix, avgSog, nowMs)
        else -> RouteStats(effRoute.zipWithNext { a, b -> distNm(a, b) }.sum(), null, null, null)
    }
    // planned time from the polar (the boat isn't moving, or a what-if route); not once pins have been passed on a
    // route sailed from the boat (the planned times run from the first pin)
    val planned = if (calc == null && (!linked || RoutePlan.pinNext <= 1)) etas.lastOrNull()?.afterMs else null
    return if (s.hours == null && planned != null) s.copy(hours = planned / 3_600_000.0, etaMs = if (linked) nowMs + planned else null) else s
}


/** The app's own page in Android settings (permissions, notifications, battery). */
private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
