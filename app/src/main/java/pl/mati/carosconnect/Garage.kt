package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wiele aut z CAR OS. Każde auto ma swoje dane (miejsce parkowania, status z radia, trasy, kolejka
 * komend) w osobnych plikach ustawień; ustawienia aplikacji (wygląd, powiadomienia…) są wspólne.
 *
 * „Aktywne” auto pokazuje cała aplikacja ([Store], [CarData], [Outbox]); pozostałe obsługuje praca
 * w tle: przy połączeniu z ich radiem (Bluetooth) aplikacja sama przełącza się na to auto (zapis
 * parkowania jak zawsze), alarm „auto jedzie bez Ciebie” i powiadomienia działają dla wszystkich.
 */
object Garage {
    data class Car(val id: String, val name: String)

    /** Pierwsze auto – dane w starych plikach (bez przenoszenia po aktualizacji). */
    const val FIRST = "c1"

    val cars = MutableStateFlow<List<Car>>(emptyList())
    val active = MutableStateFlow(FIRST)
    /** Rośnie przy każdej zmianie listy / wyboru – np. czuwanie słucha wtedy nowych aut. */
    val version = MutableStateFlow(0)

    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("garage", Context.MODE_PRIVATE)
        sp = p
        val list = try {
            val a = JSONArray(p.getString("cars", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { Car(it.getString("id"), it.optString("n")) } }
        } catch (_: Exception) {
            emptyList()
        }
        cars.value = list.ifEmpty { listOf(Car(FIRST, "")) }
        active.value = p.getString("active", null)?.takeIf { id -> cars.value.any { it.id == id } } ?: cars.value.first().id
    }

    private fun save() {
        val a = JSONArray()
        cars.value.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name)) }
        sp?.edit()?.putString("cars", a.toString())?.putString("active", active.value)?.apply()
    }

    /** Plik ustawień auta: pierwsze auto – stare nazwy, kolejne – z dopiskiem „@id”. */
    fun file(base: String, id: String = active.value): String = if (id == FIRST) base else "$base@$id"

    fun prefs(ctx: Context, base: String, id: String? = null): SharedPreferences {
        init(ctx)
        return ctx.applicationContext.getSharedPreferences(file(base, id ?: active.value), Context.MODE_PRIVATE)
    }

    fun pairingOf(ctx: Context, id: String): Link.Pairing? =
        if (id == active.value) Store.pairing.value else Store.decodePairing(prefs(ctx, "parking", id).getString("pairing", null))

    fun macOf(ctx: Context, id: String): String? =
        if (id == active.value) Store.carMac.value else prefs(ctx, "parking", id).getString("car_mac", null)

    fun spotOf(ctx: Context, id: String): Store.Spot? =
        if (id == active.value) Store.spot.value else prefs(ctx, "parking", id).getString("spot", null)?.let { Store.decode(it) }

    /** Auto, do którego należy radio o tym adresie Bluetooth. */
    fun carForMac(ctx: Context, mac: String?): String? =
        mac?.let { m -> cars.value.firstOrNull { macOf(ctx, it.id)?.equals(m, ignoreCase = true) == true }?.id }

    /** Auta sparowane kodem QR (z kluczem przekaźnika). */
    fun paired(ctx: Context): List<Pair<Car, Link.Pairing>> = cars.value.mapNotNull { c -> pairingOf(ctx, c.id)?.let { c to it } }

    /** Nazwa do wyświetlenia: własna, z kodu QR (np. „Volkswagen Touran 2.0 TDI”), Bluetooth radia albo „Auto 2”. */
    fun label(ctx: Context, id: String): String {
        cars.value.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }?.let { return it }
        pairingOf(ctx, id)?.car?.takeIf { it.isNotBlank() }?.let { return it }
        val bt = if (id == active.value) Store.carName.value else prefs(ctx, "parking", id).getString("car_name", null)
        bt?.takeIf { it.isNotBlank() }?.let { return it }
        return "Auto ${cars.value.indexOfFirst { it.id == id } + 1}"
    }

    /** Numer auta na liście (do osobnych powiadomień). */
    fun index(id: String): Int = cars.value.indexOfFirst { it.id == id }.coerceAtLeast(0)

    /** Przełącz aktywne auto – cała aplikacja pokazuje jego dane. */
    fun select(ctx: Context, id: String) {
        if (id == active.value || cars.value.none { it.id == id }) return
        active.value = id
        save()
        reloadAll(ctx)
        EventLog.add(EventLog.Cat.SYNC, "Wybrane auto: ${label(ctx, id)}")
    }

    private fun reloadAll(ctx: Context) {
        val app = ctx.applicationContext
        Store.reload(app)
        CarData.reload(app)
        Outbox.reload(app)
        version.value++
        try { Guard.sync(app) } catch (_: Exception) {}
        try { Widgets.updateAll(app) } catch (_: Exception) {}
    }

    /** Nowe, puste auto (do sparowania kodem QR z jego radia) – od razu aktywne. Zwraca poprzednie. */
    fun add(ctx: Context): String {
        val prev = active.value
        val id = "c" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        cars.value = cars.value + Car(id, "")
        save()
        select(ctx, id)
        return prev
    }

    fun rename(id: String, name: String) {
        cars.value = cars.value.map { if (it.id == id) it.copy(name = name.trim()) else it }
        save()
        version.value++
    }

    /**
     * Usuń auto z telefonu (jego zapisane dane). Ostatniego auta nie da się usunąć – wtedy tylko
     * rozłączenie. Pierwsze auto dzieli pliki z ustawieniami aplikacji – czyścimy tylko dane auta.
     */
    fun remove(ctx: Context, id: String) {
        if (cars.value.size <= 1 || cars.value.none { it.id == id }) return
        val wasActive = id == active.value
        cars.value = cars.value.filterNot { it.id == id }
        if (wasActive) active.value = cars.value.first().id
        save()
        val app = ctx.applicationContext
        if (id == FIRST) {
            Store.clearCarKeys(prefs(app, "parking", id))
            CarData.clearCarKeys(prefs(app, "car", id))
            prefs(app, "outbox", id).edit().clear().apply()
        } else {
            for (b in listOf("parking", "car", "outbox")) {
                prefs(app, b, id).edit().clear().commit()
                try { app.deleteSharedPreferences(file(b, id)) } catch (_: Exception) {}
            }
        }
        try { java.io.File(app.filesDir, CarData.backupName(id)).delete() } catch (_: Exception) {}
        if (wasActive) reloadAll(app) else version.value++
    }

    /** Puste auto bez parowania (np. anulowane skanowanie kodu) – usuń i wróć do poprzedniego. */
    fun dropIfEmpty(ctx: Context, id: String, back: String) {
        if (pairingOf(ctx, id) != null || macOf(ctx, id) != null) return
        if (cars.value.size <= 1) return
        remove(ctx, id)
        select(ctx, back)
    }
}
