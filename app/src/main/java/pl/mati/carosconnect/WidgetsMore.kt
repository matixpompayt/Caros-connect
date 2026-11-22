package pl.mati.carosconnect

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val PL = Locale("pl")
private fun f1(v: Double) = String.format(PL, "%.1f", v)
private fun f2(v: Double) = String.format(PL, "%.2f", v)

/** Kafelek z trzema napisami (etykieta, wartość, podpis) – kolory z motywu. */
private fun cellText(v: RemoteViews, lk: WidgetTheme.Look, l: Int, value: Int, sub: Int, valText: String, subText: String, subColor: Int? = null) {
    v.setTextColor(l, lk.muted)
    v.setTextColor(value, lk.text)
    v.setTextColor(sub, subColor ?: lk.muted)
    v.setTextViewText(value, valText)
    v.setTextViewText(sub, subText)
    v.setViewVisibility(sub, if (subText.isBlank()) View.GONE else View.VISIBLE)
}

/** Widżet „Zasięg”: pierścień zasięgu na paliwie, spalanie, koszt kilometra, ostatnie tankowanie. */
class RangeWidget : BaseWidget() {
    override val defW = 150
    override val defH = 150

    override fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_range)
        v.setOnClickPendingIntent(R.id.r_root, WidgetTheme.openApp(ctx, 30, tab = 1))
        v.setTextColor(R.id.r_head, lk.accent)
        v.setTextColor(R.id.r_km, lk.text)
        v.setTextColor(R.id.r_unit, lk.muted)
        v.setTextColor(R.id.r_line1, lk.text)
        v.setTextColor(R.id.r_line2, lk.muted)
        val fu = CarData.status.value?.fuel
        val range = fu?.range
        val reserve = Store.minRange.value
        val avg = fu?.avg
        val full = if (fu != null && avg != null && avg > 2 && fu.tank > 0) fu.tank / avg * 100 else null
        val frac = if (range != null && full != null) (range / full).toFloat() else if (range != null) 0.6f else 0f
        val col = when {
            range == null -> lk.muted
            range < reserve -> lk.red
            range < reserve * 1.6 -> lk.orange
            else -> lk.green
        }
        val ringDp = (minOf(wDp, hDp) - 70).coerceIn(56, 140)
        v.setImageViewBitmap(R.id.r_ring, WidgetTheme.ring(ctx, lk, frac, col, ringDp))
        v.setTextViewText(R.id.r_km, range?.roundToInt()?.toString() ?: "--")
        v.setTextViewText(R.id.r_unit, if (range == null) "brak danych" else if (fu?.estimated == true) "km (szac.)" else "km")
        val l1 = listOfNotNull(fu?.avg?.let { "${f1(it)} l/100" }, fu?.costKm?.let { "${f2(it)} zł/km" })
        v.setTextViewText(R.id.r_line1, if (l1.isEmpty()) "Dodaj tankowania w CAR OS" else l1.joinToString(" · "))
        val last = fu?.fills?.maxByOrNull { it.time }
        val l2 = listOfNotNull(
            range?.let { if (it < reserve) "Zatankuj – poniżej $reserve km" else null },
            last?.let { "tankowano " + SimpleDateFormat("d.MM", PL).format(Date(it.time)) },
        )
        v.setTextViewText(R.id.r_line2, l2.joinToString(" · "))
        v.setTextColor(R.id.r_line2, if (range != null && range < reserve) lk.red else lk.muted)
        v.setViewVisibility(R.id.r_line2, if (l2.isEmpty() || hDp < 130) View.GONE else View.VISIBLE)
        return v
    }

    companion object {
        fun updateAll(ctx: Context) = refresh(ctx, RangeWidget())
    }
}

/** Widżet „Stan auta”: przebieg, akumulator, najbliższy serwis, pogoda przy aucie. */
class StatusWidget : BaseWidget() {
    override val defW = 280
    override val defH = 180

    override fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_status)
        v.setOnClickPendingIntent(R.id.s_root, WidgetTheme.openApp(ctx, 31, tab = 1))
        v.setTextColor(R.id.s_head, lk.accent)
        v.setTextColor(R.id.s_car, lk.muted)
        listOf(R.id.s_odo_bg, R.id.s_bat_bg, R.id.s_svc_bg, R.id.s_wx_bg).forEach { WidgetTheme.cell(v, it, lk) }
        val st = CarData.status.value
        v.setTextViewText(R.id.s_car, st?.car?.takeIf { it.isNotBlank() } ?: "")
        // przebieg
        cellText(
            v, lk, R.id.s_odo_l, R.id.s_odo_v, R.id.s_odo_s,
            st?.odo?.let { String.format(PL, "%,d km", it).replace(',', ' ') } ?: "--",
            st?.let { "dziś ${BaseWidget.km(it.todayM)} km" } ?: "",
        )
        // akumulator
        val b = st?.bat
        cellText(
            v, lk, R.id.s_bat_l, R.id.s_bat_v, R.id.s_bat_s,
            b?.rest?.let { "${f1(it)} V" } ?: when (b?.level) { null -> "--"; 0 -> "OK"; 1 -> "Uwaga"; else -> "Słaby" },
            b?.let { when (it.level) { 0 -> "dobry"; 1 -> "do sprawdzenia"; else -> "słaby – naładuj" } } ?: "brak pomiaru",
            b?.let { when (it.level) { 0 -> lk.green; 1 -> lk.orange; else -> lk.red } },
        )
        // serwis – najpilniejszy
        val svc = st?.serviceList?.filter { it.known }?.sortedWith(compareBy({ if (it.overdue) 0 else if (it.soon) 1 else 2 }, { -it.fraction }))?.firstOrNull()
        cellText(
            v, lk, R.id.s_svc_l, R.id.s_svc_v, R.id.s_svc_s,
            svc?.name ?: "--",
            svc?.text ?: "brak przypomnień",
            svc?.let { if (it.overdue) lk.red else if (it.soon) lk.orange else lk.muted },
        )
        // pogoda przy aucie
        val wx = CarData.weather.value?.takeIf { System.currentTimeMillis() - it.time < 6 * 3600_000L }
        cellText(
            v, lk, R.id.s_wx_l, R.id.s_wx_v, R.id.s_wx_s,
            wx?.let { "${it.temp.roundToInt()}°C" } ?: "--",
            wx?.let { if (it.frost) "przymrozek – szyby!" else "min. ${it.minPast.roundToInt()}° · wilg. ${it.humidity}%" } ?: "",
            wx?.let { if (it.frost) lk.orange else null },
        )
        v.setViewVisibility(R.id.s_row2, if (hDp < 140) View.GONE else View.VISIBLE)
        return v
    }

    companion object {
        fun updateAll(ctx: Context) = refresh(ctx, StatusWidget())
    }
}

/** Widżet „Jazda”: kilometry dziś / w tygodniu / w miesiącu i ostatnia trasa. */
class DriveWidget : BaseWidget() {
    override val defW = 280
    override val defH = 130

    override fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_drive)
        v.setOnClickPendingIntent(R.id.d_root, WidgetTheme.openApp(ctx, 32, tab = 2))
        v.setTextColor(R.id.d_head, lk.accent)
        v.setTextColor(R.id.d_last, lk.text2)
        listOf(R.id.d_day_bg, R.id.d_week_bg, R.id.d_month_bg).forEach { WidgetTheme.cell(v, it, lk) }
        val st = CarData.status.value
        cellText(v, lk, R.id.d_day_l, R.id.d_day_v, R.id.d_day_s, st?.let { BaseWidget.km(it.todayM) } ?: "--", "km")
        cellText(v, lk, R.id.d_week_l, R.id.d_week_v, R.id.d_week_s, st?.let { BaseWidget.km(it.weekM) } ?: "--", "km")
        cellText(v, lk, R.id.d_month_l, R.id.d_month_v, R.id.d_month_s, st?.let { BaseWidget.km(it.monthM) } ?: "--", "km")
        val t = CarData.trips.value.maxByOrNull { it.start }
        v.setTextViewText(
            R.id.d_last,
            t?.let {
                val min = (it.durationS / 60).toInt()
                "Ostatnia trasa ${BaseWidget.whenDay(it.start)}${BaseWidget.hm(it.start)}: ${BaseWidget.km(it.distanceM)} km · " +
                    (if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min") + " · śr. ${it.avgKmh.roundToInt()} km/h"
            } ?: "Brak tras – pojawią się po jeździe z CAR OS",
        )
        v.setViewVisibility(R.id.d_last, if (hDp < 120) View.GONE else View.VISIBLE)
        return v
    }

    companion object {
        fun updateAll(ctx: Context) = refresh(ctx, DriveWidget())
    }
}

/** Widżet „Praca”: najbliższa zmiana, godzina wyjazdu (z czasem dojazdu z CAR OS). */
class WorkWidget : BaseWidget() {
    override val defW = 200
    override val defH = 120

    override fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_work)
        v.setOnClickPendingIntent(R.id.k_root, WidgetTheme.openApp(ctx, 33, tab = 1))
        v.setTextColor(R.id.k_head, lk.accent)
        v.setTextColor(R.id.k_day, lk.muted)
        v.setTextColor(R.id.k_big, lk.text)
        v.setTextColor(R.id.k_sub, lk.muted)
        v.setTextColor(R.id.k_note, lk.orange)
        val st = CarData.status.value
        val now = System.currentTimeMillis()
        val cur = st?.shifts?.firstOrNull { it.start <= now && it.end > now }
        val sh = st?.shifts?.filter { it.start > now }?.minByOrNull { it.start }
        when {
            cur != null -> {
                v.setTextViewText(R.id.k_day, "teraz")
                v.setTextViewText(R.id.k_big, "W pracy do ${BaseWidget.hm(cur.end)}")
                v.setTextViewText(R.id.k_sub, listOf(cur.name, cur.hours).filter { it.isNotBlank() }.joinToString(" · ") +
                    (sh?.let { "\nNastępna: ${BaseWidget.whenDay(it.start)}${BaseWidget.hm(it.start)}" } ?: ""))
            }
            sh != null -> {
                val dep = WorkReminder.departure(sh)
                v.setTextViewText(R.id.k_day, (BaseWidget.whenDay(sh.start).ifBlank { "dziś " }).trim())
                v.setTextViewText(R.id.k_big, if (dep != null) "Wyjazd ${BaseWidget.hm(dep)}" else "Zmiana ${BaseWidget.hm(sh.start)}")
                val parts = ArrayList<String>()
                if (sh.name.isNotBlank()) parts += sh.name
                parts += "start ${BaseWidget.hm(sh.start)}"
                st?.commuteS?.let { parts += "dojazd ${(it / 60.0).roundToInt()} min" }
                v.setTextViewText(R.id.k_sub, parts.joinToString(" · "))
                if (dep != null && dep - now in 0..45 * 60_000L) {
                    v.setViewVisibility(R.id.k_note, View.VISIBLE)
                    v.setTextViewText(R.id.k_note, "Za ${((dep - now) / 60_000).toInt()} min wyjazd")
                } else v.setViewVisibility(R.id.k_note, View.GONE)
                return v
            }
            else -> {
                v.setTextViewText(R.id.k_day, "")
                v.setTextViewText(R.id.k_big, "Brak zmian")
                v.setTextViewText(R.id.k_sub, "Grafik dodasz w CAR OS (Praca)")
            }
        }
        v.setViewVisibility(R.id.k_note, View.GONE)
        return v
    }

    companion object {
        fun updateAll(ctx: Context) = refresh(ctx, WorkWidget())
    }
}

/** Widżet „Skróty”: prowadź do auta, zapisz miejsce tutaj, trasy, auto. */
class ShortcutsWidget : BaseWidget() {
    override val defW = 280
    override val defH = 80

    override fun build(ctx: Context, lk: WidgetTheme.Look, wDp: Int, hDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_shortcuts)
        val items = listOf(
            intArrayOf(R.id.x_walk, R.id.x_walk_bg, R.id.x_walk_i, R.id.x_walk_t),
            intArrayOf(R.id.x_save, R.id.x_save_bg, R.id.x_save_i, R.id.x_save_t),
            intArrayOf(R.id.x_route, R.id.x_route_bg, R.id.x_route_i, R.id.x_route_t),
            intArrayOf(R.id.x_car, R.id.x_car_bg, R.id.x_car_i, R.id.x_car_t),
        )
        items.forEachIndexed { i, (_, bg, ic, t) ->
            WidgetTheme.cell(v, bg, lk, hl = i == 0)
            v.setInt(ic, "setColorFilter", if (i == 0) lk.accent else lk.text)
            v.setTextColor(t, lk.text)
            v.setViewVisibility(t, if (hDp < 70) View.GONE else View.VISIBLE)
        }
        val walk = PendingIntent.getActivity(ctx, 40, WalkNav.intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.x_walk, walk)
        v.setOnClickPendingIntent(R.id.x_save, WidgetTheme.openApp(ctx, 41, tab = 0, action = "save_here"))
        v.setOnClickPendingIntent(R.id.x_route, WidgetTheme.openApp(ctx, 42, tab = 2))
        v.setOnClickPendingIntent(R.id.x_car, WidgetTheme.openApp(ctx, 43, tab = 1))
        return v
    }

    companion object {
        fun updateAll(ctx: Context) = refresh(ctx, ShortcutsWidget())
    }
}
