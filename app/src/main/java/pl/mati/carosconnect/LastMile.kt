package pl.mati.carosconnect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Ostatnia mila: CAR OS przy zaparkowaniu 150 m – 3 km od celu trasy dokłada cel do wiadomości „park”.
 * Telefon pokazuje powiadomienie i kartę w zakładce Auto – „Prowadź pieszo” chodnikami do celu.
 */
object LastMile {
    data class Offer(val name: String, val lat: Double, val lon: Double, val meters: Int, val parkTime: Long)

    /** Aktualna propozycja (do godziny po zaparkowaniu). */
    val offer = MutableStateFlow<Offer?>(null)

    fun minutes(m: Int): Int = (m / 1.3 / 60).roundToInt().coerceAtLeast(1)

    fun init(ctx: Context) {
        val sp = CarData.prefs ?: return
        val o = try { JSONObject(sp.getString("lastmile", null) ?: return) } catch (_: Exception) { return }
        val of = Offer(o.optString("n"), o.optDouble("lat"), o.optDouble("lon"), o.optInt("m"), o.optLong("t"))
        if (fresh(of)) offer.value = of
    }

    private fun fresh(o: Offer) = System.currentTimeMillis() - o.parkTime < 3600_000L

    /** Z wiadomości „park”: raz na postój – powiadomienie (gdy świeże). */
    fun offer(ctx: Context, o: Offer) {
        if (o.lat.isNaN() || o.lon.isNaN() || !fresh(o)) return
        val sp = CarData.prefs
        if ((sp?.getLong("lastmile_t", 0L) ?: 0L) == o.parkTime) { offer.value = o; return }
        sp?.edit()?.putLong("lastmile_t", o.parkTime)
            ?.putString("lastmile", JSONObject().put("n", o.name).put("lat", o.lat).put("lon", o.lon).put("m", o.meters).put("t", o.parkTime).toString())
            ?.apply()
        offer.value = o
        Notifs.lastMile(ctx, o)
        EventLog.add(EventLog.Cat.PARK, "Ostatnia mila: do celu ${o.name} ${o.meters} m pieszo")
    }

    fun dismiss() {
        offer.value = null
        CarData.prefs?.edit()?.remove("lastmile")?.apply()
    }

    fun isFresh(o: Offer) = fresh(o)
}
