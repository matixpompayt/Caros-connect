package pl.mati.carosconnect

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Widżet „Ceny paliw”: ON / Pb95 / Pb98 / LPG dziś, zmiana i ceny na jutro. */
class FuelWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        FuelPrices.init(ctx)
        val v = views(ctx)
        ids.forEach { mgr.updateAppWidget(it, v) }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FuelPrices.refresh(ctx)
                updateAll(ctx)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("EEE d.MM", Locale("pl"))

        fun updateAll(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, FuelWidget::class.java))
                if (ids.isEmpty()) return
                FuelPrices.init(ctx)
                val v = views(ctx)
                ids.forEach { mgr.updateAppWidget(it, v) }
            } catch (_: Exception) {
            }
        }

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_fuel)
            val open = PendingIntent.getActivity(
                ctx, 20,
                Intent(ctx, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.f_root, open)
            v.setTextViewText(R.id.f_date, "dziś " + LocalDate.now().format(DAY))
            val s = FuelPrices.data.value
            val fav = FuelPrices.fav.value
            val cells = listOf(
                FuelPrices.Fuel.ON to Triple(R.id.f_on, R.id.f_on_p, R.id.f_on_c),
                FuelPrices.Fuel.PB95 to Triple(R.id.f_pb95, R.id.f_pb95_p, R.id.f_pb95_c),
                FuelPrices.Fuel.PB98 to Triple(R.id.f_pb98, R.id.f_pb98_p, R.id.f_pb98_c),
                FuelPrices.Fuel.LPG to Triple(R.id.f_lpg, R.id.f_lpg_p, R.id.f_lpg_c),
            )
            for ((f, ids) in cells) {
                val (box, price, change) = ids
                v.setInt(box, "setBackgroundResource", if (f == fav) R.drawable.widget_cell_fav else R.drawable.widget_cell)
                v.setTextViewText(price, s?.today?.get(f)?.let { FuelPrices.zl(it) } ?: "--")
                val ch = s?.change?.get(f)
                when {
                    ch == null -> v.setTextViewText(change, if (f == FuelPrices.Fuel.LPG) "średnia" else "")
                    kotlin.math.abs(ch) < 0.005 -> { v.setTextViewText(change, "bez zmian"); v.setTextColor(change, 0xFF9AA3AE.toInt()) }
                    else -> {
                        v.setTextViewText(change, (if (ch > 0) "▲ " else "▼ ") + FuelPrices.zl(kotlin.math.abs(ch)))
                        v.setTextColor(change, if (ch > 0) 0xFFFF6B6B.toInt() else 0xFF7CF06B.toInt())
                    }
                }
            }
            v.setTextViewText(
                R.id.f_tomorrow,
                when {
                    s == null -> "Otwórz aplikację, żeby pobrać ceny"
                    s.noCurrent -> "Brak aktualnego obwieszczenia o cenach maksymalnych"
                    s.tomorrowSame -> "Jutro te same ceny (obwieszczenie na weekend)"
                    s.tomorrow.isNotEmpty() -> "Jutro: " + listOf(FuelPrices.Fuel.ON, FuelPrices.Fuel.PB95, FuelPrices.Fuel.PB98).mapNotNull { f ->
                        s.tomorrow[f]?.let { t -> "${f.short} ${FuelPrices.zl(t)}" + (s.today[f]?.let { p -> if (kotlin.math.abs(t - p) < 0.005) "" else if (t > p) "▲" else "▼" } ?: "") }
                    }.joinToString(" · ")
                    else -> "Ceny na jutro jeszcze nieopublikowane"
                },
            )
            return v
        }
    }
}
