package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.mati.caros.core.Look
import pl.mati.caros.core.Proto

/**
 * Ten sam wygląd w telefonie i w CAR OS: Glass (gładkie / matowe), jasny / ciemny, kolor akcentu.
 * Animacje NIE – telefon jest mocniejszy od radia, każde ma swoje ustawienie.
 * Zmiana w telefonie idzie do auta komendą „look”, zmiana w radiu przychodzi w statusie – nowsza wygrywa.
 * Przy pierwszym połączeniu wygrywa wygląd z telefonu.
 */
object LookSync {
    /** Wspólny wygląd włączony (Ustawienia → Wygląd). */
    val enabled = MutableStateFlow(true)
    /** Kolor akcentu w motywie jasnym / ciemnym (null = domyślny turkus). W Glass – akcent z ustawień Glass. */
    val accent = MutableStateFlow<Int?>(null)
    private var sp: SharedPreferences? = null
    private var appCtx: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val DEFAULT = 0xFF2FD3F0.toInt()

    fun init(ctx: Context) {
        if (sp != null) return
        appCtx = ctx.applicationContext
        val p = ctx.applicationContext.getSharedPreferences("look", Context.MODE_PRIVATE)
        sp = p
        enabled.value = p.getBoolean("on", true)
        accent.value = p.getLong("accent", 0L).takeIf { it != 0L }?.toInt()
        start()
    }

    private fun time() = sp?.getLong("t", 0L) ?: 0L

    /** Wygląd telefonu teraz. */
    fun current(): Look {
        val theme = Store.theme.value
        val spec = GlassTheme.spec.value
        val glass = theme == "glass"
        val tone = if (glass) spec.tone.takeIf { it in Look.TONES } ?: "dark" else when (theme) { "light" -> "light"; "dark" -> "dark"; else -> "auto" }
        val acc = if (glass) spec.accent?.toInt() ?: GlassState.autoAccent?.toArgb() ?: GlassTheme.DEFAULT_ACCENT.toArgb()
        else accent.value ?: DEFAULT
        return Look(glass, tone, acc, spec.anim, time(), spec.frost)
    }

    fun setEnabled(v: Boolean) {
        enabled.value = v
        sp?.edit()?.putBoolean("on", v)?.apply()
        // po włączeniu – wygląd z telefonu do auta
        if (v) { sp?.edit()?.putLong("t", System.currentTimeMillis())?.apply(); push() }
    }

    /** Akcent wybrany w telefonie (motyw jasny / ciemny). */
    fun setAccent(c: Int?) {
        accent.value = c
        sp?.edit()?.putLong("accent", c?.toLong()?.and(0xFFFFFFFFL) ?: 0L)?.apply()
    }

    /** Do porównań „czy coś się zmieniło” – bez animacji (nie są wspólne). */
    private fun key(l: Look) = l.copy(anim = true).content

    @OptIn(FlowPreview::class)
    private fun start() {
        scope.launch {
            delay(2000)
            combine(Store.theme, GlassTheme.spec, accent) { _, _, _ -> key(current()) }
                .debounce(2000)
                .collect { c ->
                    val p = sp ?: return@collect
                    val prev = p.getString("c", null)
                    if (prev == null) {
                        // punkt odniesienia; czas 1 – przy pierwszym połączeniu wygląd z telefonu wygrywa z autem (0)
                        p.edit().putString("c", c).putLong("t", maxOf(1L, time())).apply()
                        return@collect
                    }
                    if (c == prev) return@collect
                    p.edit().putString("c", c).putLong("t", System.currentTimeMillis()).apply()
                    push()
                }
        }
    }

    private var pushedT = 0L
    private var pushedAt = 0L

    /** Wyślij wygląd telefonu do auta (gdy auto go obsługuje). */
    private fun push() {
        val ctx = appCtx ?: return
        if (!enabled.value || Store.pairing.value == null) return
        if (!CarData.carSupports(Proto.Cmd.LOOK) || CarData.car.value == null) return
        val l = current()
        // ten sam wygląd nie częściej niż co 10 min (status z auta mógł jeszcze nie dojść)
        if (l.t == pushedT && System.currentTimeMillis() - pushedAt < 10 * 60_000L) return
        pushedT = l.t
        pushedAt = System.currentTimeMillis()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Outbox.send(ctx, JSONObject().put("type", Proto.Cmd.LOOK).put("look", l.toJson()), "Wygląd: ${l.describe()}") }
            } catch (_: Exception) {}
        }
    }

    /** Wygląd z auta (status): nowszy – zastosuj w telefonie; starszy i inny – wyślij telefonu. */
    fun onCar(car: Look?) {
        val ctx = appCtx ?: return
        if (car == null || !enabled.value) return
        val mine = current()
        when {
            car.t > mine.t -> scope.launch(Dispatchers.Main) { apply(ctx, car) }
            mine.t > car.t && key(mine) != key(car) -> push()
        }
    }

    private fun apply(ctx: Context, l: Look) {
        if (l.glass) {
            if (Store.theme.value != "glass") Store.setTheme("glass")
            GlassTheme.update(ctx) { s ->
                // animacja zostaje telefonu (radio jest słabsze – tam może być wyłączona)
                s.copy(tone = l.tone, accent = l.accent?.toLong()?.and(0xFFFFFFFFL) ?: s.accent, frost = l.frost ?: s.frost)
            }
        } else {
            val t = when (l.tone) { "light" -> "light"; "dark" -> "dark"; else -> "auto" }
            if (Store.theme.value != t) Store.setTheme(t)
            l.accent?.let { setAccent(if (it == DEFAULT) null else it) }
            val f = l.frost
            if (f != null && GlassTheme.spec.value.frost != f) {
                GlassTheme.update(ctx) { s -> s.copy(frost = f) }
            }
        }
        // „zsynchronizowany” stan – nasłuch zmian nie wyśle tego z powrotem do auta
        sp?.edit()?.putString("c", key(current()))?.putLong("t", l.t)?.apply()
        EventLog.add(EventLog.Cat.CAR, "Wygląd z auta: ${l.describe()}")
    }
}
