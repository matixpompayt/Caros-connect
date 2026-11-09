package pl.mati.carosconnect

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Miejsca: odczyt z udostępnienia (Mapy Google, geo:) i wyszukiwarka (OpenStreetMap). */
object Places {

    private const val UA = "CarOS-Connect/1.0 (pl.mati.carosconnect)"

    private val patterns = listOf(
        Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""),
        Regex("""[?&](?:q|ll|query|daddr|destination|center)=(-?\d{1,2}\.\d+)\s*,\s*\+?(-?\d{1,3}\.\d+)"""),
        Regex("""/(?:search|place|dir)/(?:[^/]*/)?(-?\d{1,2}\.\d+),\s*\+?(-?\d{1,3}\.\d+)"""),
        Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),
        Regex("""^geo:(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),
    )

    private fun coordsIn(s: String): Pair<Double, Double>? {
        val text = try { URLDecoder.decode(URLDecoder.decode(s, "UTF-8"), "UTF-8") } catch (_: Exception) { s }
        for (re in patterns) {
            val m = re.find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat == 0.0 && lon == 0.0) continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }

    /** Zgody Google (UE): bez nich zamiast mapy przychodzi strona „Zanim przejdziesz do Google”. */
    private const val GOOGLE_COOKIES = "SOCS=CAI; CONSENT=PENDING+987"

    /** Krótki link (maps.app.goo.gl) → kolejne adresy przekierowań, na końcu treść strony. */
    private fun redirects(start: String): List<String> {
        val out = ArrayList<String>()
        var url = start
        repeat(8) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 12_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
            if (url.contains("google.") || url.contains("goo.gl")) c.setRequestProperty("Cookie", GOOGLE_COOKIES)
            try {
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && loc != null) {
                    url = URL(URL(url), loc).toString()
                    out += url
                } else {
                    if (code in 200..299) {
                        // strona końcowa: współrzędne są w meta og:image (staticmap?center=…)
                        val body = c.inputStream.bufferedReader().use { r -> r.readText().take(800_000) }
                        out += body
                    }
                    return out
                }
            } finally {
                c.disconnect()
            }
        }
        return out
    }

    /** Współrzędne na stronie Map Google: pinezka w podglądzie (og:image …&markers=lat,lon) albo dane miejsca (!3d…!4d…). */
    private fun coordsInBody(html: String): Pair<Double, Double>? {
        val t = html.replace("&amp;", "&").replace("%2C", ",").replace("%2c", ",")
        for (re in listOf(Regex("""markers=(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""), Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""))) {
            val m = re.find(t) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lon = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat == 0.0 && lon == 0.0) continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return lat to lon
        }
        return null
    }

    private fun decode2(s: String) = try { URLDecoder.decode(URLDecoder.decode(s, "UTF-8"), "UTF-8") } catch (_: Exception) { s }

    /** Nazwa i adres zaszyte w linku Map Google: ?q=Nazwa,+adres albo /maps/place/Nazwa/. */
    private fun placeQueryIn(url: String): String? {
        if (url.length > 6000) return null
        val t = decode2(url)
        val cands = listOfNotNull(
            Regex("""[?&](?:q|query|destination|daddr)=([^&#]+)""").find(t)?.groupValues?.get(1),
            Regex("""/maps/(?:place|search|dir/[^/]*)/([^/@?#]+)""").find(t)?.groupValues?.get(1),
        )
        return cands.map { it.replace('+', ' ').trim().trim(',') }
            .firstOrNull { it.length > 2 && coordsIn("q=$it") == null && !it.startsWith("http") }
    }

    /** Wynik odczytu udostępnienia: miejsce, inne pasujące wyniki i tekst do pokazania przy błędzie. */
    data class Resolved(val place: CarData.Place?, val others: List<CarData.Place>, val query: String, val shared: String)

    /**
     * Miejsce z udostępnionego tekstu (Mapy Google: nazwa, adres, link) albo z adresu geo:.
     * Kolejno: współrzędne w tekście → w przekierowaniach linku i na stronie Map →
     * nazwa/adres (z tekstu albo z linku) przez Geocoder telefonu (Google), potem OpenStreetMap.
     * Blokujące – wołać poza wątkiem głównym.
     */
    fun resolve(ctx: Context?, text: String?, uri: Uri?): Resolved {
        val shared = (text ?: uri?.toString()).orEmpty()
        // geo:lat,lon?q=lat,lon(Nazwa)  albo  geo:0,0?q=adres
        if (uri != null && uri.scheme == "geo") {
            val q = uri.getQueryParameter("q")
            val label = q?.let { Regex("""\(([^)]+)\)""").find(it)?.groupValues?.get(1) }
            coordsIn(uri.toString())?.let { (a, b) -> return Resolved(CarData.Place(label ?: "Cel z telefonu", "", a, b), emptyList(), "", shared) }
            q?.let { coordsIn("q=$it") }?.let { (a, b) -> return Resolved(CarData.Place(label ?: "Cel z telefonu", "", a, b), emptyList(), "", shared) }
            val query = q?.substringBefore("(")?.trim().orEmpty()
            val hits = if (query.isNotBlank()) geocode(ctx, query) else emptyList()
            return Resolved(hits.firstOrNull(), hits.drop(1), query, shared)
        }
        val t = shared.trim()
        if (t.isEmpty()) return Resolved(null, emptyList(), "", shared)
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val url = Regex("""https?://\S+""").find(t)?.value
        val words = lines.map { it.replace(Regex("""https?://\S+"""), "").trim().trim('-', '–', ':', ',').trim() }.filter { it.isNotEmpty() }
        var name = words.firstOrNull()?.take(80)
        var detail = words.drop(1).joinToString(", ").take(120)

        coordsIn(t)?.let { (a, b) -> return Resolved(CarData.Place(name ?: "Cel z telefonu", detail, a, b), emptyList(), "", shared) }
        val steps = if (url != null) try { redirects(url) } catch (_: Exception) { emptyList() } else emptyList()
        val linkQuery = steps.firstNotNullOfOrNull { placeQueryIn(it) } ?: url?.let { placeQueryIn(it) }
        if (name == null && linkQuery != null) {
            name = linkQuery.substringBefore(",").trim().take(80)
            if (detail.isBlank()) detail = linkQuery.substringAfter(",", "").trim().take(120)
        }
        for (step in steps) {
            val c = if (step.startsWith("http")) coordsIn(step) else coordsInBody(step)
            c?.let { (a, b) -> return Resolved(CarData.Place(name ?: "Cel z telefonu", detail, a, b), emptyList(), "", shared) }
        }

        // bez współrzędnych: szukaj po nazwie i adresie
        val queries = listOfNotNull(
            linkQuery,
            listOfNotNull(name, detail.ifBlank { null }).joinToString(", ").ifBlank { null },
            detail.ifBlank { null },
            name,
        ).map { it.trim() }.filter { it.length > 2 }.distinct()
        for (q in queries) {
            val hits = geocode(ctx, q)
            if (hits.isNotEmpty()) {
                val best = hits.first().let { h -> if (name != null) h.copy(name = name!!, detail = detail.ifBlank { h.detail }) else h }
                return Resolved(best, hits.drop(1).take(4), q, shared)
            }
        }
        return Resolved(null, emptyList(), queries.firstOrNull().orEmpty(), shared)
    }

    /** Nazwa/adres → miejsca: najpierw Geocoder telefonu (baza Google, zna sklepy i firmy), potem OpenStreetMap. */
    fun geocode(ctx: Context?, q: String): List<CarData.Place> {
        val near = ctx?.let { try { Locator.lastKnown(it) } catch (_: Exception) { null } }
        val out = ArrayList<CarData.Place>()
        if (ctx != null && Geocoder.isPresent()) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale("pl")).getFromLocationName(q, 5)?.forEach { a ->
                    val lines = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }
                    val feature = a.featureName?.takeIf { f -> f.isNotBlank() && !f.first().isDigit() && f != a.thoroughfare }
                    out += CarData.Place(
                        feature ?: listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { q.substringBefore(",") },
                        lines.joinToString(", ").ifBlank { listOfNotNull(a.locality, a.countryName).joinToString(", ") },
                        a.latitude, a.longitude,
                    )
                }
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) out += search(q, near)
        // samą nazwę (bez numeru) szukaj najbliżej telefonu; adres z numerem zostaw w kolejności geokodera
        if (near != null && out.size > 1 && q.none { it.isDigit() }) out.sortBy {
            val r = FloatArray(1)
            Location.distanceBetween(near.latitude, near.longitude, it.lat, it.lon, r)
            r[0]
        }
        return out
    }

    /** Adres dla współrzędnych (Nominatim reverse): „ulica nr, miejscowość”. */
    fun reverse(lat: Double, lon: Double): String? {
        val url = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&accept-language=pl&lat=%.6f&lon=%.6f", lat, lon)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        return try {
            if (c.responseCode !in 200..299) return null
            val o = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val a = o.optJSONObject("address")
            if (a != null) {
                val street = listOf(a.optString("road"), a.optString("house_number")).filter { it.isNotBlank() }.joinToString(" ")
                val town = listOf("city", "town", "village", "hamlet", "suburb").map { a.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
                val s = listOf(street, town).filter { it.isNotBlank() }.joinToString(", ")
                if (s.isNotBlank()) return s
            }
            o.optString("display_name").split(", ").take(3).joinToString(", ").ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Wyszukiwarka OpenStreetMap (Nominatim), wyniki od najbliższych. */
    fun search(query: String, near: Location?): List<CarData.Place> {
        if (query.isBlank()) return emptyList()
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&accept-language=pl")
        sb.append("&q=").append(URLEncoder.encode(query, "UTF-8"))
        if (near != null) {
            val d = 1.5
            sb.append(String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", near.longitude - d, near.latitude + d, near.longitude + d, near.latitude - d))
        }
        val c = URL(sb.toString()).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", UA)
        return try {
            if (c.responseCode !in 200..299) return emptyList()
            val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            val out = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val parts = o.optString("display_name").split(", ")
                CarData.Place(
                    o.optString("name").ifBlank { parts.firstOrNull().orEmpty() },
                    parts.drop(1).take(3).joinToString(", "),
                    o.getString("lat").toDouble(), o.getString("lon").toDouble(),
                )
            }
            if (near == null) out else out.sortedBy {
                val r = FloatArray(1)
                Location.distanceBetween(near.latitude, near.longitude, it.lat, it.lon, r)
                r[0]
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            c.disconnect()
        }
    }
}
