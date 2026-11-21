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
     * Miejsca z OpenStreetMap (Overpass): stacje paliw, MOP-y / rest area.
     */
    suspend fun stops(r: RoutePreview.Result, rangeKm: Double?): List<Stop> = withContext(Dispatchers.IO) {
        val out = ArrayList<Stop>()
        val cum = DoubleArray(r.pts.size)
        for (k in 1 until r.pts.size) cum[k] = cum[k - 1] + RoutePreview.dist(r.pts[k - 1], r.pts[k])
        val totalKm = cum.last() / 1000
        val speed = cum.last() / r.travelS.coerceAtLeast(60.0) // m/s
        fun pointAt(km: Double): Int = cum.indexOfFirst { it >= km * 1000 }.let { if (it < 0) cum.lastIndex else it }
        fun timeAt(i: Int) = r.depart + (cum[i] / speed * 1000).toLong()
        // tankowanie
        if (rangeKm != null && rangeKm < totalKm + 40) {
            val target = (rangeKm - 40).coerceIn(5.0, totalKm)
            val idx = pointAt(target)
            findNear(r.pts, idx, "fuel")?.let { (p, i) ->
                out += Stop("fuel", p, cum[i] / 1000, timeAt(i), "Zasięg ok. ${rangeKm.roundToInt()} km – zatankuj tutaj")
            }
        }
        // przerwy co ok. 2 h (najwyżej 3)
        val twoH = 2 * 3600.0
        var t = twoH
        var n = 0
        while (t < r.travelS - 20 * 60 && n < 3) {
            val idx = pointAt(t * speed / 1000)
            val near = findNear(r.pts, idx, "rest") ?: findNear(r.pts, idx, "fuel")
            near?.let { (p, i) ->
                if (out.none { kotlin.math.abs(it.atKm - cum[i] / 1000) < 15 }) {
                    out += Stop("break", p, cum[i] / 1000, timeAt(i), "Po ok. ${(t / 3600).roundToInt()} h jazdy – przerwa 15 min")
                }
            }
            t += twoH
            n++
        }
        out.sortedBy { it.atKm }
    }

    /** Najbliższa stacja / miejsce odpoczynku przy trasie w okolicy punktu [idx] (do 3 km od trasy). */
    private fun findNear(pts: List<LatLng>, idx: Int, kind: String): Pair<CarData.Place, Int>? {
        val c = pts[idx]
        val filter = if (kind == "fuel") "[amenity=fuel]" else "[highway~\"^(services|rest_area)$\"]"
        val q = "[out:json][timeout:15];(node$filter(around:4000,${c.latitude},${c.longitude});way$filter(around:4000,${c.latitude},${c.longitude}););out center 25;"
        val body = try {
            RoutePreview.get("https://overpass-api.de/api/interpreter?data=" + java.net.URLEncoder.encode(q, "UTF-8"))
        } catch (_: Exception) {
            return null
        }
        val els = JSONObject(body).optJSONArray("elements") ?: return null
        var best: Pair<CarData.Place, Int>? = null
        var bestScore = Double.MAX_VALUE
        // fragment trasy wokół punktu (żeby wybrać miejsce przy samej drodze)
        val from = (idx - 400).coerceAtLeast(0)
        val to = (idx + 400).coerceAtMost(pts.lastIndex)
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val lat = e.optDouble("lat", e.optJSONObject("center")?.optDouble("lat") ?: Double.NaN)
            val lon = e.optDouble("lon", e.optJSONObject("center")?.optDouble("lon") ?: Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val ll = LatLng(lat, lon)
            var dmin = Double.MAX_VALUE
            var at = idx
            var k = from
            while (k <= to) {
                val d = RoutePreview.dist(ll, pts[k])
                if (d < dmin) { dmin = d; at = k }
                k += 2
            }
            if (dmin > 1500) continue
            val tags = e.optJSONObject("tags")
            val name = listOfNotNull(tags?.optString("brand")?.ifBlank { null }, tags?.optString("name")?.ifBlank { null }).distinct().joinToString(" ")
            // bliżej trasy i bliżej docelowego miejsca = lepiej
            val score = dmin * 3 + kotlin.math.abs(at - idx) * 2.0
            if (score < bestScore) {
                bestScore = score
                val label = name.ifBlank { if (kind == "fuel") "Stacja paliw" else "Miejsce odpoczynku" }
                best = CarData.Place(label, if (kind == "fuel") "Stacja paliw" else "Przerwa", lat, lon) to at
            }
        }
        return best
    }
}
