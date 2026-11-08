package pl.mati.carosconnect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motyw „Glass”: kolorowe, powoli pływające plamy światła w tle i półprzezroczyste
 * „szklane” karty z jasną krawędzią u góry. Tło jest samo w sobie rozmyte (gradienty
 * radialne), więc szkło nie potrzebuje rozmywania tego, co pod nim – działa na każdym Androidzie.
 */
object Glass {
    val Base1 = Color(0xFF0B1026)
    val Base2 = Color(0xFF150B30)
    val Base3 = Color(0xFF051B26)
    val Violet = Color(0xFF7C3AED)
    val Sky = Color(0xFF0EA5E9)
    val Teal = Color(0xFF14B8A6)
    val Pink = Color(0xFFDB2777)

    /** Wypełnienie szkła: jaśniej u góry, ciemniej u dołu. */
    fun fill(strength: Float = 1f) = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.13f * strength), Color.White.copy(alpha = 0.055f * strength)),
    )

    /** Krawędź szkła: odbicie światła u góry, prawie niewidoczna u dołu. */
    val edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.07f)))
}

/** Tło całej aplikacji w motywie Glass. */
@Composable
fun GlassBackdrop(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "glass")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(37_000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.linearGradient(listOf(Glass.Base1, Glass.Base2, Glass.Base3), start = Offset.Zero, end = Offset(w, h)))
        fun blob(cx: Float, cy: Float, r: Float, c: Color, alpha: Float) {
            val center = Offset(cx, cy)
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.45f), c.copy(alpha = 0f)), center = center, radius = r),
                radius = r, center = center,
            )
        }
        // odczyt a/b tylko tutaj (faza rysowania) → animacja nie przebudowuje interfejsu
        blob(w * (0.10f + 0.30f * a), h * (0.10f + 0.10f * b), w * 0.95f, Glass.Violet, 0.55f)
        blob(w * (0.98f - 0.25f * b), h * (0.36f + 0.12f * a), w * 0.85f, Glass.Sky, 0.42f)
        blob(w * (0.15f + 0.35f * b), h * (0.70f - 0.10f * a), w * 0.90f, Glass.Teal, 0.36f)
        blob(w * (0.90f - 0.30f * a), h * (0.98f - 0.08f * b), w * 0.75f, Glass.Pink, 0.32f)
        // lekkie przyciemnienie – tekst ma być czytelny
        drawRect(Color.Black.copy(alpha = 0.18f))
    }
}

/**
 * Tło powierzchni (karty, wiersze, przyciski) zgodne z motywem:
 * w Glass – szkło z jasną krawędzią, w pozostałych – zwykły kolor.
 */
fun Modifier.surface(shape: Shape, color: Color = C.Card, border: Color? = null, borderWidth: Dp = 1.dp): Modifier =
    if (C.isGlass) {
        val strength = if (color == C.Raised) 1.25f else 1f
        this.background(Glass.fill(strength), shape).then(
            if (border != null && border != C.Border) Modifier.border(borderWidth, border, shape)
            else Modifier.border(borderWidth, Glass.edge, shape),
        )
    } else {
        this.background(color, shape).then(if (border != null) Modifier.border(borderWidth, border, shape) else Modifier)
    }
