package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.mati.caros.core.Proto
import java.util.concurrent.TimeUnit

/**
 * Komendy do CAR OS z potwierdzeniem (protokół 2):
 * wysłano → radio wykonało (albo błąd / nie zna komendy). Bez internetu komenda czeka w kolejce
 * i wychodzi sama, gdy internet wróci. Gdy radio jest wyłączone, przekaźnik trzyma ją do ok. 12 h
 * – radio wykona ją po włączeniu, a potwierdzenie przyjdzie z następnym odświeżeniem.
 */
object Outbox {
    enum class State { QUEUED, SENT, DONE, FAILED, UNKNOWN, NOACK }

    data class Item(
        val mid: String, val type: String, val label: String, val time: Long, val state: State,
        val detail: String = "", val payload: String = "", val tries: Int = 0, val doneAt: Long = 0L,
    ) {
        fun toJson(): JSONObject = JSONObject().put("mid", mid).put("type", type).put("label", label).put("t", time)
            .put("s", state.name).put("d", detail).put("p", payload).put("n", tries).put("da", doneAt)

        companion object {
            fun from(o: JSONObject) = Item(
                o.getString("mid"), o.optString("type"), o.optString("label"), o.optLong("t"),
                try { State.valueOf(o.optString("s")) } catch (_: Exception) { State.SENT },
                o.optString("d"), o.optString("p"), o.optInt("n"), o.optLong("da"),
            )
        }
    }

    /** Najnowsze pierwsze. */
    val items = MutableStateFlow<List<Item>>(emptyList())
    private var sp: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = HashMap<String, Job>()
    private const val KEEP_MS = 24 * 3600_000L

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("outbox", Context.MODE_PRIVATE)
        sp = p
        items.value = try {
            val a = JSONArray(p.getString("items", "[]"))
            (0 until a.length()).map { Item.from(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }.filter { System.currentTimeMillis() - it.time < KEEP_MS }
    }

    private fun save() {
        val a = JSONArray()
        items.value.take(30).forEach { a.put(it.toJson()) }
        sp?.edit()?.putString("items", a.toString())?.apply()
    }

    @Synchronized
    private fun put(item: Item) {
        items.value = (listOf(item) + items.value.filter { x -> x.mid != item.mid })
            .filter { x -> System.currentTimeMillis() - x.time < KEEP_MS }.take(30)
        save()
    }

    @Synchronized
    private fun update(mid: String, f: (Item) -> Item): Item? {
        var out: Item? = null
        items.value = items.value.map { if (it.mid == mid) f(it).also { n -> out = n } else it }
        save()
        return out
    }

    /**
     * Wysyła komendę z potwierdzeniem (blokujące – poza wątkiem głównym). Bez internetu: [State.QUEUED]
     * i ponowienie w tle. Rzuca [IllegalStateException], gdy telefon nie jest połączony z CAR OS.
     */
    fun send(ctx: Context, o: JSONObject, label: String): Item {
        init(ctx)
        val p = Store.pairing.value ?: throw IllegalStateException("Najpierw połącz z CAR OS kodem QR")
        val mid = Proto.newMid()
        o.put(Proto.MID, mid)
        Proto.stamp(o, BuildConfig.VERSION_NAME)
        put(Item(mid, o.optString(Proto.TYPE), label, System.currentTimeMillis(), State.QUEUED, payload = o.toString()))
        return try {
            Link.send(p, o)
            val st = if (CarData.carSupports(Proto.Feat.ACK)) State.SENT else State.NOACK
            val item = update(mid) { x -> x.copy(state = st, tries = x.tries + 1) }
            if (st == State.SENT) watch(ctx, mid)
            item ?: items.value.first { x -> x.mid == mid }
        } catch (_: Exception) {
            update(mid) { x -> x.copy(tries = x.tries + 1) }
            scheduleRetry(ctx)
            items.value.first { x -> x.mid == mid }
        }
    }

    suspend fun sendAsync(ctx: Context, o: JSONObject, label: String): Item = withContext(Dispatchers.IO) { send(ctx, o, label) }

    /** Komunikat po wysłaniu (Toast). */
    fun sentText(it: Item, done: String): String = when (it.state) {
        State.QUEUED -> "Brak internetu – wyślę do auta, gdy wróci"
        else -> done
    }

    /** Zaraz po wysłaniu: kilka szybkich sprawdzeń, czy radio potwierdziło (ok. 3 min). */
    private fun watch(ctx: Context, mid: String) {
        synchronized(watchers) { watchers[mid]?.cancel() }
        val job = scope.launch {
            val since = System.currentTimeMillis() / 1000 - 5
            for (d in longArrayOf(2, 3, 4, 5, 8, 10, 15, 20, 30, 45, 60)) {
                delay(d * 1000)
                if (items.value.firstOrNull { it.mid == mid }?.state != State.SENT) break
                val p = Store.pairing.value ?: break
                val acks = try { Link.fetchAcks(p, since) } catch (_: Exception) { continue }
                onAcks(ctx, acks)
            }
            synchronized(watchers) { watchers.remove(mid) }
        }
        synchronized(watchers) { watchers[mid] = job }
    }

    /** Potwierdzenia z radia (szybkie odpytanie albo zwykłe odświeżenie). */
    fun onAcks(ctx: Context, acks: List<Proto.Ack>) {
        init(ctx)
        for (a in acks) {
            val cur = items.value.firstOrNull { it.mid == a.mid } ?: continue
            if (cur.state == State.DONE || cur.state == State.FAILED || cur.state == State.UNKNOWN) continue
            val st = when {
                !a.known -> State.UNKNOWN
                a.ok -> State.DONE
                else -> State.FAILED
            }
            update(a.mid) { it.copy(state = st, detail = a.text, payload = "", doneAt = System.currentTimeMillis()) }
            if (st == State.UNKNOWN) CarData.setNack(a.of)
            EventLog.add(
                EventLog.Cat.CAR,
                when (st) {
                    State.DONE -> "Auto wykonało: ${cur.label}" + (a.text.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")
                    State.FAILED -> "Błąd w aucie: ${cur.label}" + (a.text.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "")
                    else -> "CAR OS w aucie nie zna: ${cur.label} – zaktualizuj CAR OS"
                },
                err = st != State.DONE,
            )
        }
    }

    /** Wyślij komendy z kolejki (internet wrócił). Zwraca, ile jeszcze czeka. */
    fun flush(ctx: Context): Int {
        init(ctx)
        val p = Store.pairing.value ?: return 0
        var waiting = 0
        for (item in items.value.filter { x -> x.state == State.QUEUED }.reversed()) {
            if (System.currentTimeMillis() - item.time > 12 * 3600_000L) {
                update(item.mid) { x -> x.copy(state = State.FAILED, detail = "nie wysłano – brak internetu przez 12 h", payload = "") }
                continue
            }
            val o = try { JSONObject(item.payload) } catch (_: Exception) { continue }
            try {
                Link.send(p, o)
                val st = if (CarData.carSupports(Proto.Feat.ACK)) State.SENT else State.NOACK
                update(item.mid) { x -> x.copy(state = st, tries = x.tries + 1) }
                if (st == State.SENT) watch(ctx, item.mid)
            } catch (_: Exception) {
                update(item.mid) { x -> x.copy(tries = x.tries + 1) }
                waiting++
            }
        }
        return waiting
    }

    /** Wyślij teraz to, co czeka w kolejce (przycisk „Wyślij”). */
    suspend fun flushAsync(ctx: Context): Int = withContext(Dispatchers.IO) { try { flush(ctx) } catch (_: Exception) { 1 } }

    fun dismiss(mid: String) {
        items.value = items.value.filter { it.mid != mid }
        save()
    }

    private fun scheduleRetry(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("caros-outbox", ExistingWorkPolicy.REPLACE, req)
    }

    /** Opis stanu do listy w zakładce Auto. */
    fun stateText(it: Item): String = when (it.state) {
        State.QUEUED -> "Czeka na internet – wyślę sama"
        State.SENT -> "Wysłano – czeka na auto (radio musi mieć internet)"
        State.DONE -> it.detail.ifBlank { "Auto wykonało" }
        State.FAILED -> "Błąd: " + it.detail.ifBlank { "nie udało się w aucie" }
        State.UNKNOWN -> "CAR OS w aucie nie zna tej funkcji – zaktualizuj CAR OS"
        State.NOACK -> "Wysłano (ten CAR OS nie potwierdza – zaktualizuj do 2.0)"
    }
}

/** Ponowienie wysyłki z kolejki, gdy wróci internet. */
class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        CarData.init(applicationContext)
        val left = withContext(Dispatchers.IO) { try { Outbox.flush(applicationContext) } catch (_: Exception) { 1 } }
        return if (left > 0 && runAttemptCount < 8) Result.retry() else Result.success()
    }
}
