package pl.mati.carosconnect

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

/** Synchronizacja z CAR OS + pogoda przy aucie + przypomnienia (aplikacja i praca w tle). */
object Sync {

    /**
     * @param ask true = poproś radio o świeże dane (pociągnięcie w dół); radio odpowiada w kilka sekund, jeśli jest włączone.
     * @return krótki komunikat dla użytkownika.
     */
    suspend fun run(ctx: Context, ask: Boolean, wantBackup: Boolean = false): String = withContext(Dispatchers.IO) {
        Store.init(ctx)
        CarData.init(ctx)
        val p = Store.pairing.value
        var msg = "Odświeżono"
        if (p != null) {
            if (ask) {
                try {
                    Link.send(p, JSONObject().put("type", "sync").put("backup", wantBackup || System.currentTimeMillis() - CarData.backupTime.value > 20 * 3_600_000L))
                    delay(if (wantBackup) 9000 else 5000)
                } catch (_: Exception) {
                }
            }
            msg = try {
                val before = Store.spot.value?.time
                val beforeStatus = CarData.status.value?.time
                val inbox = Link.fetchInbox(p)
                inbox.park?.let { Store.offerCarSpot(ctx, it) }
                inbox.status?.let { CarData.setStatusJson(it) }
                CarData.mergeTrips(inbox.trips)
                inbox.backup?.let { (text, created, car) -> CarData.setBackup(text, created, car) }
                CarData.setLastSync(System.currentTimeMillis())
                when {
                    Store.spot.value?.time != before -> "Pobrano nowe miejsce parkowania z CAR OS"
                    CarData.status.value?.time != beforeStatus -> "Pobrano aktualne dane auta"
                    else -> "Odświeżono – brak nowych danych z CAR OS"
                }
            } catch (_: Exception) {
                "Brak połączenia z przekaźnikiem"
            }
        }
        try { updateWeather() } catch (_: Exception) {}
        CarWidget.updateAll(ctx)
        msg
    }

    /** Temperatura przy aucie i ryzyko szronu (Open-Meteo), najwyżej co 30 min. */
    private fun updateWeather() {
        val s = Store.spot.value ?: return
        val w = CarData.weather.value
        if (w != null && System.currentTimeMillis() - w.time < 30 * 60_000) return
        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m&hourly=temperature_2m&past_hours=10&forecast_hours=1&timezone=auto",
            s.lat, s.lon,
        )
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode !in 200..299) return
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val cur = o.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m")
            val hum = cur.optInt("relative_humidity_2m", 0)
            val hourly = o.getJSONObject("hourly").getJSONArray("temperature_2m")
            var min = temp
            for (i in 0 until hourly.length()) if (!hourly.isNull(i)) min = minOf(min, hourly.getDouble(i))
            val frost = temp <= 0.5 || (min <= 0.5 && temp <= 3.0) || (temp <= 3.0 && hum >= 90 && min <= 2.0)
            CarData.setWeather(CarData.Weather(System.currentTimeMillis(), temp, min, hum, frost))
        } finally {
            c.disconnect()
        }
    }

    // ---------- przypomnienia (praca w tle) ----------

    /** Serwis: najwyżej raz na 3 dni dla danej pozycji i stanu (zbliża się / po terminie). */
    fun checkService(ctx: Context) {
        if (!CarData.notifService.value) return
        val st = CarData.status.value ?: return
        val urgent = st.service.filter { it.known && (it.overdue || it.soon) }
        if (urgent.isEmpty()) return
        val sp = CarData.prefs ?: return
        val now = System.currentTimeMillis()
        val fresh = urgent.filter { s ->
            val key = "svcn_" + s.name + (if (s.overdue) "_o" else "_s")
            now - sp.getLong(key, 0L) > 3 * 86_400_000L
        }
        if (fresh.isEmpty()) return
        val ed = sp.edit()
        fresh.forEach { s -> ed.putLong("svcn_" + s.name + (if (s.overdue) "_o" else "_s"), now) }
        ed.apply()
        val first = urgent.first()
        val more = if (urgent.size > 1) " (+${urgent.size - 1})" else ""
        Notifs.service(ctx, if (first.overdue) "Serwis po terminie" else "Zbliża się serwis", "${first.name}: ${first.text}$more")
    }

    /** Szron / mróz: rano o wybranej godzinie (okno 3 h), raz dziennie. */
    fun checkFrost(ctx: Context) {
        if (!CarData.notifFrost.value || Store.spot.value == null || Store.driving.value) return
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val from = CarData.frostHour.value
        if (((h - from + 24) % 24) > 2) return
        val day = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        val sp = CarData.prefs ?: return
        if (sp.getInt("frost_day", 0) == day) return
        val w = CarData.weather.value ?: return
        if (System.currentTimeMillis() - w.time > 90 * 60_000) return
        sp.edit().putInt("frost_day", day).apply()
        if (!w.frost) return
        val t = String.format(Locale("pl"), "%.0f°C", w.temp)
        Notifs.weather(
            ctx,
            if (w.temp <= 0) "Mróz przy aucie: $t" else "Możliwy szron na aucie ($t)",
            "Szyby mogą być oblodzone – wyjdź kilka minut wcześniej",
        )
    }
}
