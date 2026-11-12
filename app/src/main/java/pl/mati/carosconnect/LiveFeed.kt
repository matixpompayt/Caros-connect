package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pozycja auta na żywo.
 *  – telefon w aucie (połączony z radiem): pozycją auta jest GPS telefonu – bez internetu i limitów,
 *  – telefonu nie ma w aucie: radio wpisuje pozycję do Twojej bazy Firebase (Realtime Database) co wybrany czas,
 *    telefon słucha jej strumieniem (od razu, bez odpytywania). Dane są szyfrowane kluczem z parowania.
 *  – bez Firebase: jak dawniej, przez przekaźnik co ok. minutę.
 */
object LiveFeed {

    /** Odświeżanie w sekundach: 30, 10, 5 albo 0 (na bieżąco). */
    val interval = MutableStateFlow(10)
    /** Adres bazy, np. https://moje-auto-default-rtdb.europe-west1.firebasedatabase.app */
    val fbUrl = MutableStateFlow("")
    /** Stan strumienia z Firebase (do ustawień i karty „Auto w drodze”). */
    val fbState = MutableStateFlow<String?>(null)
    val fbLastAt = MutableStateFlow(0L)

    val OPTIONS = listOf(30 to "30 s", 10 to "10 s", 5 to "5 s", 0 to "Na bieżąco")

    val RULES = """{
  "rules": {
    "caros": {
      "${'$'}ch": { ".read": true, ".write": true }
    }
  }
}"""

    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("live", Context.MODE_PRIVATE)
        sp = p
        interval.value = p.getInt("iv", 10)
        fbUrl.value = p.getString("fb", "") ?: ""
    }

    fun label(iv: Int) = if (iv <= 0) "na bieżąco" else "co $iv s"

    fun setInterval(v: Int) {
        interval.value = v
        sp?.edit()?.putInt("iv", v)?.apply()
    }

    /** Porządkuje wklejony adres: bez ścieżki, bez „/” na końcu, z https://. */
    fun normalize(raw: String): String? {
        var u = raw.trim().removeSuffix("/")
        if (u.isEmpty()) return ""
        if (!u.startsWith("http")) u = "https://$u"
        u = u.replaceFirst("http://", "https://")
        val host = try { URL(u).host } catch (_: Exception) { return null }
        if (!host.endsWith("firebaseio.com") && !host.endsWith("firebasedatabase.app")) return null
        return "https://$host"
    }

    fun setFbUrl(v: String) {
        fbUrl.value = v
        sp?.edit()?.putString("fb", v)?.apply()
        fbState.value = null
    }

    /** Ustawienia do radia (przekaźnikiem): co ile wysyłać i dokąd. */
    suspend fun sendCfg(): String = withContext(Dispatchers.IO) {
        val p = Store.pairing.value ?: return@withContext "Najpierw połącz z CAR OS kodem QR z radia"
        try {
            Link.send(p, JSONObject().put("type", "cfg").put("live_iv", interval.value).put("fb", fbUrl.value))
            "Wysłano do auta"
        } catch (e: Exception) {
            "Nie udało się wysłać: " + (e.message ?: "brak internetu")
        }
    }

    private fun nodeUrl(base: String, key: ByteArray, suffix: String = ""): String =
        "$base/caros/${Link.topicOf(key, "live")}$suffix.json"

    /** Próba zapisu i odczytu – sprawdza adres i reguły bazy. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        val base = fbUrl.value
        if (base.isBlank()) return@withContext "Wklej adres bazy"
        val p = Store.pairing.value ?: return@withContext "Najpierw połącz z CAR OS kodem QR z radia"
        val u = nodeUrl(base, p.key, "_test")
        try {
            val w = http(u, "PUT", "{\"ok\":{\".sv\":\"timestamp\"}}")
            if (w.first == 401 || w.first == 403) return@withContext "Baza odrzuca zapis – wklej reguły (przycisk „Kopiuj reguły”) w Firebase → Realtime Database → Reguły"
            if (w.first == 404) return@withContext "Nie ma takiej bazy – sprawdź adres"
            if (w.first !in 200..299) return@withContext "Firebase: błąd ${w.first}"
            val r = http(u, "GET", null)
            http(u, "DELETE", null)
            if (r.first in 200..299 && r.second.contains("ok")) "Działa – baza przyjmuje i oddaje dane" else "Odczyt nie działa (błąd ${r.first}) – sprawdź reguły"
        } catch (e: Exception) {
            "Brak połączenia z bazą: " + (e.message ?: "")
        }
    }

    private fun http(u: String, method: String, body: String?): Pair<Int, String> {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.requestMethod = method
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = try { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /**
     * Strumień pozycji z Firebase (Server-Sent Events) – działa, dopóki korutyna żyje (ekran Parkowanie widoczny).
     * Każda zmiana → [onLive]. Po zerwaniu łączy się ponownie.
     */
    suspend fun stream(onLive: (CarData.Live) -> Unit) = withContext(Dispatchers.IO) {
        var backoff = 2000L
        while (isActive) {
            val base = fbUrl.value
            val p = Store.pairing.value
            if (base.isBlank() || p == null) { delay(5000); continue }
            var d: String? = null
            var ts = 0L
            try {
                val c = URL(nodeUrl(base, p.key)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 75_000 // Firebase wysyła keep-alive co ok. 30 s
                c.setRequestProperty("Accept", "text/event-stream")
                try {
                    val code = c.responseCode
                    if (code == 401 || code == 403) { fbState.value = "Baza odrzuca odczyt – sprawdź reguły"; delay(30_000); continue }
                    if (code !in 200..299) { fbState.value = "Firebase: błąd $code"; delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000); continue }
                    fbState.value = "Połączono z bazą"
                    backoff = 2000L
                    val rd = c.inputStream.bufferedReader()
                    var event = ""
                    while (isActive) {
                        val line = rd.readLine() ?: break
                        when {
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                val data = line.substring(5).trim()
                                when (event) {
                                    "put", "patch" -> {
                                        val o = try { JSONObject(data) } catch (_: Exception) { null } ?: continue
                                        val path = o.optString("path", "/")
                                        val v = o.opt("data")
                                        when (path) {
                                            "/" -> if (v is JSONObject) {
                                                if (v.has("d")) d = v.optString("d")
                                                if (v.has("ts")) ts = v.optLong("ts")
                                            } else if (event == "put") { d = null }
                                            "/d" -> d = v as? String
                                            "/ts" -> ts = (v as? Number)?.toLong() ?: ts
                                        }
                                        val enc = d
                                        if (enc != null) {
                                            val plain = Link.decrypt(p.key, enc)
                                            if (plain != null) {
                                                val m = JSONObject(plain)
                                                val t = if (ts > 0) ts else m.optLong("t")
                                                fbLastAt.value = System.currentTimeMillis()
                                                onLive(
                                                    CarData.Live(
                                                        m.optDouble("lat"), m.optDouble("lon"), m.optInt("spd"), m.optInt("brg"),
                                                        t, m.optBoolean("m", true),
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    "cancel", "auth_revoked" -> { fbState.value = "Baza zamknęła dostęp – sprawdź reguły"; break }
                                }
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                fbState.value = "Brak połączenia z bazą – ponawiam"
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000)
        }
    }
}
