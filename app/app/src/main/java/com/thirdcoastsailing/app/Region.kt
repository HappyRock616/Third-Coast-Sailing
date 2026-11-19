package com.thirdcoastsailing.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

/*
 * The app's region: the five Great Lakes and the waters connected to them, plus the US coastal zones (Maine to Texas,
 * the West Coast and Hawaii). The charts, forecasts and buoys only cover these areas, so the chart never pans away
 * from them, and a phone outside them gets the Third Coast logo over Michigan instead of a boat on an empty map.
 * The exception is MOB and the anchor alarm: they keep working anywhere, so while either is on outside the region
 * the chart may follow the boat over the plain grey "no chart" background.
 */
object GreatLakes {
    // the box the center of the screen must stay in: Hawaii to Maine, the Keys to the Canadian border, plus a margin
    const val SOUTH = 18.0
    const val NORTH = 49.6
    const val WEST = -161.5
    const val EAST = -66.0

    // the areas that count as "inside": Duluth to the Thousand Islands, Toledo to Nipigon (the original Great
    // Lakes box), and each coastal zone's box (WeatherRepo.LAKE_BOX) with a margin
    private const val LAKES_SOUTH = 40.5
    private const val LAKES_NORTH = 49.5
    private const val LAKES_WEST = -93.0
    private const val LAKES_EAST = -75.0
    private const val ZONE_MARGIN = 0.5

    /**
     * Zoom-out limit. Charts are drawn from about zoom 5; further out the built-in rough basemap (land, borders and
     * names, see Basemap) shows where things are, which is enough to find a far-away chart (hold the center button).
     * 2.5 fits the lakes and Florida, or Maine to Texas, on a phone held upright.
     */
    const val MIN_ZOOM = 2.5

    /** The view shown at start and whenever the phone is outside the region: Michigan with all five lakes. */
    val HOME: CameraPosition = CameraPosition.Builder().target(LatLng(44.5, -84.5)).zoom(5.0).bearing(0.0).tilt(0.0).build()

    /** Start view before the first GPS fix: Michigan, unless only coastal zones are installed (then the first one). */
    fun startView(installed: List<String>): CameraPosition {
        if (installed.isEmpty() || installed.any { it in WeatherRepo.GREAT_LAKES }) return HOME
        val b = installed.firstNotNullOfOrNull { WeatherRepo.LAKE_BOX[it] } ?: return HOME
        return CameraPosition.Builder().target(LatLng((b[1] + b[3]) / 2, (b[0] + b[2]) / 2)).zoom(6.5).bearing(0.0).tilt(0.0).build()
    }

    /** Where the logo sits when the phone is outside the region: the middle of Michigan's Lower Peninsula. */
    val LOGO_AT = LatLng(43.9, -84.7)

    val bounds: LatLngBounds = LatLngBounds.Builder()
        .include(LatLng(NORTH, EAST)).include(LatLng(SOUTH, WEST)).build()

    /** True when a position is inside the region: the Great Lakes box or near a coastal zone. */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat in LAKES_SOUTH..LAKES_NORTH && lon in LAKES_WEST..LAKES_EAST) return true
        return WeatherRepo.LAKE_BOX.entries.any { (id, b) ->
            id !in WeatherRepo.GREAT_LAKES &&
                lon in (b[0] - ZONE_MARGIN)..(b[2] + ZONE_MARGIN) && lat in (b[1] - ZONE_MARGIN)..(b[3] + ZONE_MARGIN)
        }
    }

    /** True when there is a GPS fix and it is outside the region (no fix yet counts as inside: nothing to hide). */
    fun outside(fix: BoatFix?) = fix != null && !contains(fix.lat, fix.lon)
}

/** Size of the logo pinned over Michigan while the phone is outside the region. */
private val LOGO_DP = 132.dp

/**
 * The Third Coast logo pinned to the middle of Michigan (it moves with the chart), shown while the phone is outside
 * the region. It only marks the app's home waters: it takes no taps, so the chart under it still pans and zooms.
 */
@Composable
fun RegionLogo(map: MapLibreMap?, tick: Int) {
    tick                                                          // read so camera moves move the logo
    val m = map ?: return
    val s = m.projection.toScreenLocation(GreatLakes.LOGO_AT)
    val halfPx = with(LocalDensity.current) { LOGO_DP.toPx() / 2f }
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.tcs_logo), contentDescription = "Third Coast Sailing: outside the charted waters",
            modifier = Modifier
                .offset { IntOffset((s.x - halfPx).roundToInt(), (s.y - halfPx).roundToInt()) }
                .size(LOGO_DP).alpha(0.95f).shadow(10.dp, CircleShape).clip(CircleShape)
                .border(3.dp, SignalOrange, CircleShape),
        )
    }
}
