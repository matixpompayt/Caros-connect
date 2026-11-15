package pl.mati.carosconnect

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Calendar

/**
 * Ostrzeżenia z CamSam / CamSam PLUS / Yanosika na telefonie → baner w CAR OS.
 * Czytamy powiadomienia tych aplikacji (także własny układ – tam jest odległość), filtrujemy
 * prawdziwe ostrzeżenia i wysyłamy je szyfrowanym kanałem telefon → auto (ntfy).
 * Przekaźnik ma limit ok. 250 wiadomości dziennie, więc wysyłamy oszczędnie:
 * nowe ostrzeżenie od razu, zmiany najwyżej co 12 s, podtrzymanie co 25 s, koniec od razu.
 * Odległość między wiadomościami odlicza samo radio z własnej prędkości.
 */
object CamSamRelay {

    data class Seen(val app: String, val title: String, val text: String, val alert: Boolean, val time: Long)

    val enabled = MutableStateFlow(true)
    /** Ostatnie powiadomienie z CamSam/Yanosika (diagnostyka w Ustawieniach). */
    val lastSeen = MutableStateFlow<Seen?>(null)
    /** Kiedy ostatnio coś poszło do auta i czy się udało. */
    val lastSent = MutableStateFlow(0L)
    val lastError = MutableStateFlow<String?>(null)

    private var sp: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // kolejka (kilka wiadomości naraz: stan + ostrzeżenie), najstarsze odpadają
    private val out = Channel<JSONObject>(8, BufferOverflow.DROP_OLDEST)
    private var app: Context? = null
    private var worker: Job? = null
    private var keepalive: Job? = null

    // bieżące ostrzeżenie
    private var activePkg: String? = null
    private var activeTitle = ""
    private var activeText = ""
    private var activeApp = ""
    private var activeSentAt = 0L

    private const val UPDATE_MS = 12_000L
    private const val KEEP_MS = 25_000L
    private const val DAILY_SOFT = 150

    fun appName(pkg: String): String? = when {
        pkg.startsWith("com.camsam") -> if (pkg.contains("plus")) "CamSam PLUS" else "CamSam"
        pkg.startsWith("pl.neptis.yanosik") -> "Yanosik"
        else -> null
    }

    private val ALERT = Regex(
        "(fotoradar|radar|kamer|kontrol|policj|patrol|odcinkow|pomiar|suszark|wypadek|korek|zator|roboty drogow|przeszkod|zagroż|" +
            "czerwon|niebezpiecz|speed ?cam|speedcam|camera|blitzer|messung|kontrolle|polizei|ampel|police|accident|hazard|" +
            "section control|red light|radarfalle|stau|unfall|odstęp|odstep|koniec korku|gołoled|gololed|widoczno|kombinowan|światło|swiatlo|glatteis|baustelle|sicht|abstand)",
        RegexOption.IGNORE_CASE,
    )
    private val IDLE = Regex(
        "(działa w tle|aktywn|uruchomion|running|is active|ist aktiv|läuft|tap to|dotknij, aby|szukam gps|searching|kein gps|no gps|brak gps)",
        RegexOption.IGNORE_CASE,
    )

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("camsam", Context.MODE_PRIVATE)
        sp = p
        app = ctx.applicationContext
        enabled.value = p.getBoolean("on", true)
        lastSent.value = p.getLong("sent_at", 0L)
        startWatch()
    }

    /**
     * Co 20 s: czy CamSam działa (jego stałe powiadomienie albo okienko). Po wejściu do auta
     * stan idzie od razu – powiadomienie CamSam zwykle wisi już wcześniej i nie przychodzi ponownie.
     */
    private fun startWatch() {
        scope.launch {
            var wasInCar = false
            while (isActive) {
                try {
                    val ctx = app
                    if (ctx != null && enabled.value && Store.pairing.value != null) {
                        val car = inCar(ctx)
                        if (car && !wasInCar) synchronized(this@CamSamRelay) { runningOn = false; runningSentAt = 0L }
                        wasInCar = car
                        if (car) {
                            val notif = CamSamListener.camsamPosted()
                            val hudNow = CamSamAccess.hud.value?.let { System.currentTimeMillis() - it.at < 30_000 } == true
                            when {
                                notif == true || hudNow -> markRunning()
                                notif == false -> setRunning(false)
                                // null: brak dostępu do powiadomień – decyduje samo okienko
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                delay(20_000)
            }
        }
    }

    fun setEnabled(v: Boolean) {
        enabled.value = v
        sp?.edit()?.putBoolean("on", v)?.apply()
        if (!v && activePkg != null) end()
    }

    fun hasAccess(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun installed(ctx: Context): String? = listOf("com.camsam.plus", "com.camsam", "pl.neptis.yanosik.mobi.android").firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }

    /** Wysyłać tylko w aucie (telefon połączony z radiem), chyba że auta jeszcze nie wybrano. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun inCar(ctx: Context): Boolean {
        if (Store.driving.value) return true
        val mac = Store.carMac.value ?: return true
        return try {
            val d = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.firstOrNull { it.address.equals(mac, true) } ?: return false
            android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean ?: true
        } catch (_: Exception) {
            true
        }
    }

    // ---------- powiadomienia ----------

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        val app = appName(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        val ex = n.extras
        val parts = LinkedHashSet<String>()
        fun add(cs: CharSequence?) { cs?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { parts += it } }
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        if (ex != null) {
            add(ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_SUB_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_INFO_TEXT))
            add(ex.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { add(it) }
        }
        add(n.tickerText)
        // własny układ powiadomienia (tam CamSam pokazuje odległość i limit)
        @Suppress("DEPRECATION")
        for (rv in listOf(n.contentView, n.bigContentView, n.headsUpContentView)) {
            if (rv == null) continue
            try { texts(rv.apply(ctx, FrameLayout(ctx)), parts) } catch (_: Throwable) {}
        }
        parts.remove(title)
        val text = parts.joinToString(" · ").take(300)
        if (title.isBlank() && text.isBlank()) return
        val all = "$title $text"
        val isAlert = ALERT.containsMatchIn(all) && !IDLE.containsMatchIn(all)
        lastSeen.value = Seen(app, title, text, isAlert, System.currentTimeMillis())
        if (!enabled.value || Store.pairing.value == null) return
        // samo powiadomienie CamSam = aplikacja działa
        if (inCar(ctx)) markRunning()
        if (isAlert) {
            if (!inCar(ctx)) return
            alert(sbn.packageName, app, title.ifBlank { app }, text)
        } else if (activePkg == sbn.packageName) {
            end() // ta sama aplikacja wróciła do „spokojnie”
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (activePkg == sbn.packageName) end()
        // CamSam wyłączony (zniknęło jego stałe powiadomienie, a okienka nie ma)
        if (sbn.isOngoing && CamSamAccess.hud.value == null) setRunning(false)
    }

    // ---------- „CamSam działa” (znaczek w radiu) ----------

    private var runningOn = false
    private var runningSentAt = 0L

    @Synchronized
    fun markRunning() = setRunning(true)

    @Synchronized
    private fun setRunning(on: Boolean) {
        if (!enabled.value || Store.pairing.value == null) return
        val now = System.currentTimeMillis()
        // zmiana od razu; podtrzymanie co 5 min (radio gasi znaczek po 12 min ciszy)
        if (on == runningOn && (!on || now - runningSentAt < 5 * 60_000L)) return
        runningOn = on
        runningSentAt = now
        val name = activeApp.ifBlank { app?.let { installed(it) }?.let { appName(it) } ?: "CamSam" }
        enqueue(JSONObject().put("type", "camsam").put("on", on).put("app", name).put("t", now))
    }

    // ---------- pływające okienko CamSam (ułatwienia dostępu) ----------

    private var hudAlert = false
    private var hudIcon = ""
    private var hudBar = ""
    private var hudSentAt = 0L

    /** Prosty „odcisk” ikony 8×8 – ta sama ikona = ten sam znak. */
    private fun iconHash(b: android.graphics.Bitmap): String {
        val s = android.graphics.Bitmap.createScaledBitmap(b, 8, 8, true)
        val px = IntArray(64) { i -> val c = s.getPixel(i % 8, i / 8); (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3 }
        val avg = px.average()
        var h = 0L
        for (i in 0 until 64) if (px[i] > avg) h = h or (1L shl i)
        return java.lang.Long.toHexString(h)
    }

    private fun hamming(a: String, b: String): Int = try {
        java.lang.Long.bitCount(java.lang.Long.parseUnsignedLong(a, 16) xor java.lang.Long.parseUnsignedLong(b, 16))
    } catch (_: Exception) {
        64
    }

    private fun street(texts: List<String>): String =
        texts.filter { t -> !t.startsWith("[") && t.any { it.isLetter() } && !t.equals("km/h", true) && t.length >= 3 }
            .maxByOrNull { it.length }.orEmpty()

    /** CamSam otwarty na telefonie (pełny ekran) – działa, ale okienka ostrzeżeń nie ma. */
    fun appVisible() {
        val ctx = app ?: return
        if (enabled.value && Store.pairing.value != null && inCar(ctx)) markRunning()
    }

    private var hudQuiet = 0

    @Synchronized
    fun onHud(h: CamSamAccess.Hud) {
        val ctx = app ?: return
        if (!enabled.value || Store.pairing.value == null || !inCar(ctx)) return
        markRunning()
        val icon = h.icon
        // ostrzeżenie: w miejscu ikony coś jest (bez wycinka ekranu – po napisach)
        val alert = if (icon != null) !h.iconBlank else h.texts.any { ALERT.containsMatchIn(it) && !IDLE.containsMatchIn(it) }
        val now = System.currentTimeMillis()
        if (!alert) {
            // koniec dopiero po 3 kolejnych „spokojnych” odczytach (~3 s) – pojedyncza klatka nie gasi banera
            if (hudAlert && ++hudQuiet >= 3) {
                hudAlert = false
                hudQuiet = 0
                enqueue(JSONObject().put("type", "camsam_hud").put("alert", false).put("t", now))
            }
            return
        }
        hudQuiet = 0
        var ih = icon?.let { iconHash(it) } ?: ""
        // inny znak = wyraźnie inna ikona (miganie/animacja ikony to nie nowe ostrzeżenie), najwyżej co 5 s
        val otherSign = hudAlert && ih.isNotEmpty() && hudIcon.isNotEmpty() && hamming(ih, hudIcon) > 14 && now - hudSentAt >= 5000
        if (hudAlert && !otherSign && hudIcon.isNotEmpty()) ih = hudIcon
        val fresh = !hudAlert || otherSign
        val barChanged = h.bar != hudBar
        val due = fresh || (barChanged && now - hudSentAt >= 3000) || now - hudSentAt >= 20_000
        if (!due) return
        if (!fresh && !barChanged && !underBudget()) return
        hudAlert = true
        hudIcon = ih
        hudBar = h.bar
        hudSentAt = now
        val o = JSONObject().put("type", "camsam_hud").put("alert", true).put("app", h.app)
            .put("bar", h.bar).put("street", street(h.texts)).put("ih", ih).put("t", now)
            .put("txt", h.texts.filter { !it.startsWith("[") }.joinToString(" · ").take(120))
        // obrazek ikony przy nowym ostrzeżeniu / zmianie znaku (ok. 1 KB)
        if (icon != null && ih.isNotEmpty() && fresh) {
            try { iconB64(icon).takeIf { it.isNotEmpty() }?.let { o.put("icon", it) } } catch (_: Exception) {}
        }
        enqueue(o)
    }

    /** Okienko CamSam zniknęło (aplikacja zamknięta albo ekran wyłączony). */
    @Synchronized
    fun hudGone() {
        if (hudAlert) {
            hudAlert = false
            enqueue(JSONObject().put("type", "camsam_hud").put("alert", false).put("t", System.currentTimeMillis()))
        }
    }

    private fun texts(v: View, out: MutableSet<String>) {
        if (out.size > 20) return
        if (v is TextView) v.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
    }

    // ---------- wysyłka ----------

    @Synchronized
    private fun alert(pkg: String, app: String, title: String, text: String) {
        val now = System.currentTimeMillis()
        val fresh = activePkg != pkg || activeTitle != title
        val changed = fresh || activeText != text
        activePkg = pkg; activeApp = app; activeTitle = title; activeText = text
        val due = fresh || (changed && now - activeSentAt >= UPDATE_MS && underBudget())
        if (due) push()
        startKeepalive()
    }

    @Synchronized
    private fun push() {
        activeSentAt = System.currentTimeMillis()
        enqueue(
            JSONObject().put("type", "alert").put("app", activeApp).put("title", activeTitle)
                .put("text", activeText).put("t", activeSentAt),
        )
    }

    @Synchronized
    private fun end() {
        if (activePkg == null) return
        activePkg = null
        keepalive?.cancel()
        enqueue(JSONObject().put("type", "alert_end").put("t", System.currentTimeMillis()))
    }

    private fun startKeepalive() {
        if (keepalive?.isActive == true) return
        keepalive = scope.launch {
            while (isActive) {
                delay(5000)
                synchronized(this@CamSamRelay) {
                    if (activePkg == null) return@launch
                    if (System.currentTimeMillis() - activeSentAt >= KEEP_MS && underBudget()) push()
                }
            }
        }
    }

    /** Licznik dzienny – po przekroczeniu wysyłamy tylko nowe ostrzeżenia i ich koniec. */
    private fun underBudget(): Boolean {
        val p = sp ?: return true
        return p.getInt("day", 0) != today() || p.getInt("count", 0) < DAILY_SOFT
    }

    private fun today(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    private fun count() {
        val p = sp ?: return
        val d = today()
        val c = if (p.getInt("day", 0) == d) p.getInt("count", 0) + 1 else 1
        p.edit().putInt("day", d).putInt("count", c).putLong("sent_at", System.currentTimeMillis()).apply()
    }

    /** Ile wiadomości z ostrzeżeniami poszło dziś. */
    fun sentToday(): Int = sp?.let { if (it.getInt("day", 0) == today()) it.getInt("count", 0) else 0 } ?: 0

    private fun enqueue(o: JSONObject) {
        out.trySend(o)
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (msg in out) {
                val p = Store.pairing.value ?: continue
                try {
                    Link.send(p, msg)
                    count()
                    lastSent.value = System.currentTimeMillis()
                    lastError.value = null
                } catch (e: Exception) {
                    lastError.value = e.message ?: "Brak internetu"
                }
            }
        }
    }

    /** Próbne ostrzeżenie – sprawdzenie, czy baner pojawia się w radiu. */
    fun test() {
        enqueue(JSONObject().put("type", "alert").put("app", "CamSam PLUS").put("title", "Fotoradar (test z telefonu)")
            .put("text", "400 m · ograniczenie 50").put("t", System.currentTimeMillis()))
        scope.launch {
            delay(15_000)
            if (activePkg == null) enqueue(JSONObject().put("type", "alert_end").put("t", System.currentTimeMillis()))
        }
    }
}

/** Nasłuch powiadomień telefonu – tylko CamSam / Yanosik (reszta jest od razu pomijana). */
class CamSamListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Store.init(this)
        CamSamRelay.init(this)
        try {
            activeNotifications?.forEach { if (CamSamRelay.appName(it.packageName) != null) CamSamRelay.onPosted(this, it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (CamSamRelay.appName(sbn.packageName) == null) return
        Store.init(this)
        CamSamRelay.init(this)
        CamSamRelay.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (CamSamRelay.appName(sbn.packageName) == null) return
        CamSamRelay.onRemoved(sbn)
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, CamSamListener::class.java)

        @Volatile
        private var instance: CamSamListener? = null

        /** Czy CamSam / Yanosik ma teraz powiadomienie (null – nasłuch nieaktywny). */
        fun camsamPosted(): Boolean? = try {
            instance?.activeNotifications?.any { CamSamRelay.appName(it.packageName) != null }
        } catch (_: Exception) {
            null
        }
    }
}
