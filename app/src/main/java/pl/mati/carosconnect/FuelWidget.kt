package pl.mati.carosconnect

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Widżet „Ceny paliw”: ON / Pb95 / Pb98 / LPG dziś, zmiana i ceny na jutro. */
class FuelWidget : BaseWidget() {
    override val defW = 280
    override val defH = 130

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        FuelPrices.init(ctx)
        super.onUpdate(ctx, mgr, ids)
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

    override fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews {
        FuelPrices.init(ctx)
        val v = RemoteViews(ctx.packageName, R.layout.widget_fuel)
        v.setOnClickPendingIntent(R.id.f_root, WidgetTheme.openApp(ctx, 20, tab = 1))
        v.setTextColor(R.id.f_head, lk.accent)
        v.setTextColor(R.id.f_date, lk.muted)
        v.setTextColor(R.id.f_tomorrow, lk.text2)
        v.setTextViewText(R.id.f_date, "dziś " + LocalDate.now().format(DAY))
        val s = FuelPrices.data.value
        val fav = FuelPrices.fav.value
        val cells = listOf(
            FuelPrices.Fuel.ON to R.id.f_on, FuelPrices.Fuel.PB95 to R.id.f_pb95,
            FuelPrices.Fuel.PB98 to R.id.f_pb98, FuelPrices.Fuel.LPG to R.id.f_lpg,
        )
        val ids = mapOf(
            R.id.f_on to intArrayOf(R.id.f_on_bg, R.id.f_on_l, R.id.f_on_v, R.id.f_on_s),
            R.id.f_pb95 to intArrayOf(R.id.f_pb95_bg, R.id.f_pb95_l, R.id.f_pb95_v, R.id.f_pb95_s),
            R.id.f_pb98 to intArrayOf(R.id.f_pb98_bg, R.id.f_pb98_l, R.id.f_pb98_v, R.id.f_pb98_s),
            R.id.f_lpg to intArrayOf(R.id.f_lpg_bg, R.id.f_lpg_l, R.id.f_lpg_v, R.id.f_lpg_s),
        )
        for ((f, box) in cells) {
            val (bg, label, price, change) = ids.getValue(box).toList()
            WidgetTheme.cell(v, bg, lk, hl = f == fav)
            v.setTextColor(label, if (f == fav) lk.accent else lk.muted)
            v.setTextColor(price, lk.text)
            v.setTextViewText(price, s?.today?.get(f)?.let { FuelPrices.zl(it) } ?: "--")
            val ch = s?.change?.get(f)
            when {
                ch == null -> { v.setTextViewText(change, if (f == FuelPrices.Fuel.LPG) "średnia" else ""); v.setTextColor(change, lk.muted) }
                kotlin.math.abs(ch) < 0.005 -> { v.setTextViewText(change, "bez zmian"); v.setTextColor(change, lk.muted) }
                else -> {
                    v.setTextViewText(change, (if (ch > 0) "▲ " else "▼ ") + FuelPrices.zl(kotlin.math.abs(ch)))
                    v.setTextColor(change, if (ch > 0) lk.red else lk.green)
                }
            }
        }
        v.setViewVisibility(R.id.f_tomorrow, if (hDp < 120) android.view.View.GONE else android.view.View.VISIBLE)
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

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("EEE d.MM", Locale("pl"))
        fun updateAll(ctx: Context) = refresh(ctx, FuelWidget())
    }
}
