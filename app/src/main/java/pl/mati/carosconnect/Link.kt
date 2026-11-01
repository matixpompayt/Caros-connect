package pl.mati.carosconnect

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Połączenie z CAR OS w radiu.
 * Kod QR z radia niesie klucz (32 B), nazwę Bluetooth radia i dane auta.
 * Wymiana przez publiczny przekaźnik ntfy.sh, szyfrowana AES-GCM (przekaźnik widzi tylko szyfrogram):
 *  - kanał auto → telefon: sha256(klucz),
 *  - kanał telefon → auto: sha256(klucz | "p2c").
 */
object Link {

    data class Pairing(val key: ByteArray, val btName: String?, val car: String?, val driver: String?)

    /** Wszystko, co radio wysłało (przekaźnik trzyma wiadomości ok. 12 h). */
    data class Inbox(
        val park: Store.Spot?,
        val status: String?,
        val trips: List<CarData.Trip>,
        val backup: Triple<String, Long, String>?,
        /** Model auta (typ, kolor) z ostatniej wiadomości o parkowaniu. */
        val carIcon: Pair<String, Int?>? = null,
        /** Ostatnia pozycja auta w czasie jazdy. */
        val live: CarData.Live? = null,
    )

    private const val PREFIX = "#caros1."
    private const val RELAY = "https://ntfy.sh/"

    /** Odczyt treści kodu QR z CAR OS. Zwraca null, gdy to nie jest kod CAR OS. */
    fun parse(text: String): Pairing? = try {
        val i = text.indexOf(PREFIX)
        if (i < 0) null else {
            val raw = String(Base64.decode(text.substring(i + PREFIX.length).trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val o = JSONObject(raw)
            val key = Base64.decode(o.getString("k"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            if (key.size != 32) null
            else Pairing(key, o.optString("bt").ifBlank { null }, o.optString("car").ifBlank { null }, o.optString("drv").ifBlank { null })
        }
    } catch (_: Exception) {
        null
    }

    fun topicOf(key: ByteArray, channel: String = ""): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(key)
        if (channel.isNotEmpty()) md.update(channel.toByteArray())
        return "caros_" + md.digest().joinToString("") { "%02x".format(it) }.take(28)
    }

    /** Format v2: "2:" + base64(iv | AES-GCM(gzip(json))). */
    fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "2:" + Base64.encodeToString(iv + c.doFinal(gzip(plain.toByteArray())), Base64.NO_WRAP)
    }

    fun decrypt(key: ByteArray, msg: String): String? = try {
        val v2 = msg.startsWith("2:")
        val all = Base64.decode(if (v2) msg.substring(2) else msg.trim(), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        val out = c.doFinal(all, 12, all.size - 12)
        String(if (v2) gunzip(out) else out)
    } catch (_: Exception) {
        null
    }

    fun gzip(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }

    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    /** Wiadomość do radia (blokujące – poza wątkiem głównym). */
    fun send(p: Pairing, o: JSONObject) {
        val body = encrypt(p.key, o.toString())
        var last: Exception? = null
        for (attempt in 0..2) {
            try {
                val c = URL(RELAY + topicOf(p.key, "p2c")).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("User-Agent", "CarOS-Connect")
                try {
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val code = c.responseCode
                    if (code in 200..299) return
                    last = IOException("HTTP $code")
                    if (code == 429) Thread.sleep(6000)
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                last = e
                Thread.sleep(2000)
            }
        }
        throw last ?: IOException("Brak połączenia")
    }

    /** Kopia ustawień do radia w częściach (przekaźnik przyjmuje do 4 KB na wiadomość). */
    fun sendRestore(p: Pairing, text: String) {
        val gz = gzip(text.toByteArray())
        val size = 1900
        val n = (gz.size + size - 1) / size
        val id = System.currentTimeMillis().toString()
        for (i in 0 until n) {
            val part = gz.copyOfRange(i * size, minOf(gz.size, (i + 1) * size))
            send(p, JSONObject().put("type", "rs").put("id", id).put("i", i).put("n", n).put("d", Base64.encodeToString(part, Base64.NO_WRAP)))
            Thread.sleep(400)
        }
    }

    /** Odczyt wszystkich wiadomości od radia (blokujące). */
    fun fetchInbox(p: Pairing): Inbox {
        val c = URL(RELAY + topicOf(p.key) + "/json?poll=1&since=all").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "CarOS-Connect")
        var park: Store.Spot? = null
        var carIcon: Pair<String, Int?>? = null
        var status: String? = null
        var statusT = 0L
        val trips = ArrayList<CarData.Trip>()
        var live: CarData.Live? = null
        val parts = HashMap<String, Array<String?>>()
        val meta = HashMap<String, Pair<Long, String>>()
        try {
            if (c.responseCode !in 200..299) throw IOException("Przekaźnik: HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val o = try { JSONObject(line) } catch (_: Exception) { continue }
                    if (o.optString("event") != "message") continue
                    val plain = decrypt(p.key, o.optString("message")) ?: continue
                    val m = try { JSONObject(plain) } catch (_: Exception) { continue }
                    try {
                        when (m.optString("type")) {
                            "park" -> {
                                val s = Store.Spot(
                                    m.getDouble("lat"), m.getDouble("lon"), m.getLong("t"), m.optDouble("acc", 0.0).toFloat(),
                                    m.optString("a").ifBlank { null }, src = Store.SRC_CAR,
                                )
                                if (park == null || s.time >= park!!.time) {
                                    park = s
                                    m.optString("body").ifBlank { null }?.let { b -> carIcon = b to (if (m.has("paint")) m.optInt("paint") else null) }
                                }
                            }
                            "status" -> {
                                val t = m.optLong("t")
                                if (t >= statusT) { statusT = t; status = plain }
                            }
                            "trip" -> trips += CarData.tripFrom(m)
                            "live" -> {
                                val l = CarData.Live(m.getDouble("lat"), m.getDouble("lon"), m.optInt("spd"), m.optInt("brg"), m.getLong("t"), m.optBoolean("m", true))
                                if (live == null || l.time >= live!!.time) live = l
                            }
                            "bk" -> {
                                val id = m.getString("id")
                                val n = m.getInt("n")
                                val arr = parts.getOrPut(id) { arrayOfNulls(n) }
                                if (arr.size == n) arr[m.getInt("i")] = m.getString("d")
                                meta[id] = m.optLong("c") to m.optString("car")
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        // najnowsza kompletna kopia
        var backup: Triple<String, Long, String>? = null
        for ((id, arr) in parts.entries.sortedByDescending { it.key }) {
            if (arr.any { it == null }) continue
            try {
                val bos = ByteArrayOutputStream()
                arr.forEach { bos.write(Base64.decode(it, Base64.NO_WRAP)) }
                val text = String(gunzip(bos.toByteArray()))
                val (created, car) = meta[id] ?: (0L to "")
                backup = Triple(text, created, car)
                break
            } catch (_: Exception) {
            }
        }
        return Inbox(park, status, trips, backup, carIcon, live)
    }
}
