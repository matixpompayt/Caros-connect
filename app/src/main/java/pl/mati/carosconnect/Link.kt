package pl.mati.carosconnect

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Połączenie z CAR OS w radiu.
 * Kod QR z radia niesie klucz (32 B), nazwę Bluetooth radia i dane auta.
 * Radio wysyła zaszyfrowane (AES-GCM) wiadomości przez publiczny przekaźnik ntfy.sh –
 * przekaźnik widzi tylko szyfrogram, kanał wynika z klucza.
 */
object Link {

    data class Pairing(val key: ByteArray, val btName: String?, val car: String?, val driver: String?) {
        val topic: String get() = topicOf(key)
    }

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

    fun topicOf(key: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(key)
        return "caros_" + h.joinToString("") { "%02x".format(it) }.take(28)
    }

    fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun decrypt(key: ByteArray, msg: String): String? = try {
        val all = Base64.decode(msg.trim(), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    } catch (_: Exception) {
        null
    }

    /**
     * Ostatnie miejsce parkowania wysłane przez radio (przekaźnik trzyma wiadomości ok. 12 h).
     * Wołać poza wątkiem głównym.
     */
    fun fetchCarSpot(p: Pairing): Store.Spot? {
        val c = URL(RELAY + p.topic + "/json?poll=1&since=all").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "CarOS-Connect")
        try {
            if (c.responseCode !in 200..299) return null
            var best: Store.Spot? = null
            c.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val o = try { JSONObject(line) } catch (_: Exception) { continue }
                    if (o.optString("event") != "message") continue
                    val plain = decrypt(p.key, o.optString("message")) ?: continue
                    val m = try { JSONObject(plain) } catch (_: Exception) { continue }
                    if (m.optString("type") != "park") continue
                    val s = Store.Spot(
                        m.getDouble("lat"), m.getDouble("lon"), m.getLong("t"), m.optDouble("acc", 0.0).toFloat(),
                        m.optString("a").ifBlank { null }, src = Store.SRC_CAR,
                    )
                    if (best == null || s.time >= best!!.time) best = s
                }
            }
            return best
        } finally {
            c.disconnect()
        }
    }
}
