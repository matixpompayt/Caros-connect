package pl.mati.carosconnect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Licznik płatnego parkowania: przypomnienie 10 min przed końcem i na koniec. */
object Meter {

    const val WARN_MIN = 10
    private const val ACTION = "pl.mati.carosconnect.METER"

    fun start(ctx: Context, minutes: Int) {
        CarData.init(ctx)
        val now = System.currentTimeMillis()
        CarData.setMeter(now, now + minutes * 60_000L)
        schedule(ctx)
    }

    fun extend(ctx: Context, minutes: Int) {
        val end = CarData.meterEnd.value
        if (end == 0L) { start(ctx, minutes); return }
        val base = maxOf(end, System.currentTimeMillis())
        CarData.setMeter(CarData.meterStart.value, base + minutes * 60_000L)
        schedule(ctx)
    }

    fun stop(ctx: Context) {
        CarData.setMeter(0L, 0L)
        cancel(ctx)
    }

    private fun pi(ctx: Context, kind: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, 100 + kind,
        Intent(ctx, MeterReceiver::class.java).setAction(ACTION).putExtra("kind", kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(ctx, 0))
        am.cancel(pi(ctx, 1))
    }

    private fun schedule(ctx: Context) {
        cancel(ctx)
        val end = CarData.meterEnd.value
        if (end == 0L) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val warnAt = end - WARN_MIN * 60_000L
        if (warnAt > System.currentTimeMillis() + 30_000) set(am, warnAt, pi(ctx, 0))
        set(am, end, pi(ctx, 1))
    }

    private fun set(am: AlarmManager, at: Long, p: PendingIntent) {
        try {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }
}

class MeterReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        CarData.init(ctx)
        val end = CarData.meterEnd.value
        if (end == 0L) return
        when (intent.getIntExtra("kind", 1)) {
            0 -> Notifs.meter(ctx, "Parkowanie kończy się za ${Meter.WARN_MIN} min", "Przedłuż opłatę albo wróć do auta")
            else -> {
                Notifs.meter(ctx, "Czas parkowania minął", "Opłacony czas właśnie się skończył")
                CarData.setMeter(0L, 0L)
            }
        }
    }
}
