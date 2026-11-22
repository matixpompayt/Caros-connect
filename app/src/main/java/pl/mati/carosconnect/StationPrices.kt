package pl.mati.carosconnect

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Ceny na konkretnych stacjach.
 *
 * Niemcy: Tankerkönig (oficjalne dane MTS-K, aktualne co kilka minut) – potrzebny darmowy klucz.
 * Polska: nie ma publicznego źródła cen na poszczególnych stacjach – jest tylko cena maksymalna / średnia
 * z [FuelPrices]; przy stacjach pokazujemy wtedy podpowiedź „zwykle tańsza sieć”.
 */
object StationPrices {
    class Tk(val name: String, val brand: String, val lat: Double, val lon: Double, val price: Double, val open: Boolean)

    val tkKey = MutableStateFlow("")
    private var inited = false
    private fun sp(ctx: Context) = ctx.getSharedPreferences("fuel_prices", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        tkKey.value = sp(ctx).getString("tk_key", "") ?: ""
    }

    fun setKey(ctx: Context, k: String) {
        val v = k.trim()
        tkKey.value = v
        sp(ctx).edit().putString("tk_key", v).apply()
    }

    /** Rodzaj paliwa dla Tankerkönig (nie ma tam LPG ani 98 – dla 98 bierzemy E5). */
    fun tkType(f: FuelPrices.Fuel): String? = when (f) {
        FuelPrices.Fuel.ON -> "diesel"
        FuelPrices.Fuel.PB95, FuelPrices.Fuel.PB98 -> "e5"
        FuelPrices.Fuel.LPG -> null
    }

    /** Tag OSM z rodzajem paliwa (do odsiania stacji, które go nie mają). */
    fun osmTag(f: FuelPrices.Fuel): String = when (f) {
        FuelPrices.Fuel.ON -> "diesel"
        FuelPrices.Fuel.PB95 -> "octane_95"
        FuelPrices.Fuel.PB98 -> "octane_98"
        FuelPrices.Fuel.LPG -> "lpg"
    }

    /**
     * Stacje z cenami wokół podanych punktów (promień do 25 km) – zapytania równolegle.
     * null, gdy wszystkie zapytania się nie udały (zły klucz, brak internetu).
     */
    suspend fun germany(key: String, fuel: FuelPrices.Fuel, centers: List<Pair<Double, Double>>, radKm: Int = 25): List<Tk>? {
        val type = tkType(fuel) ?: return emptyList()
        if (key.isBlank() || centers.isEmpty()) return emptyList()
        val res = coroutineScope {
            centers.map { (lat, lon) ->
                async(Dispatchers.IO) {
                    try {
                        val url = String.format(
                            Locale.US,
                            "https://creativecommons.tankerkoenig.de/json/list.php?lat=%.5f&lng=%.5f&rad=%d&sort=price&type=%s&apikey=%s",
                            lat, lon, radKm.coerceIn(1, 25), type, java.net.URLEncoder.encode(key, "UTF-8"),
                        )
                        val js = JSONObject(http(url))
                        if (!js.optBoolean("ok")) null
                        else {
                            val a = js.optJSONArray("stations")
                            val out = ArrayList<Tk>()
                            if (a != null) for (i in 0 until a.length()) {
                                val o = a.getJSONObject(i)
                                val p = o.opt("price")
                                val price = (p as? Number)?.toDouble() ?: continue
                                if (price <= 0.3) continue
                                val brand = o.optString("brand").trim()
                                val name = o.optString("name").trim()
                                val place = o.optString("place").trim()
                                val label = when {
                                    brand.isNotEmpty() && place.isNotEmpty() -> "$brand ${place.lowercase(Locale.GERMAN).replaceFirstChar { it.titlecase(Locale.GERMAN) }}"
                                    brand.isNotEmpty() -> brand
                                    else -> name.ifEmpty { "Tankstelle" }
                                }
                                out += Tk(label, brand, o.optDouble("lat"), o.optDouble("lng"), price, o.optBoolean("isOpen", true))
                            }
                            out
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        if (res.all { it == null }) return null
        return res.filterNotNull().flatten().distinctBy { "%.5f,%.5f".format(Locale.US, it.lat, it.lon) }
    }

    /** Sprawdzenie klucza (Berlin, 2 km). null = działa, inaczej komunikat błędu. */
    suspend fun test(key: String): String? = withContext(Dispatchers.IO) {
        try {
            val js = JSONObject(http("https://creativecommons.tankerkoenig.de/json/list.php?lat=52.52&lng=13.40&rad=2&sort=dist&type=all&apikey=" + java.net.URLEncoder.encode(key.trim(), "UTF-8")))
            if (js.optBoolean("ok")) null else js.optString("message").ifBlank { "klucz nie działa" }
        } catch (e: Exception) {
            e.message ?: "brak połączenia"
        }
    }

    private var eurRate: Double? = null
    private var eurAt = 0L

    /** Kurs EUR → PLN (średni NBP), zapamiętany na 12 h. */
    suspend fun eurPln(ctx: Context): Double? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        eurRate?.let { if (now - eurAt < 12 * 3600_000L) return@withContext it }
        val p = sp(ctx)
        try {
            val js = JSONObject(http("https://api.nbp.pl/api/exchangerates/rates/a/eur/?format=json"))
            val mid = js.getJSONArray("rates").getJSONObject(0).getDouble("mid")
            eurRate = mid; eurAt = now
            p.edit().putFloat("eur_pln", mid.toFloat()).apply()
            mid
        } catch (_: Exception) {
            p.getFloat("eur_pln", 0f).takeIf { it > 1f }?.toDouble()?.also { eurRate = it }
        }
    }

    /**
     * Sieci, które w Polsce zwykle mają tańsze paliwo niż duże marki (hipermarkety, sieci niezależne).
     * To tylko podpowiedź – nie cena.
     */
    private val cheapBrands = listOf(
        "moya", "amic", "avia", "huzar", "auchan", "carrefour", "leclerc", "intermarch", "kaufland",
        "watkem", "pieprzyk", "unimot", "costco", "makro",
    )

    fun cheapBrand(name: String): Boolean {
        val n = name.lowercase(Locale("pl"))
        return cheapBrands.any { n.contains(it) }
    }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "CAROSConnect/1.0 (Android; pl.mati.carosconnect)")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
