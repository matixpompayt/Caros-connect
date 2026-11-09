package pl.mati.carosconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Motyw „Glass” w stylu iOS: tapeta (GlassTheme) + materiał szklany – rozmyta i nasycona
 * tapeta pod każdą kartą, delikatny odcień, połysk u góry i jasna krawędź „liquid glass”.
 */
object Glass {
    val Violet = Color(0xFF7C3AED)
    val Sky = Color(0xFF0EA5E9)
    val Teal = Color(0xFF14B8A6)
    val Pink = Color(0xFFDB2777)
}

/**
 * Kierunek światła na ekranie (wektor jednostkowy ku światłu, oś Y w dół). Jak w iOS:
 * odblaski na krawędziach szkła przesuwają się, gdy przechylasz telefon (czujnik grawitacji).
 */
object GlassLight {
    var dir by mutableStateOf(Offset(-0.48f, -0.88f))
}

/** Nasłuch czujnika grawitacji, tylko gdy motyw Glass jest włączony i aplikacja na ekranie. */
@Composable
fun GlassTiltSensor() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var sx = 0f
        var sy = 9.81f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                sx += (e.values[0] - sx) * 0.12f
                sy += (e.values[1] - sy) * 0.12f
                // „góra świata” w układzie ekranu + stałe światło z lewego górnego rogu
                var lx = sx / 9.81f * 0.9f - 0.38f
                var ly = -sy / 9.81f * 0.6f - 0.55f
                val len = sqrt(lx * lx + ly * ly).coerceAtLeast(0.001f)
                lx /= len; ly /= len
                val cur = GlassLight.dir
                if (abs(cur.x - lx) + abs(cur.y - ly) > 0.025f) GlassLight.dir = Offset(lx, ly)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sm != null && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(l) }
    }
}

/** Ile szklanych warstw jest nad tapetą (karta w karcie nie rozmywa drugi raz – tylko lekki odcień). */
val LocalGlassDepth = compositionLocalOf { 0 }

/** Tapeta całej aplikacji w motywie Glass. */
@Composable
fun GlassBackdrop() {
    val ctx = LocalContext.current
    val spec by GlassTheme.spec.collectAsState()
    val size = remember { mutableStateOf(IntSize.Zero) }
    val sz = size.value
    LaunchedEffect(spec, sz) {
        if (sz.width <= 0 || sz.height <= 0) return@LaunchedEffect
        val r = withContext(Dispatchers.Default) {
            try { GlassTheme.render(ctx, spec, sz.width, sz.height) } catch (_: Throwable) { null }
        } ?: return@LaunchedEffect
        GlassState.sharp = r.sharp
        GlassState.blurred = r.blurred
        GlassState.autoAccent = r.accent
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { if (it != size.value) { size.value = it; GlassState.size = it } }
            .drawBehind {
                val s = GlassState.sharp
                if (s != null) drawImage(s, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                else drawRect(Color(0xFF0B1026))
            },
    )
}

/**
 * Tło powierzchni (karty, wiersze, przyciski) zgodne z motywem:
 * w Glass – szkło, w pozostałych – zwykły kolor (i ramka, jeśli podana).
 */
fun Modifier.surface(shape: Shape, color: Color = C.Card, border: Color? = null, borderWidth: Dp = 1.dp, heavy: Boolean = false): Modifier =
    if (!C.isGlass) {
        this.background(color, shape).then(if (border != null) Modifier.border(borderWidth, border, shape) else Modifier)
    } else composed {
        val depth = LocalGlassDepth.current
        val light = C.isLight
        val raised = color == C.Raised
        val extraBorder = border?.takeIf { it != C.Border }
        val pos = remember { mutableStateOf(Offset.Zero) }
        val view = LocalView.current
        // pozycja na ekranie (okna dialogowe mają własne okno – tapeta jest liczona od rogu ekranu)
        this.onGloballyPositioned {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            pos.value = it.positionInRoot() + Offset(loc[0].toFloat(), loc[1].toFloat())
        }
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val path = Path().apply { addOutline(outline) }
                // odcień materiału (jak .regularMaterial / .thinMaterial w iOS)
                val tint: List<Color> = when {
                    heavy && light -> listOf(Color.White.copy(alpha = 0.74f))
                    heavy -> listOf(Color.Black.copy(alpha = 0.40f), Color.White.copy(alpha = 0.08f))
                    depth > 0 && light -> listOf(Color.White.copy(alpha = if (raised) 0.42f else 0.30f))
                    depth > 0 -> listOf(Color.White.copy(alpha = if (raised) 0.10f else 0.07f))
                    light -> listOf(Color.White.copy(alpha = 0.52f))
                    else -> listOf(Color.Black.copy(alpha = 0.22f), Color.White.copy(alpha = 0.07f))
                }
                val sheen = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (light) 0.30f else 0.11f),
                    0.45f to Color.Transparent,
                    startY = 0f, endY = size.height,
                )
                // krawędź: odblask od strony światła (przechylenie telefonu), słabszy po przeciwnej stronie
                val ld = GlassLight.dir
                val c = Offset(size.width / 2f, size.height / 2f)
                val rr = kotlin.math.hypot(size.width, size.height) / 2f
                val rim = Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (light) 0.95f else 0.6f),
                    0.45f to Color.White.copy(alpha = if (light) 0.35f else 0.06f),
                    1f to Color.White.copy(alpha = if (light) 0.7f else 0.26f),
                    start = c + Offset(ld.x * rr, ld.y * rr), end = c - Offset(ld.x * rr, ld.y * rr),
                )
                val stroke = Stroke(borderWidth.toPx())
                onDrawBehind {
                    clipPath(path) {
                        val blurred = GlassState.blurred
                        val root = GlassState.size
                        if (depth == 0 && blurred != null && root.width > 0) {
                            val p = pos.value
                            drawImage(
                                blurred,
                                dstOffset = IntOffset(-p.x.roundToInt(), -p.y.roundToInt()),
                                dstSize = root,
                                filterQuality = FilterQuality.Low,
                            )
                        }
                        tint.forEach { drawRect(it) }
                        drawRect(sheen)
                    }
                    drawOutline(outline, rim, style = stroke)
                    if (extraBorder != null) drawOutline(outline, SolidColor(extraBorder), style = stroke)
                }
            }
    }
