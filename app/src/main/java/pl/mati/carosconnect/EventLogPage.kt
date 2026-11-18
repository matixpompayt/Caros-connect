package pl.mati.carosconnect

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Osobna strona „Dziennik zdarzeń” (otwierana z Ustawień albo z Parkingu – wtedy od razu filtr). */
object LogPage {
    val open = MutableStateFlow(false)
    val cat = MutableStateFlow<EventLog.Cat?>(null)

    fun show(c: EventLog.Cat? = null) {
        cat.value = c
        open.value = true
    }

    fun close() {
        open.value = false
    }
}

private val PLL = Locale("pl")
private val HMS = SimpleDateFormat("HH:mm:ss", PLL)
private val DAYK = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

private fun dayStart(daysAgo: Int): Calendar = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -daysAgo)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

private fun dayHeader(t: Long): String {
    val c = Calendar.getInstance()
    val today = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    c.timeInMillis = t
    val d = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    return when (today - d) {
        0 -> "Dziś"
        1 -> "Wczoraj"
        else -> SimpleDateFormat("EEEE, d MMMM", PLL).format(Date(t)).replaceFirstChar { it.uppercase() }
    }
}

@Composable
fun EventLogPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val events by EventLog.events.collectAsState()
    val counters by EventLog.counters.collectAsState()
    val catSel by LogPage.cat.collectAsState()
    var period by rememberSaveable { mutableIntStateOf(7) }
    var metricName by rememberSaveable { mutableStateOf(EventLog.Metric.DRIVES.name) }
    val metric = EventLog.Metric.valueOf(metricName)
    var errOnly by rememberSaveable { mutableStateOf(false) }
    var limit by remember { mutableIntStateOf(120) }
    var clearAsk by remember { mutableStateOf(false) }

    // nagłówek strony
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.ArrowBack, null, tint = C.Text2, modifier = Modifier.size(22.dp))
            Text("Wstecz", fontSize = 16.sp, color = C.Text2)
        }
        Spacer(Modifier.weight(1f))
        RoundIcon(Icons.Rounded.Share, "Udostępnij") {
            val text = buildString {
                append("CAR OS Connect – dziennik zdarzeń\n\n")
                events.take(500).forEach { e ->
                    append(SimpleDateFormat("d.MM HH:mm:ss", PLL).format(Date(e.time))).append("  [").append(e.cat.label).append("] ")
                    if (e.err) append("! ")
                    append(e.msg).append('\n')
                }
            }
            try {
                ctx.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Dziennik zdarzeń")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {}
        }
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Rounded.DeleteSweep, "Wyczyść") { clearAsk = true }
    }
    Column {
        Text("Dziennik zdarzeń", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Text)
        Text("Co robiła aplikacja: połączenia z radiem, parkowanie, CamSam, wysłane cele, błędy", fontSize = 14.sp, color = C.Muted)
    }

    // ---------- statystyki ----------
    val periods = listOf(1 to "Dziś", 7 to "7 dni", 30 to "30 dni")
    if (C.isGlass) LiquidSegmented(periods.map { it.second }, periods.indexOfFirst { it.first == period }, { period = periods[it].first })
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        periods.forEach { (d, l) -> Chip(l, period == d, Modifier.weight(1f)) { period = d } }
    }
    val fromKey = DAYK.format(dayStart(period - 1).time)
    val toKey = DAYK.format(Date())
    val todayKey = toKey
    EventLog.Metric.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { m ->
                val v = counters.entries.filter { it.key in fromKey..toKey }.sumOf { it.value[m.key] ?: 0 }
                val today = counters[todayKey]?.get(m.key) ?: 0
                MetricTile(m, v, if (period > 1) today else null, m == metric, Modifier.weight(1f)) { metricName = m.name }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    DayChart(metric, counters, if (period == 1) 7 else period)

    // ---------- lista zdarzeń ----------
    Label("Zdarzenia", Modifier.padding(top = 6.dp))
    val since = dayStart(period - 1).timeInMillis
    val inPeriod = remember(events, since) { events.filter { it.time >= since } }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Wszystko · ${inPeriod.size}", catSel == null && !errOnly) { LogPage.cat.value = null; errOnly = false }
        val errs = inPeriod.count { it.err }
        if (errs > 0) Chip("Błędy · $errs", errOnly, dot = C.Orange) { errOnly = !errOnly; LogPage.cat.value = null }
        EventLog.Cat.entries.forEach { c ->
            val n = inPeriod.count { it.cat == c }
            if (n > 0 || catSel == c) Chip("${c.label} · $n", catSel == c, dot = Color(c.color)) {
                LogPage.cat.value = if (catSel == c) null else c
                errOnly = false
            }
        }
    }
    val shown = remember(inPeriod, catSel, errOnly) { inPeriod.filter { (catSel == null || it.cat == catSel) && (!errOnly || it.err) } }
    val perDay = remember(shown) { shown.groupingBy { DAYK.format(Date(it.time)) }.eachCount() }
    if (shown.isEmpty()) {
        Card {
            Text(
                if (events.isEmpty()) "Dziennik jest pusty – zdarzenia zapiszą się przy następnej jeździe, parkowaniu i połączeniu z radiem."
                else "Brak zdarzeń w tym okresie" + (catSel?.let { " (${it.label})" } ?: ""),
                fontSize = 14.sp, color = C.Muted,
            )
        }
    } else {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).surface(RoundedCornerShape(22.dp), C.Card, border = C.Border)
                .padding(horizontal = 16.dp, vertical = 10.dp).animateContentSize(),
        ) {
            var lastDay = ""
            shown.take(limit).forEach { e ->
                val dk = DAYK.format(Date(e.time))
                if (dk != lastDay) {
                    val first = lastDay.isEmpty()
                    lastDay = dk
                    val n = perDay[dk] ?: 0
                    Row(Modifier.padding(top = if (first) 0.dp else 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dayHeader(e.time), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.weight(1f))
                        Text("$n", fontSize = 13.sp, color = C.Muted)
                    }
                }
                EventRow(e)
            }
            if (shown.size > limit) {
                Text(
                    "Pokaż więcej (${shown.size - limit})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Accent,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { limit += 200 }.padding(vertical = 12.dp),
                )
            }
        }
    }
    Text("Dziennik trzyma ostatnie 60 dni (do 3000 zdarzeń). Zostaje w telefonie.", fontSize = 12.sp, color = C.Muted)

    if (clearAsk) AlertDialog(
        onDismissRequest = { clearAsk = false },
        containerColor = C.DialogBg, modifier = Modifier.glassDialog(),
        title = { Text("Wyczyścić dziennik?", color = C.Text) },
        text = { Text("Znikną wszystkie zdarzenia i statystyki z telefonu.", color = C.Muted) },
        confirmButton = { TextButton(onClick = { EventLog.clear(); clearAsk = false }) { Text("Wyczyść") } },
        dismissButton = { TextButton(onClick = { clearAsk = false }) { Text("Anuluj") } },
    )
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).surface(CircleShape, C.Raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = C.Text, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Chip(text: String, on: Boolean, modifier: Modifier = Modifier, dot: Color? = null, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp))
            .surface(RoundedCornerShape(14.dp), if (on) C.Accent.copy(alpha = 0.18f) else C.Raised, border = if (on) C.Accent.copy(alpha = 0.6f) else null)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Text else C.Text2, maxLines = 1)
    }
}

@Composable
private fun MetricTile(m: EventLog.Metric, value: Int, today: Int?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val warn = m == EventLog.Metric.ERR && value > 0
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape)
            .surface(shape, C.Raised, border = if (selected) C.Accent else null, borderWidth = if (selected) 2.dp else 1.dp)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(m.label, fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$value", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (warn) C.Orange else C.Text)
            today?.let { Text("dziś $it", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(bottom = 4.dp)) }
        }
    }
}

/** Słupki: wybrana statystyka dzień po dniu. */
@Composable
private fun DayChart(m: EventLog.Metric, counters: Map<String, Map<String, Int>>, days: Int) {
    val data = (days - 1 downTo 0).map { ago ->
        val c = dayStart(ago)
        c.timeInMillis to (counters[DAYK.format(c.time)]?.get(m.key) ?: 0)
    }
    val max = data.maxOf { it.second }.coerceAtLeast(1)
    val total = data.sumOf { it.second }
    val col = if (m == EventLog.Metric.ERR) C.Orange else C.Accent
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.weight(1f))
            Text(String.format(PLL, "śr. %.1f / dzień", total.toDouble() / days), fontSize = 13.sp, color = C.Muted)
        }
        Row(
            Modifier.fillMaxWidth().height(130.dp).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (days > 10) 3.dp else 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            data.forEachIndexed { i, (t, v) ->
                val f by animateFloatAsState(v.toFloat() / max, tween(450), label = "bar")
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (v > 0 && (days <= 10 || v == max)) Text("$v", fontSize = 11.sp, color = C.Text2, maxLines = 1)
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(0.82f * f.coerceAtLeast(if (v > 0) 0.04f else 0.015f))
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(if (i == data.lastIndex) col else col.copy(alpha = if (v > 0) 0.55f else 0.18f)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            data.forEachIndexed { i, (t, _) ->
                val show = days <= 10 || i % 5 == (data.lastIndex % 5)
                Text(
                    if (!show) "" else if (days <= 10) SimpleDateFormat("EEEEEE", PLL).format(Date(t)) else SimpleDateFormat("d.MM", PLL).format(Date(t)),
                    fontSize = 10.sp, color = if (i == data.lastIndex) C.Text else C.Muted, maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        if (m == EventLog.Metric.OUT) Text("Przekaźnik przyjmuje ok. 250 wiadomości dziennie – ostrzeżenia CamSam są ograniczane, żeby się zmieścić.", fontSize = 12.sp, color = C.Muted)
    }
}

@Composable
private fun EventRow(e: EventLog.Ev) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(HMS.format(Date(e.time)), fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 2.dp).width(56.dp))
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(Color(e.cat.color)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (e.err) Icon(Icons.Rounded.Warning, null, tint = C.Orange, modifier = Modifier.size(15.dp))
                Text(e.msg, fontSize = 14.sp, color = if (e.err) C.Orange else C.Text)
            }
            Text(e.cat.label, fontSize = 11.sp, color = C.Muted)
        }
    }
}
