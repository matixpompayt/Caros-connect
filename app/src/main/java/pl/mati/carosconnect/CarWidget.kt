package pl.mati.carosconnect

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.widget.RemoteViews

/** Widżet ekranu głównego: gdzie stoi auto, odległość, „Prowadź”. */
class CarWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, CarWidget::class.java))
                if (ids.isEmpty()) return
                val v = views(ctx)
                ids.forEach { mgr.updateAppWidget(it, v) }
            } catch (_: Exception) {
            }
        }

        private fun views(ctx: Context): RemoteViews {
            Store.init(ctx)
            CarData.init(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_car)
            val open = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            v.setTextViewText(R.id.w_title, (Store.pairing.value?.car ?: "Moje auto").uppercase())
            val s = Store.spot.value
            if (s == null) {
                v.setTextViewText(R.id.w_address, "Brak zapisanego miejsca")
                v.setTextViewText(R.id.w_info, "Zapisze się po zgaszeniu auta")
                v.setOnClickPendingIntent(R.id.w_nav, open)
            } else {
                v.setTextViewText(R.id.w_address, s.address ?: s.coords)
                val parts = mutableListOf(whenText(s.time))
                try {
                    Locator.lastKnown(ctx)?.let { me ->
                        if (System.currentTimeMillis() - me.time < 30 * 60_000) {
                            val d = me.distanceTo(Location("car").apply { latitude = s.lat; longitude = s.lon })
                            parts += distText(d)
                        }
                    }
                } catch (_: Exception) {
                }
                val end = CarData.meterEnd.value
                if (end > System.currentTimeMillis()) parts += "parkometr do " + java.text.SimpleDateFormat("HH:mm", java.util.Locale("pl")).format(java.util.Date(end))
                v.setTextViewText(R.id.w_info, parts.joinToString(" · "))
                val walk = PendingIntent.getActivity(ctx, 11, Notifs.walkIntent(s), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                v.setOnClickPendingIntent(R.id.w_nav, walk)
            }
            // paliwo, wyjazd do pracy, akumulator
            try {
                val st = CarData.status.value
                val extra = ArrayList<String>()
                st?.fuel?.range?.let { extra += "Zasięg ${it.toInt()} km" }
                st?.shifts?.firstOrNull { it.start > System.currentTimeMillis() }?.let { sh ->
                    val dep = WorkReminder.departure(sh)
                    val fmt = java.text.SimpleDateFormat("H:mm", java.util.Locale("pl"))
                    extra += if (dep != null) "Wyjazd ${whenDay(dep)}${fmt.format(java.util.Date(dep))}" else "Zmiana ${whenDay(sh.start)}${fmt.format(java.util.Date(sh.start))}"
                }
                st?.bat?.let { b -> if (b.level >= 1) extra += (if (b.level >= 2) "Słaby akumulator" else "Akumulator – uwaga") }
                if (extra.isEmpty()) v.setViewVisibility(R.id.w_extra, android.view.View.GONE)
                else {
                    v.setViewVisibility(R.id.w_extra, android.view.View.VISIBLE)
                    v.setTextViewText(R.id.w_extra, extra.joinToString(" · "))
                    v.setTextColor(R.id.w_extra, if ((st?.bat?.level ?: 0) >= 2) 0xFFFF6B6B.toInt() else 0xFFEEF1F4.toInt())
                }
            } catch (_: Exception) {}
            return v
        }

        /** „” dziś, „jutro ” albo dzień tygodnia przed godziną. */
        private fun whenDay(t: Long): String {
            val c = java.util.Calendar.getInstance()
            val today = c.get(java.util.Calendar.DAY_OF_YEAR)
            c.timeInMillis = t
            return when (c.get(java.util.Calendar.DAY_OF_YEAR) - today) {
                0 -> ""
                1 -> "jutro "
                else -> java.text.SimpleDateFormat("EEE ", java.util.Locale("pl")).format(java.util.Date(t))
            }
        }
    }
}
