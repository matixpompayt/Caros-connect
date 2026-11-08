package pl.mati.carosconnect

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * Pasek zakładek motywu Glass: szklana kapsuła, a zaznaczenie to „kropla wody”,
 * która przepływa do nowej zakładki – przód leci szybko, tył dogania z opóźnieniem,
 * więc kropla się wydłuża i zwęża z tyłu, na końcu sprężynuje i rozchodzi się mała fala.
 */
@Composable
fun LiquidTabBar(items: List<Pair<ImageVector, String>>, tab: Int, onTab: (Int) -> Unit) {
    val light = C.isLight
    val haptic = LocalHapticFeedback.current
    val n = items.size
    // środek przodu i tyłu kropli (w jednostkach zakładek: 0..n-1)
    val front = remember { Animatable(tab.toFloat()) }
    val rear = remember { Animatable(tab.toFloat()) }
    val splash = remember { Animatable(1f) }
    var dir by remember { mutableIntStateOf(1) }

    LaunchedEffect(tab) {
        val target = tab.toFloat()
        if (abs(front.value - target) < 0.001f && abs(rear.value - target) < 0.001f) return@LaunchedEffect
        dir = if (target > front.value) 1 else -1
        coroutineScope {
            launch {
                front.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 520f))
            }
            launch {
                delay(55)
                rear.animateTo(target, spring(dampingRatio = 0.52f, stiffness = 150f))
            }
            launch {
                // fala, gdy przód kropli „dopłynie”
                delay(230)
                splash.snapTo(0f)
                splash.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            }
        }
    }

    val barShape = RoundedCornerShape(36.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).clip(barShape).surface(barShape).padding(5.dp)
                .drawBehind { drawDrop(front.value, rear.value, dir, splash.value, n, light) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, (icon, label) ->
                val on = i == tab
                val src = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (on) C.Accent else C.Text2, tween(260), label = "tint")
                // ikona pod kroplą jest lekko powiększona – jak przez soczewkę wody
                val lens by animateFloatAsState(if (on) 1.14f else 1f, spring(dampingRatio = 0.42f, stiffness = 420f), label = "lens")
                Column(
                    Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(29.dp))
                        .clickable(interactionSource = src, indication = null) {
                            if (i != tab) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onTab(i)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = lens; scaleY = lens })
                    Text(label, fontSize = 12.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/** Kropla: dwie półkoliste „czapki” (przód pełny, tył zwężony) połączone gładkimi krzywymi. */
private fun DrawScope.drawDrop(fc: Float, rc: Float, dir0: Int, splash: Float, n: Int, light: Boolean) {
    if (n <= 0) return
    val itemW = size.width / n
    val h0 = size.height
    val w0 = itemW - 2.dp.toPx()
    val cy = size.height / 2
    fun cx(i: Float) = itemW * (i + 0.5f)

    val gap = abs(fc - rc)
    val s = min(1f, gap * 1.1f) // 0 = spoczynek, 1 = mocno rozciągnięta
    val d = when { fc > rc + 0.0005f -> 1; fc < rc - 0.0005f -> -1; else -> dir0 }
    val rf = h0 / 2f * (1f - 0.10f * s)
    val rr = h0 / 2f * (1f - 0.48f * s)
    val fcx = cx(fc) + d * (w0 / 2f - rf)
    val rcx = cx(rc) - d * (w0 / 2f - rr)
    val k = abs(fcx - rcx) * 0.5f

    val path = Path().apply {
        moveTo(rcx, cy - rr)
        cubicTo(rcx + d * k, cy - rr, fcx - d * k, cy - rf, fcx, cy - rf)
        arcTo(Rect(Offset(fcx, cy), rf), -90f, d * 180f, false)
        cubicTo(fcx - d * k, cy + rf, rcx + d * k, cy + rr, rcx, cy + rr)
        arcTo(Rect(Offset(rcx, cy), rr), 90f, d * 180f, false)
        close()
    }

    // fala po dopłynięciu
    if (splash < 1f) {
        val grow = 10.dp.toPx() * splash
        val x0 = cx(fc) - w0 / 2f - grow
        val ringW = w0 + 2 * grow
        val ringH = h0 + grow * 0.6f
        drawRoundRect(
            Color.White.copy(alpha = (if (light) 0.55f else 0.35f) * (1f - splash)),
            topLeft = Offset(x0, cy - ringH / 2f), size = Size(ringW, ringH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(ringH / 2f),
            style = Stroke(1.5.dp.toPx() * (1f - splash * 0.6f)),
        )
    }

    // cień pod kroplą
    if (!light) translate(top = 2.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.16f)) }
    // „woda”: przezroczysta, jaśniejsza u góry
    drawPath(path, if (light) Color.White.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.15f))
    clipPath(path) {
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (light) 0.5f else 0.24f),
                0.5f to Color.Transparent,
                1f to Color.White.copy(alpha = if (light) 0.25f else 0.08f),
            ),
        )
        // odblask światła przy przodzie kropli
        val gx = fcx - d * rf * 0.15f
        drawOval(
            Color.White.copy(alpha = if (light) 0.75f else 0.42f),
            topLeft = Offset(gx - rf * 0.45f, cy - rf * 0.78f), size = Size(rf * 0.9f, rf * 0.26f),
        )
    }
    // krawędź jak załamanie światła na brzegu kropli
    drawPath(
        path,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = if (light) 0.95f else 0.7f),
            0.5f to Color.White.copy(alpha = if (light) 0.4f else 0.12f),
            1f to Color.White.copy(alpha = if (light) 0.8f else 0.4f),
            start = Offset(min(fcx, rcx) - rf, cy - rf), end = Offset(maxOf(fcx, rcx) + rf, cy + rf),
        ),
        style = Stroke(1.3.dp.toPx()),
    )
}
