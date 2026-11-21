package pl.mati.carosconnect

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
    var ordering by remember { mutableStateOf(false) }
    var slots by remember(r.pts) { mutableStateOf<List<SmartPlan.Slot>?>(null) }
    var slotsBusy by remember(r.pts) { mutableStateOf(false) }
    var stops by remember(r.pts) { mutableStateOf<List<SmartPlan.Stop>?>(null) }
    var stopsErr by remember(r.pts) { mutableStateOf<String?>(null) }
    var stopsTry by remember(r.pts) { mutableStateOf(0) }
    val via = points.dropLast(1).map { LatLng(it.lat, it.lon) }
    val needStops = (range != null && range < r.distanceM / 1000 + 40) || r.travelS > 2 * 3600 + 20 * 60
    LaunchedEffect(r.pts, range, stopsTry) {
        stopsErr = null
        if (!needStops) { stops = emptyList(); return@LaunchedEffect }
        stops = null
        stops = try {
            SmartPlan.stops(ctx, r, range)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            stopsErr = e.message ?: "błąd"
            emptyList()
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

        // 4) tankowanie i przerwy
        val st = stops
        when {
            !needStops -> Line(Icons.Rounded.LocalGasStation, C.Muted, if (range != null) "Zasięg ${range.roundToInt()} km wystarczy, przerwa niepotrzebna" else "Krótka trasa – bez postojów")
            st == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Szukam stacji i miejsc na przerwę…", fontSize = 13.sp, color = C.Muted)
            }
            st.isEmpty() && stopsErr != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.LocalGasStation, null, tint = C.Orange, modifier = Modifier.size(20.dp))
                Text("Nie udało się pobrać stacji – serwery map nie odpowiadają ($stopsErr)", fontSize = 13.sp, color = C.Text, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.18f)).clickable { stopsTry++ }.padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text("Ponów", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Accent) }
            }
            st.isEmpty() -> Line(Icons.Rounded.LocalGasStation, C.Muted, "Brak stacji i MOP-ów blisko trasy w miejscach, gdzie wypada postój")
            else -> st.forEach { s ->
                val added = points.any { kotlin.math.abs(it.lat - s.place.lat) < 1e-5 && kotlin.math.abs(it.lon - s.place.lon) < 1e-5 }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (s.kind == "fuel") Icons.Rounded.LocalGasStation else Icons.Rounded.Coffee, null, tint = if (s.kind == "fuel") C.Orange else C.Accent, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.place.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.atKm.roundToInt()} km · ok. ${RoutePreview.hhmm(s.atTime)} · ${s.note}", fontSize = 12.sp, color = C.Muted, maxLines = 2)
                    }
                    if (!added && points.size < 4) Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent.copy(alpha = 0.18f)).clickable {
                            val vp = points.dropLast(1)
                            val at = SmartPlan.insertIndex(r, vp, s.atKm)
                            onPoints(vp.take(at) + s.place + vp.drop(at) + points.takeLast(1))
                            Toast.makeText(ctx, "Dodano postój: ${s.place.name}", Toast.LENGTH_SHORT).show()
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("Dodaj", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Accent) }
                    else if (added) Text("dodany", fontSize = 12.sp, color = C.Green)
                }
            }
        }
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
