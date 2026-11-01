package pl.mati.carosconnect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Przypomnienia przed pracą wg grafiku z CAR OS (Tesla / własny):
 * 2 h przed zmianą („wyjedź o …” z czasem dojazdu) i w chwili wyjazdu.
 */
object WorkReminder {
    private val HM = SimpleDateFormat("H:mm", Locale("pl"))
    private const val BUFFER_MS = 10 * 60_000L // zapas na parking / wejście

    data class Event(val at: Long, val kind: Int, val shift: CarData.Shift)

    /** Wszystkie przyszłe przypomnienia (do pokazania „najbliższe” w ustawieniach). */
    fun events(now: Long = System.currentTimeMillis()): List<Event> {
        val s = CarData.status.value ?: return emptyList()
        val commute = (s.commuteS ?: 0) * 1000L
        val out = ArrayList<Event>()
        for (sh in s.shifts) {
            if (sh.start <= now) continue
            if (CarData.notifWork.value) out += Event(sh.start - 2 * 3_600_000L, 0, sh)
            if (CarData.notifDepart.value && commute > 0) out += Event(sh.start - commute - BUFFER_MS, 1, sh)
        }
        return out.filter { it.at > now - 60_000 }.sortedBy { it.at }
    }

    fun departure(sh: CarData.Shift): Long? {
        val c = CarData.status.value?.commuteS ?: return null
        return sh.start - c * 1000L - BUFFER_MS
    }

    private fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 61, Intent(ctx, WorkReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        CarData.init(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = intent(ctx)
        am.cancel(p)
        val sp = CarData.prefs ?: return
        val done = sp.getString("work_done", "") ?: ""
        val next = events().firstOrNull { "${it.shift.start}:${it.kind}" !in done.split(",") } ?: return
        val at = maxOf(next.at, System.currentTimeMillis() + 5_000)
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    /** Alarm: pokaż przypomnienia, których czas minął, i ustaw następny. */
    fun fire(ctx: Context) {
        CarData.init(ctx)
        val sp = CarData.prefs ?: return
        val now = System.currentTimeMillis()
        val done = (sp.getString("work_done", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()
        for (e in events(now + 60_000)) {
            if (e.at > now + 60_000) break
            val key = "${e.shift.start}:${e.kind}"
            if (key in done) continue
            done += key
            // za późno (np. telefon był wyłączony) – nie przypominaj po rozpoczęciu zmiany
            if (now > e.shift.start) continue
            val commute = CarData.status.value?.commuteS?.let { it / 60 }
            val dep = departure(e.shift)
            val name = e.shift.name.lowercase(Locale("pl"))
            if (e.kind == 0) {
                Notifs.work(
                    ctx,
                    "Za 2 h zmiana $name (${e.shift.hours})",
                    if (dep != null && commute != null) "Wyjedź o ${HM.format(Date(dep))} – dojazd ok. $commute min"
                    else "Zmiana zaczyna się o ${HM.format(Date(e.shift.start))}",
                )
            } else {
                Notifs.work(
                    ctx,
                    "Czas wyjeżdżać do pracy",
                    "Zmiana $name o ${HM.format(Date(e.shift.start))}" + (commute?.let { " · dojazd ok. $it min" } ?: ""),
                )
            }
        }
        sp.edit().putString("work_done", done.takeLast(40).joinToString(",")).apply()
        schedule(ctx)
    }
}

class WorkReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WorkReminder.fire(ctx)
    }
}
