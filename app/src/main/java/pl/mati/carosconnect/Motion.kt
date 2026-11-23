package pl.mati.carosconnect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Animacje CAR OS Connect: wejście kart po kolei, sprężyste wciśnięcia, logo. */

class StaggerCounter {
    var next = 0
}

val LocalStagger = staticCompositionLocalOf<StaggerCounter?> { null }

/** Wejście elementu: pojawienie się + lekki wjazd od dołu, kolejne elementy chwilę później. */
fun Modifier.stagger(): Modifier = composed {
    // podgląd / zrzuty ekranu – bez animacji wejścia
    if (androidx.compose.ui.platform.LocalInspectionMode.current) return@composed Modifier
    val counter = LocalStagger.current
    val idx = remember { counter?.let { it.next++ } ?: 0 }
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(idx.coerceAtMost(10) * 40L)
        // wygląd 2.0: sprężyście – karta lekko „dojeżdża” na miejsce
        p.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 210f))
    }
    Modifier.graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * 30.dp.toPx()
        val s = 0.95f + 0.05f * p.value
        scaleX = s
        scaleY = s
    }
}

/** Sprężyste „wciśnięcie”. */
fun Modifier.pressScale(src: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by src.collectIsPressedAsState()
    val glass = C.isGlass
    val s by animateFloatAsState(
        if (isPressed) (if (glass) 1f + (1f - pressed).coerceAtMost(0.05f) * 0.8f else pressed) else 1f,
        if (glass) spring(dampingRatio = 0.45f, stiffness = 520f) else spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "press",
    )
    Modifier.graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** Pulsowanie (np. kropka „W trasie”, kończący się parkometr). */
@Composable
fun rememberPulse(enabled: Boolean, min: Float = 0.35f, periodMs: Int = 800): Float {
    if (!enabled) return 1f
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(1f, min, infiniteRepeatable(tween(periodMs), RepeatMode.Reverse), label = "pv")
    return v
}

/** Logo CAR OS rysowane z animacją: łuk się „zapala”, wskazówka wychyla się i wraca. */
@Composable
fun AnimatedLogo(size: Dp, accent: Color = C.Accent) {
    val arc = remember { Animatable(0f) }
    val needle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { arc.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        delay(300)
        needle.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        needle.animateTo(0.62f, spring(dampingRatio = 0.35f, stiffness = 120f))
    }
    val glow = rememberInfiniteTransition(label = "glow")
    val g by glow.animateFloat(0.25f, 0.55f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "g")
    Canvas(Modifier.size(size)) {
        val w = this.size.minDimension
        val stroke = w * 0.075f
        val r = w * 0.36f
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val tl = Offset(c.x - r, c.y - r)
        val sz = androidx.compose.ui.geometry.Size(r * 2, r * 2)
        // poświata
        drawArc(accent.copy(alpha = g * 0.35f), 135f, 270f * arc.value, false, tl, sz, style = Stroke(stroke * 2.4f, cap = StrokeCap.Round))
        drawArc(accent, 135f, 270f * arc.value, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
        // wskazówka: od lewego dołu (135°) do ~ prawej góry
        rotate(-45f + 225f * needle.value - 90f, c) {
            drawLine(Color(0xFFEEF1F4), c, Offset(c.x, c.y - r * 0.72f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
        }
        drawCircle(Color(0xFFEEF1F4), stroke * 0.7f, c)
    }
}

// ---------- wejście i wyjście ekranów na cały ekran ----------

/**
 * Postęp wejścia ekranu z AnimatedVisibility: 0 → 1 przy wejściu, 1 → 0 przy wyjściu
 * (AnimatedVisibility trzyma treść, aż ta animacja się skończy). Elementy ekranu dzielą go
 * między siebie ([reveal]) – wlatują po kolei, a przy wyjściu znikają w odwrotnej kolejności.
 */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun androidx.compose.animation.AnimatedVisibilityScope.screenProgress(): androidx.compose.runtime.State<Float> =
    transition.animateFloat(
        transitionSpec = {
            if (targetState == androidx.compose.animation.EnterExitState.Visible) tween(620, easing = FastOutSlowInEasing)
            else tween(380, easing = FastOutSlowInEasing)
        },
        label = "screen",
    ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }

/** Część postępu [p] między [from] a [to] → 0..1. */
fun seg(p: Float, from: Float, to: Float): Float = ((p - from) / (to - from)).coerceIn(0f, 1f)

/** Gotowy postęp (zrzuty ekranu, ekran bez animacji). */
val SHOWN: androidx.compose.runtime.State<Float> = androidx.compose.runtime.mutableFloatStateOf(1f)

/**
 * Element ekranu pojawia się w swojej części postępu [p]: przesunięcie o [dy] (ujemne – z góry),
 * skala od [scale], przenikanie. Czyta postęp tylko przy rysowaniu – bez przeliczania ekranu.
 */
fun Modifier.reveal(
    p: androidx.compose.runtime.State<Float>, from: Float, to: Float,
    dy: Dp = 0.dp, scale: Float = 1f, fade: Boolean = true,
): Modifier = graphicsLayer {
    val q = seg(p.value, from, to)
    if (fade) alpha = q
    translationY = (1f - q) * dy.toPx()
    val s = scale + (1f - scale) * q
    scaleX = s
    scaleY = s
}
