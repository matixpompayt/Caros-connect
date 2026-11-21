package pl.mati.carosconnect

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Podgląd trasy przed wysłaniem celu do auta: start z miejsca zaparkowanego auta,
 * trasa (OSRM – ta sama co w radiu), korki i czas z korkami (TomTom, jeśli jest klucz),
 * pogoda na trasie na godzinę przejazdu (Open-Meteo) i godzina przyjazdu.
 */
object RoutePreview {

    data class Jam(val pts: List<LatLng>, val delayS: Int, val magnitude: Int, val kind: String)
    data class Wx(val kind: String, val name: String, val fromKm: Double, val toKm: Double, val time: Long, val code: Int, val temp: Double?)

    /** Wariant trasy z czasem z korkami. */
    data class Alt(
        val pts: List<LatLng>, val distanceM: Double, val baseS: Double,
        val trafficS: Int?, val delayS: Int?, val jams: List<Jam>, val trafficNote: String?,
    ) {
        val travelS: Double get() = trafficS?.toDouble() ?: baseS
    }

    data class Result(
        val pts: List<LatLng>,
        val distanceM: Double,
        /** Czas bez korków (OSRM). */
        val baseS: Double,
        /** Czas z korkami (TomTom) i opóźnienie – null, gdy brak klucza / danych. */
        val trafficS: Int?,
        val delayS: Int?,
        val jams: List<Jam>,
        val trafficNote: String?,
        val weather: List<Wx>?,
        val destTemp: Double?,
        val depart: Long,
        /** Warianty trasy (gdy bez punktów po drodze) i który jest pokazany. */
        val alts: List<Alt> = emptyList(),
        val altIndex: Int = 0,
    ) {
        val travelS: Double get() = trafficS?.toDouble() ?: baseS
        val arrive: Long get() = depart + (travelS * 1000).toLong()
    }

    fun tomtomKey(ctx: Context): String =
        ctx.getSharedPreferences("traffic", Context.MODE_PRIVATE).getString("tomtom", "")?.trim().orEmpty()

    private fun dist(a: LatLng, b: LatLng): Double {
        val kx = 111_320.0 * cos(Math.toRadians(a.latitude))
        return hypot((a.longitude - b.longitude) * kx, (a.latitude - b.latitude) * 110_540.0)
    }

    suspend fun compute(
        ctx: Context, from: LatLng, to: LatLng, depart: Long, withWeather: Boolean = true,
        /** Punkty po drodze (w kolejności). */
        via: List<LatLng> = emptyList(),
    ): Result = withContext(Dispatchers.IO) {
        // 1) trasy jak w radiu: bez punktów po drodze – do 3 wariantów (OSRM sam wybiera po swoich prędkościach,
        //    a bywa, że inny wariant jest krótszy i szybszy – wybieramy po czasie z korkami)
        val coords = (listOf(from) + via + to).joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude) }
        val alt = if (via.isEmpty()) "&alternatives=3" else ""
        val osrm = JSONObject(get("https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=polyline$alt"))
        if (osrm.optString("code") != "Ok") throw IllegalStateException("Brak trasy (${osrm.optString("code")})")
        val arr = osrm.getJSONArray("routes")
        val raw = (0 until arr.length()).mapNotNull { i ->
            val r0 = arr.getJSONObject(i)
            val pts = decodePolyline(r0.getString("geometry"))
            if (pts.size < 2) return@mapNotNull null
            var len = 0.0
            for (k in 1 until pts.size) len += dist(pts[k - 1], pts[k])
            val distance = r0.optDouble("distance", len)
            Triple(pts, distance, r0.optDouble("duration", distance / 15.0))
        }
        if (raw.isEmpty()) throw IllegalStateException("Brak trasy")
        coroutineScope {
            // TomTom sam liczy najszybsze trasy z korkami – często inne (krótsze) niż warianty OSRM
            val tom = if (via.isEmpty()) async { ttRoutes(ctx, from, to, depart) } else null
            val ttAlts = tom?.await().orEmpty()
            // 2) korki dla wariantów OSRM (te same drogi co TomTom pomijamy)
            val osrmAlts = raw.filter { (pts, _, _) -> ttAlts.none { same(pts, it.pts) } }.map { (pts, d, base) ->
                async {
                    val t = try { traffic(ctx, pts, depart, via) } catch (e: Exception) { Triple(null, emptyList<Jam>(), "Korki: brak danych (${e.message ?: "błąd"})") }
                    Alt(pts, d, base, t.first?.first, t.first?.second, t.second, t.third)
                }
            }.map { it.await() }
            val alts = (ttAlts + osrmAlts).take(4)
            // najszybszy z korkami; przy różnicy poniżej minuty – krótszy
            val best = alts.indices.minWith(compareBy<Int>({ (alts[it].travelS / 60).roundToInt() }, { alts[it].distanceM }))
            build(alts, best, depart, withWeather)
        }
    }

    /** Pokaż inny wariant (z pogodą dla niego). */
    suspend fun choose(r: Result, i: Int): Result = withContext(Dispatchers.IO) {
        if (i !in r.alts.indices) r else build(r.alts, i, r.depart, true)
    }

    private fun build(alts: List<Alt>, i: Int, depart: Long, withWeather: Boolean): Result {
        val a = alts[i]
        val cum = DoubleArray(a.pts.size)
        for (k in 1 until a.pts.size) cum[k] = cum[k - 1] + dist(a.pts[k - 1], a.pts[k])
        // 3) pogoda na wybranej trasie
        val wx = if (!withWeather) null else try { weather(a.pts, cum, depart, a.travelS) } catch (_: Exception) { null }
        return Result(
            pts = a.pts, distanceM = a.distanceM, baseS = a.baseS,
            trafficS = a.trafficS, delayS = a.delayS, jams = a.jams, trafficNote = a.trafficNote,
            weather = wx?.first, destTemp = wx?.second, depart = depart,
            alts = alts, altIndex = i,
        )
    }

    /** Kilka punktów trasy (do radia – wybiera ten sam wariant). */
    fun shape(r: Result, n: Int = 8): List<LatLng> {
        if (r.pts.size < 3) return emptyList()
        return (1..n).map { k -> r.pts[(r.pts.size - 1) * k / (n + 1)] }
    }

    // ---------- korki (TomTom) ----------

    /** ((czas z korkami, opóźnienie) albo null, korki, uwaga). */
    private fun traffic(ctx: Context, pts: List<LatLng>, depart: Long, via: List<LatLng> = emptyList()): Triple<Pair<Int, Int>?, List<Jam>, String?> {
        val key = tomtomKey(ctx)
        if (key.isBlank()) return Triple(null, emptyList(), "Korki: dodaj klucz TomTom (Ustawienia → Korki w CAR OS)")
        val step = max(1, pts.size / 900)
        val sp = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }
        val body = JSONObject().put("supportingPoints", JSONArray().apply {
            sp.forEach { put(JSONObject().put("latitude", it.latitude).put("longitude", it.longitude)) }
        })
        val a = sp.first()
        val b = sp.last()
        // wyjazd później niż za chwilę – prognoza ruchu na tę godzinę
        val at = if (depart - System.currentTimeMillis() > 2 * 60_000) {
            "&departAt=" + java.net.URLEncoder.encode(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(depart)), "UTF-8")
        } else ""
        // TomTom przy punktach pomocniczych przyjmuje tylko start i cel – punkty po drodze
        // podajemy jako „pointWaypoints” (indeksy w punktach pomocniczych), inaczej błąd 400
        val url = String.format(
            Locale.US,
            "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?key=%s&traffic=true&travelMode=car&routeType=fastest" +
                "&sectionType=traffic&routeRepresentation=polyline&computeTravelTimeFor=all%s",
            a.latitude, a.longitude, b.latitude, b.longitude, java.net.URLEncoder.encode(key, "UTF-8"), at,
        )
        val wp = JSONArray()
        var last = 0
        for (v in via) {
            val idx = (last + 1 until sp.lastIndex).minByOrNull { dist(sp[it], v) } ?: continue
            if (idx <= last) continue
            wp.put(JSONObject().put("supportingPointIndex", idx).put("waypointSourceType", "USER_DEFINED"))
            last = idx
        }
        fun post(withWp: Boolean): Pair<Int, String> {
            val b2 = JSONObject(body.toString())
            if (withWp && wp.length() > 0) b2.put("pointWaypoints", wp)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            return try {
                c.outputStream.use { it.write(b2.toString().toByteArray()) }
                val code = c.responseCode
                val t = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to t
            } finally {
                c.disconnect()
            }
        }
        var (code, text) = post(withWp = true)
        // bez punktów po drodze w zapytaniu – trasa i tak idzie po punktach pomocniczych
        if (code == 400 && wp.length() > 0) post(withWp = false).let { code = it.first; text = it.second }
        if (code == 401 || code == 403) return Triple(null, emptyList(), "Korki: klucz TomTom nieprawidłowy")
        if (code !in 200..299) {
            val why = try {
                JSONObject(text).let { o -> o.optJSONObject("detailedError")?.optString("message")?.ifBlank { null } ?: o.optJSONObject("error")?.optString("description") }
            } catch (_: Exception) { null }
            return Triple(null, emptyList(), "Korki: TomTom błąd $code" + (why?.let { " – ${it.take(80)}" } ?: ""))
        }
        val route = JSONObject(text).getJSONArray("routes").getJSONObject(0)
        val a2 = parseTT(route)
        return Triple(a2.trafficS!! to (a2.delayS ?: 0), a2.jams, null)
    }

    /** Trasa z odpowiedzi TomTom: punkty, czas z korkami i bez, korki. */
    private fun parseTT(route: JSONObject): Alt {
        val sum = route.getJSONObject("summary")
        val tt = ArrayList<LatLng>()
        val legs = route.getJSONArray("legs")
        for (i in 0 until legs.length()) {
            val p = legs.getJSONObject(i).getJSONArray("points")
            for (k in 0 until p.length()) {
                val o = p.getJSONObject(k)
                tt += LatLng(o.getDouble("latitude"), o.getDouble("longitude"))
            }
        }
        val jams = ArrayList<Jam>()
        route.optJSONArray("sections")?.let { secs ->
            for (i in 0 until secs.length()) {
                val s = secs.getJSONObject(i)
                if (s.optString("sectionType") != "TRAFFIC") continue
                val si = s.optInt("startPointIndex", -1)
                val ei = s.optInt("endPointIndex", -1)
                if (si < 0 || ei <= si || ei >= tt.size) continue
                jams += Jam(tt.subList(si, ei + 1).toList(), s.optInt("delayInSeconds"), s.optInt("magnitudeOfDelay"), s.optString("simpleCategory", "JAM"))
            }
        }
        val travel = sum.optInt("travelTimeInSeconds")
        val delay = sum.optInt("trafficDelayInSeconds")
        val base = sum.optInt("noTrafficTravelTimeInSeconds", travel - delay).toDouble()
        return Alt(tt, sum.optDouble("lengthInMeters", 0.0), base, travel, delay, jams, null)
    }

    /** Trasy policzone przez TomTom (najszybsze z aktualnymi korkami + warianty). */
    private fun ttRoutes(ctx: Context, from: LatLng, to: LatLng, depart: Long): List<Alt> {
        val key = tomtomKey(ctx)
        if (key.isBlank()) return emptyList()
        val at = if (depart - System.currentTimeMillis() > 2 * 60_000) {
            "&departAt=" + java.net.URLEncoder.encode(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(depart)), "UTF-8")
        } else ""
        val url = String.format(
            Locale.US,
            "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?key=%s&traffic=true&travelMode=car&routeType=fastest" +
                "&maxAlternatives=2&sectionType=traffic&routeRepresentation=polyline&computeTravelTimeFor=all%s",
            from.latitude, from.longitude, to.latitude, to.longitude, java.net.URLEncoder.encode(key, "UTF-8"), at,
        )
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        return try {
            if (c.responseCode !in 200..299) return emptyList()
            val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("routes")
            (0 until arr.length()).mapNotNull { i -> try { parseTT(arr.getJSONObject(i)).takeIf { it.pts.size >= 2 } } catch (_: Exception) { null } }
        } catch (_: Exception) {
            emptyList()
        } finally {
            c.disconnect()
        }
    }

    /** Czy dwie trasy jadą tą samą drogą (średnia odległość punktów < 150 m). */
    private fun same(a: List<LatLng>, b: List<LatLng>): Boolean {
        val step = max(1, b.size / 500)
        var sum = 0.0
        val n = 24
        for (k in 1..n) {
            val p = a[(a.size - 1) * k / (n + 1)]
            var m = Double.MAX_VALUE
            var i = 0
            while (i < b.size) { val d = dist(p, b[i]); if (d < m) m = d; i += step }
            sum += m
        }
        return sum / n < 150
    }

    // ---------- pogoda (Open-Meteo) ----------

    private data class Cond(val kind: String, val name: String, val severity: Int)

    private fun cond(code: Int, precip: Double, temp: Double, vis: Double, gust: Double): Cond? {
        val p = if (precip.isNaN()) 0.0 else precip
        val snowCode = code in 71..77 || code in 85..86
        return when {
            code >= 95 -> Cond("storm", "Burza", 6)
            code in 56..57 || code in 66..67 || (p >= 0.1 && !temp.isNaN() && temp <= 0.5 && !snowCode) -> Cond("freezing", "Marznący deszcz – ślisko", 7)
            snowCode || (p >= 0.1 && !temp.isNaN() && temp <= -1.0) -> Cond("snow", if (code == 75 || code == 86 || p >= 2.0) "Intensywny śnieg" else "Śnieg", 5)
            code == 65 || code == 82 || p >= 4.0 -> Cond("rain", "Ulewa", 4)
            code in 61..64 || code in 80..81 || p >= 0.5 -> Cond("rain", "Deszcz", 3)
            code in 51..55 || p >= 0.1 -> Cond("drizzle", "Mżawka", 2)
            code in 45..48 || (!vis.isNaN() && vis < 400) -> Cond("fog", "Mgła", 3)
            !gust.isNaN() && gust >= 70 -> Cond("wind", "Silny wiatr", 3)
            !temp.isNaN() && temp <= 0.0 -> Cond("frost", "Mróz – możliwa ślizgawica", 1)
            else -> null
        }
    }

    /** (odcinki ze złą pogodą, temperatura na miejscu). */
    private fun weather(pts: List<LatLng>, cum: DoubleArray, depart: Long, travelS: Double): Pair<List<Wx>, Double?> {
        val total = cum.last()
        val n = ceil(total / 5000.0).toInt().coerceIn(1, 29)
        val samples = (0..n).map { i ->
            val a = min(total, total * i / n)
            val idx = cum.indexOfFirst { it >= a }.let { if (it < 0) pts.lastIndex else it }
            a to pts[idx]
        }
        val lats = samples.joinToString(",") { String.format(Locale.US, "%.3f", it.second.latitude) }
        val lons = samples.joinToString(",") { String.format(Locale.US, "%.3f", it.second.longitude) }
        val base = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons&timeformat=unixtime&forecast_days=3" +
            "&hourly=precipitation,weather_code,temperature_2m,visibility,wind_gusts_10m"
        val body = try {
            get(base + "&minutely_15=precipitation,weather_code,temperature_2m,visibility,wind_gusts_10m&forecast_minutely_15=96")
        } catch (_: Exception) {
            get(base)
        }
        val arr = body.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONArray().put(JSONObject(it)) }

        data class At(val km: Double, val time: Long, val c: Cond?, val code: Int, val temp: Double)
        val at = ArrayList<At>()
        for (i in samples.indices) {
            val o = arr.optJSONObject(i) ?: continue
            val (a, _) = samples[i]
            val eta = depart + (travelS * (a / max(1.0, total)) * 1000).toLong()
            val v = valuesAt(o, eta) ?: continue
            at += At(a / 1000.0, eta, cond(v.code, v.precip, v.temp, v.vis, v.gust), v.code, v.temp)
        }
        if (at.isEmpty()) return emptyList<Wx>() to null
        val spacingKm = total / n / 1000.0
        val out = ArrayList<Wx>()
        var i = 0
        while (i < at.size) {
            val c = at[i].c
            if (c == null) { i++; continue }
            var j = i
            var worst = at[i]
            while (j + 1 < at.size && at[j + 1].c?.kind == c.kind) {
                j++
                if ((at[j].c?.severity ?: 0) > (worst.c?.severity ?: 0)) worst = at[j]
            }
            out += Wx(c.kind, worst.c?.name ?: c.name, at[i].km, min(total / 1000.0, at[j].km + spacingKm / 2), at[i].time, worst.code, worst.temp.takeIf { !it.isNaN() })
            i = j + 1
        }
        return out to at.last().temp.takeIf { !it.isNaN() }
    }

    private class V(val precip: Double, val code: Int, val temp: Double, val vis: Double, val gust: Double)

    /** Wartości na daną chwilę: z danych co 15 min, a gdy poza ich zakresem – godzinowe. */
    private fun valuesAt(o: JSONObject, t: Long): V? {
        fun pick(block: JSONObject?, perHour: Boolean): V? {
            block ?: return null
            val times = block.optJSONArray("time") ?: return null
            if (times.length() == 0) return null
            val first = times.getLong(0) * 1000
            val last = times.getLong(times.length() - 1) * 1000
            if (t < first - 3600_000 || t > last + (if (perHour) 3600_000 else 900_000)) return null
            var k = 0
            while (k < times.length() - 1 && times.getLong(k + 1) * 1000 <= t) k++
            fun d(n: String) = block.optJSONArray(n)?.optDouble(k, Double.NaN) ?: Double.NaN
            val p = d("precipitation").let { if (!perHour && !it.isNaN()) it * 4 else it }
            return V(p, block.optJSONArray("weather_code")?.optInt(k, 0) ?: 0, d("temperature_2m"), d("visibility"), d("wind_gusts_10m"))
        }
        return pick(o.optJSONObject("minutely_15"), false) ?: pick(o.optJSONObject("hourly"), true)
    }

    /** Najważniejszy komunikat o pogodzie (jak w radiu). */
    fun headline(r: Result): String? {
        val w = r.weather ?: return null
        val main = w.firstOrNull { it.kind != "frost" } ?: w.firstOrNull() ?: return "Bez opadów na trasie"
        return when {
            main.kind == "frost" -> "Mróz na trasie" + (w.mapNotNull { it.temp }.minOrNull()?.let { " (do ${it.roundToInt()}°)" } ?: "") + " – uważaj na ślizgawicę"
            main.fromKm < 1 -> "${main.name} od początku trasy" + (if (main.toKm >= 2) ", przez ${main.toKm.roundToInt()} km" else "")
            else -> "${main.name} za ${main.fromKm.roundToInt()} km (ok. ${hhmm(main.time)})" + (if (main.toKm - main.fromKm >= 2) ", przez ${(main.toKm - main.fromKm).roundToInt()} km" else "")
        }
    }

    fun hhmm(t: Long): String = SimpleDateFormat("H:mm", Locale.ROOT).format(Date(t))

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "CarOSConnect")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
