package pl.mati.carosconnect

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import pl.mati.caros.core.LiveShare
import pl.mati.caros.core.Proto

/**
 * Jazda na żywo przez link: telefon tworzy klucz (link zna od razu), CAR OS w aucie wysyła pozycję
 * zaszyfrowaną tym kluczem, a bliscy oglądają ją na stronie w przeglądarce. Włączone w radiu – link
 * przychodzi w statusie i telefon proponuje wysłanie go (powiadomienie).
 */
object TripShare {
    /** Udostępnianie widziane z auta (status) albo właśnie włączone z telefonu. */
    data class Info(val link: String, val until: Long, val toDest: Boolean, val by: String, val since: Long)

    val info = MutableStateFlow<Info?>(null)

    /** Status z auta: "share" (null = wyłączone). */
    fun onStatus(ctx: Context?, o: JSONObject?) {
        if (o == null) {
            // wyłączone w aucie – ale nie kasuj świeżo włączonego z telefonu (status mógł być sprzed komendy)
            val cur = info.value
            if (cur != null && System.currentTimeMillis() - cur.since > 3 * 60_000L) info.value = null
            return
        }
        val i = Info(o.optString("link"), o.optLong("until"), o.optBoolean("dest"), o.optString("by", "car"), o.optLong("since"))
        if (i.link.isBlank()) return
        val prev = info.value
        info.value = i
        // włączone w radiu – link do wysłania (raz na udostępnienie)
        val sp = CarData.prefs
        if (ctx != null && i.by == "car" && prev?.link != i.link && sp?.getString("share_notified", null) != i.link) {
            sp?.edit()?.putString("share_notified", i.link)?.apply()
            Notifs.shareLink(ctx, i.link, if (i.toDest) "Udostępniasz jazdę do celu – wyślij link bliskim" else "Udostępniasz jazdę – wyślij link bliskim")
        }
    }

    /** Okno systemowe „Udostępnij” z linkiem. */
    fun chooser(link: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Jazda na żywo")
            .putExtra(Intent.EXTRA_TEXT, "Śledź moją jazdę na żywo (mapa, trasa i godzina przyjazdu): $link")
        return Intent.createChooser(send, "Wyślij link do jazdy na żywo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Włącz z telefonu: [minutes] = 0 – do celu. Blokujące (poza wątkiem głównym).
     * Zwraca link albo null (brak połączenia z CAR OS).
     */
    fun start(ctx: Context, minutes: Int): Pair<String, Outbox.Item>? {
        if (Store.pairing.value == null) return null
        val key = LiveShare.newKey()
        val link = LiveShare.link(key)
        val item = Outbox.send(
            ctx, JSONObject().put("type", Proto.Cmd.SHARE).put("k", LiveShare.keyText(key)).put("min", minutes),
            "Jazda na żywo: " + if (minutes == 0) "do celu" else "na ${minutes / 60} h",
        )
        val now = System.currentTimeMillis()
        info.value = Info(link, now + (if (minutes == 0) 12 * 60 else minutes) * 60_000L, minutes == 0, "phone", now)
        CarData.prefs?.edit()?.putString("share_notified", link)?.apply()
        return link to item
    }

    fun stop(ctx: Context): Outbox.Item? {
        if (Store.pairing.value == null) return null
        val item = Outbox.send(ctx, JSONObject().put("type", Proto.Cmd.SHARE_END), "Jazda na żywo: koniec")
        info.value = null
        return item
    }
}
