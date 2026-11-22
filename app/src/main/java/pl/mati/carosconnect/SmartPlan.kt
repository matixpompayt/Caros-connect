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
     * Cena wybranego paliwa: [price] w walucie [cur], [pln] – w złotych; [src]: „station” – na tej stacji,
     * „max” – cena maksymalna w Polsce, „avg” – średnia w kraju (UE); [iso] – kraj; [cheap] – sieć zwykle tańsza.
     */
    data class Opt(
        val place: CarData.Place, val atKm: Double, val atTime: Long, val offM: Double, val rest: Boolean,
        val price: Double? = null, val cur: String? = null, val pln: Double? = null, val cheap: Boolean = false, val open: Boolean = true,
        val src: String? = null, val iso: String? = null,
    )

    /** Grupa: „fuel” (tankowanie nr [n]) albo „break” (przerwa po [hours] h) – kilka propozycji, najlepsza pierwsza. */
    data class Group(val kind: String, val hours: Int, val atKm: Double, val options: List<Opt>, val n: Int = 1)

    /**
     * [fuel] – rodzaj paliwa; [fullKm] – zasięg pełnego baku użyty do planu (z auta albo szacunek [fullGuess]);
     * [noPrices] – kraje na trasie tankowania, gdzie nie ma cen na stacjach (pokazujemy średnie).
     */
    data class Stops(
        val groups: List<Group>, val totalKm: Double, val fuel: FuelPrices.Fuel = FuelPrices.Fuel.ON,
        val fullKm: Double? = null, val fullGuess: Boolean = false, val deKeyMissing: Boolean = false, val deOk: Boolean? = null,
    )

    private var cacheKey = ""
    private var cacheTotal = 0.0
    private var cacheVal: Stops? = null

    /**
     * Postoje: tankowanie – tyle razy, ile trzeba (np. 1000 km), zanim zasięg spadnie poniżej minimum z ustawień,
     * zakładając tankowanie do pełna; przerwa co ok. 2 h jazdy (jeśli nie wypada przy tankowaniu).
     * Stacje z OSM (jedno zapytanie), ceny na stacjach z oficjalnych źródeł (DE, FR, AT, IT, ES),
     * w Polsce cena maksymalna, w innych krajach UE – średnia z biuletynu Komisji Europejskiej.
     * Najtańsze (w złotych) pierwsze. Wynik zapamiętany dla trasy.
     * Rzuca [java.io.IOException], gdy nie udało się nic sprawdzić (brak internetu / serwery zajęte).
     */
    suspend fun stops(
        ctx: Context, r: RoutePreview.Result, rangeKm: Double?, reserveKm: Int = Store.minRange.value,
        fuel: FuelPrices.Fuel = FuelPrices.fav.value, tkKey: String = StationPrices.tkKey.value, fullKmCar: Double? = null,
    ): Stops = withContext(Dispatchers.IO) {
        val n = r.pts.size
        if (n < 2) return@withContext Stops(emptyList(), 0.0, fuel)
        val lats = DoubleArray(n) { r.pts[it].latitude }
        val lons = DoubleArray(n) { r.pts[it].longitude }
        val cum = DoubleArray(n)
        for (k in 1 until n) cum[k] = cum[k - 1] + RoutePreview.dist(r.pts[k - 1], r.pts[k])
        val total = cum.last()
        val totalKm = total / 1000
        val key = String.format(
            java.util.Locale.US, "%.3f,%.3f|%.3f,%.3f|%d|%d|%s|%d|%d",
            lats[0], lons[0], lats[n - 1], lons[n - 1], ((rangeKm ?: -1.0) / 20).roundToInt(), reserveKm, fuel.key, tkKey.length,
            ((fullKmCar ?: -1.0) / 20).roundToInt(),
        )
        // ta sama trasa (np. tylko doszedł postój z listy) – bez ponownego szukania
        synchronized(this@SmartPlan) {
            if (key == cacheKey && abs(total - cacheTotal) < maxOf(3000.0, cacheTotal * 0.03)) cacheVal?.let { return@withContext it }
        }
        val speed = total / r.travelS.coerceAtLeast(60.0) // m/s
        val fullGuess = fullKmCar == null
        val fullKm = fullKmCar ?: maxOf(rangeKm ?: 0.0, 650.0)
        val wins = ArrayList<AlongRoute.Window>()
        val meta = ArrayList<Pair<String, Int>>()
        // tankowania: kolejno, zanim zasięg spadnie poniżej minimum; po każdym – pełny bak
        val fuelAt = ArrayList<Double>() // przewidywane miejsca tankowania (m)
        if (rangeKm != null) {
            var pos = 0.0
            var left = rangeKm * 1000
            var k = 0
            while (left - (total - pos) < reserveKm * 1000 && k < 6) {
                val target = (pos + left - reserveKm * 1000).coerceIn(pos + 5_000, total)
                val back = minOf(70_000.0, target - pos - 5_000).coerceAtLeast(5_000.0)
                wins += AlongRoute.Window(target, back, 10_000.0, "fuel"); meta += "fuel" to (++k)
                val at = (target - minOf(20_000.0, back / 2)).coerceAtLeast(pos + 1_000)
                fuelAt += at
                pos = at
                left = fullKm * 1000
                if (fullKm <= reserveKm + 30) break // bak za mały na sensowny plan
            }
        }
        // przerwy co ok. 2 h – pomiń, gdy w pobliżu (25 km) i tak jest tankowanie
        var t = 2 * 3600.0
        var nb = 0
        while (t < r.travelS - 20 * 60 && nb < 6) {
            val target = t * speed
            if (fuelAt.none { abs(it - target) < 25_000 }) {
                wins += AlongRoute.Window(target, 30_000.0, 20_000.0, "rest"); meta += "break" to (t / 3600).roundToInt()
                nb++
            }
            t += 2 * 3600.0
        }
        if (wins.isEmpty()) return@withContext Stops(emptyList(), totalKm, fuel, if (rangeKm != null) fullKm else null, fullGuess)
        val fuelIdx = meta.indices.filter { meta[it].first == "fuel" }

        // kraje w oknach tankowania (co ~8 km) → które źródła cen zapytać i w jakich punktach
        val perCountry = HashMap<String, ArrayList<Pair<Double, Double>>>()
        for (i in fuelIdx) {
            val w = wins[i]
            val lo = (w.targetM - w.backM).coerceAtLeast(0.0)
            val hi = (w.targetM + w.fwdM).coerceAtMost(total)
            var m = lo
            var lastCenter = -1e12
            while (m <= hi) {
                val k = cum.indexOfFirst { it >= m }.let { if (it < 0) n - 1 else it }
                val iso = Countries.at(ctx, lats[k], lons[k])
                if (iso != null && iso in StationPrices.stationCountries) {
                    val step = when (iso) { "DE" -> 40_000.0; "FR" -> 25_000.0; "IT" -> 18_000.0; "AT" -> 15_000.0; else -> 30_000.0 }
                    val list = perCountry.getOrPut(iso) { ArrayList() }
                    if (m - lastCenter >= step || list.isEmpty()) { list += lats[k] to lons[k]; lastCenter = m }
                }
                m += 8_000.0
            }
        }
        // równolegle: stacje z OSM (jedno zapytanie) + ceny na stacjach w krajach z danymi + kurs euro + średnie UE
        val (hits, priced, rate) = coroutineScope {
            val osm = async {
                AlongRoute.findAll(lats, lons, cum, wins, if (fuelIdx.isNotEmpty()) 8 else 3, RoutePreview.tomtomKey(ctx), fuelTag = StationPrices.osmTag(fuel))
            }
            val px = perCountry.map { (iso, centers) -> async { iso to StationPrices.stations(iso, fuel, centers, tkKey) } }
            val eur = async { if (fuelIdx.isNotEmpty()) StationPrices.eurPln(ctx) else null }
            val eu = async { if (fuelIdx.isNotEmpty()) EuFuel.refresh(ctx) }
            eu.await()
            Triple(osm.await(), px.awaitAll(), eur.await())
        }
        val plMax = FuelPrices.data.value?.today?.get(fuel)
        val deOk = priced.firstOrNull { it.first == "DE" }?.let { it.second != null }
        val allPx = priced.flatMap { it.second.orEmpty() }

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
                val list = opts.toMutableList()
                // ceny na stacjach: dopisz do stacji z OSM (do 150 m) albo dodaj stację, której w OSM nie było
                for (t2 in allPx) {
                    val (off, at) = AlongRoute.nearRoute(t2.lat, t2.lon, lats, lons, cum, lo, hi)
                    if (off > 1200 || at < lo - 500 || at > hi + 500) continue
                    val pln = if (t2.cur == "PLN") t2.price else rate?.let { t2.price * it }
                    val j = list.indexOfFirst { o -> o.src != "station" && RoutePreview.dist(LatLng(o.place.lat, o.place.lon), LatLng(t2.lat, t2.lon)) < 150 }
                    if (j >= 0) list[j] = list[j].copy(price = t2.price, cur = t2.cur, pln = pln, open = t2.open, src = "station")
                    else list += Opt(
                        CarData.Place(t2.name, "Stacja paliw", t2.lat, t2.lon), at / 1000, r.depart + (at / speed * 1000).toLong(), off, false,
                        price = t2.price, cur = t2.cur, pln = pln, open = t2.open, src = "station",
                    )
                }
                // bez ceny na stacji: Polska – cena maksymalna, reszta UE – średnia w kraju
                for (k in list.indices) {
                    val o = list[k]
                    val iso = Countries.at(ctx, o.place.lat, o.place.lon)
                    if (o.src == "station") { list[k] = o.copy(iso = iso); continue }
                    list[k] = when {
                        iso == "PL" && plMax != null -> o.copy(price = plMax, cur = "PLN", pln = plMax, src = "max", iso = iso)
                        else -> EuFuel.price(iso, fuel)?.let { e -> o.copy(price = e, cur = "EUR", pln = rate?.let { e * it }, src = "avg", iso = iso) } ?: o.copy(iso = iso)
                    }
                }
                // najtańsze (w złotych) pierwsze; przy tej samej cenie – cena ze stacji przed szacunkiem, tańsza sieć, lepiej położona
                val order = HashMap<Opt, Int>()
                list.forEachIndexed { k, o -> order.putIfAbsent(o, k) }
                opts = list.sortedWith(
                    compareBy<Opt>(
                        { if (it.open) 0 else 1 },
                        { it.pln?.let { p -> (p * 20).roundToInt() } ?: Int.MAX_VALUE }, // co 5 gr
                        { if (it.src == "station") 0 else 1 },
                        { if (it.cheap) 0 else 1 },
                        { order[it] ?: 999 },
                    ),
                ).take(4)
            }
            Group(kind, h, wins[i].targetM / 1000, opts, if (kind == "fuel") h else 1)
        }
        val needDe = perCountry.containsKey("DE") && tkKey.isBlank() && fuel != FuelPrices.Fuel.LPG
        val res = Stops(groups, totalKm, fuel, fullKm, fullGuess, needDe, deOk)
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
