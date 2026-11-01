package pl.mati.carosconnect

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

    /** Krótki link (maps.app.goo.gl) → kolejne adresy przekierowań. */
    private fun redirects(start: String): List<String> {
        val out = ArrayList<String>()
        var url = start
        repeat(7) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            try {
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && loc != null) {
                    url = URL(URL(url), loc).toString()
                    out += url
                } else {
                    if (code in 200..299) {
                        // strona końcowa: współrzędne bywają w treści
                        val body = c.inputStream.bufferedReader().use { r -> r.readText().take(300_000) }
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

    /**
     * Miejsce z udostępnionego tekstu (Mapy Google: nazwa, adres, link) albo z adresu geo:.
     * Blokujące – wołać poza wątkiem głównym.
     */
    fun resolve(text: String?, uri: Uri?): CarData.Place? {
        // geo:lat,lon?q=lat,lon(Nazwa)  albo  geo:0,0?q=adres
        if (uri != null && uri.scheme == "geo") {
            val q = uri.getQueryParameter("q")
            val label = q?.let { Regex("""\(([^)]+)\)""").find(it)?.groupValues?.get(1) }
            coordsIn(uri.toString())?.let { (a, b) -> return CarData.Place(label ?: "Cel z telefonu", "", a, b) }
            q?.let { coordsIn("q=$it") }?.let { (a, b) -> return CarData.Place(label ?: "Cel z telefonu", "", a, b) }
            if (!q.isNullOrBlank()) return search(q.substringBefore("("), null).firstOrNull()
            return null
        }
        val t = (text ?: uri?.toString() ?: return null).trim()
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val url = Regex("""https?://\S+""").find(t)?.value
        val words = lines.filterNot { it.startsWith("http") }
        val name = words.firstOrNull()?.take(80) ?: "Cel z telefonu"
        val detail = words.drop(1).joinToString(", ").take(120)

        coordsIn(t)?.let { (a, b) -> return CarData.Place(name, detail, a, b) }
        if (url != null) {
            try {
                for (step in redirects(url)) coordsIn(step)?.let { (a, b) -> return CarData.Place(name, detail, a, b) }
            } catch (_: Exception) {
            }
        }
        // bez współrzędnych: szukaj po nazwie i adresie
        val q = listOf(name, detail).filter { it.isNotBlank() && it != "Cel z telefonu" }.joinToString(", ")
        if (q.isBlank()) return null
        return (search(q, null).firstOrNull() ?: search(detail.ifBlank { name }, null).firstOrNull())
            ?.let { it.copy(name = name) }
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
