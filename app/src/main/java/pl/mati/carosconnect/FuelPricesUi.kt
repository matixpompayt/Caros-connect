package pl.mati.carosconnect

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.mati.carosconnect.FuelPrices.Fuel
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val PLF = Locale("pl")
private val F_DAY = DateTimeFormatter.ofPattern("EEE d.MM", PLF)
private val F_AXIS = DateTimeFormatter.ofPattern("d.MM", PLF)
private val RED = Color(0xFFFF5A5F)

/** Ceny paliw: dziś, zmiana, jutro, wykres historii (jak w CAR OS). */
@Composable
fun FuelPricesSection() {
    val ctx = LocalContext.current
    val snap by FuelPrices.data.collectAsState()
    val fav by FuelPrices.fav.collectAsState()
    LaunchedEffect(Unit) { FuelPrices.refresh(ctx); FuelWidget.updateAll(ctx) }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Label("Ceny paliw", Modifier.weight(1f))
        Text("maksymalne · dziś ${LocalDate.now().format(F_DAY)}", fontSize = 12.sp, color = C.Muted)
    }
    val s = snap
    if (s == null) {
        Card { Text("Pobieram ceny paliw…", fontSize = 15.sp, color = C.Muted) }
        return
    }
    Card {
        listOf(Fuel.ON, Fuel.PB95, Fuel.PB98, Fuel.LPG).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { f -> PriceTile(f, s, f == fav, Modifier.weight(1f)) { FuelPrices.setFavorite(ctx, f) } }
            }
        }
        TomorrowRow(s)
        History(s)
        val upd = try {
            OffsetDateTime.parse(s.updated).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d.MM HH:mm"))
        } catch (_: Exception) { null }
        Text(
            "ON i benzyny: obwieszczenia Ministra Energii · LPG: średnia rynkowa" + (upd?.let { " · stan na $it" } ?: ""),
            fontSize = 11.sp, color = C.Muted,
        )
    }
}

@Composable
private fun PriceTile(f: Fuel, s: FuelPrices.Snapshot, fav: Boolean, modifier: Modifier, onFav: () -> Unit) {
    val c = Color(f.color)
    val p = s.today[f]
    val ch = s.change[f]
    val tm = if (s.hasNewTomorrow) s.tomorrow[f] else null
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).surface(shape, C.Raised, border = if (fav) C.Accent.copy(alpha = 0.8f) else null, borderWidth = if (fav) 1.5.dp else 1.dp)
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(7.dp))
            Text(f.short, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.weight(1f))
            Icon(
                if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Ulubione paliwo",
                tint = if (fav) Color(0xFFFBBF24) else C.Muted,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onFav).padding(10.dp),
            )
        }
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Text)) { append(p?.let { FuelPrices.zl(it) } ?: "--") }
                withStyle(SpanStyle(fontSize = 13.sp, color = C.Muted)) { append(" zł/l") }
            },
            maxLines = 1,
        )
        val (txt, col) = when {
            ch == null -> (if (f == Fuel.LPG) "średnia" else "—") to C.Muted
            abs(ch) < 0.005 -> "bez zmian" to C.Muted
            ch > 0 -> "▲ ${FuelPrices.zl(ch)} zł" + (if (f == Fuel.LPG) " · 7 dni" else "") to RED
            else -> "▼ ${FuelPrices.zl(-ch)} zł" + (if (f == Fuel.LPG) " · 7 dni" else "") to C.Green
        }
        Text(txt, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = col, maxLines = 1)
        if (tm != null && p != null) {
            val d = tm - p
            Text(
                "jutro ${FuelPrices.zl(tm)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = when { abs(d) < 0.005 -> C.Text2; d > 0 -> RED; else -> C.Green },
            )
        }
    }
}

@Composable
private fun TomorrowRow(s: FuelPrices.Snapshot) {
    val tomorrow = LocalDate.now().plusDays(1).format(F_DAY)
    val (icon, color, text) = when {
        s.noCurrent -> Triple(Icons.Rounded.Warning, C.Orange, "Brak aktualnego obwieszczenia" + (s.latestFrom?.let { " – ostatnie ceny od ${it.format(F_AXIS)}" } ?: ""))
        s.tomorrowSame -> Triple(Icons.Rounded.EventAvailable, C.Text2, "Jutro ($tomorrow) te same ceny – obwieszczenie na cały weekend")
        s.tomorrow.isNotEmpty() -> Triple(Icons.Rounded.EventAvailable, C.Accent, "Są już ceny na jutro ($tomorrow)")
        else -> Triple(Icons.Rounded.Schedule, C.Muted, "Ceny na jutro jeszcze nieopublikowane (zwykle w dni robocze po południu)")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 13.sp, color = if (color == C.Muted) C.Text2 else C.Text)
    }
}

@Composable
private fun History(s: FuelPrices.Snapshot) {
    var range by rememberSaveable { mutableStateOf(30) }
    var hidden by rememberSaveable { mutableStateOf("lpg") }
    val show = Fuel.entries.filter { it.key !in hidden.split(",") }.toSet()
    val days = remember(s, range) {
        s.days.takeLast(range + (if (s.days.lastOrNull()?.date?.isAfter(LocalDate.now()) == true) 1 else 0))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text("Historia", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.weight(1f))
        listOf(14 to "14 dni", 30 to "30 dni", 90 to "3 mies.").forEach { (d, l) ->
            val on = range == d
            Text(
                l, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Bg else C.Text2,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Accent else C.Raised)
                    .clickable { range = d }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Fuel.entries.forEach { f ->
            val on = f in show
            val c = Color(f.color)
            Row(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.copy(alpha = 0.16f) else Color.Transparent)
                    .border(1.dp, if (on) c.copy(alpha = 0.5f) else C.Border, RoundedCornerShape(12.dp))
                    .clickable {
                        val h = hidden.split(",").filter { it.isNotBlank() }.toMutableSet()
                        if (on) { if (show.size > 1) h += f.key } else h -= f.key
                        hidden = h.joinToString(",")
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) c else C.Muted))
                Text(f.short, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Text else C.Muted)
            }
        }
    }
    if (days.count { d -> show.any { d.prices[it] != null } } < 2) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(
                if (show == setOf(Fuel.LPG)) "Historia LPG dopiero się zbiera – wróć za kilka dni" else "Za mało danych do wykresu",
                fontSize = 14.sp, color = C.Muted,
            )
        }
    } else {
        PriceChart(days, show, Modifier.fillMaxWidth().height(200.dp))
    }
}

@Composable
private fun PriceChart(days: List<FuelPrices.Day>, show: Set<Fuel>, modifier: Modifier) {
    var sel by remember(days) { mutableStateOf<Int?>(null) }
    val muted = C.Muted.toArgb()
    val textCol = C.Text.toArgb()
    val grid = C.Border
    val lineCol = C.Text
    val tipBg = if (C.isLight) Color(0xFFFFFFFF) else if (C.isGlass) Color(0xF2222943) else Color(0xFF262C35)
    val today = LocalDate.now()
    val todayIdx = days.indexOfFirst { it.date == today }
    val n = days.size
    val leftDp = 42.dp; val rightDp = 8.dp; val topDp = 8.dp; val bottomDp = 22.dp

    fun idxAt(x: Float, w: Float, left: Float, right: Float): Int =
        if (n <= 1) 0 else (((x - left) / (w - left - right)) * (n - 1)).roundToInt().coerceIn(0, n - 1)

    Canvas(
        modifier
            .pointerInput(days) {
                detectTapGestures { o ->
                    val i = idxAt(o.x, size.width.toFloat(), leftDp.toPx(), rightDp.toPx())
                    sel = if (sel == i) null else i
                }
            }
            .pointerInput(days) {
                detectHorizontalDragGestures { ch, _ ->
                    ch.consume()
                    sel = idxAt(ch.position.x, size.width.toFloat(), leftDp.toPx(), rightDp.toPx())
                }
            },
    ) {
        val left = leftDp.toPx(); val right = rightDp.toPx(); val top = topDp.toPx(); val bottom = bottomDp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val vals = days.flatMap { d -> show.mapNotNull { d.prices[it] } }
        if (vals.isEmpty()) return@Canvas
        var lo = vals.min(); var hi = vals.max()
        if (hi - lo < 0.2) { val m = (hi + lo) / 2; lo = m - 0.1; hi = m + 0.1 }
        val rawStep = (hi - lo) / 4
        val step = listOf(0.05, 0.1, 0.2, 0.25, 0.5, 1.0, 2.0).firstOrNull { it >= rawStep } ?: 2.0
        lo = floor(lo / step) * step; hi = ceil(hi / step) * step
        fun x(i: Int) = left + if (n <= 1) w / 2 else w * i / (n - 1)
        fun y(v: Double) = top + h * (1 - ((v - lo) / (hi - lo))).toFloat()
        val nc = drawContext.canvas.nativeCanvas

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.sp.toPx(); color = muted }
        var g = lo
        while (g <= hi + 1e-6) {
            val yy = y(g)
            drawLine(grid, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1.dp.toPx())
            paint.textAlign = Paint.Align.RIGHT
            nc.drawText(FuelPrices.zl(g), left - 6.dp.toPx(), yy + 3.dp.toPx(), paint)
            g += step
        }
        paint.textAlign = Paint.Align.CENTER
        val labels = 4.coerceAtMost(n)
        for (k in 0 until labels) {
            val i = if (labels == 1) 0 else ((n - 1) * k.toFloat() / (labels - 1)).roundToInt()
            val d = days[i].date
            val t = when (d) { today -> "dziś"; today.plusDays(1) -> "jutro"; else -> d.format(F_AXIS) }
            nc.drawText(t, x(i).coerceIn(left + 14.dp.toPx(), left + w - 14.dp.toPx()), size.height - 4.dp.toPx(), paint)
        }
        if (todayIdx in 0 until n - 1) {
            drawLine(
                grid, Offset(x(todayIdx), top), Offset(x(todayIdx), top + h),
                strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (f in Fuel.entries.filter { it in show }) {
            val col = Color(f.color)
            val path = Path()
            var open = false
            var lastI = -1
            days.forEachIndexed { i, d ->
                val v = d.prices[f]
                if (v == null) { open = false; return@forEachIndexed }
                if (!open) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
                open = true; lastI = i
            }
            drawPath(path, col, style = stroke)
            if (lastI >= 0) drawCircle(col, 4.dp.toPx(), Offset(x(lastI), y(days[lastI].prices[f]!!)))
        }
        val si = sel
        if (si != null && si in 0 until n) {
            val sx = x(si)
            drawLine(lineCol.copy(alpha = 0.5f), Offset(sx, top), Offset(sx, top + h), strokeWidth = 1.dp.toPx())
            val d = days[si]
            val rows = Fuel.entries.filter { it in show && d.prices[it] != null }
            rows.forEach { f -> drawCircle(Color(f.color), 5.dp.toPx(), Offset(sx, y(d.prices[f]!!))) }
            val lineH = 17.dp.toPx()
            val boxW = 118.dp.toPx()
            val boxH = lineH * (rows.size + 1) + 10.dp.toPx()
            val bx = if (sx + 10.dp.toPx() + boxW > left + w) sx - 10.dp.toPx() - boxW else sx + 10.dp.toPx()
            val by = top + 2.dp.toPx()
            drawRoundRect(tipBg, Offset(bx, by), Size(boxW, boxH), CornerRadius(10.dp.toPx()))
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.sp.toPx(); color = textCol; isFakeBoldText = true }
            val title = when (d.date) { today -> "Dziś"; today.plusDays(1) -> "Jutro"; else -> d.date.format(F_DAY) }
            nc.drawText(title, bx + 10.dp.toPx(), by + lineH, tp)
            tp.isFakeBoldText = false
            rows.forEachIndexed { k, f ->
                val yy = by + lineH * (k + 2)
                drawCircle(Color(f.color), 3.5.dp.toPx(), Offset(bx + 13.dp.toPx(), yy - 4.dp.toPx()))
                tp.textAlign = Paint.Align.LEFT
                nc.drawText(f.short, bx + 21.dp.toPx(), yy, tp)
                tp.textAlign = Paint.Align.RIGHT
                nc.drawText(FuelPrices.zl(d.prices[f]!!), bx + boxW - 10.dp.toPx(), yy, tp)
            }
        }
    }
}
