package com.thirdcoastsailing.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thirdcoastsailing.app.ui.theme.Alarm
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Mist
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.NavyLight
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import com.thirdcoastsailing.app.ui.theme.ThirdCoastSailingTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.maplibre.geojson.Feature
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)          // offline map engine; no API key, no Google services
        enableEdgeToEdge()
        setContent { ThirdCoastSailingTheme { App() } }
    }
}

private const val PREFS = "tcs"
private const val APP_VERSION = "0.6"
private const val MAX_ZOOM = 17.0           // cap so charts are not stretched past their scale (refined per chart later)
private const val HOUR_MS = 3_600_000L
private const val UPDATE_EVERY_MS = HOUR_MS  // automatic weather update interval while online and the app is open
private const val FORECAST_HOURS = 48        // forecast time slider range
private const val CRUISE_FACTOR = 0.85       // routing: fraction of polar speed actually sailed
private const val TACK_PENALTY_MIN = 15.0    // routing: time lost per tack or gybe
private const val DEFAULT_DEPTH_LIMIT_FT = 24.0
private const val WX_SOURCE = "wx_field"     // map source + layer for the wind / gust colour field
private const val WX_LAYER = "wx_field_layer"

/** GPS state shown in the menu. */
private data class GpsInfo(val hasPermission: Boolean, val locationOn: Boolean, val fix: BoatFix?,
                           val sats: Pair<Int, Int>, val avgSogKn: Double?)

/** 42.77330° N  86.20340° W */
private fun latLonText(lat: Double, lon: Double) =
    "%.5f° %s  %.5f° %s".format(kotlin.math.abs(lat), if (lat >= 0) "N" else "S", kotlin.math.abs(lon), if (lon >= 0) "E" else "W")

/** True when the phone has a working internet connection. */
private fun online(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** What the info card shows after tapping a harbor or buoy. */
private data class Info(val title: String, val lines: List<String>, val phone: String = "", val website: String = "",
                        val station: String? = null)          // set for buoys: live readings and graph are added

@Composable
private fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var night by remember { mutableStateOf(prefs.getBoolean("night", false)) }
    var offline by remember { mutableStateOf(prefs.getBoolean("offline", false)) }
    var hidden by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet())!!.toSet()) }
    val lakeStore = remember { LakeStore(context) }
    val boatStore = remember { BoatStore(context) }
    var lakes by remember { mutableStateOf(lakeStore.list()) }
    var activeBoat by remember { mutableStateOf(boatStore.load().let { s -> s.boats.firstOrNull { it.id == s.activeId } }) }
    var status by remember { mutableStateOf("") }
    var fix by remember { mutableStateOf<BoatFix?>(null) }
    val speedAvg = remember { SpeedAverage() }                 // 15-minute average SOG for the 1/2/3 h dots
    var avgSog by remember { mutableStateOf<Double?>(null) }
    var centerRequest by remember { mutableStateOf(0) }
    // Wind gauge: on/off from the menu, position and size from drag / pinch (remembered)
    var gaugeOn by remember { mutableStateOf(prefs.getBoolean("gauge", false)) }
    var gaugeOffset by remember { mutableStateOf(Offset(prefs.getFloat("gaugeX", 40f), prefs.getFloat("gaugeY", 260f))) }
    var gaugeScale by remember { mutableStateOf(prefs.getFloat("gaugeScale", 1f)) }
    var depthFt by remember { mutableStateOf<Double?>(null) }
    var sats by remember { mutableStateOf(0 to 0) }
    var info by remember { mutableStateOf<Info?>(null) }
    var showBoats by remember { mutableStateOf(false) }
    var removeLake by remember { mutableStateOf<Lake?>(null) }
    // Weather: layer toggles (wind and gust colour fields are linked so only one is on), forecast time, update state
    val weather = remember { WeatherRepo(context) }
    var wxColor by remember { mutableStateOf(prefs.getString("wxColor", "none") ?: "none") }   // "wind", "gust" or "none"
    var wxBarbs by remember { mutableStateOf(prefs.getBoolean("wxBarbs", false)) }
    var wxWaves by remember { mutableStateOf(prefs.getBoolean("wxWaves", false)) }
    var aheadH by remember { mutableStateOf(0f) }                      // forecast slider: hours ahead of now
    var lastWx by remember { mutableStateOf(weather.lastUpdateMs) }
    var wxVersion by remember { mutableStateOf(0) }                    // bumps after each update so overlays reload
    var updating by remember { mutableStateOf(false) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var frame by remember { mutableStateOf<WxFrame?>(null) }
    var forecastWind by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Route: points laid in with "+", removed with "−"; optional calculated (weather-routed) version
    val routeStore = remember { RouteStore(prefs) }
    var routePts by remember { mutableStateOf(routeStore.load()) }
    var placing by remember { mutableStateOf(false) }
    var calcResult by remember { mutableStateOf<RouteResult?>(null) }
    var calcState by remember { mutableStateOf(CalcState.IDLE) }
    val calcCancel = remember { AtomicBoolean(false) }
    // Buoys: stations from the chart packages, coloured by NOAA's latest reports (refreshed with weather)
    val buoyRepo = remember { BuoyRepo(context) }
    var buoyVersion by remember { mutableStateOf(0) }
    var buoyStations by remember { mutableStateOf<List<BuoyStation>>(emptyList()) }
    var buoyObs by remember { mutableStateOf<Map<String, BuoyObs>>(emptyMap()) }
    val depthLimitFt = remember { prefs.getFloat("depthLimitFt", DEFAULT_DEPTH_LIMIT_FT.toFloat()).toDouble() }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)

    // Folder picker for "Import lake package"
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { lakeStore.import(uri) { msg -> scope.launch { status = msg } } }
            lakes = lakeStore.list()
        }
    }

    // ---- GPS: permission, then updates while the app is visible
    var hasLocation by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = it[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!hasLocation) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val gps = remember { BoatLocation(context, onFix = { fix = it; speedAvg.add(it.time, it.sogKn); avgSog = speedAvg.average() }, onSatellites = { u, v -> sats = u to v }) }
    var locationOn by remember { mutableStateOf(gps.locationEnabled()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(hasLocation, lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                locationOn = gps.locationEnabled()
                if (hasLocation) gps.start()
            }
            if (e == Lifecycle.Event.ON_PAUSE && hasLocation) gps.stop()
        }
        lifecycle.addObserver(obs)
        if (hasLocation && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) gps.start()
        onDispose { lifecycle.removeObserver(obs); if (hasLocation) gps.stop() }
    }

    fun savePrefs() {
        prefs.edit().putBoolean("night", night).putBoolean("offline", offline).putStringSet("hidden", hidden).apply()
    }

    val visibleLakes = lakes.filter { it.id !in hidden }
    val currentLakes by rememberUpdatedState(visibleLakes)
    val depthLookup = remember { DepthLookup { currentLakes } }
    DisposableEffect(Unit) { onDispose { depthLookup.close() } }
    LaunchedEffect(fix) {
        val f = fix ?: return@LaunchedEffect
        depthFt = withContext(Dispatchers.IO) { runCatching { depthLookup.depthFt(f.lat, f.lon) }.getOrNull() }
    }
    val activePolar = remember(activeBoat) { activeBoat?.let { runCatching { boatStore.polar(it) }.getOrNull() } }

    // ---- weather updates
    val lakeIds = lakes.map { it.id }
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
            status = msg
            lastWx = weather.lastUpdateMs
            wxVersion++
            updating = false
        }
    }
    // Automatic: on start, when the lake set changes, then hourly - only while the app is open, online and not offline
    LaunchedEffect(offline, lakeIds) {
        var first = true
        var lastTry = 0L
        while (true) {
            val now = System.currentTimeMillis()
            // after a failed attempt wait 15 minutes before trying again
            val due = first || (now - weather.lastUpdateMs >= UPDATE_EVERY_MS && now - lastTry >= 15 * 60_000L)
            if (!offline && due && online(context) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                runUpdate()
                first = false
                lastTry = now
            }
            delay(60_000)
            clock = System.currentTimeMillis()
        }
    }

    // Buoy stations for the visible lakes, their latest reports, and the map markers built from both
    LaunchedEffect(visibleLakes.map { it.id }) {
        buoyStations = withContext(Dispatchers.IO) { runCatching { buoyRepo.stations(visibleLakes) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(buoyVersion) { buoyObs = withContext(Dispatchers.IO) { runCatching { buoyRepo.latest() }.getOrDefault(emptyMap()) } }
    val buoyFeatures = remember(buoyStations, buoyObs, clock) { BuoyRepo.features(buoyStations, buoyObs, clock) }

    // Messages at the bottom fade after 10 s
    LaunchedEffect(status) { if (status.isNotEmpty()) { delay(10_000); status = "" } }

    // ---- route editing and weather routing
    val routeStart = fix?.let { GeoPt(it.lat, it.lon) }
    fun setRoute(p: List<GeoPt>) {
        routePts = p
        routeStore.save(p)
        calcCancel.set(true)                                   // any calculation is now out of date
        calcResult = null
        calcState = CalcState.IDLE
    }
    fun onCalc() {
        when (calcState) {
            CalcState.DONE -> { calcResult = null; calcState = CalcState.IDLE }
            CalcState.WORKING -> calcCancel.set(true)
            CalcState.IDLE -> {
                val polar = activePolar ?: run { status = "Set up a boat first: menu → Boat & Polars"; return }
                val start = routeStart ?: routePts.firstOrNull()
                val wps = if (routeStart != null) routePts else routePts.drop(1)
                if (start == null || wps.isEmpty()) { status = "Add a route point first"; return }
                val files = visibleLakes.map { File(it.pmtiles.parentFile, "${it.id}_hazards.gpkg") }
                calcCancel.set(false)
                calcState = CalcState.WORKING
                status = "Calculating route…"
                scope.launch {
                    val r = withContext(Dispatchers.Default) {
                        runCatching {
                            val all = listOf(start) + wps
                            val mask = NavMask.build(files, Router.boxFor(all), depthLimitFt * 0.3048, all) { calcCancel.get() }
                            Router.route(start, wps, System.currentTimeMillis(), polar, WindLookup(weather), mask,
                                CRUISE_FACTOR, TACK_PENALTY_MIN) { calcCancel.get() }
                        }
                    }
                    if (calcState != CalcState.WORKING) return@launch  // route changed while calculating
                    r.onSuccess {
                        calcResult = it
                        calcState = CalcState.DONE
                        status = "Route: %.1f nm sailed, %s".format(it.distanceNm, hoursText((it.endMs - it.startMs) / 3_600_000.0))
                    }.onFailure {
                        calcState = CalcState.IDLE
                        status = it.message ?: "Route calculation failed"
                    }
                }
            }
        }
    }

    // ---- forecast fields for the selected time (only those the switched-on layers need)
    val layers = WxLayers(
        colorKind = when (wxColor) { "wind" -> Wx.WIND_SPEED; "gust" -> Wx.GUST; else -> null },
        barbs = wxBarbs, waves = wxWaves,
    )
    val forecastMs = (clock / HOUR_MS) * HOUR_MS + aheadH.roundToInt() * HOUR_MS
    LaunchedEffect(layers, forecastMs, wxVersion) {
        frame = if (!layers.any) null
        else withContext(Dispatchers.IO) { runCatching { WxFrame.load(weather, layers, forecastMs) }.getOrNull() }
    }
    // Forecast wind at the boat, now, for the gauge
    LaunchedEffect(fix?.lat?.let { (it * 100).roundToInt() }, fix?.lon?.let { (it * 100).roundToInt() }, wxVersion, clock / HOUR_MS) {
        val f = fix ?: return@LaunchedEffect
        forecastWind = withContext(Dispatchers.IO) { runCatching { weather.wind(System.currentTimeMillis(), f.lat, f.lon) }.getOrNull() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,          // keep swipes for panning the chart
        drawerContent = {
            MenuSheet(
                lakes = lakes, hidden = hidden, night = night, offline = offline, boatName = activeBoat?.name ?: "—",
                onImport = { scope.launch { drawer.close() }; picker.launch(null) },
                onToggleLake = { id -> hidden = if (id in hidden) hidden - id else hidden + id; savePrefs() },
                onRemoveLake = { removeLake = it },
                onBoats = { scope.launch { drawer.close() }; showBoats = true },
                onNight = { night = it; savePrefs() },
                onOffline = { offline = it; savePrefs() },
                gaugeOn = gaugeOn,
                onGauge = { gaugeOn = it; prefs.edit().putBoolean("gauge", it).apply() },
                wxColor = wxColor, wxBarbs = wxBarbs, wxWaves = wxWaves,
                onWxColor = { wxColor = it; prefs.edit().putString("wxColor", it).apply() },
                onWxBarbs = { wxBarbs = it; prefs.edit().putBoolean("wxBarbs", it).apply() },
                onWxWaves = { wxWaves = it; prefs.edit().putBoolean("wxWaves", it).apply() },
                gps = GpsInfo(hasLocation, locationOn, fix, sats, avgSog),
                onGpsPermission = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                onLocationSettings = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            ChartMap(
                visibleLakes, night, fix, avgSog, centerRequest, frame, layers, routeStart, routePts, calcResult, buoyFeatures,
                onTap = { ll ->
                    if (placing) {                                   // "+" armed: this tap drops the next route point
                        setRoute(routePts + GeoPt(ll.latitude, ll.longitude))
                        placing = false
                        true
                    } else false
                },
                onInfo = { info = it },
            )

            // Right edge: add route point (+), center on boat, remove last route point (−)
            Column(
                Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RoundButton("+", armed = placing) {
                    placing = !placing
                    if (placing) status = "Tap the chart to place point ${routePts.size + 1}"
                }
                Box(
                    Modifier.size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Navy)
                        .border(2.dp, SignalOrange, CircleShape).clickable { centerRequest++ },
                    contentAlignment = Alignment.Center
                ) { CrosshairIcon() }
                RoundButton("−", armed = false) {
                    placing = false
                    if (routePts.isNotEmpty()) setRoute(routePts.dropLast(1))
                }
            }

            // Menu button: the Third Coast Sailing logo
            Image(
                painter = painterResource(R.drawable.tcs_logo), contentDescription = "Menu",
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(10.dp)
                    .size(58.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                    .border(2.dp, SignalOrange, CircleShape)
                    .clickable { scope.launch { drawer.open() } }
            )

            // Top: route box (distance, VMG, time to destination, calculate button) while a route is laid in
            if (routePts.isNotEmpty()) {
                val cr = calcResult
                val stats = if (cr != null) calculatedStats(cr, clock) else plannedStats(routeStart, routePts, fix, avgSog, clock)
                RouteBox(stats, calcState, onCalc = { onCalc() },
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(start = 78.dp, top = 10.dp, end = 10.dp))
            }

            // Wind / heading gauge (transparent, over the chart)
            if (gaugeOn) WindGauge(
                data = GaugeData(
                    headingDeg = fix?.cogDeg?.toDouble(), sogKn = fix?.sogKn?.toDouble(),
                    depthFt = depthFt, twdDeg = forecastWind?.second, twsKn = forecastWind?.first,   // forecast wind at the boat, now
                    polar = activePolar,
                ),
                offset = gaugeOffset, scale = gaugeScale,
                onTransform = { pan, zoom ->
                    gaugeScale = (gaugeScale * zoom).coerceIn(0.6f, 2.2f)
                    gaugeOffset += pan
                    prefs.edit().putFloat("gaugeX", gaugeOffset.x).putFloat("gaugeY", gaugeOffset.y)
                        .putFloat("gaugeScale", gaugeScale).apply()
                },
            )

            // Bottom: messages, course/speed bar (hidden while the gauge shows them), weather boxes
            Column(
                Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(8.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (status.isNotEmpty()) Pill(status)
                if (lakes.isEmpty()) Pill("No charts yet: tap the logo → Import lake package")
                if (!gaugeOn) {
                    val f = fix
                    Pill(if (f == null) "GPS: no fix yet (details in the menu)"
                    else "COG %s   SOG %s".format(f.cogDeg?.let { "%03.0f°T".format(it) } ?: "—",
                        f.sogKn?.let { "%.1f kn".format(it) } ?: "—"))
                }
                val h = aheadH.roundToInt()
                WeatherPanel(
                    layers = layers, aheadH = h, maxH = FORECAST_HOURS, onAhead = { aheadH = it.toFloat() },
                    forecastLabel = (if (h == 0) "Now · " else "+$h h · ") + WeatherRepo.dayHour(forecastMs),
                    noData = frame?.empty == true,
                    lastUpdateMs = lastWx, nowMs = clock, updating = updating, offline = offline,
                    waveRun = remember(wxVersion) { weather.waveRun() }, onUpdate = { runUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Info card for a tapped harbor or buoy
            // (tapping anywhere else on the chart closes it: the map tap handler clears it when nothing is hit)
            info?.let { i -> InfoCard(i, Modifier.align(Alignment.BottomEnd), buoyObs, buoyRepo, offline, clock) { info = null } }
        }
    }

    if (showBoats) BoatScreen(boatStore) {
        showBoats = false
        activeBoat = boatStore.load().let { s -> s.boats.firstOrNull { it.id == s.activeId } }
    }

    removeLake?.let { l ->
        AlertDialog(
            onDismissRequest = { removeLake = null },
            title = { Text("Remove ${l.name} charts?") },
            text = { Text("This frees its storage on the phone. You can import it again later.") },
            confirmButton = {
                TextButton(onClick = { lakeStore.remove(l.id); lakes = lakeStore.list(); removeLake = null }) {
                    Text("Remove", color = SignalOrange)
                }
            },
            dismissButton = { TextButton(onClick = { removeLake = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, background: androidx.compose.ui.graphics.Color = Navy.copy(alpha = 0.9f),
                 color: androidx.compose.ui.graphics.Color = Foam, bold: Boolean = false) {
    Text(
        text, fontSize = 13.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier.background(background, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** Small round orange-ringed button (route + / −). Armed = filled orange. Half the size of the center button. */
@Composable
private fun RoundButton(label: String, armed: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(if (armed) SignalOrange else Navy)
            .border(2.dp, SignalOrange, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (armed) Navy else SignalOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

/** Side menu: logo header, GPS, charts, weather, boat, display, connection. */
@Composable
private fun MenuSheet(
    lakes: List<Lake>, hidden: Set<String>, night: Boolean, offline: Boolean, boatName: String,
    onImport: () -> Unit, onToggleLake: (String) -> Unit, onRemoveLake: (Lake) -> Unit, onBoats: () -> Unit,
    onNight: (Boolean) -> Unit, onOffline: (Boolean) -> Unit,
    gaugeOn: Boolean, onGauge: (Boolean) -> Unit,
    wxColor: String, wxBarbs: Boolean, wxWaves: Boolean,
    onWxColor: (String) -> Unit, onWxBarbs: (Boolean) -> Unit, onWxWaves: (Boolean) -> Unit,
    gps: GpsInfo, onGpsPermission: () -> Unit, onLocationSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = Navy, modifier = Modifier.widthIn(max = 340.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.tcs_logo), null,
                    Modifier.size(64.dp).clip(CircleShape).border(2.dp, SignalOrange, CircleShape))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Third Coast Sailing", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Foam)
                    Text("Great Lakes charts & weather", fontSize = 12.sp, color = Mist)
                }
            }
            Spacer(Modifier.height(16.dp))

            MenuHeader("GPS")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NavyLight).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val f = gps.fix
                when {
                    !gps.hasPermission -> {
                        Text("Location permission needed", color = Foam, fontSize = 15.sp)
                        TextButton(onClick = onGpsPermission) { Text("Allow", color = SignalOrange) }
                    }
                    !gps.locationOn -> {
                        Text("Phone Location is off", color = Foam, fontSize = 15.sp)
                        TextButton(onClick = onLocationSettings) { Text("Turn on", color = SignalOrange) }
                    }
                    f == null -> {
                        Text("Searching for a fix…", color = Foam, fontSize = 15.sp)
                        Text("Satellites ${gps.sats.first} used / ${gps.sats.second} seen", color = Mist, fontSize = 12.5.sp)
                    }
                    else -> {
                        Text(latLonText(f.lat, f.lon), color = Foam, fontSize = 16.sp)
                        Text("COG %s · SOG %s · avg %s".format(
                            f.cogDeg?.let { "%03.0f°T".format(it) } ?: "—",
                            f.sogKn?.let { "%.1f kn".format(it) } ?: "—",
                            gps.avgSogKn?.let { "%.1f kn".format(it) } ?: "—"), color = Mist, fontSize = 12.5.sp)
                        val age = ((System.currentTimeMillis() - f.time) / 1000).coerceAtLeast(0)
                        Text("Satellites ${gps.sats.first} used / ${gps.sats.second} seen · fix ${age} s ago",
                            color = Mist, fontSize = 12.5.sp)
                    }
                }
            }

            MenuDivider()
            MenuHeader("Charts")
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import lake package…") }
            if (lakes.isEmpty()) Text("No lakes on this device yet", color = Mist, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp))
            for (l in lakes) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = l.id !in hidden, onCheckedChange = { onToggleLake(l.id) })
                    Column(Modifier.weight(1f)) {
                        Text(l.name, color = Foam, fontSize = 15.sp)
                        Text("Updated ${l.chartsAsOf.ifEmpty { "?" }}  ·  built ${l.built.ifEmpty { "?" }}",
                            color = Mist, fontSize = 11.sp)
                    }
                    TextButton(onClick = { onRemoveLake(l) }) { Text("Remove", color = SignalOrange, fontSize = 12.sp) }
                }
            }

            MenuDivider()
            MenuHeader("Boat")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NavyLight)
                    .clickable(onClick = onBoats).padding(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Boat & Polars", color = Foam, fontSize = 15.sp)
                    Text("Active: $boatName", color = Mist, fontSize = 12.sp)
                }
                Text("›", color = SignalOrange, fontSize = 22.sp)
            }

            MenuDivider()
            MenuHeader("Weather")
            // Wind and gust colour fields are linked: turning one on turns the other off
            SwitchRow("Wind speed", "Coloured wind field over the chart", wxColor == "wind") { onWxColor(if (it) "wind" else "none") }
            Spacer(Modifier.height(8.dp))
            SwitchRow("Gusts", "Coloured gust field (replaces wind speed)", wxColor == "gust") { onWxColor(if (it) "gust" else "none") }
            Spacer(Modifier.height(8.dp))
            SwitchRow("Wind barbs", "Half feather 5 kt · feather 10 kt · pennant 50 kt", wxBarbs, onWxBarbs)
            Spacer(Modifier.height(8.dp))
            SwitchRow("Waves", "Animated wave crests, coloured by height", wxWaves, onWxWaves)

            MenuDivider()
            MenuHeader("Display")
            SwitchRow("Night display", "Dark chart for night / cabin use", night, onNight)
            Spacer(Modifier.height(8.dp))
            SwitchRow("Wind gauge", "Compass, wind and polar over the chart (pinch to size, drag to move)", gaugeOn, onGauge)

            MenuDivider()
            MenuHeader("Connection")
            SwitchRow("Offline mode", "Pause automatic weather downloads (tap the Weather box to update once)", offline, onOffline)

            MenuDivider()
            Text("Version $APP_VERSION · Not for navigation. NOAA chart data governs.", color = Mist, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MenuHeader(text: String) =
    Text(text.uppercase(), color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp))

@Composable
private fun MenuDivider() = HorizontalDivider(color = NavyLight, modifier = Modifier.padding(vertical = 14.dp))

@Composable
private fun SwitchRow(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Foam, fontSize = 15.sp)
            Text(subtitle, color = Mist, fontSize = 12.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Bottom-right window for a tapped harbor or buoy; phone and website are tappable. ✕ closes it. */
@Composable
private fun InfoCard(info: Info, modifier: Modifier, buoyObs: Map<String, BuoyObs>, buoyRepo: BuoyRepo,
                     offline: Boolean, nowMs: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    // Buoy: last 24 h for the graph (downloaded on tap unless offline mode; else the saved copy)
    var history by remember(info.station) { mutableStateOf<List<BuoyObs>?>(null) }
    var historyLoaded by remember(info.station) { mutableStateOf(false) }
    LaunchedEffect(info.station) {
        val st = info.station ?: return@LaunchedEffect
        history = withContext(Dispatchers.IO) { runCatching { buoyRepo.history(st, network = !offline && online(context)) }.getOrNull() }
        historyLoaded = true
    }
    Box(
        modifier.safeDrawingPadding().padding(12.dp).widthIn(min = 260.dp, max = 340.dp)
            .shadow(8.dp, RoundedCornerShape(14.dp)).background(Navy, RoundedCornerShape(14.dp))
            .border(1.dp, SignalOrange, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(info.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Foam,
                modifier = Modifier.padding(end = 36.dp))          // room for the close icon
            for (l in info.lines) Text(l, color = Mist, fontSize = 14.sp)
            info.station?.let { st ->
                for (l in buoyLines(st, buoyObs[st], nowMs)) Text(l, color = Mist, fontSize = 14.sp)
                val h = history
                when {
                    h != null -> BuoyGraph(h, nowMs)
                    !historyLoaded -> Text("Loading 24 h graph…", color = Mist, fontSize = 12.sp)
                    offline -> Text("Graph: none saved (offline mode)", color = Mist, fontSize = 12.sp)
                    else -> Text("Graph: no data from NOAA", color = Mist, fontSize = 12.sp)
                }
            }
            if (info.phone.isNotBlank() || info.website.isNotBlank()) Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)
            ) {
                if (info.phone.isNotBlank()) Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${info.phone.substringBefore(" x")}")))
                }) { Text("Call ${info.phone}", fontSize = 13.sp) }
                if (info.website.isNotBlank()) OutlinedButton(onClick = {
                    val url = if (info.website.startsWith("http")) info.website else "https://${info.website}"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }) { Text("Website", color = SignalOrange, fontSize = 13.sp) }
            }
        }
        // Close icon in the top-right corner
        Text(
            "✕", color = SignalOrange, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).clickable(onClick = onClose)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** Read a feature property as text ("" when missing). */
private fun Feature.text(key: String): String =
    runCatching { getProperty(key)?.takeIf { !it.isJsonNull }?.asString ?: "" }.getOrDefault("").trim()

private fun kindLabel(k: String) = when (k.lowercase()) {
    "marina" -> "Marina"; "yacht_club", "club" -> "Yacht club"; "public_dock" -> "Public dock"
    "fuel_dock" -> "Fuel dock"; "mooring_field" -> "Mooring field"; "anchorage" -> "Anchorage"
    "harbor_of_refuge" -> "Harbor of refuge"; "dock" -> "Dock"
    else -> k.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Turn a tapped map feature into card content. */
private fun toInfo(f: Feature, layerId: String): Info =
    if (layerId == BUOY_LAYER) {
        val id = f.text("station")
        Info(
            title = f.text("name").ifEmpty { "Station $id" },
            lines = listOfNotNull(f.text("owner").takeIf { it.isNotEmpty() }),
            station = id,
        )
    } else {
        val vhf = f.text("vhf")
        Info(
            title = f.text("name").ifEmpty { "(unnamed)" },
            lines = listOfNotNull(kindLabel(f.text("kind")),
                if (vhf.isNotEmpty()) "VHF $vhf" else "VHF: not listed",
                if (f.text("phone").isEmpty()) "Phone: not listed" else null),
            phone = f.text("phone"), website = f.text("website")
        )
    }

/** The chart: MapLibre MapView inside Compose, restyled when lakes or day/night change. */
@Composable
private fun ChartMap(lakes: List<Lake>, night: Boolean, fix: BoatFix?, avgSog: Double?, centerRequest: Int,
                     frame: WxFrame?, layers: WxLayers, routeStart: GeoPt?, routePts: List<GeoPt>, calc: RouteResult?,
                     buoys: FeatureCollection, onTap: (LatLng) -> Boolean, onInfo: (Info?) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var centered by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }               // bumps on every camera move so the overlays follow
    var idleTick by remember { mutableStateOf(0) }           // bumps when the camera stops (weather marks re-space)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentLakes by rememberUpdatedState(lakes)
    val currentOnInfo by rememberUpdatedState(onInfo)

    // Forward lifecycle events; MapView needs them
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.setMaxZoomPreference(MAX_ZOOM)
            m.setMinZoomPreference(4.0)
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(LatLng(44.5, -84.5)).zoom(5.0).build()
            m.addOnCameraMoveListener { tick++ }
            m.addOnCameraIdleListener { tick++; idleTick++ }
            // Tap: look for a harbor or buoy within a finger's width
            m.addOnMapClickListener { ll ->
                if (currentOnTap(ll)) return@addOnMapClickListener true
                val p = m.projection.toScreenLocation(ll)
                val r = 28f * context.resources.displayMetrics.density
                val ids = ChartStyle.tapLayers(currentLakes)
                val hit = if (ids.isEmpty()) null
                else ids.firstNotNullOfOrNull { id ->
                    m.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), id).firstOrNull()?.let { it to id }
                }
                currentOnInfo(hit?.let { (f, id) -> toInfo(f, id) })
                hit != null
            }
            map = m
        }
    }

    // (Re)build the style whenever the lake set or day/night changes
    LaunchedEffect(map, lakes, night) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(ChartStyle.build(lakes, night))) { s -> style = s; tick++ }
    }

    // Center on the first fix, and whenever the center button is pressed: boat centered, north up, default zoom
    LaunchedEffect(map, fix != null, centerRequest) {
        val f = fix ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (centered && centerRequest == 0) return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(f.lat, f.lon)).zoom(12.0).bearing(0.0).tilt(0.0).build()))
        centered = true
    }

    // Wind / gust colour field as a map image layer under the land, so it moves with the chart and tints water only
    LaunchedEffect(style, frame, layers.colorKind, lakes) {
        val s = style ?: return@LaunchedEffect
        val src = when (layers.colorKind) { Wx.WIND_SPEED -> frame?.speed; Wx.GUST -> frame?.gust; else -> null }
        val box = WeatherRepo.boxFor(lakes.map { it.id })
        val img = if (src == null || box == null) null
        else withContext(Dispatchers.Default) { runCatching { buildFieldImage(src, box) }.getOrNull() }
        if (!s.isFullyLoaded) return@LaunchedEffect
        if (img == null) {
            s.getLayer(WX_LAYER)?.let { s.removeLayer(WX_LAYER) }
            s.getSource(WX_SOURCE)?.let { s.removeSource(WX_SOURCE) }
            return@LaunchedEffect
        }
        val existing = s.getSourceAs<ImageSource>(WX_SOURCE)
        if (existing != null) {
            existing.setCoordinates(img.quad)
            existing.setImage(img.bitmap)
        } else {
            s.addSource(ImageSource(WX_SOURCE, img.quad, img.bitmap))
            val layer = RasterLayer(WX_LAYER, WX_SOURCE).withProperties(PropertyFactory.rasterOpacity(FIELD_OPACITY))
            val anchor = ChartStyle.landAnchor(lakes)
            if (anchor != null && s.getLayer(anchor) != null) s.addLayerBelow(layer, anchor) else s.addLayer(layer)
        }
    }

    // Live buoy markers on top of the chart: fill = wind speed, ring = yellow over 3 ft / red over 5 ft waves
    LaunchedEffect(style, buoys) {
        val s = style ?: return@LaunchedEffect
        if (!s.isFullyLoaded) return@LaunchedEffect
        val src = s.getSourceAs<GeoJsonSource>(BUOY_SOURCE)
        if (src != null) src.setGeoJson(buoys)
        else {
            s.addSource(GeoJsonSource(BUOY_SOURCE, buoys))
            s.addLayer(CircleLayer(BUOY_LAYER, BUOY_SOURCE).withProperties(
                PropertyFactory.circleRadius(6.5f),
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("ring"))),
                PropertyFactory.circleStrokeWidth(Expression.get("ringW")),
            ).apply { minZoom = 6f })
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        WeatherOverlay(map, tick, idleTick, frame, layers)
        RouteOverlay(map, tick, routeStart, routePts, calc, night)
        BoatOverlay(map, fix, avgSog, tick)
    }
}

/** Circle with cross-hair, drawn in orange. */
@Composable
private fun CrosshairIcon() {
    androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
        val c = center
        val r = size.minDimension * 0.30f
        val w = 2.2.dp.toPx()
        drawCircle(SignalOrange, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        val ext = size.minDimension * 0.48f
        drawLine(SignalOrange, c.copy(y = c.y - ext), c.copy(y = c.y - r * 0.45f), w)
        drawLine(SignalOrange, c.copy(y = c.y + r * 0.45f), c.copy(y = c.y + ext), w)
        drawLine(SignalOrange, c.copy(x = c.x - ext), c.copy(x = c.x - r * 0.45f), w)
        drawLine(SignalOrange, c.copy(x = c.x + r * 0.45f), c.copy(x = c.x + ext), w)
        drawCircle(SignalOrange, w, c)
    }
}
