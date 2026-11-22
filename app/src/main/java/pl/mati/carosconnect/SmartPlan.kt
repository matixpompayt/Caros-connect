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

    /**
     * Jedno miejsce do wyboru: [atKm] – km od startu, [offM] – ile od trasy, [rest] – MOP / parking.
     * [price] – cena wybranego paliwa na tej stacji ([cur] „EUR”/„PLN”), [pln] – w złotych; [cheap] – sieć zwykle tańsza.
     */
    data class Opt(
        val place: CarData.Place, val atKm: Double, val atTime: Long, val offM: Double, val rest: Boolean,
        val price: Double? = null, val cur: String? = null, val pln: Double? = null, val cheap: Boolean = false, val open: Boolean = true,
    )

    /** Grupa: „fuel” (tankowanie) albo „break” (przerwa po [hours] h) – do 3 propozycji, najlepsza pierwsza. */
    data class Group(val kind: String, val hours: Int, val atKm: Double, val options: List<Opt>)

    /** [fuel] – rodzaj paliwa; [plMax] – cena maksymalna / średnia w Polsce dziś (zł/l); [deOk] – ceny z Niemiec działają. */
    data class Stops(val groups: List<Group>, val totalKm: Double, val fuel: FuelPrices.Fuel = FuelPrices.Fuel.ON, val plMax: Double? = null, val deOk: Boolean? = null)

    private var cacheKey = ""
    private var cacheTotal = 0.0
    private var cacheVal: Stops? = null

    /**
     * Postoje: stacja, gdy zasięg nie wystarcza (z zapasem z ustawień, domyślnie 100 km), i przerwa co ok. 2 h jazdy.
     * Jedno zapytanie dla wszystkich postojów, kilka serwerów naraz; wynik zapamiętany dla tej trasy
     * (dodanie postoju z listy nie szuka od nowa).
     * Rzuca [java.io.IOException], gdy nie udało się nic sprawdzić (brak internetu / serwery zajęte).
     */
    suspend fun stops(
        ctx: Context, r: RoutePreview.Result, rangeKm: Double?, reserveKm: Int = Store.minRange.value,
        fuel: FuelPrices.Fuel = FuelPrices.fav.value, tkKey: String = StationPrices.tkKey.value,
    ): Stops = withContext(Dispatchers.IO) {
        val n = r.pts.size
        if (n < 2) return@withContext Stops(emptyList(), 0.0)
        val lats = DoubleArray(n) { r.pts[it].latitude }
        val lons = DoubleArray(n) { r.pts[it].longitude }
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + RoutePreview.dist(r.pts[k - 1], r.pts[k])
        val total = cum.last()
        val key = String.format(
            java.util.Locale.US, "%.3f,%.3f|%.3f,%.3f|%d|%d|%s|%d",
            lats[0], lons[0], lats[n - 1], lons[n - 1], ((rangeKm ?: -1.0) / 20).roundToInt(), reserveKm, fuel.key, tkKey.length,
        )
        // ta sama trasa (np. tylko doszedł postój z listy) – bez ponownego szukania
        synchronized(this@SmartPlan) {
            if (key == cacheKey && abs(total - cacheTotal) < maxOf(3000.0, cacheTotal * 0.03)) cacheVal?.let { return@withContext it }
        }
        val speed = total / r.travelS.coerceAtLeast(60.0) // m/s
        val wins = ArrayList<AlongRoute.Window>()
        val meta = ArrayList<Pair<String, Int>>()
        // tankowanie: zanim zasięg spadnie poniżej minimum z ustawień (domyślnie 100 km)
        if (rangeKm != null && rangeKm < total / 1000 + reserveKm) {
            val target = ((rangeKm - reserveKm) * 1000).coerceIn(5_000.0, total)
            wins += AlongRoute.Window(target, 70_000.0, 10_000.0, "fuel"); meta += "fuel" to 0
        }
        // przerwy co ok. 2 h (najwyżej 3)
        var t = 2 * 3600.0
        while (t < r.travelS - 20 * 60 && meta.count { it.first == "break" } < 3) {
            wins += AlongRoute.Window(t * speed, 30_000.0, 20_000.0, "rest"); meta += "break" to (t / 3600).roundToInt()
            t += 2 * 3600.0
        }
        if (wins.isEmpty()) return@withContext Stops(emptyList(), total / 1000, fuel)
        val fi = meta.indexOfFirst { it.first == "fuel" }
        // równolegle: stacje z OSM (jedno zapytanie) + ceny na stacjach w Niemczech + kurs euro + cena w Polsce
        val (hits, tk, rate) = coroutineScope {
            val osm = async { AlongRoute.findAll(lats, lons, cum, wins, if (fi >= 0) 8 else 3, RoutePreview.tomtomKey(ctx), fuelTag = StationPrices.osmTag(fuel)) }
            val de = async {
                if (fi < 0 || tkKey.isBlank() || StationPrices.tkType(fuel) == null) emptyList()
                else {
                    // koła po 25 km co ok. 40 km wzdłuż okna tankowania
                    val w = wins[fi]
                    val lo = (w.targetM - w.backM).coerceAtLeast(0.0)
                    val hi = (w.targetM + w.fwdM).coerceAtMost(total)
                    val centers = ArrayList<Pair<Double, Double>>()
                    var m = lo + 15_000
                    while (m < hi + 15_000) {
                        val k = cum.indexOfFirst { it >= minOf(m, hi) }.let { if (it < 0) n - 1 else it }
                        centers += lats[k] to lons[k]
                        m += 40_000
                    }
                    StationPrices.germany(tkKey, fuel, centers)
                }
            }
            val eur = async { if (fi >= 0 && tkKey.isNotBlank()) StationPrices.eurPln(ctx) else null }
            Triple(osm.await(), de.await(), eur.await())
        }
        val plMax = FuelPrices.data.value?.today?.get(fuel)
        val groups = wins.indices.map { i ->
            val (kind, h) = meta[i]
            var opts = hits[i].map { x ->
                Opt(
                    CarData.Place(x.name, if (x.rest) "Miejsce na przerwę" else "Stacja paliw", x.lat, x.lon),
                    x.atM / 1000, r.depart + (x.atM / speed * 1000).toLong(), x.offM, x.rest,
                    cheap = !x.rest && StationPrices.cheapBrand(x.brand + " " + x.name),
                )
            }
            if (kind == "fuel") {
                val w = wins[i]
                val lo = (w.targetM - w.backM).coerceAtLeast(0.0)
                val hi = (w.targetM + w.fwdM).coerceAtMost(total)
                // ceny z Niemiec: dopisz do stacji z OSM (do 150 m) albo dodaj stację, której w OSM nie było
                val list = opts.toMutableList()
                tk?.forEach { t ->
                    val (off, at) = AlongRoute.nearRoute(t.lat, t.lon, lats, lons, cum, lo, hi)
                    if (off > 1200 || at < lo - 500 || at > hi + 500) return@forEach
                    val pln = rate?.let { t.price * it }
                    val j = list.indexOfFirst { o -> RoutePreview.dist(LatLng(o.place.lat, o.place.lon), LatLng(t.lat, t.lon)) < 150 }
                    if (j >= 0) list[j] = list[j].copy(price = t.price, cur = "EUR", pln = pln, open = t.open)
                    else list += Opt(
                        CarData.Place(t.name, "Stacja paliw", t.lat, t.lon), at / 1000, r.depart + (at / speed * 1000).toLong(), off, false,
                        price = t.price, cur = "EUR", pln = pln, open = t.open,
                    )
                }
                // najtańsza na początku (otwarte, z ceną), potem bez ceny – tańsze sieci, potem najlepiej położone
                val order = HashMap<Opt, Int>()
                list.forEachIndexed { k, o -> order.putIfAbsent(o, k) }
                opts = list.sortedWith(
                    compareBy<Opt>({ if (it.price != null && it.open) 0 else 1 }, { it.pln ?: it.price ?: Double.MAX_VALUE }, { if (it.cheap) 0 else 1 }, { order[it] ?: 999 }),
                ).take(4)
            }
            Group(kind, h, wins[i].targetM / 1000, opts)
        }
        val res = Stops(groups, total / 1000, fuel, plMax, if (fi < 0 || tkKey.isBlank()) null else tk != null)
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
