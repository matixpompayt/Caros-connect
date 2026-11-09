package pl.mati.carosconnect

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pasek zakładek jak w iOS 26 („Liquid Glass”): szklana kapsuła, a zaznaczenie to kropla
 * – soczewka, która załamuje światło na brzegach, przepływa do nowej zakładki (przód szybciej,
 * tył dogania, więc kropla się wyciąga i sprężynuje), a po przytrzymaniu podnosi się,
 * powiększa to, co pod nią, i można ją przeciągać palcem między zakładkami.
 */
@Composable
fun LiquidTabBar(items: List<Pair<ImageVector, String>>, tab: Int, onTab: (Int) -> Unit) {
    val light = C.isLight
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val n = items.size
    val curTab by rememberUpdatedState(tab)
    val curOnTab by rememberUpdatedState(onTab)

    // środek przodu i tyłu kropli w jednostkach zakładek, „podniesienie” (0..1)
    val front = remember { Animatable(tab.toFloat()) }
    val rear = remember { Animatable(tab.toFloat()) }
    val lift = remember { Animatable(0f) }
    var dir by remember { mutableIntStateOf(1) }
    val barSize = remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(tab) {
        val target = tab.toFloat()
        if (abs(front.value - target) < 0.001f && abs(rear.value - target) < 0.001f) return@LaunchedEffect
        dir = if (target > front.value) 1 else -1
        launch { front.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 520f)) }
        launch { delay(40); rear.animateTo(target, spring(dampingRatio = 0.55f, stiffness = 230f)) }
    }

    val barShape = RoundedCornerShape(36.dp)
    val useShader = LiquidLens.supported
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(68.dp)
                .onSizeChanged { barSize.value = it }
                .then(
                    if (useShader && Build.VERSION.SDK_INT >= 33) Modifier.graphicsLayer {
                        val sz = barSize.value
                        if (sz.width > 0) {
                            val pad = 5.dp.toPx()
                            val g = dropGeom(front.value, rear.value, lift.value, dir, n, sz.width.toFloat(), sz.height.toFloat(), pad, density.density)
                            val l = GlassLight.dir
                            renderEffect = LiquidLens.effect(
                                sz.width.toFloat(), sz.height.toFloat(), g, lift.value, light, l.x, l.y, pad, density.density,
                            )
                        }
                    } else Modifier,
                )
                .clip(barShape).surface(barShape)
                .then(
                    if (!useShader) Modifier.drawWithContent {
                        // Android < 13: kropla rysowana w Compose + powiększenie treści pod nią
                        val pad = 5.dp.toPx()
                        val g = dropGeom(front.value, rear.value, lift.value, dir, n, size.width, size.height, pad, density.density)
                        val path = dropPath(g)
                        drawDropBody(path, g, light, lift.value)
                        val mag = 1f + 0.22f * lift.value
                        if (mag > 1.01f) {
                            clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
                            clipPath(path) { scale(mag, Offset(g.lensX, g.cy)) { this@drawWithContent.drawContent() } }
                        } else drawContent()
                        drawDropRim(path, g, light)
                    } else Modifier,
                )
                .pointerInput(n) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pad = 5.dp.toPx()
                        val itemW = (size.width - 2 * pad) / n
                        fun idx(x: Float) = ((x - pad) / itemW - 0.5f).coerceIn(0f, (n - 1).toFloat())
                        scope.launch { lift.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 600f)) }
                        var dragging = false
                        var x = down.position.x
                        var lastSnap = curTab
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            x = ch.position.x
                            if (!ch.pressed) break
                            if (!dragging && abs(x - down.position.x) > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                ch.consume()
                                val t = idx(x)
                                if (abs(t - front.value) > 0.001f) dir = if (t > front.value) 1 else -1
                                scope.launch { front.animateTo(t, spring(dampingRatio = 0.85f, stiffness = 1500f)) }
                                scope.launch { rear.animateTo(t, spring(dampingRatio = 0.6f, stiffness = 320f)) }
                                val snap = t.roundToInt()
                                if (snap != lastSnap) { lastSnap = snap; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            }
                        }
                        val target = idx(x).roundToInt()
                        val changed = target != curTab
                        if (changed) {
                            if (!dragging) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            curOnTab(target)
                        } else {
                            scope.launch { front.animateTo(target.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f)) }
                            scope.launch { rear.animateTo(target.toFloat(), spring(dampingRatio = 0.55f, stiffness = 230f)) }
                        }
                        scope.launch {
                            delay(if (changed && !dragging) 200 else 60)
                            lift.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 260f))
                        }
                    }
                },
        ) {
            Row(Modifier.fillMaxSize().padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, (icon, label) ->
                    val on = i == tab
                    val tint by animateColorAsState(if (on) C.Accent else C.Text2, tween(260), label = "tint")
                    Column(
                        Modifier.weight(1f).fillMaxSize().semantics(mergeDescendants = true) {
                            role = Role.Tab
                            selected = on
                            onClick(label) { onTab(i); true }
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(25.dp))
                        Text(label, fontSize = 11.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Kształt kropli: przednia i tylna „czapka” połączone gładkimi krzywymi. */
private fun dropPath(g: DropGeom): Path {
    val d = g.dir
    val k = abs(g.frontX - g.rearX) * 0.5f
    val cy = g.cy
    return Path().apply {
        moveTo(g.rearX, cy - g.rr)
        cubicTo(g.rearX + d * k, cy - g.rr, g.frontX - d * k, cy - g.rf, g.frontX, cy - g.rf)
        arcTo(Rect(Offset(g.frontX, cy), g.rf), -90f, d * 180f, false)
        cubicTo(g.frontX - d * k, cy + g.rf, g.rearX + d * k, cy + g.rr, g.rearX, cy + g.rr)
        arcTo(Rect(Offset(g.rearX, cy), g.rr), 90f, d * 180f, false)
        close()
    }
}

private fun DrawScope.drawDropBody(path: Path, g: DropGeom, light: Boolean, lift: Float) {
    if (!light) translate(top = 3.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.10f + 0.10f * lift)) }
    drawPath(path, if (light) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f + 0.04f * lift))
    clipPath(path) {
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (light) 0.45f else 0.18f),
                0.5f to Color.Transparent,
                1f to Color.White.copy(alpha = if (light) 0.2f else 0.06f),
            ),
        )
    }
}

private fun DrawScope.drawDropRim(path: Path, g: DropGeom, light: Boolean) {
    val l = GlassLight.dir
    val r = maxOf(abs(g.frontX - g.rearX) / 2f + g.rf, g.rf)
    val c = Offset(g.lensX, g.cy)
    drawPath(
        path,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = if (light) 0.95f else 0.75f),
            0.5f to Color.White.copy(alpha = if (light) 0.35f else 0.10f),
            1f to Color.White.copy(alpha = if (light) 0.7f else 0.35f),
            start = c + Offset(l.x * r, l.y * r), end = c - Offset(l.x * r, l.y * r),
        ),
        style = Stroke(1.3.dp.toPx()),
    )
}
