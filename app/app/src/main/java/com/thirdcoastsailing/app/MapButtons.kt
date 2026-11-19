package com.thirdcoastsailing.app

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thirdcoastsailing.app.ui.theme.Foam
import com.thirdcoastsailing.app.ui.theme.Navy
import com.thirdcoastsailing.app.ui.theme.SignalOrange
import kotlinx.coroutines.launch

/* Small controls drawn over the chart: the message pill, the route + / − buttons and the center button. */

/** Hold "−" this long to clear the whole route (ms). */
private const val ROUTE_CLEAR_HOLD_MS = 1200

/** A small rounded message box at the bottom of the chart. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, background: Color = Navy.copy(alpha = 0.9f),
         color: Color = Foam, bold: Boolean = false) {
    Text(
        text, fontSize = 13.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier.background(background, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** Small round orange-ringed button (route + / −). Armed = filled orange; disabled = faded. Half the size of the center button. */
@Composable
fun RoundButton(label: String, armed: Boolean, enabled: Boolean = true, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    // with onHold: a tap is onClick, holding ROUTE_CLEAR_HOLD_MS fills a ring round the button and then calls onHold
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    val gesture = if (onHold == null) Modifier.clickable(enabled = enabled, onClick = onClick)
    else Modifier.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectTapGestures(onPress = {
            var held = false
            val job = scope.launch {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(ROUTE_CLEAR_HOLD_MS, easing = LinearEasing))
                held = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                hold?.invoke()
                progress.snapTo(0f)
            }
            val released = tryAwaitRelease()
            if (job.isActive) { job.cancel(); scope.launch { progress.snapTo(0f) } }
            if (released && !held) click()
        })
    }
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        if (onHold != null) Canvas(Modifier.size(34.dp)) {
            val p = progress.value
            if (p > 0f) drawArc(SignalOrange, -90f, 360f * p, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Box(
            Modifier.size(26.dp).alpha(if (enabled) 1f else 0.35f).shadow(4.dp, CircleShape).clip(CircleShape)
                .background(if (armed) SignalOrange else Navy)
                .border(2.dp, SignalOrange, CircleShape).then(gesture),
            contentAlignment = Alignment.Center
        ) { Text(label, color = if (armed) Navy else SignalOrange, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    }
}

/** The center button: tap = center on the boat; hold = zoom out to every installed chart (and the boat). */
@Composable
fun CenterButton(onTap: () -> Unit, onHold: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    // the gesture detector is set up once, so it must call the newest lambdas, not the first ones it was given
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    Box(
        Modifier.size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Navy)
            .border(2.dp, SignalOrange, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); hold() },
                )
            },
        contentAlignment = Alignment.Center
    ) { CrosshairIcon() }
}

/** Circle with cross-hair, drawn in orange. */
@Composable
private fun CrosshairIcon() {
    Canvas(Modifier.size(30.dp)) {
        val c = center
        val r = size.minDimension * 0.30f
        val w = 2.2.dp.toPx()
        drawCircle(SignalOrange, r, c, style = Stroke(w))
        val ext = size.minDimension * 0.48f
        drawLine(SignalOrange, c.copy(y = c.y - ext), c.copy(y = c.y - r * 0.45f), w)
        drawLine(SignalOrange, c.copy(y = c.y + r * 0.45f), c.copy(y = c.y + ext), w)
        drawLine(SignalOrange, c.copy(x = c.x - ext), c.copy(x = c.x - r * 0.45f), w)
        drawLine(SignalOrange, c.copy(x = c.x + r * 0.45f), c.copy(x = c.x + ext), w)
        drawCircle(SignalOrange, w, c)
    }
}
