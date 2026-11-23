package pl.mati.carosconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private val PLN = Locale("pl")

/** Pola kalkulatora: litry × cena za litr = kwota. */
private enum class FF { L, P, C }

private fun num(t: String) = t.replace(',', '.').toDoubleOrNull()
private fun fmt2(v: Double) = String.format(PLN, "%.2f", v)

/** Cena: 3 miejsca po przecinku tylko gdy potrzebne (6,29 / 6,289). */
private fun fmtPrice(v: Double): String = String.format(PLN, "%.3f", v).let { if (it.endsWith("0")) it.dropLast(1) else it }

private fun thousandsKm(v: Int): String = "%,d".format(v).replace(',', ' ')

/**
 * Nowe tankowanie – panel od dołu. Kalkulator: wpisz dwie dowolne wartości z trzech (litry, cena za litr,
 * kwota), trzecia liczy się sama (np. cena + ile zapłaciłem → ile litrów). Ostatnio wpisane pola są „Twoje”,
 * najdawniej ruszane – obliczane. Szybkie wybory: cena z ostatniego tankowania / średnia, kwoty, do pełna.
 */
@Composable
fun FuelSheet(
    odo: Int?, onDismiss: () -> Unit,
    /** Zrzuty ekranu: kwota wpisana na start (liczy litry z ceny). */
    previewCost: String? = null,
    onSave: (CarData.Fill) -> Unit,
) {
    val status by CarData.status.collectAsState()
    val prices by FuelPrices.data.collectAsState()
    val fav by FuelPrices.fav.collectAsState()
    val fu = status?.fuel
    val last = fu?.fills?.maxByOrNull { it.time }
    val lastPrice = last?.takeIf { it.liters > 0 && it.cost > 0 }?.let { it.cost / it.liters }
    val avgPrice = prices?.today?.get(fav)
    val left = fu?.let { CarData.litersLeft(it) }
    val toFull = if (fu != null && left != null) (fu.tank - left).coerceAtLeast(0.0) else null

    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(lastPrice?.let { fmtPrice(it) } ?: "") }
    var cost by remember { mutableStateOf("") }
    // kolejność wpisywania (ostatnie na końcu); cena z ostatniego tankowania liczy się jako wpisana
    var order by remember { mutableStateOf(if (lastPrice != null) listOf(FF.P) else emptyList()) }
    var km by remember { mutableStateOf(odo?.toString() ?: "") }
    var full by remember { mutableStateOf(true) }
    fun set(f: FF, raw: String) {
        // przecinek albo kropka, najwyżej jeden separator i 3 miejsca po nim
        var v = raw.filter { it.isDigit() || it == ',' || it == '.' }.replace('.', ',')
        val i = v.indexOf(',')
        if (i >= 0) v = v.substring(0, i + 1) + v.substring(i + 1).replace(",", "").take(3)
        v = v.take(9)
        val o = order - f + f
        var l = if (f == FF.L) v else liters
        var p = if (f == FF.P) v else price
        var c = if (f == FF.C) v else cost
        if (o.size >= 2) {
            val lv = num(l)
            val pv = num(p)
            val cv = num(c)
            when (FF.entries.first { it !in o.takeLast(2) }) {
                FF.L -> l = if (pv != null && cv != null && pv > 0) fmt2(cv / pv) else ""
                FF.P -> p = if (lv != null && cv != null && lv > 0) fmtPrice(cv / lv) else ""
                FF.C -> c = if (lv != null && pv != null) fmt2(lv * pv) else ""
            }
        }
        order = o
        liters = l
        price = p
        cost = c
    }

    remember { previewCost?.let { set(FF.C, it) } }
    // które pole jest obliczane: to, którego najdawniej nie ruszałeś (po wpisaniu dwóch)
    val computed: FF? = if (order.size >= 2) FF.entries.first { it !in order.takeLast(2) } else null

    val l = num(liters)
    val p = num(price)
    val c = num(cost)
    val o = km.toIntOrNull()
    val ok = l != null && l in 1.0..200.0 && c != null && c >= 0 && o != null && o > 0

    CarSheet(
        "Nowe tankowanie", onDismiss, "Wpisz dwie dowolne wartości – trzecią policzę sam", Icons.Rounded.LocalGasStation,
        bottom = { ctl ->
            SheetButton(
                "Zapisz tankowanie", Icons.Rounded.LocalGasStation, enabled = ok,
                sub = if (l != null && c != null) "${fmt2(l)} l · ${fmt2(c)} zł" + if (full) " · do pełna" else "" else null,
            ) {
                onSave(CarData.Fill(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), o!!, l!!, c!!, full))
                ctl.close()
            }
        },
    ) {
        // litry × cena = kwota
        CalcField("Litry", liters, "l", computed == FF.L) { set(FF.L, it) }
        QuickRow {
            if (toFull != null && toFull >= 1) SheetChip(String.format(PLN, "Do pełna ≈ %.0f l", toFull)) { set(FF.L, String.format(PLN, "%.0f", toFull)); full = true }
            listOf(20, 30, 40, 50).forEach { n -> SheetChip("$n l") { set(FF.L, "$n") } }
        }
        Operator("×")
        CalcField("Cena za litr", price, "zł/l", computed == FF.P) { set(FF.P, it) }
        QuickRow {
            if (lastPrice != null) SheetChip("Ostatnio ${fmtPrice(lastPrice)}") { set(FF.P, fmtPrice(lastPrice)) }
            if (avgPrice != null) SheetChip("Średnia ${fav.short} ${fmtPrice(avgPrice)}") { set(FF.P, fmtPrice(avgPrice)) }
        }
        Operator("=")
        CalcField("Kwota", cost, "zł", computed == FF.C) { set(FF.C, it) }
        QuickRow { listOf(50, 100, 150, 200, 250, 300).forEach { n -> SheetChip("$n zł") { set(FF.C, "$n") } } }

        // wynik i co będzie po tankowaniu
        if (l != null && p != null && c != null && l > 0) Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Accent.copy(alpha = 0.10f))
                .border(1.dp, C.Accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                when (computed) {
                    FF.L -> "Zatankujesz ${fmt2(l)} l"
                    FF.P -> "Płacisz ${fmtPrice(p)} zł za litr"
                    else -> "Zapłacisz ${fmt2(c)} zł"
                },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Text,
            )
            Text("${fmt2(l)} l × ${fmtPrice(p)} zł/l = ${fmt2(c)} zł", fontSize = 14.sp, color = C.Text2)
            if (fu != null && left != null) {
                val after = if (full) fu.tank.toDouble() else (left + l).coerceAtMost(fu.tank.toDouble())
                val range = fu.avg?.takeIf { it > 0 }?.let { after / it * 100 }
                Text(
                    String.format(PLN, "Po tankowaniu w baku ok. %.0f z %d l", after, fu.tank) + (range?.let { " · zasięg ok. ${it.roundToInt()} km" } ?: ""),
                    fontSize = 14.sp, color = C.Text2,
                )
            }
        }
        if (l != null && toFull != null && l > toFull + 3) Warn(String.format(PLN, "Więcej niż mieści bak (wolne ok. %.0f l) – sprawdź litry albo pojemność baku", toFull))

        // przebieg
        CalcField("Przebieg", km, "km", false, decimal = false) { km = it.filter(Char::isDigit).take(7) }
        val lastOdo = last?.odo
        if (lastOdo != null) {
            if (o != null && o < lastOdo) Warn("Mniej niż przy ostatnim tankowaniu (${thousandsKm(lastOdo)} km)")
            else Text(
                "Ostatnie tankowanie przy ${thousandsKm(lastOdo)} km" + (o?.takeIf { it > lastOdo }?.let { " · przejechane ${thousandsKm(it - lastOdo)} km" } ?: ""),
                fontSize = 13.sp, color = C.Muted, modifier = Modifier.padding(start = 4.dp),
            )
        }

        // do pełna / dolewka
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segment("Do pełna", "do odbicia pistoletu", full, Modifier.weight(1f)) { full = true }
            Segment("Dolewka", "część baku", !full, Modifier.weight(1f)) { full = false }
        }
        Text(
            "Tankowania „do pełna” liczą spalanie i zasięg najdokładniej.",
            fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Duże pole liczby z jednostką; obliczone – w kolorze CAR OS ze znaczkiem. */
@Composable
internal fun CalcField(label: String, value: String, unit: String, computed: Boolean, decimal: Boolean = true, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .then(
                when {
                    focused -> Modifier.background(C.Card).border(1.5.dp, C.Accent, shape)
                    computed -> Modifier.background(C.Accent.copy(alpha = 0.08f)).border(1.dp, C.Accent.copy(alpha = 0.45f), shape)
                    else -> Modifier.surface(shape, C.Card, border = C.Border)
                },
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 13.sp, color = C.Muted, modifier = Modifier.weight(1f))
            if (computed) Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(C.Accent.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Calculate, null, tint = C.Accent, modifier = Modifier.size(14.dp))
                Text("obliczone", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = C.Accent)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (computed && !focused) C.Accent else C.Text, textAlign = TextAlign.End),
                cursorBrush = SolidColor(C.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Next),
                interactionSource = src,
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (value.isEmpty()) Text("—", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Muted.copy(alpha = 0.5f))
                        inner()
                    }
                },
            )
            Text(" $unit", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text2, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
internal fun QuickRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Znak działania między polami (× / =) – widać, co z czego wynika. */
@Composable
private fun Operator(sign: String) {
    Box(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(28.dp).clip(CircleShape).background(C.Raised), contentAlignment = Alignment.Center) {
            Text(sign, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Text2)
        }
    }
}

@Composable
private fun Segment(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape)
            .then(if (on) Modifier.background(C.Accent.copy(alpha = 0.16f)).border(1.5.dp, C.Accent, shape) else Modifier.surface(shape, C.Card, border = C.Border))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Text else C.Text2)
        Text(sub, fontSize = 12.sp, color = C.Muted)
    }
}

@Composable
internal fun Warn(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Orange.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = C.Orange, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 13.sp, color = C.Text)
    }
}
