package pl.mati.carosconnect

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Dziennik zdarzeń telefonu: połączenia z radiem, parkowanie, CamSam, wysłane cele, pozycja na żywo, błędy.
 * Do tego liczniki dzienne (statystyki): ile czego było każdego dnia – także rzeczy zbyt częste na wpis
 * (wiadomości do radia). Zapis do pliku z opóźnieniem, żeby nie pisać przy każdym zdarzeniu.
 */
object EventLog {

    enum class Cat(val label: String, val color: Long) {
        CAR("Auto i radio", 0xFF4FC3F7),
        PARK("Parkowanie", 0xFF66BB6A),
        CAMSAM("CamSam", 0xFFFFA726),
        NAV("Cele i trasy", 0xFFBA68C8),
        LIVE("Pozycja na żywo", 0xFF26C6DA),
        SYNC("Połączenie", 0xFF90A4AE),
        APP("Aplikacja", 0xFF8D99AE),
    }

    /** Statystyki (klucze liczników dziennych). */
    enum class Metric(val key: String, val label: String, val unit: String) {
        DRIVES("drives", "Połączenia z radiem", "razy"),
        PARKS("parks", "Zapisane parkowania", "razy"),
        CAMSAM("camsam", "Ostrzeżenia CamSam", "ostrzeżeń"),
        NAV("nav", "Cele wysłane do auta", "celów"),
        OUT("out", "Wiadomości do radia", "wiadomości"),
        IN("in", "Dane z radia", "pobrań"),
        ERR("err", "Błędy", "błędów"),
    }

    data class Ev(val time: Long, val cat: Cat, val msg: String, val err: Boolean = false)

    /** Najnowsze pierwsze. */
    val events = MutableStateFlow<List<Ev>>(emptyList())
    /** Dzień (yyyyMMdd) → licznik → ile. */
    val counters = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())

    private const val MAX = 3000
    private const val KEEP_DAYS = 60
    private var file: File? = null
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val DAY = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    /** Ostatni czas wpisu danego rodzaju (żeby powtarzające się błędy nie zalały dziennika). */
    private val lastOf = HashMap<String, Long>()

    fun dayKey(t: Long): String = synchronized(DAY) { DAY.format(Date(t)) }

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.applicationContext.filesDir, "events.json")
        file = f
        val mem = events.value
        val memC = counters.value
        try {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val a = o.optJSONArray("e") ?: JSONArray()
                val list = ArrayList<Ev>(a.length())
                for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    val cat = runCatching { Cat.valueOf(x.optString("c")) }.getOrDefault(Cat.APP)
                    list += Ev(x.getLong("t"), cat, x.optString("m"), x.optBoolean("x"))
                }
                val c = HashMap<String, Map<String, Int>>()
                o.optJSONObject("n")?.let { n ->
                    for (d in n.keys()) {
                        val m = n.getJSONObject(d)
                        c[d] = m.keys().asSequence().associateWith { m.optInt(it) }
                    }
                }
                events.value = (mem + list).sortedByDescending { it.time }.take(MAX)
                counters.value = merge(c, memC)
            } else {
                events.value = mem
                counters.value = memC
            }
        } catch (_: Exception) {
        }
    }

    private fun merge(a: Map<String, Map<String, Int>>, b: Map<String, Map<String, Int>>): Map<String, Map<String, Int>> {
        val out = HashMap(a)
        b.forEach { (d, m) ->
            val cur = HashMap(out[d] ?: emptyMap())
            m.forEach { (k, v) -> cur[k] = (cur[k] ?: 0) + v }
            out[d] = cur
        }
        return out
    }

    /** Stare wpisy z dziennika parkowania (pierwsze uruchomienie z nowym dziennikiem). */
    @Synchronized
    fun importOld(lines: List<String>) {
        if (lines.isEmpty() || events.value.any { it.cat == Cat.PARK }) return
        val fmt = SimpleDateFormat("d.MM HH:mm:ss", Locale.ROOT)
        val now = Calendar.getInstance()
        val list = lines.mapNotNull { line ->
            val ts = line.substringBefore("  ")
            val msg = line.substringAfter("  ", "")
            val d = runCatching { fmt.parse(ts) }.getOrNull() ?: return@mapNotNull null
            val c = Calendar.getInstance().apply { time = d; set(Calendar.YEAR, now.get(Calendar.YEAR)) }
            if (c.after(now)) c.add(Calendar.YEAR, -1)
            Ev(c.timeInMillis, catOfParkLine(msg), msg, isErrorText(msg))
        }
        events.value = (events.value + list).sortedByDescending { it.time }.take(MAX)
        saveSoon()
    }

    fun catOfParkLine(msg: String): Cat = when {
        msg.startsWith("Połączono") || msg.startsWith("Rozłączono") || msg.startsWith("Koniec jazdy") || msg.contains("usługi jazdy") || msg.contains("Usługa jazdy") -> Cat.CAR
        else -> Cat.PARK
    }

    fun isErrorText(msg: String): Boolean =
        listOf("Brak ", "zablokowan", "nie pozwolił", "błąd", "Błąd", "nie udało", "Nie udało", "awaryj").any { msg.contains(it) }

    /** Wpis w dzienniku. [throttleKey] – ten sam rodzaj wpisu najwyżej raz na [throttleMs]. */
    @Synchronized
    fun add(cat: Cat, msg: String, err: Boolean = false, throttleKey: String? = null, throttleMs: Long = 10 * 60_000L) {
        val now = System.currentTimeMillis()
        if (throttleKey != null) {
            val last = lastOf[throttleKey] ?: 0L
            if (now - last < throttleMs) return
            lastOf[throttleKey] = now
        }
        events.value = (listOf(Ev(now, cat, msg, err)) + events.value).take(MAX)
        if (err) bump(Metric.ERR)
        saveSoon()
    }

    @Synchronized
    fun bump(m: Metric, by: Int = 1) {
        val d = dayKey(System.currentTimeMillis())
        val cur = counters.value
        val day = HashMap(cur[d] ?: emptyMap())
        day[m.key] = (day[m.key] ?: 0) + by
        counters.value = HashMap(cur).apply { put(d, day) }
        saveSoon()
    }

    @Synchronized
    fun clear() {
        events.value = emptyList()
        counters.value = emptyMap()
        lastOf.clear()
        saveSoon()
    }

    private fun saveSoon() {
        if (file == null) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            save()
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        try {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            val cutDay = dayKey(cutoff)
            val a = JSONArray()
            events.value.asSequence().filter { it.time >= cutoff }.take(MAX).forEach { e ->
                a.put(JSONObject().put("t", e.time).put("c", e.cat.name).put("m", e.msg).apply { if (e.err) put("x", true) })
            }
            val n = JSONObject()
            counters.value.filterKeys { it >= cutDay }.forEach { (d, m) -> n.put(d, JSONObject(m as Map<*, *>)) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("e", a).put("n", n).toString())
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    /** Suma licznika od dnia fromDay do toDay włącznie (yyyyMMdd). */
    fun sum(m: Metric, fromDay: String, toDay: String): Int =
        counters.value.entries.filter { it.key in fromDay..toDay }.sumOf { it.value[m.key] ?: 0 }

    // ---------- wpisy z wiadomości do radia (wszystko idzie przez Link.send) ----------

    private var lastAlertKey = ""
    private var lastAlertAt = 0L

    /** Wysłano do radia – licznik i wpis dla ważnych rodzajów. */
    fun onSent(o: JSONObject) {
        try {
            bump(Metric.OUT)
            when (o.optString("type")) {
                "route", "nav" -> { bump(Metric.NAV); add(Cat.NAV, "Wysłano cel do auta: " + o.optString("n").ifBlank { "punkt na mapie" }) }
                "fav" -> add(Cat.NAV, "Dodano do ulubionych w radiu: " + o.optString("n"))
                "place_set" -> add(Cat.NAV, "Ustawiono adres " + (if (o.optString("slot") == "work") "pracy" else "domu") + " w radiu")
                "alert" -> {
                    val key = o.optString("title") + "|" + o.optString("text")
                    val now = System.currentTimeMillis()
                    if (key != lastAlertKey || now - lastAlertAt > 60_000) {
                        bump(Metric.CAMSAM)
                        add(Cat.CAMSAM, "Ostrzeżenie do radia: " + listOf(o.optString("title"), o.optString("text")).filter { it.isNotBlank() }.joinToString(" – ").take(120))
                    }
                    lastAlertKey = key
                    lastAlertAt = now
                }
                "camsam_hud" -> if (o.optBoolean("alert") && o.has("icon")) {
                    bump(Metric.CAMSAM)
                    add(Cat.CAMSAM, "Ostrzeżenie z okienka CamSam" + o.optString("street").takeIf { it.isNotBlank() }?.let { " – $it" }.orEmpty())
                }
                "camsam" -> add(Cat.CAMSAM, o.optString("app").ifBlank { "CamSam" } + if (o.optBoolean("on")) " włączony – radio pokazuje znaczek" else " wyłączony")
                "fuel_add" -> add(Cat.APP, "Tankowanie wysłane do radia")
                "fuel_del" -> add(Cat.APP, "Usunięto tankowanie w radiu")
                "svc_done", "svc_item", "svc_odo" -> add(Cat.APP, "Zmiana serwisu wysłana do radia")
                "cfg" -> add(Cat.APP, "Ustawienia wysłane do radia", throttleKey = "cfg", throttleMs = 60_000)
                "rs" -> if (o.optInt("i") == 0) add(Cat.APP, "Kopia ustawień wysyłana do radia")
            }
        } catch (_: Exception) {
        }
    }

    fun onSendFailed(o: JSONObject, e: Exception?) {
        add(
            Cat.SYNC, "Nie wysłano do radia (${o.optString("type")}): ${e?.message ?: "brak internetu"}",
            err = true, throttleKey = "send_" + o.optString("type"),
        )
    }
}
