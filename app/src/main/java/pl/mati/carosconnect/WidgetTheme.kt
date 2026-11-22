package pl.mati.carosconnect

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wygląd widżetów zgodny z motywem aplikacji:
 *  – Ciemny / Jasny / Jak telefon – pełne karty w kolorach aplikacji,
 *  – Glass – „płynne szkło”: rozmyte, nasycone tło motywu Glass (to samo co w aplikacji), mleczna warstwa,
 *    połysk u góry i jasna krawędź jak w iOS; akcent z motywu,
 *  – „Przezroczyste szkło” (opcja) – szkło na tapecie telefonu w każdym motywie.
 * Tło rysujemy do bitmapy (RemoteViews nie umie rozmycia ani gradientów krawędzi).
 */
object WidgetTheme {

    class Look(
        val light: Boolean, val glass: Boolean, val clear: Boolean,
        val text: Int, val text2: Int, val muted: Int,
        val accent: Int, val onAccent: Int,
        val green: Int, val red: Int, val orange: Int,
        /** kafelki w środku widżetu: kolor + przezroczystość (0..255) */
        val cell: Int, val cellAlpha: Int,
        val card: Int, val border: Int,
    )

    /** "theme" – jak motyw, "clear" – przezroczyste szkło na tapecie. */
    val style = MutableStateFlow("theme")
    /** Przezroczystość tła przy „Przezroczyste szkło” (0.15..0.85). */
    val clearAlpha = MutableStateFlow(0.38f)
    private var inited = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val p = sp(ctx)
        style.value = p.getString("style", "theme") ?: "theme"
        clearAlpha.value = p.getFloat("clear_alpha", 0.38f)
    }

    fun setStyle(ctx: Context, v: String) {
        style.value = v
        sp(ctx).edit().putString("style", v).apply()
        Widgets.updateAll(ctx)
    }

    fun setClearAlpha(ctx: Context, v: Float) {
        clearAlpha.value = v.coerceIn(0.1f, 0.85f)
        sp(ctx).edit().putFloat("clear_alpha", clearAlpha.value).apply()
        Widgets.updateAll(ctx)
    }

    private fun sysDark(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // akcent z tła Glass (gdy „automatycznie”) – liczony raz na wygląd tła
    private var autoAccentKey = ""
    private var autoAccent: Color? = null

    fun look(ctx: Context): Look {
        init(ctx)
        Store.init(ctx)
        val mode = Store.theme.value
        val clear = style.value == "clear"
        return if (mode == "glass") {
            GlassTheme.init(ctx)
            val s = GlassTheme.spec.value
            val light = GlassTheme.isLight(s, sysDark(ctx))
            val acc = s.accent?.let { Color(it) } ?: run {
                val key = "${s.kind}|${s.preset}|${s.solid}|${s.mix}|${s.seed}|${s.rev}"
                if (key != autoAccentKey) {
                    autoAccent = try { GlassTheme.render(ctx, s, 240, 240).accent } catch (_: Exception) { null }
                    autoAccentKey = key
                }
                autoAccent ?: Color(0xFF38BDF8)
            }
            val pal = GlassTheme.pal(light, acc)
            build(pal, light, glass = true, clear = clear)
        } else {
            val light = mode == "light" || (mode == "auto" && !sysDark(ctx))
            build(if (light) C.LIGHT else C.DARK, light, glass = false, clear = clear)
        }
    }

    private fun build(p: Pal, light: Boolean, glass: Boolean, clear: Boolean): Look {
        val accent = p.accent
        val onAccent = if (accent.luminance() > 0.45f) 0xFF0A0C0F.toInt() else 0xFFFFFFFF.toInt()
        val see = glass || clear
        return Look(
            light, glass, clear,
            text = p.text.toArgb(), text2 = p.text2.toArgb(), muted = p.muted.toArgb(),
            accent = accent.toArgb(), onAccent = onAccent,
            green = p.green.toArgb(), red = if (light) 0xFFD32F2F.toInt() else 0xFFFF6B6B.toInt(), orange = p.orange.toArgb(),
            cell = if (see) (if (light) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()) else p.raised.toArgb(),
            cellAlpha = if (see) (if (light) 120 else 30) else 255,
            card = p.card.toArgb(), border = p.border.toArgb(),
        )
    }

    // ---------- tło ----------

    private val bgCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }

    /** Tło widżetu [wDp]×[hDp] (bitmapa w obniżonej rozdzielczości – tło i tak jest gładkie). */
    fun background(ctx: Context, lk: Look, wDp: Int, hDp: Int, radiusDp: Float = 26f): Bitmap {
        val dens = min(ctx.resources.displayMetrics.density, 2.25f)
        val w = max(40, (wDp * dens).roundToInt())
        val h = max(40, (hDp * dens).roundToInt())
        val s = GlassTheme.spec.value
        val key = "$w×$h|${lk.light}|${lk.glass}|${lk.clear}|${clearAlpha.value}|${lk.card}|${if (lk.glass) "${s.kind}${s.preset}${s.solid}${s.mix}${s.seed}${s.rev}${s.dim}" else ""}"
        synchronized(bgCache) { bgCache[key]?.let { return it } }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = radiusDp * dens
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        when {
            lk.clear -> {
                // przezroczyste szkło: mleczna warstwa na tapecie
                val a = (clearAlpha.value * 255).roundToInt()
                p.color = if (lk.light) android.graphics.Color.argb(a, 255, 255, 255) else android.graphics.Color.argb(a, 8, 10, 16)
                c.drawRoundRect(rect, r, r, p)
            }
            lk.glass -> {
                // rozmyte tło motywu Glass przycięte do kształtu widżetu
                val bl = try { GlassTheme.render(ctx, s, max(64, w / 2), max(64, h / 2)).blurred.asAndroidBitmap() } catch (_: Exception) { null }
                if (bl != null) {
                    val sh = BitmapShader(bl, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val m = android.graphics.Matrix()
                    m.setScale(w / bl.width.toFloat(), h / bl.height.toFloat())
                    sh.setLocalMatrix(m)
                    p.shader = sh
                    c.drawRoundRect(rect, r, r, p)
                    p.shader = null
                }
                // mleczna warstwa szkła
                p.color = if (lk.light) 0x66FFFFFF else 0x38000000
                c.drawRoundRect(rect, r, r, p)
                p.color = if (lk.light) 0x1FFFFFFF else 0x14FFFFFF
                c.drawRoundRect(rect, r, r, p)
            }
            else -> {
                p.color = lk.card
                c.drawRoundRect(rect, r, r, p)
            }
        }

        if (lk.glass || lk.clear) {
            // połysk: jaśniej u góry, delikatna plama światła w lewym górnym rogu
            p.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, p)
            p.shader = RadialGradient(w * 0.18f, h * 0.05f, max(w, h) * 0.55f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, p)
            p.shader = null
            // jasna krawędź „płynnego szkła”: mocniej u góry, słabiej u dołu
            val sw = 1.4f * dens
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), if (lk.light) 0xE6FFFFFF.toInt() else 0x99FFFFFF.toInt(), if (lk.light) 0x59FFFFFF else 0x1FFFFFFF, Shader.TileMode.CLAMP)
            val ins = RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
            c.drawRoundRect(ins, r - sw / 2, r - sw / 2, p)
            // cienka wewnętrzna linia (grubość szkła)
            p.strokeWidth = 0.8f * dens
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            val in2 = RectF(sw + dens, sw + dens, w - sw - dens, h - sw - dens)
            c.drawRoundRect(in2, r - sw - dens, r - sw - dens, p)
            p.shader = null
            p.style = Paint.Style.FILL
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dens
            p.color = lk.border
            val ins = RectF(dens / 2, dens / 2, w - dens / 2, h - dens / 2)
            c.drawRoundRect(ins, r, r, p)
            p.style = Paint.Style.FILL
        }
        synchronized(bgCache) { bgCache[key] = b }
        return b
    }

    /** Pierścień postępu (np. zasięg) – [frac] 0..1, kolor [col]. */
    fun ring(ctx: Context, lk: Look, frac: Float, col: Int, sizeDp: Int = 96): Bitmap {
        val dens = ctx.resources.displayMetrics.density
        val sz = (sizeDp * dens).roundToInt()
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val sw = 9f * dens
        val r = RectF(sw / 2 + dens, sw / 2 + dens, sz - sw / 2 - dens, sz - sw / 2 - dens)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        p.color = if (lk.glass || lk.clear) (if (lk.light) 0x33000000 else 0x33FFFFFF) else (lk.text and 0x00FFFFFF) or 0x26000000
        c.drawArc(r, 135f, 270f, false, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.005f) {
            p.color = col
            c.drawArc(r, 135f, 270f * f, false, p)
        }
        return b
    }

    // ---------- pomocnicze do RemoteViews ----------

    /** Rozmiar widżetu w dp (szerokość, wysokość) – z opcji launchera, inaczej domyślny. */
    fun sizeDp(mgr: AppWidgetManager, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o: Bundle = try { mgr.getAppWidgetOptions(id) } catch (_: Exception) { Bundle() }
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).takeIf { it > 0 } ?: defW
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).takeIf { it > 0 } ?: defH
        return w to h
    }

    /** Tło + kolory podstawowe. */
    fun applyBg(ctx: Context, v: RemoteViews, lk: Look, wDp: Int, hDp: Int) {
        v.setImageViewBitmap(R.id.wbg, background(ctx, lk, wDp, hDp))
    }

    /** Przycisk-pigułka: ImageView [bg] (biały kształt) + tekst [label]. */
    fun pill(v: RemoteViews, bg: Int, label: Int, lk: Look, primary: Boolean) {
        if (primary) {
            v.setInt(bg, "setColorFilter", lk.accent)
            v.setInt(bg, "setImageAlpha", 255)
            v.setTextColor(label, lk.onAccent)
        } else {
            v.setInt(bg, "setColorFilter", if (lk.glass || lk.clear) 0xFFFFFFFF.toInt() else lk.cell)
            v.setInt(bg, "setImageAlpha", if (lk.glass || lk.clear) (if (lk.light) 150 else 40) else 255)
            v.setTextColor(label, lk.text)
        }
    }

    /** Kafelek w środku widżetu (ImageView z białym kształtem). [hl] – wyróżniony akcentem. */
    fun cell(v: RemoteViews, bg: Int, lk: Look, hl: Boolean = false) {
        if (hl) {
            v.setInt(bg, "setColorFilter", lk.accent)
            v.setInt(bg, "setImageAlpha", if (lk.glass || lk.clear) 70 else 60)
        } else {
            v.setInt(bg, "setColorFilter", lk.cell)
            v.setInt(bg, "setImageAlpha", lk.cellAlpha)
        }
    }

    fun openApp(ctx: Context, req: Int, tab: Int = -1, action: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (tab >= 0) i.putExtra("tab", tab)
        if (action != null) i.putExtra("widget_action", action)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Wszystkie widżety aplikacji – odświeżenie po zmianie danych albo motywu. */
object Widgets {
    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        CarWidget.updateAll(app)
        FuelWidget.updateAll(app)
        RangeWidget.updateAll(app)
        StatusWidget.updateAll(app)
        DriveWidget.updateAll(app)
        WorkWidget.updateAll(app)
        ShortcutsWidget.updateAll(app)
    }

    /** Lista widżetów do „Dodaj na ekran główny”: (klasa, nazwa, opis). */
    val all = listOf(
        Triple(CarWidget::class.java, "Auto", "Gdzie stoi auto, odległość, parkometr, „Prowadź do auta”"),
        Triple(RangeWidget::class.java, "Zasięg", "Zasięg na paliwie (pierścień), spalanie, koszt km"),
        Triple(StatusWidget::class.java, "Stan auta", "Przebieg, akumulator, najbliższy serwis, przymrozek"),
        Triple(DriveWidget::class.java, "Jazda", "Kilometry dziś / w tygodniu / w miesiącu i ostatnia trasa"),
        Triple(WorkWidget::class.java, "Praca", "Najbliższa zmiana i godzina wyjazdu"),
        Triple(ShortcutsWidget::class.java, "Skróty", "Prowadź do auta, zapisz miejsce, trasy, auto"),
        Triple(FuelWidget::class.java, "Ceny paliw", "Maksymalne ceny dziś i jutro"),
    )

    fun count(ctx: Context, cls: Class<*>): Int = try {
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls)).size
    } catch (_: Exception) { 0 }

    /** Prośba do launchera o dodanie widżetu (Android 8+, jeśli launcher pozwala). */
    fun pin(ctx: Context, cls: Class<*>): Boolean = try {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null) else false
    } catch (_: Exception) { false }
}
