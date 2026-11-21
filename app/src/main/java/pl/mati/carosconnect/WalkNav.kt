package pl.mati.carosconnect

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Nawigacja piesza do auta w CAR OS Connect (zamiast Map Google): trasa pieszo (OSRM – profil pieszy),
 * mapa 3D z autem i ludzikiem, kamera za Tobą w kierunku, w którym patrzysz, wskazówki po polsku
 * („Skręć w lewo w ul. …”), odległość i czas, głos, nowa trasa po zejściu z drogi, wibracja przy aucie.
 */
object WalkNav {
    val active = MutableStateFlow(false)

    data class Step(val idx: Int, val type: String, val modifier: String, val name: String, val lat: Double, val lon: Double, val instr: String = "")
    data class Route(val pts: List<LatLng>, val cum: DoubleArray, val steps: List<Step>) {
        val length get() = cum.last()
    }

    fun start() {
        if (Store.spot.value != null) active.value = true
    }

    fun stop() {
        active.value = false
    }

    /** Intencja: otwórz aplikację od razu w nawigacji do auta (powiadomienie, widżet). */
    fun intent(ctx: Context): Intent =
        Intent(ctx, MainActivity::class.java).putExtra("walk_to_car", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun googleMaps(ctx: Context, s: Store.Spot) {
        try { ctx.startActivity(Notifs.walkIntent(s)) } catch (_: Exception) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.mapsUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }

    // ---------- trasa ----------

    /** Skąd jest trasa – do napisu na ekranie. */
    val source = MutableStateFlow("")

    /**
     * Trasa piesza: najpierw Valhalla (profil pieszy, woli chodniki i ścieżki dla pieszych, przejścia,
     * omija podjazdy i zaułki), a gdy nie odpowie – OSRM z profilem pieszym.
     */
    /** Ostatni błąd wyznaczania trasy (pokazywany, gdy trasy nie ma). */
    val error = MutableStateFlow("")

    suspend fun route(from: Location, to: Store.Spot): Route? = withContext(Dispatchers.IO) {
        var e1 = ""
        var e2 = ""
        val r = (try { valhalla(from, to) } catch (e: Exception) { e1 = e.message ?: e.javaClass.simpleName; null })?.also { source.value = "chodnikami" }
            ?: (try { osrm(from, to) } catch (e: Exception) { e2 = e.message ?: e.javaClass.simpleName; null })?.also { source.value = "pieszo" }
        error.value = if (r == null) "Valhalla: $e1 · OSRM: $e2" else ""
        r
    }

    private const val UA = "CAROSConnect/1.0 (Android phone companion app for CAR OS; pl.mati.carosconnect)"

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun valhalla(from: Location, to: Store.Spot): Route? {
        val ped = JSONObject()
            .put("walkway_factor", 0.6)      // ścieżki i chodniki wydzielone – chętniej
            .put("sidewalk_factor", 0.6)     // ulice z chodnikiem – chętniej
            .put("alley_factor", 2.0)        // zaułki – niechętnie
            .put("driveway_factor", 5.0)     // podjazdy / prywatne – prawie nigdy
            .put("use_living_streets", 0.6)
            .put("use_tracks", 0.3)
            .put("service_penalty", 20)
        val req = JSONObject()
            .put("locations", org.json.JSONArray()
                .put(JSONObject().put("lat", from.latitude).put("lon", from.longitude))
                .put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", "pedestrian")
            .put("costing_options", JSONObject().put("pedestrian", ped))
            .put("language", "pl-PL")
            .put("units", "kilometers")
        val js = JSONObject(http("https://valhalla1.openstreetmap.de/route?json=" + java.net.URLEncoder.encode(req.toString(), "UTF-8")))
        val legs = js.getJSONObject("trip").getJSONArray("legs")
        val pts = ArrayList<LatLng>()
        val steps = ArrayList<Step>()
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val base = pts.size
            pts += decode6(leg.getString("shape"))
            if (pts.isEmpty()) continue
            val ms = leg.getJSONArray("maneuvers")
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                val (type, mod) = vType(m.optInt("type"))
                val idx = (base + m.optInt("begin_shape_index")).coerceIn(0, pts.lastIndex)
                val name = m.optJSONArray("street_names")?.optString(0).orEmpty()
                val instr = m.optString("instruction").trim().removeSuffix(".")
                steps += Step(idx, type, mod, name, pts[idx].latitude, pts[idx].longitude, instr)
            }
        }
        return build(pts, steps)
    }

    /** Typ manewru Valhalli → typ / kierunek jak w OSRM (strzałka, głos). */
    private fun vType(t: Int): Pair<String, String> = when (t) {
        1, 2, 3 -> "depart" to ""
        4, 5, 6 -> "arrive" to ""
        9, 20, 18 -> "turn" to "slight right"
        10 -> "turn" to "right"
        11 -> "turn" to "sharp right"
        12, 13 -> "turn" to "uturn"
        14 -> "turn" to "sharp left"
        15 -> "turn" to "left"
        16, 21, 19 -> "turn" to "slight left"
        23 -> "fork" to "slight right"
        24 -> "fork" to "slight left"
        26, 27 -> "roundabout" to ""
        else -> "continue" to "straight"
    }

    private fun osrm(from: Location, to: Store.Spot): Route? {
        val url = String.format(
            Locale.US,
            "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
            from.longitude, from.latitude, to.lon, to.lat,
        )
        val r = JSONObject(http(url)).getJSONArray("routes").getJSONObject(0)
        val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until coords.length()).map { val p = coords.getJSONArray(it); LatLng(p.getDouble(1), p.getDouble(0)) }
        if (pts.size < 2) return null
        val steps = ArrayList<Step>()
        val legs = r.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val st = legs.getJSONObject(l).getJSONArray("steps")
            for (i in 0 until st.length()) {
                val s = st.getJSONObject(i)
                val m = s.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                val lat = loc.getDouble(1); val lon = loc.getDouble(0)
                steps += Step(nearestIdx(pts, lat, lon), m.optString("type"), m.optString("modifier"), s.optString("name"), lat, lon)
            }
        }
        return build(pts, steps)
    }

    private fun build(pts: List<LatLng>, steps: List<Step>): Route? {
        if (pts.size < 2) return null
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + dist(pts[i - 1].latitude, pts[i - 1].longitude, pts[i].latitude, pts[i].longitude)
        return Route(pts, cum, steps)
    }

    /** Polilinia z dokładnością 6 miejsc (Valhalla). */
    private fun decode6(s: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += d else lon += d
            }
            out += LatLng(lat / 1e6, lon / 1e6)
        }
        return out
    }

    /** Pozostała część trasy od Twojego miejsca (rzut na trasę) do końca. */
    fun remainingLine(r: Route, sn: Snap): List<LatLng> {
        val i = sn.seg.coerceIn(0, r.pts.size - 2)
        val a = r.pts[i]; val b = r.pts[i + 1]
        val segLen = r.cum[i + 1] - r.cum[i]
        val t = if (segLen < 0.01) 0.0 else ((sn.along - r.cum[i]) / segLen).coerceIn(0.0, 1.0)
        val p = LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
        return listOf(p) + r.pts.subList(i + 1, r.pts.size)
    }

    private fun nearestIdx(pts: List<LatLng>, lat: Double, lon: Double): Int {
        var best = 0
        var bd = Double.MAX_VALUE
        pts.forEachIndexed { i, p ->
            val d = dist(lat, lon, p.latitude, p.longitude)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dy = lat2 - lat1
        val dx = (lon2 - lon1) * cos(Math.toRadians(lat1))
        return ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
    }

    /** Rzut pozycji na trasę: (przebyty dystans wzdłuż trasy, odległość od trasy, indeks odcinka). */
    data class Snap(val along: Double, val off: Double, val seg: Int)

    fun snap(r: Route, lat: Double, lon: Double): Snap {
        var best = Snap(0.0, Double.MAX_VALUE, 0)
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        for (i in 1 until r.pts.size) {
            val a = r.pts[i - 1]; val b = r.pts[i]
            val ax = (a.longitude - lon) * kx; val ay = (a.latitude - lat) * 111_320.0
            val bx = (b.longitude - lon) * kx; val by = (b.latitude - lat) * 111_320.0
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 < 1e-6) 0.0 else (-(ax * vx + ay * vy) / len2).coerceIn(0.0, 1.0)
            val px = ax + vx * t; val py = ay + vy * t
            val d = sqrt(px * px + py * py)
            if (d < best.off) best = Snap(r.cum[i - 1] + sqrt(len2) * t, d, i - 1)
        }
        return best
    }

    // ---------- wskazówki po polsku ----------

    /** Kąt strzałki manewru (0 = prosto, −90 = w lewo…). */
    fun arrow(s: Step): Float = when {
        s.type == "arrive" -> 0f
        s.modifier == "uturn" -> 180f
        s.modifier == "sharp left" -> -135f
        s.modifier == "left" -> -90f
        s.modifier == "slight left" -> -45f
        s.modifier == "slight right" -> 45f
        s.modifier == "right" -> 90f
        s.modifier == "sharp right" -> 135f
        else -> 0f
    }

    private fun turnText(m: String): String = when (m) {
        "left" -> "Skręć w lewo"
        "right" -> "Skręć w prawo"
        "slight left" -> "Odbij lekko w lewo"
        "slight right" -> "Odbij lekko w prawo"
        "sharp left" -> "Skręć ostro w lewo"
        "sharp right" -> "Skręć ostro w prawo"
        "uturn" -> "Zawróć"
        else -> "Idź prosto"
    }

    fun text(s: Step): String {
        if (s.type == "arrive") return "Auto jest tutaj"
        if (s.instr.isNotBlank()) return s.instr
        val name = s.name.trim()
        val into = if (name.isNotEmpty()) " w $name" else ""
        val along = if (name.isNotEmpty()) " – $name" else ""
        return when (s.type) {
            "depart" -> "Ruszaj" + (if (name.isNotEmpty()) " – $name" else "")
            "arrive" -> "Auto jest tutaj"
            "turn", "end of road", "on ramp", "off ramp" -> turnText(s.modifier) + into
            "fork" -> (if (s.modifier.contains("left")) "Trzymaj się lewej" else if (s.modifier.contains("right")) "Trzymaj się prawej" else "Idź prosto") + along
            "roundabout", "rotary", "roundabout turn" -> "Przejdź przez rondo" + along
            "new name", "continue" -> (if (s.modifier.isBlank() || s.modifier == "straight") "Idź dalej" else turnText(s.modifier)) + along
            else -> (if (s.modifier.isBlank()) "Idź dalej" else turnText(s.modifier)) + along
        }
    }

    fun distText(m: Double): String = when {
        m >= 1000 -> String.format(Locale("pl"), "%.1f km", m / 1000)
        m >= 100 -> "${(m / 10).roundToInt() * 10} m"
        else -> "${m.roundToInt().coerceAtLeast(1)} m"
    }

    // ---------- głos ----------

    /** Przybliżenie kamery w nawigacji – domyślnie blisko (jak „przy aucie”), zapamiętuje Twoje. */
    const val DEFAULT_ZOOM = 19.6
    val zoom = MutableStateFlow(DEFAULT_ZOOM)

    fun saveZoom(ctx: Context, z: Double) {
        zoom.value = z.coerceIn(16.5, 21.0)
        ctx.getSharedPreferences("walknav", Context.MODE_PRIVATE).edit().putFloat("zoom", zoom.value.toFloat()).apply()
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    val voice = MutableStateFlow(true)

    fun initVoice(ctx: Context) {
        val sp = ctx.getSharedPreferences("walknav", Context.MODE_PRIVATE)
        voice.value = sp.getBoolean("voice", true)
        zoom.value = sp.getFloat("zoom", DEFAULT_ZOOM.toFloat()).toDouble()
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pl", "PL")
                ttsReady = true
            }
        }
    }

    fun setVoice(ctx: Context, on: Boolean) {
        voice.value = on
        ctx.getSharedPreferences("walknav", Context.MODE_PRIVATE).edit().putBoolean("voice", on).apply()
        if (!on) tts?.stop()
    }

    fun say(text: String) {
        if (!voice.value || !ttsReady) return
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "walk") } catch (_: Exception) {}
    }

    fun shutdownVoice() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
    }

    fun buzz(ctx: Context) {
        try {
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 90, 120, 90, 260), -1))
            else @Suppress("DEPRECATION") v.vibrate(400)
        } catch (_: Exception) {}
    }
}

/** Ekran nawigacji pieszej do auta – na całym ekranie, nad zakładkami. */
@Composable
fun WalkNavScreen(perms: Perms, actions: Actions) {
    val ctx = LocalContext.current
    val spot by Store.spot.collectAsState()
    val s = spot ?: run { WalkNav.stop(); return }
    val me = rememberPhoneLocation(perms.loc)
    val azimuth = rememberAzimuth(me, true)
    val voice by WalkNav.voice.collectAsState()
    var route by remember { mutableStateOf<WalkNav.Route?>(null) }
    var routing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var lastRouteAt by remember { mutableStateOf(0L) }
    var follow by remember { mutableStateOf(true) }
    var arrived by remember { mutableStateOf(false) }
    var spokenStep by remember { mutableStateOf(-1) }
    var warnedStep by remember { mutableStateOf(-1) }
    var offCount by remember { mutableStateOf(0) }

    BackHandler { WalkNav.stop() }
    // ekran nie gaśnie w czasie prowadzenia
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        WalkNav.initVoice(ctx)
        EventLog.add(EventLog.Cat.PARK, "Prowadzenie do auta – start")
        onDispose {
            view.keepScreenOn = false
            WalkNav.shutdownVoice()
        }
    }
    LaunchedEffect(perms.loc) { if (!perms.loc) actions.requestLoc() }

    // trasa: pierwsza przy pierwszej pozycji, nowa po zejściu z trasy (najwyżej co 15 s),
    // po nieudanej próbie – ponownie co 20 s. Jedna pętla – wcześniej efekt kasował sam siebie
    // (zmiana „routing” zmieniała klucz efektu) i zostawało „Wyznaczam trasę…” w linii prostej.
    val meNow by rememberUpdatedState(me)
    LaunchedEffect(s.lat, s.lon) {
        while (true) {
            val l = meNow
            val wait = if (route == null && failed) 20_000L else 15_000L
            if (l != null && !arrived && (route == null || offCount >= 2) && System.currentTimeMillis() - lastRouteAt > wait) {
                routing = true
                lastRouteAt = System.currentTimeMillis()
                val r = withTimeoutOrNull(30_000) { WalkNav.route(l, s) }
                routing = false
                if (r != null) {
                    val reroute = route != null
                    route = r
                    failed = false
                    offCount = 0
                    spokenStep = -1
                    warnedStep = -1
                    if (reroute) WalkNav.say("Nowa trasa")
                } else {
                    if (route == null) failed = true
                    if (offCount >= 2) offCount = 0 // nie męcz serwera co sekundę – następna próba za 15 s
                }
            }
            delay(1000)
        }
    }

    // postęp na trasie
    val direct = me?.let { WalkNav.dist(it.latitude, it.longitude, s.lat, s.lon) }
    val r = route
    val snap = if (r != null && me != null) WalkNav.snap(r, me.latitude, me.longitude) else null
    val remaining = when {
        r != null && snap != null -> (r.length - snap.along).coerceAtLeast(0.0) + WalkNav.dist(r.pts.last().latitude, r.pts.last().longitude, s.lat, s.lon)
        else -> direct
    }
    // następny manewr przed nami (pomijamy start)
    val next = if (r != null && snap != null) r.steps.firstOrNull { it.type != "depart" && r.cum[it.idx] > snap.along + 3 } else null
    val toNext = if (r != null && snap != null && next != null) (r.cum[next.idx] - snap.along).coerceAtLeast(0.0) else null

    LaunchedEffect(snap?.off, me?.accuracy) {
        val sn = snap ?: return@LaunchedEffect
        val limit = maxOf(30.0, (me?.accuracy ?: 0f) * 1.3)
        offCount = if (sn.off > limit) offCount + 1 else 0
    }
    // głos: start, „za 50 m…”, przy manewrze; przyjście do auta
    LaunchedEffect(r, next?.idx, (toNext ?: 999.0) < 55, (toNext ?: 999.0) < 18) {
        val rr = r ?: return@LaunchedEffect
        if (spokenStep == -1) {
            spokenStep = -2
            WalkNav.say("Prowadzę do auta. " + WalkNav.distText(rr.length) + ". " + (next?.let { WalkNav.text(it) } ?: ""))
            return@LaunchedEffect
        }
        val n = next ?: return@LaunchedEffect
        val d = toNext ?: return@LaunchedEffect
        if (d < 18 && spokenStep != n.idx) {
            spokenStep = n.idx
            WalkNav.say(WalkNav.text(n))
        } else if (d in 18.0..55.0 && warnedStep != n.idx && n.type != "arrive") {
            warnedStep = n.idx
            WalkNav.say("Za ${(d / 10).roundToInt() * 10} metrów " + WalkNav.text(n).replaceFirstChar { it.lowercase() })
        }
    }
    LaunchedEffect(direct != null && direct < maxOf(12.0, (me?.accuracy ?: 0f).toDouble().coerceAtMost(25.0))) {
        if (direct != null && direct < maxOf(12.0, (me?.accuracy ?: 0f).toDouble().coerceAtMost(25.0)) && !arrived) {
            arrived = true
            WalkNav.buzz(ctx)
            WalkNav.say("Jesteś przy aucie")
            EventLog.add(EventLog.Cat.PARK, "Prowadzenie do auta – na miejscu")
        }
    }

    // kierunek: przy chodzeniu kurs z GPS, na stojąco kompas
    val heading = me?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 1.2f }?.bearing ?: azimuth

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        // linia trasy: od Ciebie do auta (przebyta część znika); przerywana – dojście do trasy i od trasy do auta
        val line = if (r != null && snap != null) WalkNav.remainingLine(r, snap) else r?.pts.orEmpty()
        val gaps = ArrayList<List<LatLng>>()
        if (r != null && line.isNotEmpty()) {
            val e = r.pts.last()
            if (WalkNav.dist(e.latitude, e.longitude, s.lat, s.lon) > 3) gaps += listOf(e, LatLng(s.lat, s.lon))
            if (me != null && snap != null && snap.off > 8) gaps += listOf(LatLng(me.latitude, me.longitude), line.first())
        } else if (me != null && !arrived) {
            gaps += listOf(LatLng(me.latitude, me.longitude), LatLng(s.lat, s.lon))
        }
        WalkMap(s, me, heading, line, gaps, follow, { follow = false }, Modifier.fillMaxSize())

        // górna karta: manewr
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.Accent).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val near = direct != null && direct < 40
                when {
                    arrived || near || next?.type == "arrive" ->
                        Icon(Icons.Rounded.DirectionsCar, null, tint = C.Bg, modifier = Modifier.size(46.dp))
                    next != null -> Icon(Icons.Rounded.Navigation, null, tint = C.Bg, modifier = Modifier.size(46.dp).rotate(WalkNav.arrow(next)))
                    me != null && heading != null -> Icon(
                        Icons.Rounded.Navigation, null, tint = C.Bg,
                        modifier = Modifier.size(46.dp).rotate(WalkNav.bearing(me.latitude, me.longitude, s.lat, s.lon) - heading),
                    )
                    else -> Icon(Icons.Rounded.MyLocation, null, tint = C.Bg, modifier = Modifier.size(42.dp))
                }
                Column(Modifier.weight(1f)) {
                    val (big, small) = when {
                        me == null -> "Szukam GPS…" to "Wyjdź na zewnątrz, jeśli długo nie łapie"
                        arrived -> "Jesteś przy aucie" to (s.address ?: "")
                        near -> "Auto jest blisko" to "${WalkNav.distText(direct!!)} – rozejrzyj się"
                        routing && route == null -> "Wyznaczam trasę…" to WalkNav.distText(direct ?: 0.0) + " w linii prostej"
                        next != null && toNext != null -> WalkNav.distText(toNext) to WalkNav.text(next)
                        failed || route == null -> WalkNav.distText(direct ?: 0.0) to "Nie mam trasy pieszej – ponawiam. Idź za strzałką"
                        else -> WalkNav.distText(remaining ?: 0.0) to "Idź dalej"
                    }
                    Text(big, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Bg, maxLines = 1)
                    if (small.isNotBlank()) Text(small, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Bg.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            val err by WalkNav.error.collectAsState()
            if (failed && route == null && err.isNotBlank()) Text(
                err, fontSize = 12.sp, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Dialog).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        // przyciski po prawej
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NavRoundBtn({ WalkNav.setVoice(ctx, !voice) }) {
                Icon(if (voice) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, "Głos", tint = if (voice) C.Accent else C.Text, modifier = Modifier.size(24.dp))
            }
            NavRoundBtn({ WalkNav.googleMaps(ctx, s) }) { Icon(Icons.Rounded.Map, "Mapy Google", tint = C.Text, modifier = Modifier.size(24.dp)) }
        }
        AnimatedVisibility(!follow, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 14.dp, bottom = 128.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(C.Dialog).clickable { follow = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Navigation, null, tint = C.Accent, modifier = Modifier.size(20.dp))
                Text("Wyśrodkuj", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            }
        }

        // dolna karta: ile zostało, kiedy będziesz, zakończ
        AnimatedVisibility(
            true, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            Row(
                Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(C.Dialog)
                    .padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val rem = remaining
                    if (arrived) {
                        Text("Na miejscu", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Green)
                        Text(Store.carName.value ?: "Twoje auto", fontSize = 15.sp, color = C.Muted)
                    } else if (rem != null) {
                        val min = (rem / 1.3 / 60).roundToInt().coerceAtLeast(1)
                        val at = java.text.SimpleDateFormat("HH:mm", Locale("pl")).format(java.util.Date(System.currentTimeMillis() + (rem / 1.3 * 1000).toLong()))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("$min min", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Green)
                            Text(WalkNav.distText(rem), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.padding(bottom = 3.dp))
                        }
                        val src by WalkNav.source.collectAsState()
                        Text("Na miejscu o $at · " + (if (route != null && src.isNotBlank()) src else "pieszo"), fontSize = 15.sp, color = C.Muted)
                    } else {
                        Text("Prowadzę do auta", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Text)
                        Text(s.address ?: s.coords, fontSize = 14.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp)).background(if (arrived) C.Accent else NAV_RED.copy(alpha = 0.16f))
                        .clickable { WalkNav.stop() }.padding(horizontal = 18.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Close, null, tint = if (arrived) C.Bg else NAV_RED, modifier = Modifier.size(20.dp))
                        Text(if (arrived) "Gotowe" else "Zakończ", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (arrived) C.Bg else NAV_RED)
                    }
                }
            }
        }
    }
}

private val NAV_RED = androidx.compose.ui.graphics.Color(0xFFE5484D)

@Composable
private fun NavRoundBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(C.Dialog).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
