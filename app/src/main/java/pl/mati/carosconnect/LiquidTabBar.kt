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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pasek zakładek motywu Glass (iOS 26): kapsuła z kroplą-soczewką. */
@Composable
fun LiquidTabBar(items: List<Pair<ImageVector, String>>, tab: Int, onTab: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
        LiquidSelector(items.size, tab, onTab, Modifier.fillMaxWidth(), height = 68.dp, pad = 5.dp) { i, on ->
            val (icon, label) = items[i]
            val tint by animateColorAsState(if (on) C.Accent else C.Text2, tween(260), label = "tint")
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, label, tint = tint, modifier = Modifier.size(25.dp))
                Text(label, fontSize = 11.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Przełącznik segmentowy (iOS 26): szklana kapsuła, wybrany segment to przepływająca kropla. */
@Composable
fun LiquidSegmented(
    items: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, height: Dp = 42.dp, fontSize: TextUnit = 14.sp,
) {
    LiquidSelector(items.size, selected, onSelect, modifier.fillMaxWidth(), height = height, pad = 3.dp) { i, on ->
        val col by animateColorAsState(if (on) C.Text else C.Text2, tween(220), label = "seg")
        Text(items[i], fontSize = fontSize, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = col, maxLines = 1, softWrap = false)
    }
}

/**
 * Wspólny silnik: kropla-soczewka nad rzędem elementów. Stuknięcie – kropla podnosi się
 * i przepływa (przód szybciej, tył dogania, sprężynuje); przytrzymanie – można ją przeciągać.
 * selected spoza 0..n-1 = brak zaznaczenia (bez kropli).
 */
@Composable
fun LiquidSelector(
    n: Int, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier, height: Dp, pad: Dp,
    item: @Composable (i: Int, on: Boolean) -> Unit,
) {
    val light = C.isLight
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val gSpec by GlassTheme.spec.collectAsState()
    // animacje wyłączone: kropla przeskakuje od razu, bez przelewania, podnoszenia i powiększania
    val anim = gSpec.anim
    val curAnim by rememberUpdatedState(anim)
    val curSel by rememberUpdatedState(selected)
    val curOnSelect by rememberUpdatedState(onSelect)
    val has = selected in 0 until n

    val front = remember { Animatable(selected.coerceIn(0, maxOf(0, n - 1)).toFloat()) }
    val rear = remember { Animatable(selected.coerceIn(0, maxOf(0, n - 1)).toFloat()) }
    val lift = remember { Animatable(0f) }
    var dir by remember { mutableIntStateOf(1) }
    val box = remember { mutableStateOf(IntSize.Zero) }
    val fx = remember { LensFx() }
    var wasHidden by remember { mutableStateOf(!has) }

    LaunchedEffect(selected) {
        if (!has) { wasHidden = true; return@LaunchedEffect }
        val target = selected.toFloat()
        if (wasHidden) { wasHidden = false; front.snapTo(target); rear.snapTo(target); return@LaunchedEffect }
        if (abs(front.value - target) < 0.001f && abs(rear.value - target) < 0.001f) return@LaunchedEffect
        dir = if (target > front.value) 1 else -1
        if (!anim) { front.snapTo(target); rear.snapTo(target); lift.snapTo(0f); return@LaunchedEffect }
        launch { front.animateTo(target, spring(dampingRatio = 0.78f, stiffness = 520f)) }
        launch { delay(40); rear.animateTo(target, spring(dampingRatio = 0.55f, stiffness = 230f)) }
    }

    val shape = RoundedCornerShape(height / 2)
    val useShader = LiquidLens.supported
    Box(
        modifier.height(height)
            .onSizeChanged { box.value = it }
            .then(
                if (useShader && Build.VERSION.SDK_INT >= 33) Modifier.graphicsLayer {
                    val sz = box.value
                    renderEffect = if (sz.width > 0 && has && !wasHidden) {
                        val p = pad.toPx()
                        val g = dropGeom(front.value, rear.value, lift.value, dir, n, sz.width.toFloat(), sz.height.toFloat(), p, density.density)
                        val l = GlassLight.dir
                        fx.effect(sz.width.toFloat(), sz.height.toFloat(), g, lift.value, light, l.x, l.y, maxOf(p, 4.dp.toPx()), density.density)
                    } else null
                } else Modifier,
            )
            .clip(shape).surface(shape)
            .then(
                if (!useShader) Modifier.drawWithContent {
                    // Android < 13: kropla w Compose + powiększenie treści pod nią
                    if (!has || wasHidden) { drawContent(); return@drawWithContent }
                    val g = dropGeom(front.value, rear.value, lift.value, dir, n, size.width, size.height, pad.toPx(), density.density)
                    val path = dropPath(g)
                    drawDropBody(path, light, lift.value)
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
                    val p = pad.toPx()
                    val itemW = (size.width - 2 * p) / n
                    fun idx(x: Float) = ((x - p) / itemW - 0.5f).coerceIn(0f, (n - 1).toFloat())
                    val startSel = curSel
                    val animOn = curAnim
                    val canDrag = startSel in 0 until n
                    if (animOn) scope.launch { lift.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 600f)) }
                    var dragging = false
                    var x = down.position.x
                    var lastSnap = startSel
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        x = ch.position.x
                        if (!ch.pressed) break
                        if (canDrag && !dragging && abs(x - down.position.x) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            ch.consume()
                            val t = idx(x)
                            if (abs(t - front.value) > 0.001f) dir = if (t > front.value) 1 else -1
                            if (animOn) {
                                scope.launch { front.animateTo(t, spring(dampingRatio = 0.85f, stiffness = 1500f)) }
                                scope.launch { rear.animateTo(t, spring(dampingRatio = 0.6f, stiffness = 320f)) }
                            } else {
                                // bez animacji kropla przeskakuje na zakładkę pod palcem
                                val snapTo = t.roundToInt().toFloat()
                                scope.launch { front.snapTo(snapTo); rear.snapTo(snapTo) }
                            }
                            val snap = t.roundToInt()
                            if (snap != lastSnap) { lastSnap = snap; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        }
                    }
                    val target = idx(x).roundToInt()
                    val changed = target != curSel
                    if (changed) {
                        if (!dragging) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        curOnSelect(target)
                    } else if (animOn) {
                        scope.launch { front.animateTo(target.toFloat(), spring(dampingRatio = 0.75f, stiffness = 500f)) }
                        scope.launch { rear.animateTo(target.toFloat(), spring(dampingRatio = 0.55f, stiffness = 230f)) }
                    } else {
                        scope.launch { front.snapTo(target.toFloat()); rear.snapTo(target.toFloat()) }
                    }
                    if (animOn) scope.launch {
                        delay(if (changed && !dragging) 200 else 60)
                        lift.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 260f))
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until n) {
                val on = i == selected
                Box(
                    Modifier.weight(1f).fillMaxSize().semantics(mergeDescendants = true) {
                        role = Role.Tab
                        this.selected = on
                        onClick { onSelect(i); true }
                    },
                    contentAlignment = Alignment.Center,
                ) { item(i, on) }
            }
        }
    }
}

/** Kształt kropli: przednia i tylna „czapka” połączone gładkimi krzywymi. */
internal fun dropPath(g: DropGeom): Path {
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

internal fun DrawScope.drawDropBody(path: Path, light: Boolean, lift0: Float) {
    val lift = lift0.coerceIn(0f, 1f)
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

internal fun DrawScope.drawDropRim(path: Path, g: DropGeom, light: Boolean) {
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
