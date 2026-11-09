package pl.mati.carosconnect

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ======================= wciśnięcie: poświata zamiast „fali” =======================

/** iOS nie ma androidowej fali – w Glass wciśnięcie to miękka poświata pod palcem. */
object GlassIndication : Indication {
    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
        val glow = remember(interactionSource) { Animatable(0f) }
        val pos = remember(interactionSource) { mutableStateOf(Offset.Unspecified) }
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> {
                        pos.value = i.pressPosition
                        launch { glow.animateTo(1f, tween(110)) }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                        if (glow.value < 0.7f) glow.animateTo(0.85f, tween(70))
                        glow.animateTo(0f, tween(480))
                    }
                }
            }
        }
        return remember(interactionSource) { GlassGlow(glow, pos) }
    }
}

private class GlassGlow(val glow: Animatable<Float, AnimationVector1D>, val pos: MutableState<Offset>) : IndicationInstance {
    override fun ContentDrawScope.drawIndication() {
        drawContent()
        val g = glow.value.coerceIn(0f, 1f)
        if (g <= 0.01f) return
        val p = pos.value.takeIf { it.isSpecified } ?: center
        val r = maxOf(size.maxDimension * 0.8f, 56.dp.toPx())
        val a = if (C.isLight) 0.55f else 0.24f
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = a * g),
                0.45f to Color.White.copy(alpha = a * 0.35f * g),
                1f to Color.Transparent,
                center = p, radius = r,
            ),
        )
    }
}

// ======================= przełącznik =======================

private val SW_W = 72.dp // warstwa z zapasem – soczewka wystaje ponad tor
private val SW_H = 46.dp
private val TR_W = 58.dp
private val TR_H = 30.dp
private val KN_W = 36.dp
private val KN_H = 26.dp
val IOS_GREEN = Color(0xFF34C759)

/**
 * Przełącznik jak w iOS 26: gałka w kształcie pastylki; po dotknięciu zamienia się w szklaną
 * soczewkę większą od toru (załamuje i powiększa kolor pod sobą), można ją przeciągnąć,
 * a po puszczeniu sprężyście wraca do białej gałki po nowej stronie.
 */
@Composable
fun LiquidSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val light = C.isLight
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val cur by rememberUpdatedState(checked)
    val onChange by rememberUpdatedState(onCheckedChange)
    val pos = remember { Animatable(if (checked) 1f else 0f) }
    val press = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val fx = remember { LensFx() }
    val shader = LiquidLens.supported && Build.VERSION.SDK_INT >= 33

    LaunchedEffect(checked) {
        if (dragging) return@LaunchedEffect
        val t = if (checked) 1f else 0f
        if (abs(pos.value - t) < 0.001f) return@LaunchedEffect
        // zmiana z zewnątrz (stuknięcie w cały wiersz) – też krótka „kropla”
        launch {
            if (press.value < 0.5f) press.animateTo(0.9f, spring(dampingRatio = 0.8f, stiffness = 900f))
            delay(170)
            press.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 320f))
        }
        pos.animateTo(t, spring(dampingRatio = 0.62f, stiffness = 420f))
    }

    val offColor = if (light) Color(0xFF787880).copy(alpha = 0.24f) else Color.White.copy(alpha = 0.19f)
    Box(
        modifier.size(SW_W, SW_H)
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "włączone" else "wyłączone"
                onClick { onChange(!cur); true }
            }
            .then(
                if (shader) Modifier.graphicsLayer {
                    val p = press.value.coerceIn(0f, 1f)
                    renderEffect = if (p > 0.01f) {
                        val w = SW_W.toPx(); val h = SW_H.toPx()
                        val tx = (w - TR_W.toPx()) / 2f
                        val travel = TR_W.toPx() - 4.dp.toPx() - KN_W.toPx()
                        val cx = tx + 2.dp.toPx() + pos.value.coerceIn(-0.05f, 1.05f) * travel + KN_W.toPx() / 2f
                        val g = DropGeom.capsule(cx, h / 2f, (KN_W + 10.dp * p).toPx(), (KN_H + 10.dp * p).toPx())
                        val l = GlassLight.dir
                        fx.effect(w, h, g, p, light, l.x, l.y, 4.dp.toPx(), density.density)
                    } else null
                } else Modifier,
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    scope.launch { press.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f)) }
                    val travel = (TR_W - 4.dp - KN_W).toPx()
                    val start = pos.value
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        val dx = ch.position.x - down.position.x
                        if (!moved && abs(dx) > viewConfiguration.touchSlop / 2f) { moved = true; dragging = true }
                        if (moved) scope.launch { pos.snapTo((start + dx / travel).coerceIn(0f, 1f)) }
                    }
                    val target = if (moved) pos.value > 0.5f else !cur
                    dragging = false
                    if (target != cur) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChange(target)
                    }
                    scope.launch { pos.animateTo(if (target) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
                    scope.launch { delay(90); press.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 320f)) }
                }
            }
            .drawBehind {
                // sprężyny „przestrzeliwują” (np. 1,05 albo -0,03) – kolory i przezroczystość muszą zostać w 0..1
                val p = press.value.coerceIn(0f, 1f)
                val t = pos.value
                val tc = t.coerceIn(0f, 1f)
                val tx = (size.width - TR_W.toPx()) / 2f
                val ty = (size.height - TR_H.toPx()) / 2f
                val tw = TR_W.toPx(); val th = TR_H.toPx()
                drawRoundRect(lerp(offColor, IOS_GREEN, tc), Offset(tx, ty), Size(tw, th), CornerRadius(th / 2f))
                val kh = KN_H.toPx()
                // bez shadera gałka tylko się wydłuża (jak w starszym iOS)
                val kw = KN_W.toPx() + if (shader) 0f else 8.dp.toPx() * p
                val travel = tw - 4.dp.toPx() - kw
                val kx = tx + 2.dp.toPx() + t.coerceIn(-0.05f, 1.05f) * travel
                val ky = ty + 2.dp.toPx()
                val alpha = if (shader) 1f - p else 1f
                if (alpha > 0.01f) {
                    drawRoundRect(Color.Black.copy(alpha = 0.16f * alpha), Offset(kx, ky + 1.5.dp.toPx()), Size(kw, kh), CornerRadius(kh / 2f))
                    drawRoundRect(Color.White.copy(alpha = alpha), Offset(kx, ky), Size(kw, kh), CornerRadius(kh / 2f))
                }
            },
    )
}

// ======================= suwak =======================

/** Suwak jak w iOS 26: cienki tor, gałka-pastylka; przy przesuwaniu staje się szklaną soczewką. */
@Composable
fun LiquidSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, onValueChangeFinished: (() -> Unit)? = null,
) {
    val light = C.isLight
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val onChange by rememberUpdatedState(onValueChange)
    val onFinish by rememberUpdatedState(onValueChangeFinished)
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    fun frac(v: Float) = ((v - valueRange.start) / span).coerceIn(0f, 1f)
    val shown = remember { Animatable(frac(value)) }
    val press = remember { Animatable(0f) }
    var active by remember { mutableStateOf(false) }
    val box = remember { mutableStateOf(IntSize.Zero) }
    val fx = remember { LensFx() }
    val shader = LiquidLens.supported && Build.VERSION.SDK_INT >= 33
    val accent = C.Accent
    val trackOff = if (light) Color(0xFF787880).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.18f)

    LaunchedEffect(value) { if (!active) shown.snapTo(frac(value)) }

    val inset = 22.dp
    Box(
        modifier.fillMaxWidth().height(46.dp)
            .onSizeChanged { box.value = it }
            .semantics {
                stateDescription = "${(frac(value) * 100).toInt()}%"
                setProgress { v -> onChange(v.coerceIn(valueRange.start, valueRange.endInclusive)); onFinish?.invoke(); true }
            }
            .then(
                if (shader) Modifier.graphicsLayer {
                    val p = press.value.coerceIn(0f, 1f)
                    val sz = box.value
                    renderEffect = if (p > 0.01f && sz.width > 0) {
                        val w = sz.width.toFloat(); val h = sz.height.toFloat()
                        val ins = inset.toPx()
                        val cx = ins + shown.value.coerceIn(0f, 1f) * (w - 2 * ins)
                        val g = DropGeom.capsule(cx, h / 2f, (38.dp + 12.dp * p).toPx(), (24.dp + 12.dp * p).toPx())
                        val l = GlassLight.dir
                        fx.effect(w, h, g, p, light, l.x, l.y, 4.dp.toPx(), density.density)
                    } else null
                } else Modifier,
            )
            .pointerInput(valueRange) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    active = true
                    val ins = inset.toPx()
                    fun f(x: Float) = ((x - ins) / (size.width - 2 * ins)).coerceIn(0f, 1f)
                    scope.launch { press.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f)) }
                    scope.launch { shown.animateTo(f(down.position.x), spring(dampingRatio = 0.8f, stiffness = 700f)) }
                    onChange(valueRange.start + f(down.position.x) * span)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        val fr = f(ch.position.x)
                        scope.launch { shown.snapTo(fr) }
                        onChange(valueRange.start + fr * span)
                    }
                    active = false
                    onFinish?.invoke()
                    scope.launch { delay(60); press.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 320f)) }
                }
            }
            .drawBehind {
                val ins = inset.toPx()
                val th = 6.dp.toPx()
                val cy = size.height / 2f
                val tw = size.width - 2 * ins
                val x = ins + shown.value.coerceIn(0f, 1f) * tw
                drawRoundRect(trackOff, Offset(ins - th / 2f, cy - th / 2f), Size(tw + th, th), CornerRadius(th / 2f))
                drawRoundRect(accent, Offset(ins - th / 2f, cy - th / 2f), Size(x - ins + th, th), CornerRadius(th / 2f))
                val p = press.value.coerceIn(0f, 1f)
                val kw = 38.dp.toPx() + if (shader) 0f else 6.dp.toPx() * p
                val kh = 24.dp.toPx()
                val alpha = if (shader) 1f - p else 1f
                if (alpha > 0.01f) {
                    drawRoundRect(Color.Black.copy(alpha = 0.16f * alpha), Offset(x - kw / 2f, cy - kh / 2f + 1.5.dp.toPx()), Size(kw, kh), CornerRadius(kh / 2f))
                    drawRoundRect(Color.White.copy(alpha = alpha), Offset(x - kw / 2f, cy - kh / 2f), Size(kw, kh), CornerRadius(kh / 2f))
                }
            },
    )
}

// ======================= okna dialogowe =======================

/** Szklane tło okna dialogowego (Glass) – razem z containerColor = C.DialogBg. */
fun Modifier.glassDialog(): Modifier =
    if (C.isGlass) this.clip(RoundedCornerShape(28.dp)).surface(RoundedCornerShape(28.dp), heavy = true) else this
