package pl.mati.carosconnect

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * Ceny paliw w Polsce – ten sam plik co w CAR OS (docs/fuel.json w repo Car-lanchuer,
 * aktualizowany co pół godziny przez GitHub Actions): maksymalne ceny ON / Pb95 / Pb98
 * z obwieszczeń Ministra Energii i średnia cena LPG.
 */
object FuelPrices {
    private const val URL_JSON = "https://raw.githubusercontent.com/matixpompayt/Car-lanchuer/main/docs/fuel.json"
    private const val MAX_VALID_DAYS = 5L
    private val PLL = Locale("pl")

    enum class Fuel(val key: String, val label: String, val short: String, val color: Long) {
        ON("on", "Olej napędowy", "ON", 0xFF38BDF8),
        PB95("pb95", "Benzyna 95", "Pb95", 0xFF34D399),
        PB98("pb98", "Benzyna 98", "Pb98", 0xFFFBBF24),
        LPG("lpg", "Autogaz · średnia", "LPG", 0xFFA78BFA),
    }

    data class Day(val date: LocalDate, val prices: Map<Fuel, Double>)

    data class Snapshot(
        val days: List<Day>,
        val today: Map<Fuel, Double>,
        val tomorrow: Map<Fuel, Double>,
        /** Jutro obowiązuje to samo obwieszczenie co dziś (weekend). */
        val tomorrowSame: Boolean,
        /** Zmiana dziś względem poprzedniego obwieszczenia (LPG: względem ceny sprzed tygodnia). */
        val change: Map<Fuel, Double>,
        val latestFrom: LocalDate?,
        val updated: String,
    ) {
        val noCurrent get() = today[Fuel.ON] == null && today[Fuel.PB95] == null
        val hasNewTomorrow get() = tomorrow.isNotEmpty() && !tomorrowSame
    }

    val data = MutableStateFlow<Snapshot?>(null)
    val loading = MutableStateFlow(false)
    val fav = MutableStateFlow(Fuel.ON)
    val notify = MutableStateFlow(true)
    private var lastFetch = 0L
    private var inited = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences("fuel_prices", Context.MODE_PRIVATE)
    private fun cache(ctx: Context) = File(ctx.filesDir, "fuel.json")

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        val p = sp(ctx)
        fav.value = Fuel.entries.firstOrNull { it.key == p.getString("fav", "on") } ?: Fuel.ON
        notify.value = p.getBoolean("notify", true)
        try { cache(ctx).takeIf { it.exists() }?.readText()?.let { data.value = parse(it) } } catch (_: Exception) {}
    }

    fun setFavorite(ctx: Context, f: Fuel) {
        fav.value = f
        sp(ctx).edit().putString("fav", f.key).apply()
        FuelWidget.updateAll(ctx)
    }

    fun setNotify(ctx: Context, v: Boolean) {
        notify.value = v
        sp(ctx).edit().putBoolean("notify", v).apply()
    }

    /** Pobiera plik (najwyżej co 10 min, chyba że force). Zwraca true, gdy się udało. */
    suspend fun refresh(ctx: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        init(ctx)
        if (!force && System.currentTimeMillis() - lastFetch < 10 * 60_000L && data.value != null) {
            // dane z pamięci mogły być liczone wczoraj – przelicz dni
            if (data.value?.days?.lastOrNull()?.date?.let { it.isBefore(LocalDate.now()) } == true) reparse(ctx)
            return@withContext true
        }
        loading.value = true
        try {
            val c = URL(URL_JSON + "?t=" + System.currentTimeMillis() / 600_000).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "CarOS-Connect")
            val text = try {
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
            data.value = parse(text)
            cache(ctx).writeText(text)
            lastFetch = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            reparse(ctx)
            false
        } finally {
            loading.value = false
        }
    }

    private fun reparse(ctx: Context) {
        try { cache(ctx).takeIf { it.exists() }?.readText()?.let { data.value = parse(it) } } catch (_: Exception) {}
    }

    fun zl(v: Double): String = String.format(PLL, "%.2f", v)
    fun signed(v: Double): String = (if (v > 0.004) "+" else if (v < -0.004) "−" else "±") + String.format(PLL, "%.2f", kotlin.math.abs(v))

    /** W tle (SyncWorker): odśwież, zaktualizuj widżet, powiadom o cenach na jutro (raz na obwieszczenie). */
    suspend fun check(ctx: Context) {
        init(ctx)
        val ok = refresh(ctx)
        FuelWidget.updateAll(ctx)
        if (!ok || !notify.value) return
        val s = data.value ?: return
        if (!s.hasNewTomorrow) return
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val p = sp(ctx)
        if (p.getString("notified", "") == tomorrow) return
        p.edit().putString("notified", tomorrow).apply()
        val f = fav.value.takeIf { it != Fuel.LPG && s.tomorrow[it] != null } ?: Fuel.ON
        val t = s.tomorrow[f] ?: return
        val d = s.today[f]?.let { t - it }
        val title = "Jutro ${f.short} ${zl(t)} zł" + (d?.let { " (${signed(it)})" } ?: "")
        val text = listOf(Fuel.ON, Fuel.PB95, Fuel.PB98).mapNotNull { x ->
            s.tomorrow[x]?.let { v -> "${x.short} ${zl(v)}" + (s.today[x]?.let { " (${signed(v - it)})" } ?: "") }
        }.joinToString(" · ")
        Notifs.fuel(ctx, title, "Maksymalne ceny na jutro: $text")
    }

    fun parse(text: String): Snapshot {
        val o = JSONObject(text)
        val max = ArrayList<Pair<LocalDate, Map<Fuel, Double>>>()
        o.optJSONArray("history")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                val d = try { LocalDate.parse(h.getString("from")) } catch (_: Exception) { continue }
                max += d to mapOf(Fuel.ON to h.optDouble("on"), Fuel.PB95 to h.optDouble("pb95"), Fuel.PB98 to h.optDouble("pb98")).filterValues { !it.isNaN() }
            }
        }
        max.sortBy { it.first }
        val lpg = ArrayList<Pair<LocalDate, Double>>()
        o.optJSONArray("lpgHistory")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.getJSONObject(i)
                val d = try { LocalDate.parse(h.getString("date")) } catch (_: Exception) { continue }
                val v = h.optDouble("price")
                if (!v.isNaN()) lpg += d to v
            }
        }
        lpg.sortBy { it.first }

        val today = LocalDate.now()
        fun maxEntry(d: LocalDate) = max.lastOrNull { !it.first.isAfter(d) }?.takeIf { !it.first.plusDays(MAX_VALID_DAYS).isBefore(d) }
        fun lpgAt(d: LocalDate) = lpg.lastOrNull { !it.first.isAfter(d) }?.takeIf { !it.first.plusDays(10).isBefore(d) }?.second

        val cur = maxEntry(today)
        val todayMax = cur?.second ?: emptyMap()
        val todayLpg = lpgAt(today)
        val now = HashMap(todayMax).apply { todayLpg?.let { put(Fuel.LPG, it) } }

        val tomorrow = HashMap<Fuel, Double>()
        max.firstOrNull { it.first == today.plusDays(1) }?.second?.let { tomorrow.putAll(it) }
        var tomorrowSame = false
        if (tomorrow.isEmpty() && cur != null && cur.first.dayOfWeek == DayOfWeek.SATURDAY && !today.plusDays(1).isAfter(cur.first.plusDays(2))) {
            tomorrow.putAll(cur.second); tomorrowSame = true
        }

        val change = HashMap<Fuel, Double>()
        val prevMax = cur?.let { c -> max.lastOrNull { it.first.isBefore(c.first) }?.second }
        for (f in listOf(Fuel.ON, Fuel.PB95, Fuel.PB98)) {
            val a = todayMax[f]; val b = prevMax?.get(f)
            if (a != null && b != null) change[f] = a - b
        }
        val lpgWeek = lpg.lastOrNull { !it.first.isAfter(today.minusDays(7)) }?.second
        if (todayLpg != null && lpgWeek != null) change[Fuel.LPG] = todayLpg - lpgWeek

        val first = (listOfNotNull(max.firstOrNull()?.first, lpg.firstOrNull()?.first).minOrNull() ?: today)
            .let { if (it.isBefore(today.minusDays(90))) today.minusDays(90) else it }
        val last = if (tomorrow.isNotEmpty() && !tomorrowSame) today.plusDays(1) else today
        val days = ArrayList<Day>()
        var d = first
        while (!d.isAfter(last)) {
            val m = HashMap<Fuel, Double>()
            maxEntry(d)?.second?.let { m.putAll(it) }
            lpgAt(d)?.let { m[Fuel.LPG] = it }
            days += Day(d, m)
            d = d.plusDays(1)
        }
        return Snapshot(days, now, tomorrow, tomorrowSame, change, cur?.first ?: max.lastOrNull()?.first, o.optString("updated"))
    }
}
