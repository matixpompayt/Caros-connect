package pl.mati.carosconnect

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

/**
 * Zaplanowany wyjazd: cel, godzina wyjazdu (albo „być na miejscu o…”) i przypomnienie 10 min przed wyjazdem.
 * Przy przypomnieniu trasa liczona jest jeszcze raz z aktualnymi korkami – godzina wyjazdu może się przesunąć.
 */
object TripPlan {

    data class Plan(
        val name: String, val detail: String, val lat: Double, val lon: Double,
        val fromLat: Double, val fromLon: Double,
        /** Godzina wyjazdu i przyjazdu (ms). */
        val depart: Long, val arrive: Long,
        /** true – użytkownik podał „być o”, wyjazd liczony wstecz. */
        val arriveBy: Boolean,
        val delayS: Int?,
    ) {
        val remindAt get() = depart - REMIND_MS
    }

    const val REMIND_MS = 10 * 60_000L
    private const val CH = "trip_plan"
    private const val ID = 10

    val plan = MutableStateFlow<Plan?>(null)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("trip_plan", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val raw = sp(ctx).getString("plan", null) ?: run { plan.value = null; return }
        plan.value = try {
            val o = JSONObject(raw)
            Plan(
                o.getString("n"), o.optString("d"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("flat"), o.getDouble("flon"),
                o.getLong("dep"), o.getLong("arr"), o.optBoolean("by"), if (o.has("delay")) o.optInt("delay") else null,
            ).takeIf { it.arrive > System.currentTimeMillis() - 3600_000 }
        } catch (_: Exception) {
            null
        }
    }

    private fun persist(ctx: Context, p: Plan?) {
        plan.value = p
        sp(ctx).edit().putString(
            "plan",
            p?.let {
                JSONObject().put("n", it.name).put("d", it.detail).put("lat", it.lat).put("lon", it.lon)
                    .put("flat", it.fromLat).put("flon", it.fromLon).put("dep", it.depart).put("arr", it.arrive)
                    .put("by", it.arriveBy).apply { it.delayS?.let { d -> put("delay", d) } }.toString()
            },
        ).apply()
    }

    fun set(ctx: Context, p: Plan) {
        EventLog.add(EventLog.Cat.NAV, "Zaplanowano wyjazd: ${p.name} – wyjazd ${RoutePreview.hhmm(p.depart)}, przyjazd ${RoutePreview.hhmm(p.arrive)}")
        persist(ctx, p)
        schedule(ctx)
    }

    fun cancel(ctx: Context) {
        plan.value?.let { EventLog.add(EventLog.Cat.NAV, "Odwołano wyjazd: ${it.name}") }
        persist(ctx, null)
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx))
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (_: Exception) {}
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 71, Intent(ctx, TripPlanReceiver::class.java).setAction(TripPlanReceiver.FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        val p = plan.value ?: return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(ctx)
        am.cancel(pi)
        val at = maxOf(p.remindAt, System.currentTimeMillis() + 3_000)
        if (p.depart < System.currentTimeMillis() - 60_000) return
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Alarm: przelicz trasę z aktualnymi korkami i pokaż „czas wyjeżdżać”. */
    suspend fun fire(ctx: Context) {
        load(ctx)
        val p = plan.value ?: return
        val now = System.currentTimeMillis()
        // świeże korki: przy „być o” nowa godzina wyjazdu, przy stałym wyjeździe – nowy przyjazd
        val fresh = withTimeoutOrNull(8_000) {
            try {
                RoutePreview.compute(ctx, LatLng(p.fromLat, p.fromLon), LatLng(p.lat, p.lon), maxOf(now, p.depart), withWeather = false)
            } catch (_: Exception) {
                null
            }
        }
        val q = if (fresh != null) {
            if (p.arriveBy) {
                val dep = p.arrive - (fresh.travelS * 1000).toLong()
                p.copy(depart = dep, delayS = fresh.delayS)
            } else {
                p.copy(arrive = p.depart + (fresh.travelS * 1000).toLong(), delayS = fresh.delayS)
            }
        } else p
        persist(ctx, q)
        val late = q.depart < now
        val title = if (late) "Wyjedź teraz do: ${q.name}" else "Za ${((q.depart - now) / 60_000.0).roundToInt()} min wyjazd do: ${q.name}"
        val text = buildString {
            append(if (q.arriveBy) "Żeby być o ${RoutePreview.hhmm(q.arrive)}, wyjedź o ${RoutePreview.hhmm(q.depart)}" else "Wyjazd o ${RoutePreview.hhmm(q.depart)}, przyjazd ok. ${RoutePreview.hhmm(q.arrive)}")
            q.delayS?.let { d -> append(if (d >= 60) " · korki +${(d / 60.0).roundToInt()} min" else " · bez korków") }
            if (late && q.arriveBy) append(" – już po czasie, jedź od razu")
        }
        EventLog.add(EventLog.Cat.NAV, "Przypomnienie o wyjeździe: $title – $text")
        notify(ctx, title, text)
        // przypomnienie zrobione – plan zostaje do przyjazdu (widać go w aplikacji), bez kolejnych alarmów
    }

    private fun notify(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH, "Wyjazd w trasę", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Przypomnienie 10 min przed zaplanowanym wyjazdem (z korkami)"
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 72, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val nav = PendingIntent.getBroadcast(
            ctx, 73, Intent(ctx, TripPlanReceiver::class.java).setAction(TripPlanReceiver.NAV),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 74, Intent(ctx, TripPlanReceiver::class.java).setAction(TripPlanReceiver.CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (Store.pairing.value != null) b.addAction(Notification.Action.Builder(null, "Nawiguj w aucie", nav).build())
        b.addAction(Notification.Action.Builder(null, "Odwołaj", stop).build())
        try { nm.notify(ID, b.build()) } catch (_: SecurityException) {}
    }

    /** Akcja z powiadomienia: wyślij cel do radia (trasa będzie gotowa po uruchomieniu). */
    fun sendRoute(ctx: Context) {
        val p = plan.value ?: return
        val pairing = Store.pairing.value ?: return
        Link.send(pairing, JSONObject().put("type", "route").put("n", p.name).put("d", p.detail).put("lat", p.lat).put("lon", p.lon))
    }
}

class TripPlanReceiver : BroadcastReceiver() {
    companion object {
        const val FIRE = "pl.mati.carosconnect.TRIP_FIRE"
        const val NAV = "pl.mati.carosconnect.TRIP_NAV"
        const val CANCEL = "pl.mati.carosconnect.TRIP_CANCEL"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val pending = goAsync()
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    FIRE -> TripPlan.fire(app)
                    NAV -> {
                        TripPlan.load(app)
                        try {
                            TripPlan.sendRoute(app)
                            app.getSystemService(NotificationManager::class.java)?.cancel(10)
                        } catch (_: Exception) {
                        }
                    }
                    CANCEL -> TripPlan.cancel(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Po restarcie telefonu – alarm przypomnienia ustawiany jeszcze raz. */
class TripBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        TripPlan.load(ctx)
        TripPlan.schedule(ctx)
    }
}
