package pl.mati.carosconnect

import kotlin.math.abs
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckBox
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

private fun dur(s: Int): String {
    val m = (s / 60.0).roundToInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
}

/**
 * Karta „Inteligentny plan” w podglądzie trasy: omijanie korków, kolejność punktów,
 * najlepsza godzina wyjazdu, tankowanie i przerwy.
 */
@Composable
fun SmartPlanCard(
    r: RoutePreview.Result, from: LatLng?, points: List<CarData.Place>,
    onPoints: (List<CarData.Place>) -> Unit, onDepart: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by CarData.status.collectAsState()
    val range = status?.fuel?.range
    val fullKm = SmartPlan.fullRangeKm(status?.fuel)
    val reserve by Store.minRange.collectAsState()
    val fuelSel by FuelPrices.fav.collectAsState()
    val tk by StationPrices.tkKey.collectAsState()
    var ordering by remember { mutableStateOf(false) }
    var slots by remember(r.pts) { mutableStateOf<List<SmartPlan.Slot>?>(null) }
    var slotsBusy by remember(r.pts) { mutableStateOf(false) }
    var stops by remember(r.pts) { mutableStateOf<SmartPlan.Stops?>(null) }
    var stopsErr by remember(r.pts) { mutableStateOf<String?>(null) }
    var stopsTry by remember(r.pts) { mutableStateOf(0) }
    val via = points.dropLast(1).map { LatLng(it.lat, it.lon) }
    val needStops = (range != null && range < r.distanceM / 1000 + reserve) || r.travelS > 2 * 3600 + 20 * 60
    LaunchedEffect(r.pts, range, reserve, fuelSel, tk, fullKm, stopsTry) {
        stopsErr = null
        if (!needStops) { stops = SmartPlan.Stops(emptyList(), r.distanceM / 1000.0); return@LaunchedEffect }
        stops = null
        stops = try {
            SmartPlan.stops(ctx, r, range, reserve, fuelSel, tk, fullKm)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            stopsErr = e.message ?: "błąd"
            SmartPlan.Stops(emptyList(), r.distanceM / 1000.0)
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Raised).padding(12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = C.Accent, modifier = Modifier.size(20.dp))
            Text("Inteligentny plan", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Text)
        }

        // 1) omijanie korków i zamkniętych dróg
        when {
            r.avoidedClosure -> Line(Icons.Rounded.Traffic, C.Green, "Omija zamkniętą drogę" + if (r.savedS >= 60) " i korki (−${dur(r.savedS)})" else "")
            r.savedS >= 60 -> Line(Icons.Rounded.Traffic, C.Green, "Omija korki: ${dur(r.savedS)} szybciej niż zwykła trasa")
            r.alts.size > 1 && r.altIndex == r.alts.indices.minByOrNull { r.alts[it].travelS } -> Line(Icons.Rounded.Traffic, C.Green, "Wybrana najszybsza trasa z aktualnymi korkami")
        }

        // 2) kolejność punktów
        if (points.size >= 3 && from != null) {
            SmartButton(Icons.Rounded.SwapVert, if (ordering) "Układam…" else "Ułóż punkty w najlepszej kolejności", ordering) {
                ordering = true
                scope.launch {
                    val res = SmartPlan.optimizeOrder(from, points)
                    ordering = false
                    when {
                        res == null -> Toast.makeText(ctx, "Nie udało się ułożyć – sprawdź internet", Toast.LENGTH_SHORT).show()
                        res.first == points || res.second < 30 -> Toast.makeText(ctx, "Obecna kolejność jest najlepsza", Toast.LENGTH_SHORT).show()
                        else -> {
                            onPoints(res.first)
                            Toast.makeText(ctx, "Nowa kolejność – szybciej o ${dur(res.second)}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // 3) najlepsza godzina wyjazdu
        val sl = slots
        if (sl == null) {
            SmartButton(Icons.Rounded.Schedule, if (slotsBusy) "Sprawdzam korki w najbliższych godzinach…" else "Kiedy najlepiej wyjechać?", slotsBusy) {
                if (RoutePreview.tomtomKey(ctx).isBlank()) {
                    Toast.makeText(ctx, "Potrzebny klucz TomTom (Ustawienia → Korki w CAR OS)", Toast.LENGTH_LONG).show()
                    return@SmartButton
                }
                slotsBusy = true
                scope.launch {
                    slots = SmartPlan.departures(ctx, r, via, maxOf(System.currentTimeMillis(), r.depart))
                    slotsBusy = false
                }
            }
        } else if (sl.isNotEmpty()) {
            val best = sl.minBy { it.travelS }
            val now = sl.first()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (best.depart == now.depart || now.travelS - best.travelS < 120) "Teraz jest dobrze – później nie będzie wyraźnie szybciej"
                    else "Najlepiej wyjechać o ${RoutePreview.hhmm(best.depart)}: ${dur(best.travelS)} (−${dur(now.travelS - best.travelS)})",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text,
                )
                // słupki: czas przejazdu dla kolejnych godzin wyjazdu
                val mx = sl.maxOf { it.travelS }.toFloat()
                val mn = sl.minOf { it.travelS }.toFloat()
                Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                    sl.forEach { s ->
                        val f = if (mx - mn < 1f) 0.6f else 0.3f + 0.7f * (s.travelS - mn) / (mx - mn)
                        Box(
                            Modifier.weight(1f).fillMaxHeight(f).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (s == best) C.Green else C.Accent.copy(alpha = 0.45f))
                                .clickable { onDepart(s.depart) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    sl.forEachIndexed { i, s ->
                        Text(
                            if (i % 4 == 0) RoutePreview.hhmm(s.depart) else "", fontSize = 10.sp, color = C.Muted, maxLines = 1, softWrap = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (best.depart != now.depart && now.travelS - best.travelS >= 120) {
                    SmartButton(Icons.Rounded.Schedule, "Ustaw wyjazd na ${RoutePreview.hhmm(best.depart)}", false) { onDepart(best.depart) }
                } else {
                    Text("Dotknij słupka, żeby ustawić tę godzinę wyjazdu", fontSize = 12.sp, color = C.Muted)
                }
            }
        } else {
            Text("Brak prognozy ruchu na najbliższe godziny", fontSize = 13.sp, color = C.Muted)
        }

        // 4) tankowanie i przerwy – zaznacz, które chcesz; zasięg na stacji i na miejscu
        val st = stops
        val totalKm = r.distanceM / 1000.0
        when {
            !needStops -> Line(
                Icons.Rounded.LocalGasStation, C.Muted,
                if (range != null) "Zasięg ${range.roundToInt()} km – na miejscu zostanie ok. ${(range - totalKm).roundToInt()} km (minimum $reserve km). Postoje niepotrzebne"
                else "Krótka trasa – bez postojów",
            )
            st == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Szukam stacji i miejsc na przerwę…", fontSize = 13.sp, color = C.Muted)
            }
            st.groups.isEmpty() && stopsErr != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.LocalGasStation, null, tint = C.Orange, modifier = Modifier.size(20.dp))
                Text("Nie udało się pobrać stacji – serwery map nie odpowiadają ($stopsErr)", fontSize = 13.sp, color = C.Text, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.18f)).clickable { stopsTry++ }.padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text("Ponów", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Accent) }
            }
            st.groups.isEmpty() -> Line(Icons.Rounded.LocalGasStation, C.Muted, "Brak postojów do zaplanowania")
            else -> StopsPicker(r, st, points, range, reserve, onPoints)
        }
    }
}

private fun samePlace(a: CarData.Place, b: CarData.Place) = abs(a.lat - b.lat) < 1e-5 && abs(a.lon - b.lon) < 1e-5

/** Lista postojów do zaznaczenia (tankowania i przerwy, kilka propozycji każda) + zasięg + „Zapisz w trasie”. */
@Composable
private fun StopsPicker(
    r: RoutePreview.Result, st: SmartPlan.Stops, points: List<CarData.Place>,
    range: Double?, reserve: Int, onPoints: (List<CarData.Place>) -> Unit,
) {
    val ctx = LocalContext.current
    val via = points.dropLast(1)
    val fullKm = st.fullKm
    val fuelGroups = st.groups.count { it.kind == "fuel" }
    // wybór: grupa → numer propozycji (−1 = nie chcę). Na start: to, co już jest w trasie; inaczej tankowania zaznaczone, przerwy nie.
    val inRoute = st.groups.map { g -> g.options.indexOfFirst { o -> via.any { samePlace(it, o.place) } } }
    val sel = remember(st, inRoute) {
        mutableStateListOf<Int>().apply {
            st.groups.forEachIndexed { i, g -> add(if (inRoute[i] >= 0) inRoute[i] else if (g.kind == "fuel" && g.options.isNotEmpty()) 0 else -1) }
        }
    }
    val chosen = st.groups.mapIndexedNotNull { i, g -> g.options.getOrNull(sel[i]) }
    // wybrane tankowania (km), po kolei
    val refuels = st.groups.withIndex().filter { it.value.kind == "fuel" }.mapNotNull { (i, g) -> g.options.getOrNull(sel[i])?.atKm }.sorted()
    // zasięg w danym km trasy: od ostatniego tankowania przed tym miejscem (do pełna), a przed pierwszym – obecny
    fun rangeAt(km: Double): Double? {
        if (range == null) return null
        val last = refuels.lastOrNull { it < km - 0.01 } ?: return range - km
        return (fullKm ?: return null) - (km - last)
    }
    // zasięg tuż przed tankowaniem na stacji w km [km]
    fun arriveWith(km: Double): Double? {
        if (range == null) return null
        val last = refuels.lastOrNull { it < km - 0.5 } ?: return range - km
        return (fullKm ?: return null) - (km - last)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fuelGroups > 1) Line(
            Icons.Rounded.LocalGasStation, C.Orange,
            "Trasa ${totalKmOf(st).roundToInt()} km – trzeba tankować $fuelGroups razy" +
                (fullKm?.let { " (pełny bak ok. ${it.roundToInt()} km${if (st.fullGuess) " – szacunek" else ""})" } ?: ""),
        )
        st.groups.forEachIndexed { gi, g ->
            val on = sel[gi] >= 0
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Bg.copy(alpha = 0.35f)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = g.options.isNotEmpty()) {
                        sel[gi] = if (on) -1 else 0
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(if (on) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = if (on) C.Accent else C.Muted, modifier = Modifier.size(22.dp))
                    Icon(if (g.kind == "fuel") Icons.Rounded.LocalGasStation else Icons.Rounded.Coffee, null, tint = if (g.kind == "fuel") C.Orange else C.Accent, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f)) {
                        val title = if (g.kind == "fuel") (if (fuelGroups > 1) "Tankowanie ${g.n}" else "Tankowanie") + " · ${st.fuel.label}" else "Przerwa po ok. ${g.hours} h jazdy"
                        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                        val sub = if (g.kind == "fuel") {
                            "Ok. ${g.atKm.roundToInt()} km od startu – tu zasięg spadnie do $reserve km" + (if (g.n == 1 && range != null) " (teraz ${range.roundToInt()} km)" else "")
                        } else "Ok. ${g.atKm.roundToInt()} km od startu – 15 min odpoczynku"
                        Text(sub, fontSize = 12.sp, color = C.Muted)
                    }
                }
                if (g.options.isEmpty()) {
                    Text(
                        if (g.kind == "fuel") "Nie ma stacji z tym paliwem blisko trasy w tym miejscu" else "Nie ma MOP-u ani stacji blisko trasy w tym miejscu",
                        fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(start = 30.dp),
                    )
                } else if (on) {
                    g.options.forEachIndexed { oi, o ->
                        val picked = sel[gi] == oi
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (picked) C.Accent.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { sel[gi] = oi }.padding(start = 26.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(if (picked) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null, tint = if (picked) C.Accent else C.Muted, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.place.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val parts = ArrayList<String>()
                                parts += "${o.atKm.roundToInt()} km"
                                parts += "ok. ${RoutePreview.hhmm(o.atTime)}"
                                parts += if (o.offM < 150) "przy trasie" else "${(o.offM / 50).roundToInt() * 50} m od trasy"
                                o.iso?.takeIf { it != "PL" }?.let { parts += Countries.name(it) }
                                if (g.kind == "break" && !o.rest) parts += "stacja"
                                Text(parts.joinToString(" · "), fontSize = 11.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (g.kind == "fuel") PriceLine(o, oi == 0 && o.pln != null && o.open && g.options.count { it.pln != null } > 1)
                                val left = if (g.kind == "fuel") arriveWith(o.atKm) else rangeAt(o.atKm)
                                if (left != null) Text(
                                    when {
                                        left < 0 -> "Zabraknie ok. ${(-left).roundToInt()} km przed tym miejscem"
                                        g.kind == "fuel" -> "Dojedziesz z ok. ${left.roundToInt()} km w baku"
                                        else -> "W baku zostanie ok. ${left.roundToInt()} km"
                                    },
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                    color = when { left < 0 -> Color(0xFFFF6B6B); left < reserve -> C.Orange; else -> C.Green },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (fuelGroups > 0) FuelPriceNote(st)

        // zasięg na miejscu
        if (range != null) {
            val total = totalKmOf(st)
            val atEnd = rangeAt(total)
            val (txt, col) = when {
                refuels.isNotEmpty() && atEnd != null -> "Po zatankowaniu do pełna na miejscu zostanie ok. ${atEnd.roundToInt()} km zasięgu" +
                    (if (atEnd < reserve) " – poniżej minimum $reserve km" else "") to (if (atEnd < 0) Color(0xFFFF6B6B) else if (atEnd < reserve) C.Orange else C.Green)
                refuels.isNotEmpty() -> "Na miejscu: zależy, ile zatankujesz" to C.Muted
                range - total >= 0 -> "Bez tankowania na miejscu zostanie ok. ${(range - total).roundToInt()} km zasięgu" +
                    (if (range - total < reserve) " – poniżej minimum $reserve km" else "") to (if (range - total < reserve) C.Orange else C.Green)
                else -> "Bez tankowania zabraknie ok. ${(total - range).roundToInt()} km – zaznacz stację" to Color(0xFFFF6B6B)
            }
            Line(Icons.Rounded.LocalGasStation, col, txt)
        }

        // zapis w trasie
        val current = st.groups.map { g -> g.options.firstOrNull { o -> via.any { samePlace(it, o.place) } } }
        val dirty = st.groups.indices.any { i -> current[i] != st.groups[i].options.getOrNull(sel[i]) }
        if (dirty) {
            SmartButton(Icons.Rounded.AutoAwesome, if (chosen.isEmpty()) "Usuń postoje z trasy" else "Zapisz postoje w trasie (${chosen.size})", false) {
                val all = st.groups.flatMap { it.options }
                var list = via.filter { p -> all.none { samePlace(it.place, p) } }
                if (list.size + chosen.size > MAX_VIA) {
                    Toast.makeText(ctx, "Najwyżej $MAX_VIA punktów po drodze – odznacz któryś postój", Toast.LENGTH_LONG).show()
                    return@SmartButton
                }
                for (o in chosen.sortedBy { it.atKm }) {
                    val at = SmartPlan.insertIndex(r, list, o.atKm)
                    list = list.take(at) + o.place + list.drop(at)
                }
                onPoints(list + points.takeLast(1))
                Toast.makeText(ctx, if (chosen.isEmpty()) "Postoje usunięte z trasy" else "Postoje dodane do trasy", Toast.LENGTH_SHORT).show()
            }
        } else if (chosen.isNotEmpty()) {
            Line(Icons.Rounded.CheckBox, C.Green, "Wybrane postoje są w trasie – „Nawiguj w aucie” wyśle je do auta")
        }
    }
}

/** Najwięcej punktów po drodze (CAR OS przyjmuje tyle przystanków). */
private const val MAX_VIA = 8

private fun totalKmOf(st: SmartPlan.Stops) = st.totalKm

private fun money(v: Double, digits: Int = 2) = String.format(java.util.Locale("pl"), "%.${digits}f", v)

/** Cena paliwa: na stacji (oficjalne dane), cena maksymalna w Polsce albo średnia w kraju; podpowiedź o tańszej sieci. */
@Composable
private fun PriceLine(o: SmartPlan.Opt, cheapest: Boolean) {
    val unit = if (o.cur == "EUR") "€/l" else "zł/l"
    val pr = o.price?.let { money(it, if (o.cur == "EUR") 3 else 2) + " " + unit }
    val pln = if (o.cur != "PLN") o.pln?.let { " ≈ ${money(it)} zł" } ?: "" else ""
    val txt = when {
        pr != null && o.src == "station" -> pr + pln + (if (!o.open) " · teraz zamknięta" else "")
        pr != null && o.src == "max" -> "do $pr (cena maks. w Polsce)" + (if (o.cheap) " · zwykle tańsza sieć" else "")
        pr != null && o.src == "avg" -> "ok. $pr$pln – średnio: ${Countries.name(o.iso)}" + (if (o.cheap) " · zwykle tańsza sieć" else "")
        o.cheap -> "zwykle tańsza sieć"
        else -> null
    } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            txt, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false),
            color = when { o.src == "station" && !o.open -> C.Muted; o.src == "station" -> C.Accent; else -> C.Text2 },
        )
        if (cheapest) Text(
            "najtańsza", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06210F),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Green).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Skąd są ceny. */
@Composable
private fun FuelPriceNote(st: SmartPlan.Stops) {
    val lines = ArrayList<String>()
    lines += "Ceny na stacjach na żywo: Niemcy, Francja, Austria, Włochy, Hiszpania. Polska – cena maksymalna; inne kraje UE – średnia z biuletynu Komisji Europejskiej" +
        (EuFuel.date.value?.let { " ($it)" } ?: "") + ". Najtańsze (w złotych) pierwsze"
    if (st.deKeyMissing) lines += "Niemcy: dodaj darmowy klucz Tankerkönig w Ustawieniach → Planowanie tras, żeby mieć ceny z każdej stacji"
    if (st.deOk == false) lines += "Ceny z Niemiec niedostępne – sprawdź klucz Tankerkönig w Ustawieniach"
    if (st.fullGuess && st.groups.count { it.kind == "fuel" } > 1) lines += "Brak pojemności baku / spalania z auta – pełny bak przyjęty jako ok. ${st.fullKm?.roundToInt() ?: 650} km"
    Column(Modifier.padding(start = 4.dp, top = 2.dp)) {
        lines.forEach { Text(it, fontSize = 11.sp, color = C.Muted) }
    }
}

@Composable
private fun Line(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 13.sp, color = C.Text2)
    }
}

@Composable
private fun SmartButton(icon: ImageVector, text: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, C.Accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .clickable(enabled = !busy, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        else Icon(icon, null, tint = C.Accent, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
    }
}
