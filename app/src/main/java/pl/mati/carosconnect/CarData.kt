package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Dane z CAR OS w aucie (stan, trasy, kopia) + ustawienia przypomnień i parkometru. */
object CarData {

    data class Service(
        val name: String, val overdue: Boolean, val soon: Boolean, val known: Boolean,
        val kmLeft: Int?, val daysLeft: Int?, val text: String, val fraction: Float,
    )

    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    data class Status(
        val time: Long, val car: String, val driver: String, val odo: Int?,
        val todayM: Double, val weekM: Double, val monthM: Double,
        val service: List<Service>, val favs: List<Place>, val version: String,
    )

    data class Trip(
        val start: Long, val end: Long, val distanceM: Double, val movingS: Long, val maxSpeed: Int, val path: String?,
    ) {
        val durationS get() = (end - start) / 1000
        val avgKmh get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0
    }

    data class Weather(val time: Long, val temp: Double, val minPast: Double, val humidity: Int, val frost: Boolean)

    val status = MutableStateFlow<Status?>(null)
    val trips = MutableStateFlow<List<Trip>>(emptyList())
    val backupTime = MutableStateFlow(0L)
    val backupCar = MutableStateFlow("")
    val weather = MutableStateFlow<Weather?>(null)
    val lastSync = MutableStateFlow(0L)

    /** Parkometr: koniec opłaconego czasu (0 = wyłączony) i początek. */
    val meterEnd = MutableStateFlow(0L)
    val meterStart = MutableStateFlow(0L)

    val notifService = MutableStateFlow(true)
    val notifFrost = MutableStateFlow(true)
    val frostHour = MutableStateFlow(6)

    private var sp: SharedPreferences? = null
    private var dir: File? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("car", Context.MODE_PRIVATE)
        sp = p
        dir = ctx.applicationContext.filesDir
        status.value = p.getString("status", null)?.let { parseStatus(it) }
        trips.value = try {
            val a = JSONArray(p.getString("trips", "[]"))
            (0 until a.length()).map { tripFrom(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
        backupTime.value = p.getLong("backup_time", 0L)
        backupCar.value = p.getString("backup_car", "") ?: ""
        lastSync.value = p.getLong("last_sync", 0L)
        meterEnd.value = p.getLong("meter_end", 0L)
        meterStart.value = p.getLong("meter_start", 0L)
        notifService.value = p.getBoolean("n_service", true)
        notifFrost.value = p.getBoolean("n_frost", true)
        frostHour.value = p.getInt("frost_hour", 6)
        p.getString("weather", null)?.let {
            try {
                val o = JSONObject(it)
                weather.value = Weather(o.getLong("t"), o.getDouble("c"), o.getDouble("m"), o.getInt("h"), o.getBoolean("f"))
            } catch (_: Exception) {}
        }
    }

    val prefs: SharedPreferences? get() = sp

    // ---------- stan auta ----------

    fun setStatusJson(raw: String) {
        val s = parseStatus(raw) ?: return
        val cur = status.value
        if (cur != null && cur.time > s.time) return
        status.value = s
        sp?.edit()?.putString("status", raw)?.apply()
        // trasy ze statusu (bez śladu) – uzupełniają dziennik
        try {
            val a = JSONObject(raw).optJSONArray("trips") ?: JSONArray()
            mergeTrips((0 until a.length()).map { tripFrom(a.getJSONObject(it)) })
        } catch (_: Exception) {}
    }

    /** Ulubione usunięte w telefonie – od razu znikają z listy (radio potwierdzi w następnym statusie). */
    fun removeFavLocal(p: Place) {
        val s = status.value ?: return
        status.value = s.copy(favs = s.favs.filterNot { it.lat == p.lat && it.lon == p.lon })
    }

    fun addFavLocal(p: Place) {
        val s = status.value ?: return
        if (s.favs.any { it.lat == p.lat && it.lon == p.lon }) return
        status.value = s.copy(favs = s.favs + p)
    }

    private fun parseStatus(raw: String): Status? = try {
        val o = JSONObject(raw)
        val svc = o.optJSONArray("svc") ?: JSONArray()
        val favs = o.optJSONArray("favs") ?: JSONArray()
        Status(
            time = o.getLong("t"),
            car = o.optString("car"),
            driver = o.optString("drv"),
            odo = if (o.isNull("odo")) null else o.optInt("odo"),
            todayM = o.optDouble("today", 0.0),
            weekM = o.optDouble("week", 0.0),
            monthM = o.optDouble("month", 0.0),
            service = (0 until svc.length()).map { i ->
                val s = svc.getJSONObject(i)
                Service(
                    s.optString("n"), s.optBoolean("o"), s.optBoolean("s"), s.optBoolean("k"),
                    if (s.isNull("km")) null else s.optInt("km"),
                    if (s.isNull("d")) null else s.optInt("d"),
                    s.optString("x"), s.optDouble("f", 0.0).toFloat(),
                )
            },
            favs = (0 until favs.length()).map { i ->
                val f = favs.getJSONObject(i)
                Place(f.optString("n"), f.optString("d"), f.getDouble("lat"), f.getDouble("lon"))
            },
            version = o.optString("ver"),
        )
    } catch (_: Exception) {
        null
    }

    // ---------- trasy ----------

    fun tripFrom(o: JSONObject) = Trip(
        o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.optLong("m"), o.optInt("v"),
        o.optString("p").ifBlank { null },
    )

    private fun tripJson(t: Trip): JSONObject = JSONObject()
        .put("s", t.start).put("e", t.end).put("d", t.distanceM).put("m", t.movingS).put("v", t.maxSpeed).put("p", t.path ?: "")

    fun mergeTrips(incoming: List<Trip>) {
        if (incoming.isEmpty()) return
        val map = LinkedHashMap<Long, Trip>()
        trips.value.forEach { map[it.start] = it }
        var changed = false
        for (t in incoming) {
            val old = map[t.start]
            val merged = if (old == null) t else t.copy(path = t.path ?: old.path)
            if (old != merged) { map[t.start] = merged; changed = true }
        }
        if (!changed) return
        val list = map.values.sortedByDescending { it.start }.take(200)
        trips.value = list
        val a = JSONArray()
        list.forEach { a.put(tripJson(it)) }
        sp?.edit()?.putString("trips", a.toString())?.apply()
    }

    // ---------- kopia ustawień ----------

    private fun backupFile() = dir?.let { File(it, "caros-backup.json") }

    fun setBackup(text: String, created: Long, car: String) {
        if (created <= backupTime.value) return
        try { backupFile()?.writeText(text) } catch (_: Exception) { return }
        backupTime.value = created
        backupCar.value = car
        sp?.edit()?.putLong("backup_time", created)?.putString("backup_car", car)?.apply()
    }

    fun backupText(): String? = try { backupFile()?.takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    // ---------- pogoda przy aucie ----------

    fun setWeather(w: Weather) {
        weather.value = w
        sp?.edit()?.putString(
            "weather",
            JSONObject().put("t", w.time).put("c", w.temp).put("m", w.minPast).put("h", w.humidity).put("f", w.frost).toString(),
        )?.apply()
    }

    fun setLastSync(t: Long) {
        lastSync.value = t
        sp?.edit()?.putLong("last_sync", t)?.apply()
    }

    // ---------- ustawienia ----------

    fun setMeter(start: Long, end: Long) {
        meterStart.value = start
        meterEnd.value = end
        sp?.edit()?.putLong("meter_start", start)?.putLong("meter_end", end)?.apply()
    }

    fun setNotifService(v: Boolean) { notifService.value = v; sp?.edit()?.putBoolean("n_service", v)?.apply() }
    fun setNotifFrost(v: Boolean) { notifFrost.value = v; sp?.edit()?.putBoolean("n_frost", v)?.apply() }
    fun setFrostHour(v: Int) { frostHour.value = (v + 24) % 24; sp?.edit()?.putInt("frost_hour", frostHour.value)?.apply() }
}
