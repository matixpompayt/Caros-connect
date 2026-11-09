package pl.mati.carosconnect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Ustawienia i tło motywu Glass: gotowe mieszanki, jednolity kolor, własna mieszanka,
 * zdjęcie z galerii albo obraz wygenerowany przez AI (Pollinations.ai, bez klucza).
 * Tło renderujemy raz do bitmapy + jej mocno rozmytej i nasyconej kopii – z niej
 * „szklane” powierzchnie wycinają kawałek pod sobą (Glass.kt), jak materiały w iOS.
 */
object GlassTheme {

    data class Preset(val id: String, val name: String, val colors: List<Long>, val base: Long)

    val PRESETS = listOf(
        Preset("aurora", "Zorza", listOf(0xFF7C3AED, 0xFF0EA5E9, 0xFF14B8A6, 0xFFDB2777), 0xFF0B1026),
        Preset("ios", "iOS", listOf(0xFF5856D6, 0xFFFF2D55, 0xFF5AC8FA, 0xFFFF9500), 0xFF0E0B24),
        Preset("sunset", "Zachód", listOf(0xFFF97316, 0xFFEC4899, 0xFF8B5CF6, 0xFFFACC15), 0xFF2A0E1F),
        Preset("ocean", "Ocean", listOf(0xFF0EA5E9, 0xFF2563EB, 0xFF06B6D4, 0xFF22D3EE), 0xFF04142B),
        Preset("forest", "Las", listOf(0xFF10B981, 0xFF84CC16, 0xFF0D9488, 0xFF22C55E), 0xFF04190F),
        Preset("berry", "Jagoda", listOf(0xFFDB2777, 0xFF9333EA, 0xFFF43F5E, 0xFF6366F1), 0xFF1A0820),
        Preset("fire", "Ogień", listOf(0xFFEF4444, 0xFFF97316, 0xFFFBBF24, 0xFFB91C1C), 0xFF1F0704),
        Preset("mint", "Mięta", listOf(0xFF5EEAD4, 0xFFA7F3D0, 0xFF38BDF8, 0xFFC4B5FD), 0xFF0B2027),
        Preset("peach", "Brzoskwinia", listOf(0xFFFDBA74, 0xFFF9A8D4, 0xFFFDE68A, 0xFFFCA5A5), 0xFF3B1F2B),
        Preset("graphite", "Grafit", listOf(0xFF64748B, 0xFF94A3B8, 0xFF334155, 0xFF475569), 0xFF0B0E13),
        // ciemne
        Preset("night", "Noc", listOf(0xFF1E3A8A, 0xFF312E81, 0xFF3B0764, 0xFF0B1F4D), 0xFF02040C),
        Preset("onyx", "Onyks", listOf(0xFF27272A, 0xFF1F2937, 0xFF3F3F46, 0xFF111827), 0xFF000000),
        Preset("deep", "Głębia", listOf(0xFF134E4A, 0xFF0C4A6E, 0xFF064E3B, 0xFF1E3A8A), 0xFF010B10),
        Preset("burgundy", "Bordo", listOf(0xFF7F1D1D, 0xFF881337, 0xFF4A044E, 0xFF7C2D12), 0xFF0E0205),
        Preset("darkforest", "Las nocą", listOf(0xFF14532D, 0xFF064E3B, 0xFF365314, 0xFF134E4A), 0xFF010A04),
    )

    /** Kolory do „Jednolity kolor”, „Mieszanka” i akcentu. */
    val SWATCHES = listOf(
        0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFFFACC15, 0xFF84CC16, 0xFF22C55E,
        0xFF10B981, 0xFF14B8A6, 0xFF06B6D4, 0xFF0EA5E9, 0xFF3B82F6, 0xFF6366F1,
        0xFF8B5CF6, 0xFFA855F7, 0xFFD946EF, 0xFFEC4899, 0xFFF43F5E, 0xFF94A3B8,
    )

    /** Ciemne kolory do „Jednolity kolor” i „Mieszanka”. */
    val DARK_SWATCHES = listOf(
        0xFF0A0A0F, 0xFF27272A, 0xFF1F2937, 0xFF1E293B, 0xFF0B1F4D, 0xFF1E3A8A,
        0xFF312E81, 0xFF3B0764, 0xFF4A044E, 0xFF881337, 0xFF7F1D1D, 0xFF7C2D12,
        0xFF451A03, 0xFF365314, 0xFF14532D, 0xFF064E3B, 0xFF134E4A, 0xFF0C4A6E,
    )

    /** Pomysły na tło AI: (napis, opis wysyłany do generatora po angielsku). */
    val AI_IDEAS = listOf(
        "Zorza polarna" to "aurora borealis over snowy mountains at night, glowing green and violet sky",
        "Neonowe miasto" to "neon city at night in the rain, cyberpunk, glowing reflections, bokeh",
        "Górskie jezioro" to "calm mountain lake at sunrise, pastel sky, mist, reflections",
        "Płynne szkło" to "abstract liquid glass shapes, iridescent, soft gradients, 3d render",
        "Ocean z góry" to "aerial view of turquoise ocean waves and sand, minimal",
        "Kosmos" to "colorful nebula in deep space, stars, cinematic",
        "Nocna autostrada" to "long exposure highway at night, light trails, city skyline",
        "Las we mgle" to "misty pine forest at dawn, soft light rays, moody",
        "Zachód nad morzem" to "sunset over the sea, warm orange and pink clouds, soft",
        "Pastelowe fale" to "soft pastel abstract waves, gradient, minimal, calm",
    )

    data class Spec(
        /** "dark" | "light" | "auto" */
        val tone: String = "dark",
        /** "preset" | "solid" | "mix" | "image" */
        val kind: String = "preset",
        val preset: String = "aurora",
        val solid: Long = 0xFF3B82F6,
        val mix: List<Long> = listOf(0xFF8B5CF6, 0xFF0EA5E9, 0xFFEC4899),
        val seed: Int = 7,
        /** przyciemnienie tła 0..0.7 */
        val dim: Float = 0.12f,
        /** null = automatycznie z tła */
        val accent: Long? = null,
        /** "photo" | "ai" – skąd obraz przy kind = "image" */
        val imageSrc: String = "",
        val aiPrompt: String = "",
        /** zmienia się po zapisaniu nowego obrazu → przerysuj */
        val rev: Int = 0,
    )

    val spec = MutableStateFlow(Spec())
    val aiBusy = MutableStateFlow(false)
    val aiError = MutableStateFlow<String?>(null)
    val photoBusy = MutableStateFlow(false)
    private var appCtx: Context? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("glass_theme", Context.MODE_PRIVATE)
    fun imageFile(ctx: Context) = File(ctx.filesDir, "glass_wall.jpg")

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        val p = sp(ctx)
        spec.value = Spec(
            tone = p.getString("tone", "dark") ?: "dark",
            kind = p.getString("kind", "preset") ?: "preset",
            preset = p.getString("preset", "aurora") ?: "aurora",
            solid = p.getLong("solid", 0xFF3B82F6),
            mix = (p.getString("mix", null) ?: "").split(",").mapNotNull { it.toLongOrNull(16) }.takeIf { it.size >= 2 } ?: Spec().mix,
            seed = p.getInt("seed", 7),
            dim = p.getFloat("dim", 0.12f),
            accent = p.getLong("accent", 0L).takeIf { it != 0L },
            imageSrc = p.getString("imageSrc", "") ?: "",
            aiPrompt = p.getString("aiPrompt", "") ?: "",
            rev = p.getInt("rev", 0),
        ).let { if (it.kind == "image" && !imageFile(ctx).exists()) it.copy(kind = "preset") else it }
    }

    fun update(ctx: Context, f: (Spec) -> Spec) {
        val s = f(spec.value)
        spec.value = s
        sp(ctx).edit()
            .putString("tone", s.tone).putString("kind", s.kind).putString("preset", s.preset)
            .putLong("solid", s.solid).putString("mix", s.mix.joinToString(",") { it.toString(16) })
            .putInt("seed", s.seed).putFloat("dim", s.dim).putLong("accent", s.accent ?: 0L)
            .putString("imageSrc", s.imageSrc).putString("aiPrompt", s.aiPrompt).putInt("rev", s.rev)
            .apply()
    }

    /** Czy szkło ma być jasne (ciemny tekst). */
    fun isLight(s: Spec, sysDark: Boolean) = when (s.tone) { "light" -> true; "auto" -> !sysDark; else -> false }

    val DEFAULT_ACCENT = Color(0xFF67E8F9)

    /** Paleta interfejsu w motywie Glass. Karty rysuje Glass.surface – tu kolory awaryjne i tekst. */
    fun pal(light: Boolean, accent: Color): Pal = if (!light) Pal(
        false, Color(0xFF0B1026), Color(0x1FFFFFFF), Color(0x29FFFFFF), Color(0x33FFFFFF),
        Color(0xFFF7F8FF), Color(0xFFDDE2EE), Color(0xFFB3BCCE),
        accentFor(accent, false), Color(0xFF86EFAC), Color(0xFFFDBA74), Color(0x14FFFFFF), Color(0x3338BDF8),
        glass = true,
    ) else Pal(
        true, Color(0xFFF8F9FC), Color(0x99FFFFFF), Color(0xB3FFFFFF), Color(0x80FFFFFF),
        Color(0xFF0B0F19), Color(0xFF283141), Color(0xFF4D5769),
        accentFor(accent, true), Color(0xFF15803D), Color(0xFFC2410C), Color(0x66FFFFFF), Color(0x6638BDF8),
        glass = true,
    )

    /** Akcent: na ciemnym szkle jasny i pastelowy, na jasnym – głęboki. */
    fun accentFor(c: Color, light: Boolean): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), hsv)
        if (hsv[1] < 0.12f) return if (light) Color(0xFF1F2937) else Color(0xFFF1F5F9)
        hsv[1] = if (light) max(0.75f, hsv[1]) else min(max(hsv[1], 0.45f), 0.62f)
        hsv[2] = if (light) 0.62f else 1f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    // ---------------- renderowanie tła ----------------

    class Rendered(val sharp: ImageBitmap, val blurred: ImageBitmap, val accent: Color?)

    fun render(ctx: Context, s: Spec, w: Int, h: Int): Rendered {
        val hw = max(1, w / 2); val hh = max(1, h / 2)
        val bmp: Bitmap = when (s.kind) {
            "image" -> loadCropped(imageFile(ctx), w, h) ?: mesh(PRESETS[0].colors, PRESETS[0].base, s.seed, hw, hh)
            "solid" -> solid(s.solid, hw, hh)
            "mix" -> mesh(s.mix, mixBase(s.mix), s.seed, hw, hh)
            else -> (PRESETS.firstOrNull { it.id == s.preset } ?: PRESETS[0]).let { mesh(it.colors, it.base, it.id.hashCode(), hw, hh) }
        }
        if (s.dim > 0.005f) Canvas(bmp).drawColor(android.graphics.Color.argb((s.dim * 255).roundToInt(), 0, 0, 0))

        // rozmyta kopia: mała (1/6), nasycona jak „vibrancy” w iOS, 3× rozmycie pudełkowe ≈ gaussowskie
        val sw = max(4, w / 6); val sh = max(4, h / 6)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(
            bmp, null, android.graphics.Rect(0, 0, sw, sh),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.7f) }) },
        )
        val accent = dominant(small)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) { boxBlur(px, sw, sh, 5) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        return Rendered(bmp.asImageBitmap(), small.asImageBitmap(), accent)
    }

    private fun mixBase(colors: List<Long>): Long {
        var r = 0; var g = 0; var b = 0
        colors.forEach { c -> r += ((c shr 16) and 255).toInt(); g += ((c shr 8) and 255).toInt(); b += (c and 255).toInt() }
        val n = max(1, colors.size)
        return (0xFF000000 or ((r / n * 0.22).toLong() shl 16) or ((g / n * 0.22).toLong() shl 8) or (b / n * 0.22).toLong())
    }

    private fun argb(c: Long, a: Float) = android.graphics.Color.argb((a * 255).roundToInt(), ((c shr 16) and 255).toInt(), ((c shr 8) and 255).toInt(), (c and 255).toInt())

    /** Kolorowe plamy światła na ciemnej bazie (gradienty radialne). */
    fun mesh(colors: List<Long>, base: Long, seed: Int, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(argb(base, 1f), argb(colors[0], 0.25f).blendOver(argb(base, 1f)), argb(base, 1f)), null, Shader.TileMode.CLAMP)
        c.drawPaint(p)
        val rnd = Random(seed)
        val n = colors.size
        val total = n * 2
        for (k in 0 until total) {
            val col = colors[(k * 3 + seed.mod(n)) % n]
            val cx = w * (rnd.nextFloat() * 1.2f - 0.1f)
            val cy = h * ((k + rnd.nextFloat()) / total) * 1.1f - h * 0.05f
            val r = max(w, h) * (0.32f + rnd.nextFloat() * 0.28f)
            p.shader = RadialGradient(cx, cy, r, intArrayOf(argb(col, 0.85f), argb(col, 0.38f), argb(col, 0f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
        return b
    }

    private fun Int.blendOver(under: Int): Int {
        val a = android.graphics.Color.alpha(this) / 255f
        fun ch(f: (Int) -> Int) = (f(this) * a + f(under) * (1 - a)).roundToInt()
        return android.graphics.Color.rgb(ch(android.graphics.Color::red), ch(android.graphics.Color::green), ch(android.graphics.Color::blue))
    }

    /** Jednolity kolor z delikatnym światłem z lewego górnego rogu. */
    fun solid(col: Long, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val base = argb(col, 1f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w * 0.6f, h.toFloat(), intArrayOf(argb(0xFFFFFFFF, 0.28f).blendOver(base), base, argb(0xFF000000, 0.45f).blendOver(base)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPaint(p)
        p.shader = RadialGradient(w * 0.15f, h * 0.1f, max(w, h) * 0.6f, intArrayOf(argb(0xFFFFFFFF, 0.22f), argb(0xFFFFFFFF, 0f)), null, Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.15f, h * 0.1f, max(w, h) * 0.6f, p)
        return b
    }

    /** Obraz przycięty do ekranu (jak tapeta: wypełnij i wyśrodkuj). */
    private fun loadCropped(f: File, w: Int, h: Int): Bitmap? {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val tw = min(w, 1440); val th = (h * tw.toFloat() / w).roundToInt()
        val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val scale = max(tw.toFloat() / src.width, th.toFloat() / src.height)
        val m = Matrix().apply { setScale(scale, scale); postTranslate((tw - src.width * scale) / 2f, (th - src.height * scale) / 2f) }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        src.recycle()
        return out
    }

    /** Dominujący, nasycony odcień (histogram barw ważony nasyceniem i jasnością). */
    private fun dominant(b: Bitmap): Color? {
        val bins = FloatArray(36)
        val hues = FloatArray(36)
        val hsv = FloatArray(3)
        val step = max(1, (b.width * b.height) / 4000)
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var i = 0
        while (i < px.size) {
            android.graphics.Color.colorToHSV(px[i], hsv)
            if (hsv[1] > 0.3f && hsv[2] > 0.25f) {
                val k = (hsv[0] / 10f).toInt().coerceIn(0, 35)
                val wgt = hsv[1] * hsv[2]
                bins[k] += wgt; hues[k] += hsv[0] * wgt
            }
            i += step
        }
        val best = bins.indices.maxByOrNull { bins[it] } ?: return null
        if (bins[best] < 1f) return null
        val hue = hues[best] / bins[best]
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 1f)))
    }

    /** Rozmycie pudełkowe w miejscu (poziomo + pionowo), kanały RGB. */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val p = px[row + i.coerceIn(0, w - 1)]; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val po = px[row + (x - r).coerceIn(0, w - 1)]
                val pi = px[row + (x + r + 1).coerceIn(0, w - 1)]
                sr += ((pi shr 16) and 255) - ((po shr 16) and 255)
                sg += ((pi shr 8) and 255) - ((po shr 8) and 255)
                sb += (pi and 255) - (po and 255)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val p = tmp[i.coerceIn(0, h - 1) * w + x]; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
            for (y in 0 until h) {
                px[y * w + x] = (0xFF shl 24) or ((sr / d) shl 16) or ((sg / d) shl 8) or (sb / d)
                val po = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                val pi = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sr += ((pi shr 16) and 255) - ((po shr 16) and 255)
                sg += ((pi shr 8) and 255) - ((po shr 8) and 255)
                sb += (pi and 255) - (po and 255)
            }
        }
    }

    // ---------------- zdjęcie i AI ----------------

    /** Kopiuje zdjęcie z galerii (z obrotem z EXIF) jako tło. */
    suspend fun importPhoto(ctx: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        photoBusy.value = true
        try {
            val cr = ctx.contentResolver
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0) return@withContext false
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= 2400) sample *= 2
            var b = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@withContext false
            val rot = try {
                cr.openInputStream(uri)?.use {
                    when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) { 0f }
            if (rot != 0f) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
            imageFile(ctx).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            update(ctx) { it.copy(kind = "image", imageSrc = "photo", rev = it.rev + 1) }
            true
        } catch (_: Exception) {
            false
        } finally {
            photoBusy.value = false
        }
    }

    /** Generuje tło przez Pollinations.ai (darmowe, bez konta). Zwraca true, gdy się udało. */
    suspend fun generateAi(ctx: Context, prompt: String, size: IntSize): Boolean = withContext(Dispatchers.IO) {
        if (prompt.isBlank()) return@withContext false
        aiBusy.value = true
        aiError.value = null
        try {
            val w = (if (size.width > 0) size.width else 1080).coerceIn(540, 1080)
            val h = (if (size.height > 0) (size.height * w.toFloat() / size.width).roundToInt() else 2340).coerceIn(960, 2400)
            val full = "$prompt, vertical phone wallpaper, beautiful, soft depth of field, vivid colors, high quality, no text, no watermark"
            val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(full, "UTF-8").replace("+", "%20") +
                "?width=$w&height=$h&nologo=true&model=flux&seed=${Random.nextInt(1, 999_999)}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 120_000
            c.setRequestProperty("User-Agent", "CarOS-Connect")
            val bytes = try {
                when (c.responseCode) {
                    in 200..299 -> c.inputStream.use { it.readBytes() }
                    429 -> { aiError.value = "Za dużo prób naraz – poczekaj kilkanaście sekund i spróbuj ponownie"; return@withContext false }
                    else -> { aiError.value = "Generator AI nie odpowiada (HTTP ${c.responseCode}) – spróbuj za chwilę"; return@withContext false }
                }
            } finally { c.disconnect() }
            val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (b == null) { aiError.value = "Generator zwrócił coś, co nie jest obrazem – spróbuj ponownie"; return@withContext false }
            imageFile(ctx).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            update(ctx) { it.copy(kind = "image", imageSrc = "ai", aiPrompt = prompt, rev = it.rev + 1) }
            true
        } catch (e: java.net.SocketTimeoutException) {
            aiError.value = "Generowanie trwało za długo – spróbuj ponownie"
            false
        } catch (_: Exception) {
            aiError.value = "Brak internetu albo generator AI jest niedostępny"
            false
        } finally {
            aiBusy.value = false
        }
    }
}

/** Wyrenderowane tło – czytane w fazie rysowania przez tło i szklane powierzchnie. */
object GlassState {
    var sharp by mutableStateOf<ImageBitmap?>(null)
    var blurred by mutableStateOf<ImageBitmap?>(null)
    var size by mutableStateOf(IntSize.Zero)
    var autoAccent by mutableStateOf<Color?>(null)
}
