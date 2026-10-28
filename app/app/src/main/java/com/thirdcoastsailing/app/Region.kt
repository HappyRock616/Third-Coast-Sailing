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
 * The app's region: the five Great Lakes and the waters connected to them. The charts, forecasts and buoys only
 * cover this area, so the chart never pans away from it, and a phone outside it gets the Third Coast logo over
 * Michigan instead of a boat on an empty map. The exception is MOB and the anchor alarm: they keep working anywhere,
 * so while either is on outside the region the chart may follow the boat over the plain grey "no chart" background.
 */
object GreatLakes {
    // the box the center of the screen must stay in: Duluth to the Thousand Islands, Toledo to Nipigon, plus a margin
    const val SOUTH = 40.5
    const val NORTH = 49.5
    const val WEST = -93.0
    const val EAST = -75.0

    /** Zoom-out limit: all five lakes still fit across a phone held upright. */
    const val MIN_ZOOM = 4.3

    /** The view shown at start and whenever the phone is outside the region: Michigan with all five lakes. */
    val HOME: CameraPosition = CameraPosition.Builder().target(LatLng(44.5, -84.5)).zoom(5.0).bearing(0.0).tilt(0.0).build()

    /** Where the logo sits when the phone is outside the region: the middle of Michigan's Lower Peninsula. */
    val LOGO_AT = LatLng(43.9, -84.7)

    val bounds: LatLngBounds = LatLngBounds.Builder()
        .include(LatLng(NORTH, EAST)).include(LatLng(SOUTH, WEST)).build()

    /** True when a position is inside the region. */
    fun contains(lat: Double, lon: Double) = lat in SOUTH..NORTH && lon in WEST..EAST

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
            painter = painterResource(R.drawable.tcs_logo), contentDescription = "Third Coast Sailing: Great Lakes only",
            modifier = Modifier
                .offset { IntOffset((s.x - halfPx).roundToInt(), (s.y - halfPx).roundToInt()) }
                .size(LOGO_DP).alpha(0.95f).shadow(10.dp, CircleShape).clip(CircleShape)
                .border(3.dp, SignalOrange, CircleShape),
        )
    }
}
