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

    data class Stop(val kind: String, val place: CarData.Place, val atKm: Double, val atTime: Long, val note: String)

    /**
     * Postoje: stacja, gdy zasięg nie wystarcza (z zapasem 40 km), i przerwa co ok. 2 h jazdy.
     * Miejsca przy samej trasie z OpenStreetMap (a gdy nie odpowiada – z TomTom).
     * Rzuca [java.io.IOException], gdy nie udało się nic sprawdzić (brak internetu / serwery zajęte).
     */
    suspend fun stops(ctx: Context, r: RoutePreview.Result, rangeKm: Double?): List<Stop> = withContext(Dispatchers.IO) {
        val out = ArrayList<Stop>()
        val n = r.pts.size
        if (n < 2) return@withContext out
        val lats = DoubleArray(n) { r.pts[it].latitude }
        val lons = DoubleArray(n) { r.pts[it].longitude }
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + RoutePreview.dist(r.pts[k - 1], r.pts[k])
        val total = cum.last()
        val speed = total / r.travelS.coerceAtLeast(60.0) // m/s
        val key = RoutePreview.tomtomKey(ctx)
        var ok = 0
        var err: Exception? = null
        fun look(target: Double, back: Double, fwd: Double, kind: String): AlongRoute.Hit? = try {
            AlongRoute.find(lats, lons, cum, target, back, fwd, kind, key).also { ok++ }
        } catch (e: Exception) {
            err = e
            null
        }
        // tankowanie: najlepiej przed końcem zasięgu (zapas 40 km)
        if (rangeKm != null && rangeKm < total / 1000 + 40) {
            val target = ((rangeKm - 40) * 1000).coerceIn(5_000.0, total)
            look(target, 60_000.0, 10_000.0, "fuel")?.let { h ->
                out += Stop("fuel", CarData.Place(h.name, "Stacja paliw", h.lat, h.lon), h.atM / 1000, r.depart + (h.atM / speed * 1000).toLong(),
                    "Zasięg ok. ${rangeKm.roundToInt()} km – zatankuj tutaj")
            }
        }
        // przerwy co ok. 2 h (najwyżej 3)
        var t = 2 * 3600.0
        var i = 0
        while (t < r.travelS - 20 * 60 && i < 3) {
            val target = t * speed
            if (out.none { abs(it.atKm * 1000 - target) < 25_000 }) {
                look(target, 30_000.0, 20_000.0, "rest")?.let { h ->
                    if (out.none { abs(it.atKm - h.atM / 1000) < 15 }) {
                        out += Stop("break", CarData.Place(h.name, if (h.rest) "Miejsce na przerwę" else "Stacja paliw", h.lat, h.lon), h.atM / 1000,
                            r.depart + (h.atM / speed * 1000).toLong(), "Po ok. ${(t / 3600).roundToInt()} h jazdy – przerwa 15 min")
                    }
                }
            }
            t += 2 * 3600.0
            i++
        }
        if (ok == 0 && err != null) throw java.io.IOException(err?.message ?: "brak odpowiedzi")
        out.sortedBy { it.atKm }
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
