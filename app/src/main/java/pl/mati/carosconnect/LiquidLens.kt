package pl.mati.carosconnect

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Shader „liquid glass” (Android 13+): kropla jako soczewka nad paskiem zakładek.
 * – w środku: treść pod kroplą (powiększona, gdy kropla jest „podniesiona” palcem),
 * – przy brzegu: załamanie światła – treść zza krawędzi zagina się do środka, z lekką aberracją barw,
 * – odblaski na krawędzi od strony światła (kierunek z czujnika grawitacji), cień pod kroplą.
 * Kształt to „nierówna kapsuła” (przód pełny, tył węższy) liczona funkcją odległości (SDF).
 */
object LiquidLens {
    private const val AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float2 uRear;
uniform float2 uFront;
uniform float uRr;
uniform float uRf;
uniform float uDir;
uniform float uLift;
uniform float uLight;
uniform float2 uLightDir;
uniform float uPad;
uniform float uDensity;

float sdUneven(float2 p0, float r1, float r2, float h) {
    float2 p = float2(abs(p0.x), p0.y);
    float b = clamp((r1 - r2) / h, -0.99, 0.99);
    float a = sqrt(1.0 - b * b);
    float k = dot(p, float2(-b, a));
    if (k < 0.0) { return length(p) - r1; }
    if (k > a * h) { return length(p - float2(0.0, h)) - r2; }
    return dot(p, float2(a, b)) - r1;
}

float sdDrop(float2 p) {
    float2 ax = uFront - uRear;
    float h = length(ax);
    float2 u = float2(uDir, 0.0);
    if (h > 0.01) { u = ax / h; }
    float2 v = float2(-u.y, u.x);
    float2 r = p - uRear;
    return sdUneven(float2(dot(r, v), dot(r, u)), uRr, uRf, max(h, 0.01));
}

float4 samp(float2 p) {
    float2 q = clamp(p, float2(1.0, 1.0), uSize - float2(2.0, 2.0));
    return float4(content.eval(q));
}

half4 main(float2 p) {
    float dist = sdDrop(p);
    float4 base = float4(content.eval(p));
    float dsh = sdDrop(p - float2(0.0, 3.0 * uDensity));
    float shadow = 0.0;
    if (dsh > 0.0) {
        shadow = (1.0 - clamp(dsh / ((10.0 + 8.0 * uLift) * uDensity), 0.0, 1.0)) * (0.08 + 0.14 * uLift) * (1.0 - 0.5 * uLight);
    }
    // cień: czarny „nad” treścią (działa też na przezroczystym tle)
    float4 outside = float4(base.rgb * (1.0 - shadow), shadow + base.a * (1.0 - shadow));
    if (dist > 1.0) { return half4(outside); }

    float e = 1.0;
    float2 g = float2(sdDrop(p + float2(e, 0.0)) - sdDrop(p - float2(e, 0.0)),
                      sdDrop(p + float2(0.0, e)) - sdDrop(p - float2(0.0, e)));
    float2 n = g / (length(g) + 0.00001);
    float thick = min(uRr, uRf) * 0.55;
    float depth = clamp(-dist / thick, 0.0, 1.0);
    float2 lensC = (uRear + uFront) * 0.5;
    float mag = 1.0 + 0.22 * uLift;
    float2 sp = lensC + (p - lensC) / mag;
    float rim = pow(1.0 - depth, 2.2);
    float2 off = n * (uPad * 0.9 * rim);
    float ca = 0.24 * rim;
    float4 cr = samp(sp + off * (1.0 + ca));
    float4 cg = samp(sp + off);
    float4 cb = samp(sp + off * (1.0 - ca));
    float a = cg.a;
    float3 rgb = float3(cr.r, cg.g, cb.b);
    float t = mix(0.08, 0.32, uLight) + 0.04 * uLift;
    rgb = rgb * (1.0 - t) + float3(a * t);
    // „ciało” szkła tam, gdzie pod soczewką nic nie ma (np. gałka wystająca poza tor)
    float body = mix(0.10, 0.30, uLight) * (1.0 - a);
    rgb = rgb + float3(body);
    a = a + body;
    float ndl = dot(n, uLightDir);
    float facing = clamp(ndl, 0.0, 1.0);
    float back = clamp(-ndl, 0.0, 1.0);
    float edge = 1.0 - clamp(-dist / (2.2 * uDensity), 0.0, 1.0);
    float spec = edge * (0.75 * facing * facing + 0.25 * back * back) * mix(1.0, 0.8, uLight);
    float inner = (1.0 - clamp(-dist / (thick * 1.6), 0.0, 1.0)) * facing * 0.12;
    float hl = clamp(spec + inner, 0.0, 1.0);
    // odblask: biały „nad” szkłem
    rgb = float3(hl) + rgb * (1.0 - hl);
    a = hl + a * (1.0 - hl);
    float4 inside = float4(min(rgb, float3(a)), a);
    float aa = clamp((1.0 - dist) * 0.5, 0.0, 1.0);
    return half4(mix(outside, inside, aa));
}
"""

    /** false, gdy shader się nie skompilował albo urządzenie go nie obsługuje – wtedy rysujemy kroplę zwykłym Compose. */
    var broken by androidx.compose.runtime.mutableStateOf(false)

    val supported get() = Build.VERSION.SDK_INT >= 33 && !broken

    internal val source get() = AGSL
}

/** Soczewka dla jednej kontrolki (własny RuntimeShader – kontrolki nie nadpisują sobie parametrów). */
class LensFx {
    private var shader: Any? = null

    @RequiresApi(33)
    fun effect(
        w: Float, h: Float, g: DropGeom, lift: Float, light: Boolean,
        lx: Float, ly: Float, refr: Float, density: Float,
    ): RenderEffect? = try {
        val s = (shader as? RuntimeShader) ?: RuntimeShader(LiquidLens.source).also { shader = it }
        s.setFloatUniform("uSize", w, h)
        s.setFloatUniform("uRear", g.rearX, g.cy)
        s.setFloatUniform("uFront", g.frontX, g.cy)
        s.setFloatUniform("uRr", g.rr)
        s.setFloatUniform("uRf", g.rf)
        s.setFloatUniform("uDir", g.dir.toFloat())
        s.setFloatUniform("uLift", lift)
        s.setFloatUniform("uLight", if (light) 1f else 0f)
        s.setFloatUniform("uLightDir", lx, ly)
        s.setFloatUniform("uPad", refr)
        s.setFloatUniform("uDensity", density)
        android.graphics.RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    } catch (t: Throwable) {
        LiquidLens.broken = true
        null
    }
}

/** Geometria kropli w pikselach paska: środki tylnej i przedniej „czapki”, ich promienie i kierunek ruchu. */
data class DropGeom(val rearX: Float, val frontX: Float, val cy: Float, val rr: Float, val rf: Float, val dir: Int) {
    val lensX get() = (rearX + frontX) / 2f

    companion object {
        /** Zwykła kapsuła (gałka przełącznika / suwaka): środek, szerokość, wysokość. */
        fun capsule(cx: Float, cy: Float, w: Float, h: Float): DropGeom {
            val r = h / 2f
            val half = maxOf(0f, w / 2f - r)
            return DropGeom(cx - half, cx + half, cy, r, r, 1)
        }
    }
}

/**
 * fc / rc – środek przodu i tyłu kropli w jednostkach zakładek (0..n-1),
 * lift – 0 spoczynek, 1 kropla „podniesiona” palcem.
 */
fun dropGeom(fc0: Float, rc0: Float, lift: Float, dir0: Int, n: Int, w: Float, h: Float, pad: Float, density: Float): DropGeom {
    val fc = fc0.coerceIn(0f, (n - 1).toFloat())
    val rc = rc0.coerceIn(0f, (n - 1).toFloat())
    val itemW = (w - 2 * pad) / n
    val h0 = (h - 2 * pad) * (1f + 0.03f * lift)
    val w0 = (itemW - 2 * density) * (1f + 0.04f * lift)
    fun cx(i: Float) = pad + itemW * (i + 0.5f)
    val s = minOf(1f, kotlin.math.abs(fc - rc) * 1.1f)
    val d = when { fc > rc + 0.0005f -> 1; fc < rc - 0.0005f -> -1; else -> dir0 }
    val rf = h0 / 2f * (1f - 0.06f * s)
    val rr = h0 / 2f * (1f - 0.26f * s)
    return DropGeom(cx(rc) - d * (w0 / 2f - rr), cx(fc) + d * (w0 / 2f - rf), h / 2f, rr, rf, d)
}
