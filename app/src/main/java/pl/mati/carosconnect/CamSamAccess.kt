package pl.mati.carosconnect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Odczyt pływającego okienka CamSam (to nie jest powiadomienie – dlatego sam odczyt powiadomień nie wystarczał).
 * Usługa ułatwień dostępu widzi okienka innych aplikacji:
 *  – czy CamSam działa (jest jego okienko albo powiadomienie) → w radiu znaczek „CamSam działa”,
 *  – ikona ostrzeżenia i pasek odległości: z wycinka ekranu (Android 11+) – ikona idzie do radia jako obrazek,
 *    pasek jako kolory segmentów, do tego nazwa ulicy i napisy z okienka.
 * Czyta wyłącznie okna CamSam / Yanosika.
 */
class CamSamAccess : AccessibilityService() {

    data class Hud(
        val app: String,
        val texts: List<String>,
        /** Diagnostyka: identyfikatory i opisy widoków. */
        val nodes: List<String>,
        val bounds: Rect,
        /** Segmenty paska: R – czerwony, Y – żółty, G – zielony, - – pusty. */
        val bar: String,
        val icon: Bitmap?,
        val iconBlank: Boolean,
        val at: Long,
    )

    companion object {
        /** Ostatnio odczytane okienko (podgląd w Ustawieniach). */
        val hud = MutableStateFlow<Hud?>(null)
        val running = MutableStateFlow(false)

        fun enabled(ctx: Context): Boolean {
            val s = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.split(':').any { it.startsWith(ctx.packageName + "/") && it.contains("CamSamAccess") }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val shotExec = Executors.newSingleThreadExecutor()
    private var lastShot = 0L
    private var lastSeenHud = 0L
    private var busy = false

    private val poll = object : Runnable {
        override fun run() {
            try { scan() } catch (_: Exception) {}
            // częściej, gdy CamSam ma okienko na ekranie
            handler.postDelayed(this, if (System.currentTimeMillis() - lastSeenHud < 30_000) 1000 else 4000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Store.init(this)
        CamSamRelay.init(this)
        running.value = true
        handler.post(poll)
    }

    override fun onDestroy() {
        running.value = false
        handler.removeCallbacksAndMessages(null)
        CamSamRelay.hudGone()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (CamSamRelay.appName(pkg) == null) return
        // zmiana w okienku CamSam – odczytaj od razu (nie częściej niż co 0,8 s)
        if (System.currentTimeMillis() - lastShot > 800) handler.post { try { scan() } catch (_: Exception) {} }
    }

    private fun camsamWindows(): List<Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>> {
        val out = ArrayList<Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>>()
        for (w in windows ?: emptyList()) {
            val root = try { w.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (CamSamRelay.appName(pkg) != null) out += w to root
        }
        return out
    }

    private fun scan() {
        // wycinek ekranu najwyżej raz na sekundę (limit systemu)
        if (busy || System.currentTimeMillis() - lastShot < 950) return
        val wins = camsamWindows()
        if (wins.isEmpty()) {
            if (hud.value != null && System.currentTimeMillis() - lastSeenHud > 6000) {
                hud.value = null
                CamSamRelay.hudGone()
            }
            return
        }
        lastSeenHud = System.currentTimeMillis()
        // pływające okienko: najmniejsze okno CamSam (pełny ekran aplikacji jest duży)
        val (win, root) = wins.minBy { (w, _) -> Rect().also { w.getBoundsInScreen(it) }.let { it.width() * it.height() } }
        val bounds = Rect().also { win.getBoundsInScreen(it) }
        val app = CamSamRelay.appName(root.packageName.toString()) ?: "CamSam"
        val texts = ArrayList<String>()
        val nodes = ArrayList<String>()
        val images = ArrayList<Rect>()
        walk(root, texts, nodes, images, 0)
        lastShot = System.currentTimeMillis()
        // CamSam otwarty na cały ekran (bez pływającego okienka) – tylko „działa”
        val dm = resources.displayMetrics
        if (bounds.width().toLong() * bounds.height() > dm.widthPixels.toLong() * dm.heightPixels / 2) {
            hud.value = Hud(app, texts, nodes, bounds, "", null, true, System.currentTimeMillis())
            CamSamRelay.appVisible()
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && bounds.width() > 20 && bounds.height() > 20) {
            busy = true
            shoot(app, texts, nodes, bounds, images)
        } else {
            analyze(app, texts, nodes, bounds, images, null)
        }
    }

    @androidx.annotation.RequiresApi(30)
    private fun shoot(app: String, texts: List<String>, nodes: List<String>, bounds: Rect, images: List<Rect>) {
        run {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, shotExec, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                            val shot = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            r.hardwareBuffer.close()
                            analyze(app, texts, nodes, bounds, images, shot)
                        } catch (_: Exception) {
                            analyze(app, texts, nodes, bounds, images, null)
                        } finally {
                            busy = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        busy = false
                        // za często – pomiń ten odczyt (bez wycinka wyszłoby fałszywe „koniec ostrzeżenia”)
                        if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return
                        analyze(app, texts, nodes, bounds, images, null)
                    }
                })
            } catch (_: Exception) {
                busy = false
                analyze(app, texts, nodes, bounds, images, null)
            }
        }
    }

    private fun walk(n: AccessibilityNodeInfo?, texts: MutableList<String>, nodes: MutableList<String>, images: MutableList<Rect>, depth: Int) {
        if (n == null || depth > 25 || nodes.size > 120) return
        if (n.isVisibleToUser) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += "[$it]" }
            val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (cls.contains("Image") && r.width() > 8 && r.height() > 8) images += r
            if (id.isNotEmpty() || cls.contains("Image") || n.text != null) nodes += "$cls${if (id.isNotEmpty()) "#$id" else ""} ${r.width()}×${r.height()}" + (n.text?.let { " „$it”" } ?: "")
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), texts, nodes, images, depth + 1)
    }

    /** Wycinek ekranu z okienkiem → kolory paska i ikona ostrzeżenia. */
    private fun analyze(app: String, texts: List<String>, nodes: List<String>, bounds: Rect, images: List<Rect>, shot: Bitmap?) {
        var bar = ""
        var icon: Bitmap? = null
        var blank = true
        if (shot != null) {
            val b = Rect(bounds).apply { intersect(0, 0, shot.width, shot.height) }
            if (b.width() > 20 && b.height() > 20) {
                bar = barColors(shot, b)
                // ikona ostrzeżenia: obrazek najbliżej 1/3 szerokości okienka (lewy przycisk „+” pomijamy)
                val cand = images.filter { it.centerX() > b.left + b.width() * 0.18 && it.centerX() < b.left + b.width() * 0.6 && it.centerY() < b.top + b.height() * 0.7 }
                    .maxByOrNull { it.width() * it.height() }
                    ?: Rect(b.left + (b.width() * 0.21).toInt(), b.top, b.left + (b.width() * 0.46).toInt(), b.top + (b.height() * 0.64).toInt())
                val ir = Rect(cand).apply { intersect(b) }
                if (ir.width() > 8 && ir.height() > 8) {
                    val crop = Bitmap.createBitmap(shot, ir.left, ir.top, ir.width(), ir.height())
                    blank = isBlank(crop)
                    icon = Bitmap.createScaledBitmap(crop, 48, (48f * ir.height() / ir.width()).toInt().coerceIn(24, 72), true)
                }
            }
            shot.recycle()
        }
        val h = Hud(app, texts, nodes, bounds, bar, icon, blank, System.currentTimeMillis())
        hud.value = h
        CamSamRelay.onHud(h)
    }

    /** Dolny pasek okienka: 5 segmentów (czerwony / żółty / zielony / pusty). */
    private fun barColors(bmp: Bitmap, b: Rect): String {
        // szukamy od dołu wiersza z kolorowym paskiem
        for (dy in 2 until (b.height() * 0.2).toInt().coerceAtLeast(6)) {
            val y = b.bottom - dy
            if (y <= b.top) break
            val seg = StringBuilder()
            var colored = 0
            for (k in 0 until 5) {
                val x = b.left + ((k + 0.5) * b.width() / 5).toInt()
                val c = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                val ch = when {
                    r > 170 && g < 110 && bl < 110 -> 'R'
                    r > 180 && g > 140 && bl < 90 -> 'Y'
                    g > 140 && r < 140 && bl < 140 -> 'G'
                    else -> '-'
                }
                if (ch != '-') colored++
                seg.append(ch)
            }
            if (colored >= 3) return seg.toString()
        }
        return ""
    }

    /** Pusty obszar ikony (samo tło okienka) – brak ostrzeżenia. Tło = średni kolor brzegu wycinka. */
    private fun isBlank(b: Bitmap): Boolean {
        if (b.width < 4 || b.height < 4) return true
        var br = 0L; var bg = 0L; var bb = 0L; var n = 0
        fun edge(x: Int, y: Int) { val c = b.getPixel(x, y); br += Color.red(c); bg += Color.green(c); bb += Color.blue(c); n++ }
        for (x in 0 until b.width step maxOf(1, b.width / 12)) { edge(x, 0); edge(x, b.height - 1) }
        for (y in 0 until b.height step maxOf(1, b.height / 12)) { edge(0, y); edge(b.width - 1, y) }
        val r0 = (br / n).toInt(); val g0 = (bg / n).toInt(); val b0 = (bb / n).toInt()
        var diff = 0
        var total = 0
        val step = maxOf(1, b.width / 20)
        var x = b.width / 8
        while (x < b.width * 7 / 8) {
            var y = b.height / 8
            while (y < b.height * 7 / 8) {
                val c = b.getPixel(x, y)
                val d = maxOf(kotlin.math.abs(Color.red(c) - r0), kotlin.math.abs(Color.green(c) - g0), kotlin.math.abs(Color.blue(c) - b0))
                if (d > 60) diff++
                total++
                y += step
            }
            x += step
        }
        return total == 0 || diff * 100 / total < 6
    }
}

/** Ikona do wysłania: mała WEBP w base64 – po zaszyfrowaniu wiadomość musi zmieścić się w 4 KB przekaźnika. */
fun iconB64(b: Bitmap): String {
    @Suppress("DEPRECATION")
    val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    for (q in intArrayOf(70, 50, 30)) {
        val out = ByteArrayOutputStream()
        b.compress(fmt, q, out)
        val s = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        if (s.length <= 1800) return s
    }
    return ""
}
