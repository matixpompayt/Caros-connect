package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Zapisane miejsce parkowania, historia i wybrane auto (urządzenie Bluetooth radia). */
object Store {

    data class Spot(
        val lat: Double,
        val lon: Double,
        val time: Long,
        val accuracy: Float,
        val address: String? = null,
        val manual: Boolean = false,
        /** Skąd pochodzi: telefon (rozłączenie Bluetooth / ręcznie) albo radio CAR OS. */
        val src: String = Store.SRC_PHONE,
    ) {
        val mapsUrl get() = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)
        val coords get() = String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }

    const val SRC_PHONE = "phone"
    const val SRC_CAR = "car"

    val spot = MutableStateFlow<Spot?>(null)
    val pairing = MutableStateFlow<Link.Pairing?>(null)
    /** Pominięto skanowanie kodu (połączenie tylko przez Bluetooth). */
    val skippedPairing = MutableStateFlow(false)
    val history = MutableStateFlow<List<Spot>>(emptyList())
    val carMac = MutableStateFlow<String?>(null)
    val carName = MutableStateFlow<String?>(null)
    /** true = telefon jest połączony z autem i śledzi pozycję (usługa działa). */
    val driving = MutableStateFlow(false)
    /**
     * Telefon jest teraz połączony z radiem (Bluetooth). Dopóki tak jest, auto jest „w trasie” –
     * „zaparkowane” dopiero po rozłączeniu (wtedy DriveService zapisuje miejsce).
     */
    val linked = MutableStateFlow(false)

    /** Czy radio jest połączone z telefonem (null – nie wiadomo: brak uprawnień albo nie wybrano auta). */
    @android.annotation.SuppressLint("MissingPermission")
    fun btLinked(ctx: Context): Boolean? {
        val mac = carMac.value ?: return null
        return try {
            val d = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.firstOrNull { it.address.equals(mac, true) } ?: return false
            android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean
        } catch (_: Exception) {
            null
        }
    }

    /** Odśwież stan połączenia z autem; gdy połączone, a usługa jazdy nie działa – uruchom ją. */
    fun refreshLink(ctx: Context, startService: Boolean = false): Boolean {
        val bt = btLinked(ctx)
        val on = driving.value || bt == true
        linked.value = on
        // nie uruchamiaj ponownie zaraz po zapisaniu parkowania (radio bywa jeszcze chwilę połączone)
        if (startService && bt == true && !DriveService.running && System.currentTimeMillis() - (spot.value?.time ?: 0L) > 3 * 60_000L) {
            log(ctx, "Telefon połączony z radiem, usługa jazdy nie działała – uruchamiam")
            if (!DriveService.start(ctx)) log(ctx, "Nie udało się uruchomić usługi jazdy")
        }
        return on
    }
    /** Wygląd: "auto" (jak telefon), "light", "dark". */
    val theme = MutableStateFlow("auto")
    /** Ekran główny: "car" – auto 3D na pierwszym planie, "map" – mapa w tle z panelem od dołu. */
    val layout = MutableStateFlow("car")
    /** Minimalny zasięg paliwa (km) – zapas przy planowaniu tankowania na trasie. */
    val minRange = MutableStateFlow(100)
    /** Notatka i zdjęcie do bieżącego miejsca parkowania. */
    val spotNote = MutableStateFlow("")
    val spotPhoto = MutableStateFlow<String?>(null)

    private var sp: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("parking", Context.MODE_PRIVATE)
        sp = p
        carMac.value = p.getString("car_mac", null)
        loadLog()
        EventLog.init(ctx)
        EventLog.importOld(parkLog.value)
        carName.value = p.getString("car_name", null)
        theme.value = p.getString("theme", "auto") ?: "auto"
        layout.value = p.getString("layout", "car") ?: "car"
        minRange.value = p.getInt("min_range", 100)
        spotNote.value = p.getString("spot_note", "") ?: ""
        spotPhoto.value = p.getString("spot_photo", null)
        skippedPairing.value = p.getBoolean("skip_pair", false)
        pairing.value = p.getString("pairing", null)?.let { raw ->
            try {
                val o = JSONObject(raw)
                Link.Pairing(
                    android.util.Base64.decode(o.getString("k"), android.util.Base64.NO_WRAP),
                    o.optString("bt").ifBlank { null }, o.optString("car").ifBlank { null }, o.optString("drv").ifBlank { null },
                )
            } catch (_: Exception) { null }
        }
        spot.value = p.getString("spot", null)?.let { decode(it) }
        history.value = try {
            val a = JSONArray(p.getString("history", "[]"))
            (0 until a.length()).mapNotNull { decode(a.getString(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setCar(mac: String, name: String) {
        carMac.value = mac
        carName.value = name
        sp?.edit()?.putString("car_mac", mac)?.putString("car_name", name)?.apply()
    }

    fun setPairing(p: Link.Pairing?) {
        pairing.value = p
        val ed = sp?.edit() ?: return
        if (p == null) {
            ed.remove("pairing").putBoolean("skip_pair", false)
            skippedPairing.value = false
        } else {
            ed.putString(
                "pairing",
                JSONObject().put("k", android.util.Base64.encodeToString(p.key, android.util.Base64.NO_WRAP))
                    .put("bt", p.btName ?: "").put("car", p.car ?: "").put("drv", p.driver ?: "").toString(),
            )
        }
        ed.apply()
    }

    fun setMinRange(v: Int) {
        val x = v.coerceIn(20, 300)
        minRange.value = x
        sp?.edit()?.putInt("min_range", x)?.apply()
    }

    fun setTheme(v: String) {
        theme.value = v
        sp?.edit()?.putString("theme", v)?.apply()
    }

    fun setLayout(v: String) {
        layout.value = v
        sp?.edit()?.putString("layout", v)?.apply()
    }

    fun setNote(text: String) {
        spotNote.value = text
        notesAt()?.putString("spot_note", text)?.apply()
    }

    fun setPhoto(path: String?) {
        spotPhoto.value?.takeIf { it != path }?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
        spotPhoto.value = path
        notesAt()?.putString("spot_photo", path)?.apply()
    }

    /** Zapamiętuje, do którego miejsca (czas i pozycja) dodano notatkę / zdjęcie. */
    private fun notesAt(): SharedPreferences.Editor? {
        val s = spot.value
        return sp?.edit()?.putLong("notes_for", s?.time ?: 0L)?.putLong("notes_at", System.currentTimeMillis())
            ?.putString("notes_pos", s?.let { "${it.lat},${it.lon}" } ?: "")
    }

    /** Nowe miejsce parkowania → stara notatka i zdjęcie już nie pasują (chyba że to to samo miejsce). */
    private fun clearNotesIfNew(s: Spot) {
        val p = sp ?: return
        val forT = p.getLong("notes_for", 0L)
        if (forT == s.time) return
        if (spotNote.value.isBlank() && spotPhoto.value == null) return
        // to samo parkowanie zgłoszone drugi raz (np. przez radio) – zostaw notatkę
        val pos = p.getString("notes_pos", "")?.split(",")?.mapNotNull { it.toDoubleOrNull() }
        if (pos != null && pos.size == 2) {
            val d = FloatArray(1)
            android.location.Location.distanceBetween(pos[0], pos[1], s.lat, s.lon, d)
            if (d[0] < 150 && kotlin.math.abs(s.time - forT) < 45 * 60_000L) {
                p.edit().putLong("notes_for", s.time).apply()
                return
            }
        }
        // notatka dodana przed zapisem miejsca (np. ręcznie tuż przed) – zostaw, jeśli świeża
        if (forT == 0L && System.currentTimeMillis() - p.getLong("notes_at", 0L) < 10 * 60_000L) return
        spotPhoto.value?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
        spotNote.value = ""
        spotPhoto.value = null
        p.edit().remove("spot_note").remove("spot_photo").putLong("notes_for", s.time).apply()
    }

    fun skipPairing() {
        skippedPairing.value = true
        sp?.edit()?.putBoolean("skip_pair", true)?.apply()
    }

    /** Pozycja wysłana przez radio – przyjmij, jeśli nowsza niż zapisana. */
    /**
     * Miejsce z radia. [final] – zgaszenie auta / wyłączenie ekranu radia (prawdziwe parkowanie),
     * inaczej ostatni postój w trakcie jazdy (zapas, gdy telefonu nie było w aucie albo nie zdążył zapisać).
     */
    fun offerCarSpot(ctx: Context, s: Spot, final: Boolean = true) {
        init(ctx)
        val cur = spot.value
        if (cur != null && cur.time >= s.time) return
        if (cur != null && cur.src == SRC_CAR && cur.lat == s.lat && cur.lon == s.lon) return
        if (!final) {
            // telefon w aucie – postój na światłach to nie parkowanie; zapisze się po rozłączeniu z radiem
            if (driving.value || btLinked(ctx) == true) return
            // ostatni postój z radia – tylko jeśli jest już po jeździe (min. 3 min temu)
            if (System.currentTimeMillis() - s.time < 3 * 60_000L) return
        }
        log(ctx, "Radio: " + (if (final) "miejsce po zgaszeniu auta" else "ostatni postój auta (zapas)") + " – zapisane")
        EventLog.bump(EventLog.Metric.PARKS)
        clearNotesIfNew(s)
        spot.value = s
        history.value = (listOf(s) + history.value.filterNot { it.time == s.time }).take(20)
        persist()
        Widgets.updateAll(ctx.applicationContext)
    }

    // ---------- dziennik parkowania (diagnostyka) ----------
    val parkLog = MutableStateFlow<List<String>>(emptyList())
    private val LOG_FMT = java.text.SimpleDateFormat("d.MM HH:mm:ss", java.util.Locale.ROOT)

    @Synchronized
    fun log(ctx: Context, msg: String) {
        init(ctx)
        val line = LOG_FMT.format(java.util.Date()) + "  " + msg
        val list = (listOf(line) + parkLog.value).take(50)
        parkLog.value = list
        sp?.edit()?.putString("park_log", JSONArray(list).toString())?.apply()
        android.util.Log.i("CarOsConnect", msg)
        EventLog.add(EventLog.catOfParkLine(msg), msg, EventLog.isErrorText(msg))
        if (msg.startsWith("Połączono z radiem")) EventLog.bump(EventLog.Metric.DRIVES)
    }

    fun loadLog() {
        val raw = sp?.getString("park_log", null) ?: return
        parkLog.value = try { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } } catch (_: Exception) { emptyList() }
    }

    fun isCar(mac: String?): Boolean = mac != null && mac.equals(carMac.value, ignoreCase = true)

    /** Zapisuje nowe miejsce, dopisuje do historii i w tle ustala adres. */
    fun saveSpot(ctx: Context, s: Spot) {
        init(ctx)
        EventLog.bump(EventLog.Metric.PARKS)
        clearNotesIfNew(s)
        spot.value = s
        history.value = (listOf(s) + history.value.filterNot { it.time == s.time }).take(20)
        persist()
        val app = ctx.applicationContext
        Widgets.updateAll(app)
        if (!s.manual) Notifs.parked(app, s)
        scope.launch {
            val addr = Locator.address(app, s.lat, s.lon) ?: return@launch
            val cur = spot.value
            val updated = s.copy(address = addr)
            if (cur != null && cur.time == s.time) spot.value = updated
            history.value = history.value.map { if (it.time == s.time) updated else it }
            persist()
            Widgets.updateAll(app)
            if (cur != null && cur.time == s.time && !s.manual) Notifs.parked(app, updated)
        }
    }

    fun clearHistory() {
        history.value = listOfNotNull(spot.value)
        persist()
    }

    private fun persist() {
        val a = JSONArray()
        history.value.forEach { a.put(encode(it)) }
        sp?.edit()
            ?.putString("spot", spot.value?.let { encode(it) })
            ?.putString("history", a.toString())
            ?.apply()
    }

    private fun encode(s: Spot): String = JSONObject()
        .put("lat", s.lat).put("lon", s.lon).put("t", s.time).put("acc", s.accuracy.toDouble())
        .put("a", s.address ?: "").put("m", s.manual).put("src", s.src)
        .toString()

    private fun decode(raw: String): Spot? = try {
        val o = JSONObject(raw)
        Spot(
            o.getDouble("lat"), o.getDouble("lon"), o.getLong("t"), o.optDouble("acc", 0.0).toFloat(),
            o.optString("a").ifBlank { null }, o.optBoolean("m"), o.optString("src").ifBlank { SRC_PHONE },
        )
    } catch (_: Exception) {
        null
    }
}
