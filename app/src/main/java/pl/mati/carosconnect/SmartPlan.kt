package pl.mati.carosconnect

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Inteligentne rozplanowanie trasy:
 *  – kolejność punktów po drodze (najszybsza, cel zostaje na końcu),
 *  – najlepsza godzina wyjazdu (czas przejazdu z prognozą ruchu co 15 min),
 *  – tankowanie i przerwy (stacja przed końcem zasięgu, przerwa co ok. 2 h jazdy),
 *  – unikanie korków i zamkniętych dróg (wybór trasy – w [RoutePreview.compute]).
 */
object SmartPlan {

    // ---------- kolejność punktów ----------

    /** Najszybsza kolejność punktów po drodze (cel na końcu) i ile szybciej niż obecna (s). */
    suspend fun optimizeOrder(from: LatLng, points: List<CarData.Place>): Pair<List<CarData.Place>, Int>? = withContext(Dispatchers.IO) {
        if (points.size < 3) return@withContext null
        try {
            val all = listOf(LatLng(from.latitude, from.longitude)) + points.map { LatLng(it.lat, it.lon) }
            val coords = all.joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude) }
            val trip = JSONObject(RoutePreview.get("https://router.project-osrm.org/trip/v1/driving/$coords?source=first&destination=last&roundtrip=false&overview=false"))
            if (trip.optString("code") != "Ok") return@withContext null
            val wps = trip.getJSONArray("waypoints")
            // waypoint_index = miejsce punktu w trasie
            val order = (1 until wps.length()).sortedBy { wps.getJSONObject(it).getInt("waypoint_index") }
            val newPts = order.map { points[it - 1] }
            val newS = trip.getJSONArray("trips").getJSONObject(0).getDouble("duration")
            val cur = JSONObject(RoutePreview.get("https://router.project-osrm.org/route/v1/driving/$coords?overview=false"))
            val curS = cur.getJSONArray("routes").getJSONObject(0).getDouble("duration")
            newPts to (curS - newS).roundToInt()
        } catch (_: Exception) {
            null
        }
    }

    // ---------- najlepsza godzina wyjazdu ----------

    data class Slot(val depart: Long, val travelS: Int)

    /** Czas przejazdu tą trasą przy wyjeździe co [stepMin] min przez [hours] h (prognoza ruchu TomTom). */
    suspend fun departures(ctx: Context, r: RoutePreview.Result, via: List<LatLng>, from: Long, stepMin: Int = 15, hours: Int = 3): List<Slot> =
        withContext(Dispatchers.IO) {
            val base = (from / 60_000) * 60_000
            val times = (0..(hours * 60 / stepMin)).map { base + it * stepMin * 60_000L }
            coroutineScope {
                times.map { t ->
                    async {
                        val tr = try { RoutePreview.traffic(ctx, r.pts, t, via).first?.first } catch (_: Exception) { null }
                        tr?.let { Slot(t, it) }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    // ---------- tankowanie i przerwy ----------

    /** Jedno miejsce do wyboru: [atKm] – km od startu, [offM] – ile od trasy, [rest] – MOP / parking. */
    data class Opt(val place: CarData.Place, val atKm: Double, val atTime: Long, val offM: Double, val rest: Boolean)

    /** Grupa: „fuel” (tankowanie) albo „break” (przerwa po [hours] h) – do 3 propozycji, najlepsza pierwsza. */
    data class Group(val kind: String, val hours: Int, val atKm: Double, val options: List<Opt>)

    data class Stops(val groups: List<Group>, val totalKm: Double)

    private var cacheKey = ""
    private var cacheTotal = 0.0
    private var cacheVal: Stops? = null

    /**
     * Postoje: stacja, gdy zasięg nie wystarcza (z zapasem 40 km), i przerwa co ok. 2 h jazdy.
     * Jedno zapytanie dla wszystkich postojów, kilka serwerów naraz; wynik zapamiętany dla tej trasy
     * (dodanie postoju z listy nie szuka od nowa).
     * Rzuca [java.io.IOException], gdy nie udało się nic sprawdzić (brak internetu / serwery zajęte).
     */
    suspend fun stops(ctx: Context, r: RoutePreview.Result, rangeKm: Double?): Stops = withContext(Dispatchers.IO) {
        val n = r.pts.size
        if (n < 2) return@withContext Stops(emptyList(), 0.0)
        val lats = DoubleArray(n) { r.pts[it].latitude }
        val lons = DoubleArray(n) { r.pts[it].longitude }
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + RoutePreview.dist(r.pts[k - 1], r.pts[k])
        val total = cum.last()
        val key = String.format(
            java.util.Locale.US, "%.3f,%.3f|%.3f,%.3f|%d",
            lats[0], lons[0], lats[n - 1], lons[n - 1], ((rangeKm ?: -1.0) / 20).roundToInt(),
        )
        // ta sama trasa (np. tylko doszedł postój z listy) – bez ponownego szukania
        synchronized(this@SmartPlan) {
            if (key == cacheKey && abs(total - cacheTotal) < maxOf(3000.0, cacheTotal * 0.03)) cacheVal?.let { return@withContext it }
        }
        val speed = total / r.travelS.coerceAtLeast(60.0) // m/s
        val wins = ArrayList<AlongRoute.Window>()
        val meta = ArrayList<Pair<String, Int>>()
        // tankowanie: najlepiej przed końcem zasięgu (zapas 40 km)
        if (rangeKm != null && rangeKm < total / 1000 + 40) {
            val target = ((rangeKm - 40) * 1000).coerceIn(5_000.0, total)
            wins += AlongRoute.Window(target, 70_000.0, 10_000.0, "fuel"); meta += "fuel" to 0
        }
        // przerwy co ok. 2 h (najwyżej 3)
        var t = 2 * 3600.0
        while (t < r.travelS - 20 * 60 && meta.count { it.first == "break" } < 3) {
            wins += AlongRoute.Window(t * speed, 30_000.0, 20_000.0, "rest"); meta += "break" to (t / 3600).roundToInt()
            t += 2 * 3600.0
        }
        if (wins.isEmpty()) return@withContext Stops(emptyList(), total / 1000)
        val hits = AlongRoute.findAll(lats, lons, cum, wins, 3, RoutePreview.tomtomKey(ctx))
        val groups = wins.indices.map { i ->
            val (kind, h) = meta[i]
            Group(kind, h, wins[i].targetM / 1000, hits[i].map { x ->
                Opt(
                    CarData.Place(x.name, if (x.rest) "Miejsce na przerwę" else "Stacja paliw", x.lat, x.lon),
                    x.atM / 1000, r.depart + (x.atM / speed * 1000).toLong(), x.offM, x.rest,
                )
            })
        }
        val res = Stops(groups, total / 1000)
        synchronized(this@SmartPlan) { cacheKey = key; cacheTotal = total; cacheVal = res }
        res
    }

    /** Pełny bak w km (pojemność / średnie spalanie), gdy wiadomo. */
    fun fullRangeKm(f: CarData.Fuel?): Double? {
        val avg = f?.avg ?: return null
        if (f.tank <= 0 || avg < 2) return null
        return f.tank / avg * 100
    }

    /** Gdzie wstawić postój na [atKm] km między punkty po drodze, żeby kolejność zgadzała się z trasą. */
    fun insertIndex(r: RoutePreview.Result, via: List<CarData.Place>, atKm: Double): Int {
        val n = r.pts.size
        if (n < 2 || via.isEmpty()) return via.size
        val lats = DoubleArray(n) { r.pts[it].latitude }
        val lons = DoubleArray(n) { r.pts[it].longitude }
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + RoutePreview.dist(r.pts[k - 1], r.pts[k])
        val idx = via.indexOfFirst { AlongRoute.positionOf(it.lat, it.lon, lats, lons, cum) > atKm * 1000 }
        return if (idx < 0) via.size else idx
    }
}
