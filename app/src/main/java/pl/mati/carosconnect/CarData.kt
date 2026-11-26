package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import pl.mati.caros.core.Proto
import java.io.File

/** Dane z CAR OS w aucie (stan, trasy, kopia) + ustawienia przypomnień i parkometru. */
object CarData {

    data class Service(
        val name: String, val overdue: Boolean, val soon: Boolean, val known: Boolean,
        val kmLeft: Int?, val daysLeft: Int?, val text: String, val fraction: Float,
    )

    /** Miejsce; [kind] – punkt z planu podróży: "fuel" (stacja), "break" (postój), null – zwykły punkt. */
    data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val kind: String? = null)

    /** Dom / praca z CAR OS: adres + współrzędne (gdy znane). */
    data class Addr(val address: String, val lat: Double?, val lon: Double?) {
        val isSet get() = address.isNotBlank()
        fun place(label: String) = if (lat != null && lon != null) Place(label, address, lat, lon) else null
    }

    /** Pozycja serwisowa (surowe dane z radia – do edycji). */
    data class SvcItem(
        val id: String, val name: String,
        val intervalKm: Int,      // 0 = bez limitu km
        val intervalMonths: Int,  // 0 = bez limitu czasu
        val lastKm: Int,          // -1 = nieznane
        val lastDate: Long,       // 0 = nieznane
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("ik", intervalKm)
            .put("im", intervalMonths).put("lk", lastKm).put("ld", lastDate)
    }

    data class Status(
        val time: Long, val car: String, val driver: String, val odo: Int?,
        val todayM: Double, val weekM: Double, val monthM: Double,
        val service: List<Service>, val favs: List<Place>, val version: String,
        val items: List<SvcItem> = emptyList(),
        val odoSet: Boolean = odo != null,
        /** Zmiany z telefonu, których radio jeszcze nie potwierdziło. */
        val pending: Boolean = false,
        /** Ikona auta ustawiona w CAR OS (typ nadwozia, kolor). */
        val body: String? = null,
        val paint: Int? = null,
        val shifts: List<Shift> = emptyList(),
        val commuteS: Int? = null,
        val fuel: Fuel? = null,
        /** null = starszy CAR OS bez synchronizacji domu i pracy. */
        val home: Addr? = null,
        val work: Addr? = null,
        /** Ostatni pomiar akumulatora z CAR OS (null = brak / starszy CAR OS). */
        val bat: Battery? = null,
    ) {
        /** Lista serwisowa: liczona w telefonie z surowych danych (od razu po edycji), inaczej z radia. */
        val serviceList: List<Service>
            get() = if (items.isEmpty()) service else ServiceCalc.list(items, odo, odoSet)
    }

    data class Trip(
        val start: Long, val end: Long, val distanceM: Double, val movingS: Long, val maxSpeed: Int, val path: String?,
    ) {
        val durationS get() = (end - start) / 1000
        val avgKmh get() = if (movingS > 0) distanceM / movingS * 3.6 else 0.0
    }

    /** Akumulator: napięcia w V, level 0 = dobrze, 1 = uwaga, 2 = słaby. */
    data class Battery(val time: Long, val rest: Double?, val crank: Double?, val charge: Double?, val level: Int, val msg: String)

    /** Auto w drodze: pozycja co minutę od CAR OS. */
    data class Live(val lat: Double, val lon: Double, val speed: Int, val bearing: Int, val time: Long, val moving: Boolean)

    /** Zmiana z grafiku (z radia) – do przypomnień. */
    data class Shift(val start: Long, val end: Long, val name: String, val hours: String)

    /** Tankowanie (te same dane co w radiu). */
    data class Fill(val id: String, val time: Long, val odo: Int, val liters: Double, val cost: Double, val full: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("t", time).put("o", odo).put("l", liters).put("c", cost).put("f", full)
    }

    /** [fromCan] – litry w baku z czujnika auta (dekoder CAN radia), nie szacunek z tankowań. */
    data class Fuel(
        val tank: Int, val fills: List<Fill>, val avg: Double?, val range: Double?, val costKm: Double?, val estimated: Boolean,
        val left: Double? = null, val fromCan: Boolean = false,
        /** Korekta zasięgu z licznikiem (mnożnik, 1 = bez), zasięg przed korektą, zasięg podany przez samo auto. */
        val rk: Double = 1.0, val rangeRaw: Double? = null, val rangeFromCar: Boolean = false,
    )

    val live = MutableStateFlow<Live?>(null)

    fun setLive(l: Live) {
        val cur = live.value
        if (cur != null && cur.time >= l.time) return
        live.value = l
    }

    data class Weather(val time: Long, val temp: Double, val minPast: Double, val humidity: Int, val frost: Boolean)

    val status = MutableStateFlow<Status?>(null)
    val trips = MutableStateFlow<List<Trip>>(emptyList())
    val backupTime = MutableStateFlow(0L)
    val backupCar = MutableStateFlow("")
    val weather = MutableStateFlow<Weather?>(null)
    val lastSync = MutableStateFlow(0L)

    /** Parkometr: koniec opłaconego czasu (0 = wyłączony) i początek. */
    val meterEnd = MutableStateFlow(0L)
    val meterStart = MutableStateFlow(0L)

    /** Model auta z CAR OS (typ nadwozia i kolor) – ten sam co na mapie w radiu. */
    val carBody = MutableStateFlow<String?>(null)
    val carPaint = MutableStateFlow<Int?>(null)

    val notifService = MutableStateFlow(true)
    val notifBattery = MutableStateFlow(true)
    val notifLive = MutableStateFlow(true)
    val notifFrost = MutableStateFlow(true)
    val notifWork = MutableStateFlow(true)
    val notifDepart = MutableStateFlow(true)
    val frostHour = MutableStateFlow(6)

    private var sp: SharedPreferences? = null
    private var dir: File? = null

    /** Kontekst aplikacji – do powiadomień ze statusu (np. link jazdy na żywo z radia). */
    private var appCtx: Context? = null

    /** Ustawienia powiadomień – wspólne dla wszystkich aut. */
    private var g: SharedPreferences? = null
    private val GLOBAL = setOf("n_service", "n_battery", "n_live", "n_frost", "n_work", "n_depart", "frost_hour")

    fun init(ctx: Context) {
        if (sp != null) return
        Garage.init(ctx)
        appCtx = ctx.applicationContext
        dir = ctx.applicationContext.filesDir
        val gp = ctx.applicationContext.getSharedPreferences("car", Context.MODE_PRIVATE)
        g = gp
        notifService.value = gp.getBoolean("n_service", true)
        notifBattery.value = gp.getBoolean("n_battery", true)
        notifLive.value = gp.getBoolean("n_live", true)
        notifFrost.value = gp.getBoolean("n_frost", true)
        notifWork.value = gp.getBoolean("n_work", true)
        notifDepart.value = gp.getBoolean("n_depart", true)
        frostHour.value = gp.getInt("frost_hour", 6)
        loadCar(Garage.prefs(ctx, "car"))
    }

    /** Inne auto wybrane – wczytaj jego dane. */
    fun reload(ctx: Context) {
        if (g == null) { init(ctx); return }
        live.value = null
        weather.value = null
        nack.value = null
        car.value = null
        loadCar(Garage.prefs(ctx, "car"))
    }

    /** Usunięcie auta z pierwszego pliku: dane auta precz, ustawienia powiadomień zostają. */
    fun clearCarKeys(p: SharedPreferences) {
        val ed = p.edit()
        p.all.keys.filter { it !in GLOBAL }.forEach { ed.remove(it) }
        ed.apply()
    }

    /** Plik kopii ustawień radia danego auta. */
    fun backupName(id: String) = if (id == Garage.FIRST) "caros-backup.json" else "caros-backup@$id.json"

    /** Status z radia (tekst JSON) → dane do wyświetlenia (też dla innych aut, w tle). */
    fun parse(raw: String): Status? = parseStatus(raw)

    private fun loadCar(p: SharedPreferences) {
        sp = p
        status.value = p.getString("status", null)?.let { parseStatus(it) }
        p.getString("status", null)?.let { raw -> try { car.value = Proto.carFromStatus(JSONObject(raw)) } catch (_: Exception) {} }
        nack.value = p.getString("nack", null)?.ifBlank { null }
        trips.value = try {
            val a = JSONArray(p.getString("trips", "[]"))
            (0 until a.length()).map { tripFrom(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
        backupTime.value = p.getLong("backup_time", 0L)
        backupCar.value = p.getString("backup_car", "") ?: ""
        lastSync.value = p.getLong("last_sync", 0L)
        meterEnd.value = p.getLong("meter_end", 0L)
        meterStart.value = p.getLong("meter_start", 0L)
        carBody.value = p.getString("car_body", null)
        carPaint.value = if (p.contains("car_paint")) p.getInt("car_paint", 0) else null
        p.getString("weather", null)?.let {
            try {
                val o = JSONObject(it)
                weather.value = Weather(o.getLong("t"), o.getDouble("c"), o.getDouble("m"), o.getInt("h"), o.getBoolean("f"))
            } catch (_: Exception) {}
        }
    }

    val prefs: SharedPreferences? get() = sp

    // ---------- stan auta ----------

    /** CAR OS w aucie: wersja protokołu, wersja, obsługiwane komendy (z ostatniego statusu). */
    val car = MutableStateFlow<Proto.Peer?>(null)
    /** Komenda, której CAR OS w aucie nie zna (odpowiedź „nack”) – do komunikatu „zaktualizuj CAR OS”. */
    val nack = MutableStateFlow<String?>(null)

    /** Czy CAR OS w aucie obsługuje [cap] (gdy nie wiadomo – zakładamy, że tak). */
    fun carSupports(cap: String): Boolean = car.value?.supports(cap) ?: true

    fun setNack(type: String?) {
        nack.value = type
        sp?.edit()?.putString("nack", type ?: "")?.apply()
    }

    fun setStatusJson(raw: String) {
        val s = parseStatus(raw) ?: return
        val cur = status.value
        if (cur != null && cur.time > s.time) return
        status.value = s
        try {
            val js = JSONObject(raw)
            val peer = Proto.carFromStatus(js)
            // auto zaktualizowane – stary „nie zna komendy” już nieaktualny
            if (car.value?.app != peer.app) setNack(null)
            car.value = peer
            // wspólny wygląd: wygląd z radia
            LookSync.onCar(pl.mati.caros.core.Look.fromJson(js.optJSONObject("look")))
            // jazda na żywo przez link (włączona w radiu albo z telefonu)
            if (peer.supports(Proto.Cmd.SHARE)) TripShare.onStatus(appCtx, js.optJSONObject("share"))
        } catch (_: Exception) {}
        sp?.edit()?.putString("status", raw)?.apply()
        if (s.body != null) setCarIcon(s.body, s.paint)
        // trasy ze statusu (bez śladu) – uzupełniają dziennik
        try {
            val a = JSONObject(raw).optJSONArray("trips") ?: JSONArray()
            mergeTrips((0 until a.length()).map { tripFrom(a.getJSONObject(it)) })
        } catch (_: Exception) {}
    }

    /**
     * Edycja serwisu w telefonie: zmiana od razu widoczna (i zapamiętana), oznaczona jako „czeka na radio”.
     * Nowszy stan z radia (po zastosowaniu zmiany) ją zastąpi.
     */
    fun editLocal(change: (JSONObject) -> Unit) {
        val raw = sp?.getString("status", null) ?: return
        try {
            val o = JSONObject(raw)
            change(o)
            o.put("t", System.currentTimeMillis()).put("pending", true)
            val str = o.toString()
            status.value = parseStatus(str) ?: return
            sp?.edit()?.putString("status", str)?.apply()
        } catch (_: Exception) {
        }
    }

    fun editItemLocal(item: SvcItem) = editLocal { o ->
        val a = o.optJSONArray("items") ?: return@editLocal
        for (i in 0 until a.length()) if (a.getJSONObject(i).getString("id") == item.id) a.put(i, item.toJson())
    }

    fun markDoneLocal(id: String) = editLocal { o ->
        val a = o.optJSONArray("items") ?: return@editLocal
        val km = if (o.isNull("odo")) -1 else o.optInt("odo", -1)
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (x.getString("id") == id) x.put("lk", km).put("ld", System.currentTimeMillis())
        }
    }

    fun setOdoLocal(km: Int) = editLocal { o -> o.put("odo", km).put("odoSet", true) }

    fun addFillLocal(f: Fill) = editLocal { o ->
        val fuel = o.optJSONObject("fuel") ?: JSONObject().put("tank", 60).also { o.put("fuel", it) }
        val a = fuel.optJSONArray("fills") ?: JSONArray()
        val n = JSONArray().put(f.toJson())
        for (i in 0 until a.length()) n.put(a.get(i))
        fuel.put("fills", n)
    }

    /** Korekta zasięgu zmieniona w telefonie (zaraz potwierdzi ją CAR OS): zasięg = przed korektą × [k]. */
    fun setRangeKLocal(k: Double) = editLocal { o ->
        val fuel = o.optJSONObject("fuel") ?: return@editLocal
        val raw = if (fuel.has("rraw") && !fuel.isNull("rraw")) fuel.optDouble("rraw") else null
        fuel.put("rk", k)
        if (raw != null && !fuel.optBoolean("rcar")) fuel.put("range", raw * k)
    }

    /** Pojemność baku zmieniona w telefonie (zaraz potwierdzi ją CAR OS). */
    fun setTankLocal(l: Int) = editLocal { o ->
        val fuel = o.optJSONObject("fuel") ?: JSONObject().also { o.put("fuel", it) }
        fuel.put("tank", l.coerceIn(20, 150))
    }

    /** Ile litrów zostało w baku: zasięg × spalanie (jak liczy CAR OS). */
    fun litersLeft(f: Fuel): Double? {
        f.left?.let { return it.coerceIn(0.0, f.tank.toDouble().coerceAtLeast(1.0)) }
        // litry z zasięgu przed korektą (korekta dopasowuje tylko kilometry do licznika)
        val r = f.rangeRaw ?: f.range?.let { it / f.rk } ?: return null
        val use = f.avg?.takeIf { it in 2.0..30.0 } ?: 7.5
        return (r * use / 100).coerceIn(0.0, f.tank.toDouble().coerceAtLeast(1.0))
    }

    fun removeFillLocal(id: String) = editLocal { o ->
        val fuel = o.optJSONObject("fuel") ?: return@editLocal
        val a = fuel.optJSONArray("fills") ?: return@editLocal
        val n = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") != id) n.put(a.get(i))
        fuel.put("fills", n)
    }

    fun setPlaceLocal(work: Boolean, a: Addr) = editLocal { o ->
        o.put(if (work) "work" else "home", addrJson(a))
    }

    fun addrJson(a: Addr): JSONObject =
        JSONObject().put("a", a.address).put("lat", a.lat ?: JSONObject.NULL).put("lon", a.lon ?: JSONObject.NULL)

    private fun addrFrom(o: JSONObject?): Addr? = o?.let {
        Addr(
            it.optString("a"),
            if (it.isNull("lat")) null else it.optDouble("lat"),
            if (it.isNull("lon")) null else it.optDouble("lon"),
        )
    }

    /** Ulubione usunięte w telefonie – od razu znikają z listy (radio potwierdzi w następnym statusie). */
    fun removeFavLocal(p: Place) {
        val s = status.value ?: return
        status.value = s.copy(favs = s.favs.filterNot { it.lat == p.lat && it.lon == p.lon })
    }

    fun addFavLocal(p: Place) {
        val s = status.value ?: return
        if (s.favs.any { it.lat == p.lat && it.lon == p.lon }) return
        status.value = s.copy(favs = s.favs + p)
    }

    private fun parseStatus(raw: String): Status? = try {
        val o = JSONObject(raw)
        val svc = o.optJSONArray("svc") ?: JSONArray()
        val favs = o.optJSONArray("favs") ?: JSONArray()
        Status(
            time = o.getLong("t"),
            car = o.optString("car"),
            driver = o.optString("drv"),
            odo = if (o.isNull("odo")) null else o.optInt("odo"),
            todayM = o.optDouble("today", 0.0),
            weekM = o.optDouble("week", 0.0),
            monthM = o.optDouble("month", 0.0),
            service = (0 until svc.length()).map { i ->
                val s = svc.getJSONObject(i)
                Service(
                    s.optString("n"), s.optBoolean("o"), s.optBoolean("s"), s.optBoolean("k"),
                    if (s.isNull("km")) null else s.optInt("km"),
                    if (s.isNull("d")) null else s.optInt("d"),
                    s.optString("x"), s.optDouble("f", 0.0).toFloat(),
                )
            },
            favs = (0 until favs.length()).map { i ->
                val f = favs.getJSONObject(i)
                Place(f.optString("n"), f.optString("d"), f.getDouble("lat"), f.getDouble("lon"))
            },
            version = o.optString("ver"),
            items = (o.optJSONArray("items") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    SvcItem(x.getString("id"), x.optString("n"), x.optInt("ik"), x.optInt("im"), x.optInt("lk", -1), x.optLong("ld"))
                }
            },
            odoSet = if (o.has("odoSet")) o.optBoolean("odoSet") else !o.isNull("odo"),
            pending = o.optBoolean("pending"),
            body = o.optString("body").ifBlank { null },
            paint = if (o.has("paint")) o.optInt("paint") else null,
            shifts = (o.optJSONArray("shifts") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i ->
                    val x = a.getJSONObject(i)
                    Shift(x.getLong("s"), x.getLong("e"), x.optString("n"), x.optString("h"))
                }
            },
            commuteS = if (o.isNull("commute") || !o.has("commute")) null else o.optInt("commute"),
            fuel = o.optJSONObject("fuel")?.let { f ->
                val a = f.optJSONArray("fills") ?: JSONArray()
                Fuel(
                    f.optInt("tank", 60),
                    (0 until a.length()).map { i ->
                        val x = a.getJSONObject(i)
                        Fill(x.optString("id"), x.optLong("t"), x.optInt("o"), x.optDouble("l"), x.optDouble("c"), x.optBoolean("f", true))
                    },
                    if (f.isNull("avg")) null else f.optDouble("avg"),
                    // radio nie przysłało zasięgu albo tankowanie dodane w telefonie czeka na radio – liczymy sami
                    (if (f.isNull("range") || o.optBoolean("pending")) null else f.optDouble("range"))
                        ?: localRange(f, if (o.isNull("odo")) null else o.optInt("odo")),
                    if (f.isNull("ckm")) null else f.optDouble("ckm"),
                    f.optBoolean("est", true),
                    // litry w baku z radia (nowszy CAR OS); po zmianie w telefonie – liczymy sami
                    if (!f.has("left") || f.isNull("left") || o.optBoolean("pending")) null else f.optDouble("left"),
                    f.optString("src") == "can",
                    f.optDouble("rk", 1.0).takeIf { it in 0.5..1.5 } ?: 1.0,
                    if (!f.has("rraw") || f.isNull("rraw") || o.optBoolean("pending")) null else f.optDouble("rraw"),
                    f.optBoolean("rcar"),
                )
            },
            home = addrFrom(o.optJSONObject("home")),
            work = addrFrom(o.optJSONObject("work")),
            bat = o.optJSONObject("bat")?.let { b ->
                Battery(
                    b.optLong("t"),
                    if (b.isNull("r")) null else b.optDouble("r"),
                    if (b.isNull("k")) null else b.optDouble("k"),
                    if (b.isNull("c")) null else b.optDouble("c"),
                    b.optInt("l"), b.optString("m"),
                )
            },
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Zasięg jak w CAR OS: pełny bak przy ostatnim tankowaniu „do pełna” + dolane później
     * − spalone wg przebiegu (średnie spalanie z radia albo domyślne).
     */
    private fun localRange(f: JSONObject, odo: Int?): Double? {
        val a = f.optJSONArray("fills") ?: return null
        val fills = (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { it.optInt("o") }
        val lastFull = fills.lastOrNull { it.optBoolean("f", true) && it.optInt("o") > 0 } ?: return null
        val tank = f.optInt("tank", 60).toDouble()
        val use = (if (!f.isNull("use")) f.optDouble("use") else if (!f.isNull("avg")) f.optDouble("avg") else 7.5)
            .takeIf { it in 2.0..30.0 } ?: 7.5
        val lfOdo = lastFull.optInt("o")
        val now = odo?.takeIf { it >= lfOdo } ?: fills.maxOf { it.optInt("o") }
        val added = fills.filter { it.optInt("o") > lfOdo }.sumOf { it.optDouble("l", 0.0) }
        val left = (tank + added - (now - lfOdo) * use / 100).coerceIn(0.0, tank)
        return left / use * 100 * (f.optDouble("rk", 1.0).takeIf { it in 0.5..1.5 } ?: 1.0)
    }

    // ---------- trasy ----------

    fun tripFrom(o: JSONObject) = Trip(
        o.getLong("s"), o.getLong("e"), o.getDouble("d"), o.optLong("m"), o.optInt("v"),
        o.optString("p").ifBlank { null },
    )

    fun tripJson(t: Trip): JSONObject = JSONObject()
        .put("s", t.start).put("e", t.end).put("d", t.distanceM).put("m", t.movingS).put("v", t.maxSpeed).put("p", t.path ?: "")

    /** Trasy innego auta (w tle) – prosto do jego pliku. */
    fun mergeTripsInto(p: SharedPreferences, incoming: List<Trip>) {
        if (incoming.isEmpty()) return
        val map = LinkedHashMap<Long, Trip>()
        try {
            val a = JSONArray(p.getString("trips", "[]"))
            for (i in 0 until a.length()) tripFrom(a.getJSONObject(i)).let { map[it.start] = it }
        } catch (_: Exception) {
        }
        var changed = false
        for (t in incoming) {
            val old = map[t.start]
            val merged = if (old == null) t else t.copy(path = t.path ?: old.path)
            if (old != merged) { map[t.start] = merged; changed = true }
        }
        if (!changed) return
        val a = JSONArray()
        map.values.sortedByDescending { it.start }.take(200).forEach { a.put(tripJson(it)) }
        p.edit().putString("trips", a.toString()).apply()
    }

    fun mergeTrips(incoming: List<Trip>) {
        if (incoming.isEmpty()) return
        val map = LinkedHashMap<Long, Trip>()
        trips.value.forEach { map[it.start] = it }
        var changed = false
        for (t in incoming) {
            val old = map[t.start]
            val merged = if (old == null) t else t.copy(path = t.path ?: old.path)
            if (old != merged) { map[t.start] = merged; changed = true }
        }
        if (!changed) return
        val list = map.values.sortedByDescending { it.start }.take(200)
        trips.value = list
        val a = JSONArray()
        list.forEach { a.put(tripJson(it)) }
        sp?.edit()?.putString("trips", a.toString())?.apply()
    }

    // ---------- kopia ustawień ----------

    private fun backupFile() = dir?.let { File(it, backupName(Garage.active.value)) }

    fun setBackup(text: String, created: Long, car: String) {
        if (created <= backupTime.value) return
        try { backupFile()?.writeText(text) } catch (_: Exception) { return }
        backupTime.value = created
        backupCar.value = car
        sp?.edit()?.putLong("backup_time", created)?.putString("backup_car", car)?.apply()
    }

    fun backupText(): String? = try { backupFile()?.takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    // ---------- pogoda przy aucie ----------

    fun setWeather(w: Weather) {
        weather.value = w
        sp?.edit()?.putString(
            "weather",
            JSONObject().put("t", w.time).put("c", w.temp).put("m", w.minPast).put("h", w.humidity).put("f", w.frost).toString(),
        )?.apply()
    }

    fun setCarIcon(body: String, paint: Int?) {
        if (carBody.value == body && carPaint.value == paint) return
        carBody.value = body
        carPaint.value = paint
        sp?.edit()?.putString("car_body", body)?.apply { if (paint != null) putInt("car_paint", paint) }?.apply()
    }

    fun setLastSync(t: Long) {
        lastSync.value = t
        sp?.edit()?.putLong("last_sync", t)?.apply()
    }

    // ---------- ustawienia ----------

    fun setMeter(start: Long, end: Long) {
        meterStart.value = start
        meterEnd.value = end
        sp?.edit()?.putLong("meter_start", start)?.putLong("meter_end", end)?.apply()
    }

    fun setNotifLive(v: Boolean) { notifLive.value = v; g?.edit()?.putBoolean("n_live", v)?.apply() }
    fun setNotifBattery(v: Boolean) { notifBattery.value = v; g?.edit()?.putBoolean("n_battery", v)?.apply() }
    fun setNotifService(v: Boolean) { notifService.value = v; g?.edit()?.putBoolean("n_service", v)?.apply() }
    fun setNotifWork(v: Boolean) { notifWork.value = v; g?.edit()?.putBoolean("n_work", v)?.apply() }
    fun setNotifDepart(v: Boolean) { notifDepart.value = v; g?.edit()?.putBoolean("n_depart", v)?.apply() }
    fun setNotifFrost(v: Boolean) { notifFrost.value = v; g?.edit()?.putBoolean("n_frost", v)?.apply() }
    fun setFrostHour(v: Int) { frostHour.value = (v + 24) % 24; g?.edit()?.putInt("frost_hour", frostHour.value)?.apply() }
}
