package pl.mati.carosconnect

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Wspólna baza widżetów: wygląd z motywu, rozmiar z launchera, odświeżanie. */
abstract class BaseWidget : AppWidgetProvider() {
    abstract val defW: Int
    abstract val defH: Int
    abstract fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(ctx, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle?) {
        render(ctx, mgr, id)
    }

    fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
        try {
            Store.init(ctx)
            CarData.init(ctx)
            val lk = WidgetTheme.look(ctx)
            val (w, h) = WidgetTheme.sizeDp(mgr, id, defW, defH)
            val v = build(ctx, lk, w, h)
            WidgetTheme.applyBg(ctx, v, lk, w, h)
            mgr.updateAppWidget(id, v)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun refresh(ctx: Context, p: BaseWidget) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, p.javaClass))
                ids.forEach { p.render(ctx, mgr, it) }
            } catch (_: Exception) {
            }
        }

        private val HM = SimpleDateFormat("H:mm", Locale("pl"))
        fun hm(t: Long): String = HM.format(Date(t))

        /** „” dziś, „jutro ” albo dzień tygodnia. */
        fun whenDay(t: Long): String {
            val c = java.util.Calendar.getInstance()
            val today = c.get(java.util.Calendar.DAY_OF_YEAR)
            c.timeInMillis = t
            return when (c.get(java.util.Calendar.DAY_OF_YEAR) - today) {
                0 -> ""
                1 -> "jutro "
                else -> SimpleDateFormat("EEE ", Locale("pl")).format(Date(t))
            }
        }

        fun km(m: Double): String = if (m < 10_000) String.format(Locale("pl"), "%.1f", m / 1000) else "${(m / 1000).toInt()}"
    }
}

/** Widżet „Auto”: gdzie stoi auto, odległość, parkometr, paliwo / wyjazd, „Prowadź do auta”. */
class CarWidget : BaseWidget() {
    override val defW = 200
    override val defH = 150

    override fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_car)
        val open = WidgetTheme.openApp(ctx, 10, tab = 0)
        v.setOnClickPendingIntent(R.id.w_root, open)
        v.setTextViewText(R.id.w_title, (Store.pairing.value?.car ?: "Moje auto").uppercase())
        v.setTextColor(R.id.w_title, lk.accent)
        v.setInt(R.id.w_p_bg, "setColorFilter", lk.accent)
        v.setTextColor(R.id.w_p, lk.onAccent)
        v.setTextColor(R.id.w_address, lk.text)
        v.setTextColor(R.id.w_info, lk.muted)
        v.setTextColor(R.id.w_dist, lk.text)
        WidgetTheme.pill(v, R.id.w_nav_bg, R.id.w_nav_t, lk, primary = true)
        val small = hDp < 120
        v.setViewVisibility(R.id.w_nav, if (small) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.w_dist, "")
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
                        v.setTextViewText(R.id.w_dist, distText(d))
                    }
                }
            } catch (_: Exception) {
            }
            val end = CarData.meterEnd.value
            if (end > System.currentTimeMillis()) parts += "parkometr do " + hm(end)
            v.setTextViewText(R.id.w_info, parts.joinToString(" · "))
            val walk = android.app.PendingIntent.getActivity(
                ctx, 11, WalkNav.intent(ctx), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_nav, walk)
        }
        // paliwo, wyjazd do pracy, akumulator
        val st = CarData.status.value
        val extra = ArrayList<String>()
        st?.fuel?.range?.let { extra += "Zasięg ${it.toInt()} km" }
        st?.shifts?.firstOrNull { it.start > System.currentTimeMillis() }?.let { sh ->
            val dep = WorkReminder.departure(sh)
            extra += if (dep != null) "Wyjazd ${whenDay(dep)}${hm(dep)}" else "Zmiana ${whenDay(sh.start)}${hm(sh.start)}"
        }
        st?.bat?.let { b -> if (b.level >= 1) extra += (if (b.level >= 2) "Słaby akumulator" else "Akumulator – uwaga") }
        if (extra.isEmpty() || hDp < 140) v.setViewVisibility(R.id.w_extra, View.GONE)
        else {
            v.setViewVisibility(R.id.w_extra, View.VISIBLE)
            v.setTextViewText(R.id.w_extra, extra.joinToString(" · "))
            v.setTextColor(R.id.w_extra, if ((st?.bat?.level ?: 0) >= 2) lk.red else lk.text2)
        }
        return v
    }

    companion object {
        fun updateAll(ctx: Context) = refresh(ctx, CarWidget())
    }
}
