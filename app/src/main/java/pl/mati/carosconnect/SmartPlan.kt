package pl.mati.carosconnect

import android.content.Context
import pl.mati.caros.core.AlongRoute
import pl.mati.caros.core.RoutePlan
import pl.mati.caros.core.StationPrices
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

    /**
     * Postoje: tankowanie – tyle razy, ile trzeba (np. 1000 km), zanim zasięg spadnie poniżej minimum z ustawień,
     * zakładając tankowanie do pełna; przerwa co ok. 2 h jazdy (jeśli nie wypada przy tankowaniu).
     * Liczone we wspólnym module (core/RoutePlan) – tak samo w CAR OS w aucie. Najtańsze (w złotych) pierwsze.
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
        val res = RoutePlan.stops(
            ctx, lats, lons, r.travelS, r.depart, rangeKm, reserveKm, fuel.type, tkKey, RoutePreview.tomtomKey(ctx),
            fullKmCar, FuelPrices.data.value?.today?.get(fuel),
        )
        Stops(
            res.groups.map { g ->
                Group(g.kind, g.hours, g.atKm, g.options.map { o ->
                    Opt(
                        CarData.Place(o.place.name, o.place.detail, o.place.lat, o.place.lon), o.atKm, o.atTime, o.offM, o.rest,
                        o.price, o.cur, o.pln, o.cheap, o.open, o.src, o.iso,
                    )
                }, g.n)
            },
            res.totalKm, fuel, res.fullKm, res.fullGuess, res.deKeyMissing, res.deOk,
        )
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
