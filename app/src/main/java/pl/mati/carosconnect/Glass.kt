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
import androidx.compose.ui.graphics.asAndroidBitmap
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
        // wygląd 2.0: delikatny pionowy połysk materiału i jaśniejsza krawędź u góry (światło z góry)
        val light = C.isLight
        val fill = Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(color, Color.White, if (light) 0.35f else 0.045f), color))
        this.background(fill, shape).then(
            when {
                border == null -> Modifier
                border == C.Border -> Modifier.border(
                    borderWidth,
                    Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(border, Color.White, if (light) 0.55f else 0.16f), border)),
                    shape,
                )
                else -> Modifier.border(borderWidth, border, shape)
            },
        )
    } else glassMaterial(shape, color, border, borderWidth, heavy)

/**
 * Materiał szklany. [frostOverride] – wymuś styl (podgląd w ustawieniach), [top] – rysuj tapetę
 * nawet w karcie w karcie (podgląd).
 * Gładkie szkło: pod kartą ostra tapeta, przy krawędzi zagięta jak w soczewce (Android 13+: shader),
 * matowe: rozmyta i mleczna. W obu – połysk u góry i jasna krawędź od strony światła.
 */
fun Modifier.glassMaterial(
    shape: Shape, color: Color = C.Card, border: Color? = null, borderWidth: Dp = 1.dp, heavy: Boolean = false,
    frostOverride: Boolean? = null, top: Boolean = false,
): Modifier = composed {
        val depth = if (top) 0 else LocalGlassDepth.current
        val light = C.isLight
        val raised = color == C.Raised
        val extraBorder = border?.takeIf { it != C.Border }
        val gs by GlassTheme.spec.collectAsState()
        val frost = frostOverride ?: gs.frost
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
                // okna (heavy) zawsze na rozmytej tapecie – czytelność
                val useBlur = frost || heavy
                // odcień materiału (jak .regularMaterial / .thinMaterial w iOS); gładkie – prawie przezroczysty
                val tint: List<Color> = when {
                    heavy && light -> listOf(Color.White.copy(alpha = 0.74f))
                    heavy -> listOf(Color.Black.copy(alpha = 0.40f), Color.White.copy(alpha = 0.08f))
                    depth > 0 && light -> listOf(Color.White.copy(alpha = if (raised) 0.42f else 0.30f))
                    depth > 0 -> listOf(Color.White.copy(alpha = if (raised) 0.10f else 0.07f))
                    !frost && light -> listOf(Color.White.copy(alpha = 0.24f))
                    !frost -> listOf(Color.Black.copy(alpha = 0.14f), Color.White.copy(alpha = 0.035f))
                    light -> listOf(Color.White.copy(alpha = 0.52f))
                    else -> listOf(Color.Black.copy(alpha = 0.22f), Color.White.copy(alpha = 0.07f))
                }
                val sheen = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (light) 0.30f else if (!frost) 0.13f else 0.11f),
                    0.45f to Color.Transparent,
                    startY = 0f, endY = size.height,
                )
                // krawędź: odblask od strony światła (przechylenie telefonu), słabszy po przeciwnej stronie
                val ld = GlassLight.dir
                val c = Offset(size.width / 2f, size.height / 2f)
                val rr = kotlin.math.hypot(size.width, size.height) / 2f
                val clear = !frost
                val rim = Brush.linearGradient(
                    0f to Color.White.copy(alpha = if (light) 0.95f else if (clear) 0.78f else 0.6f),
                    0.45f to Color.White.copy(alpha = if (light) 0.35f else if (clear) 0.10f else 0.06f),
                    1f to Color.White.copy(alpha = if (light) 0.7f else if (clear) 0.36f else 0.26f),
                    start = c + Offset(ld.x * rr, ld.y * rr), end = c - Offset(ld.x * rr, ld.y * rr),
                )
                val stroke = Stroke(borderWidth.toPx() * if (clear) 1.25f else 1f)
                // tapeta pod kartą i (Android 13+) shader z załamaniem światła na krawędzi
                val wall = if (useBlur) GlassState.blurred else GlassState.sharp
                val minDim = minOf(size.width, size.height)
                val radius = when (outline) {
                    is androidx.compose.ui.graphics.Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                    else -> 0f
                }.coerceIn(0f, minDim / 2f)
                val bevel = (radius * 1.1f).coerceIn(minOf(10.dp.toPx(), minDim / 2f), minOf(26.dp.toPx(), minDim / 2f).coerceAtLeast(1f))
                val refr = (if (frost) 8.dp else 15.dp).toPx()
                val fx = if (depth == 0 && wall != null && GlassRefraction.supported && android.os.Build.VERSION.SDK_INT >= 33) {
                    try { GlassFx(wall) } catch (_: Throwable) { GlassRefraction.broken = true; null }
                } else null
                onDrawBehind {
                    clipPath(path) {
                        val root = GlassState.size
                        if (depth == 0 && wall != null && root.width > 0) {
                            val p = pos.value
                            val done = fx != null && android.os.Build.VERSION.SDK_INT >= 33 && try {
                                fx.draw(this, p, radius, bevel, refr, wall.width.toFloat() / root.width, GlassLight.dir)
                                true
                            } catch (_: Throwable) {
                                GlassRefraction.broken = true
                                false
                            }
                            if (!done) drawImage(
                                wall,
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

/** Załamanie światła w szkle (Android 13+, AGSL). */
object GlassRefraction {
    @Volatile var broken = false
    val supported get() = android.os.Build.VERSION.SDK_INT >= 33 && !broken

    internal const val AGSL = """
uniform shader wall;
uniform float2 uOrigin;
uniform float2 uSize;
uniform float uRadius;
uniform float uBevel;
uniform float uRefr;
uniform float uScale;
uniform float2 uWall;
uniform float2 uLightDir;

float sdRR(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float sd(float2 p) { return sdRR(p - uSize * 0.5, uSize * 0.5, uRadius); }

half4 tap(float2 rp) {
    float2 q = clamp(rp * uScale, float2(0.5, 0.5), uWall - float2(1.5, 1.5));
    return wall.eval(q);
}

half4 main(float2 p) {
    float d = sd(p);
    float e = 1.0;
    float2 g = float2(sd(p + float2(e, 0.0)) - sd(p - float2(e, 0.0)), sd(p + float2(0.0, e)) - sd(p - float2(0.0, e)));
    float2 n = g / (length(g) + 0.00001);
    // 0 w środku karty, 1 na samej krawędzi – tam szkło jest „grube” i zagina obraz
    float x = clamp(1.0 + d / uBevel, 0.0, 1.0);
    float bend = x * x * x;
    float2 off = n * (uRefr * bend);
    float2 rp = uOrigin + p;
    // lekka tęcza (aberracja) przy brzegu
    float ca = 0.3 * bend;
    half4 cr = tap(rp + off * (1.0 + ca));
    half4 cg = tap(rp + off);
    half4 cb = tap(rp + off * (1.0 - ca));
    float3 rgb = float3(cr.r, cg.g, cb.b);
    // światło na grubym brzegu: jaśniej od strony światła, ciemniej po drugiej
    float ndl = dot(n, uLightDir);
    float facing = clamp(ndl, 0.0, 1.0);
    float away = clamp(-ndl, 0.0, 1.0);
    rgb = rgb + float3((0.06 + 0.20 * facing) * bend);
    rgb = rgb * (1.0 - 0.12 * away * bend);
    return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
}
"""
}

/** Shader jednej karty – tapeta jako wejście, parametry ustawiane przy każdym rysowaniu. */
@androidx.annotation.RequiresApi(33)
private class GlassFx(wall: androidx.compose.ui.graphics.ImageBitmap) {
    private val shader = android.graphics.RuntimeShader(GlassRefraction.AGSL).apply {
        val b = wall.asAndroidBitmap()
        setInputShader("wall", android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP))
        setFloatUniform("uWall", b.width.toFloat(), b.height.toFloat())
    }
    private val brush = androidx.compose.ui.graphics.ShaderBrush(shader)

    fun draw(ds: androidx.compose.ui.graphics.drawscope.DrawScope, origin: Offset, radius: Float, bevel: Float, refr: Float, scale: Float, light: Offset) {
        shader.setFloatUniform("uOrigin", origin.x, origin.y)
        shader.setFloatUniform("uSize", ds.size.width, ds.size.height)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uBevel", bevel)
        shader.setFloatUniform("uRefr", refr)
        shader.setFloatUniform("uScale", scale)
        shader.setFloatUniform("uLightDir", light.x, light.y)
        ds.drawRect(brush)
    }
}

/** Okno na tapetę: rysuje kawałek ostrej tapety, który jest pod tym miejscem ekranu (podgląd szkła). */
fun Modifier.wallpaperWindow(): Modifier = composed {
    val pos = remember { mutableStateOf(Offset.Zero) }
    val view = LocalView.current
    this.onGloballyPositioned {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        pos.value = it.positionInRoot() + Offset(loc[0].toFloat(), loc[1].toFloat())
    }.drawBehind {
        val s = GlassState.sharp
        val root = GlassState.size
        if (s != null && root.width > 0) {
            val p = pos.value
            drawImage(s, dstOffset = IntOffset(-p.x.roundToInt(), -p.y.roundToInt()), dstSize = root, filterQuality = FilterQuality.Medium)
        } else drawRect(Color(0xFF0B1026))
    }
}
