package pl.mati.carosconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Place
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Trasa na pełnym ekranie: cała droga, korki na trasie (kolorem) i na wszystkich drogach (TomTom),
 * lista korków – dotknięcie pokazuje dany korek na mapie.
 */
@Composable
fun RouteFullScreen(
    title: String, r: RoutePreview.Result,
    stops: List<LatLng> = emptyList(),
    /** Tryb „Dodaj punkt”: przytrzymanie palca na mapie wybiera miejsce. */
    pick: ((LatLng) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val key = remember { RoutePreview.tomtomKey(ctx) }
    var flow by remember { mutableStateOf(key.isNotBlank()) }
    var focus by remember { mutableStateOf<List<LatLng>?>(null) }
    var sel by remember { mutableStateOf(-1) }
    // korki w kolejności na trasie, z kilometrem i długością
    val jams = remember(r) {
        val cum = DoubleArray(r.pts.size)
        for (i in 1 until r.pts.size) cum[i] = cum[i - 1] + m(r.pts[i - 1], r.pts[i])
        r.jams.filter { it.pts.size >= 2 }.map { j ->
            val near = r.pts.indices.minByOrNull { m(r.pts[it], j.pts.first()) } ?: 0
            val len = (1 until j.pts.size).sumOf { m(j.pts[it - 1], j.pts[it]) }
            Triple(j, cum[near] / 1000.0, len / 1000.0)
        }.sortedBy { it.second }
    }
    // kafelki korków odświeżane co 5 min
    val flowUrl = if (flow && key.isNotBlank()) {
        "https://api.tomtom.com/traffic/map/4/tile/flow/relative-delay/{z}/{x}/{y}.png?tileSize=512&thickness=8&key=$key&t=" + (System.currentTimeMillis() / 300_000)
    } else null

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            RouteMap(
                r.pts, r.jams, Modifier.fillMaxSize(), flowUrl = flowUrl, focus = focus,
                padTop = 150, padBottom = if (pick != null) 150 else 250, padSide = 36, stops = stops, onLongPress = pick,
                others = r.alts.filterIndexed { k, _ -> k != r.altIndex }.map { it.pts },
            )

            // góra: cel, czas, zamknij
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp)).background(C.Dialog).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (pick != null) "Dodaj punkt" else title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val mins = (r.travelS / 60).roundToInt()
                    val dur = if (mins >= 60) "${mins / 60} h ${"%02d".format(mins % 60)} min" else "$mins min"
                    val km = if (r.distanceM >= 10_000) "${(r.distanceM / 1000).roundToInt()} km" else "%.1f km".format(r.distanceM / 1000).replace('.', ',')
                    Text("Przyjazd ${RoutePreview.hhmm(r.arrive)} · $dur · $km", fontSize = 14.sp, color = C.Muted)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(C.Raised).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Zamknij", tint = C.Text, modifier = Modifier.size(22.dp))
                }
            }

            // tryb „Dodaj punkt”: podpowiedź zamiast korków
            if (pick != null) Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)).background(C.Accent).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.TouchApp, null, tint = C.Bg, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f)) {
                    Text("Przytrzymaj palec na mapie", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Bg)
                    Text("w miejscu, przez które chcesz jechać – punkt doda się przed celem", fontSize = 14.sp, color = C.Bg.copy(alpha = 0.85f))
                }
            }
            // dół: korki
            if (pick == null) Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)).background(C.Dialog).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val d = r.delayS
                val (tTxt, tLvl) = RoutePreview.trafficSummary(r)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tTxt,
                        fontSize = if (tTxt.length > 24) 16.sp else 20.sp, fontWeight = FontWeight.Bold,
                        color = when (tLvl) { 0 -> C.Green; 1 -> Color(0xFFF5A623); 2 -> C.Orange; 3 -> Color(0xFFE5483F); else -> C.Muted },
                        modifier = Modifier.weight(1f),
                    )
                    if (key.isNotBlank()) Pill(if (flow) "Korki wszędzie" else "Tylko trasa", flow, Icons.Rounded.Traffic) { flow = !flow }
                }
                if (d == null && r.trafficNote != null) Text(r.trafficNote, fontSize = 13.sp, color = C.Muted)
                if (jams.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Cała trasa", sel == -1, Icons.Rounded.Route) { sel = -1; focus = null }
                        jams.forEachIndexed { i, (j, atKm, lenKm) ->
                            val col = jamColor(j)
                            Row(
                                Modifier.clip(RoundedCornerShape(14.dp))
                                    .background(if (sel == i) col.copy(alpha = 0.25f) else C.Raised)
                                    .border(if (sel == i) 1.5.dp else 0.dp, if (sel == i) col else Color.Transparent, RoundedCornerShape(14.dp))
                                    .clickable { sel = i; focus = j.pts }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                                Column {
                                    Text(
                                        if (j.kind == "ROAD_CLOSURE") "Zamknięta droga" else "+${(j.delayS / 60.0).roundToInt().coerceAtLeast(1)} min",
                                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.Text,
                                    )
                                    Text("${atKm.roundToInt()} km · ${fmtKm(lenKm)}", fontSize = 12.sp, color = C.Muted)
                                }
                            }
                        }
                    }
                }
                // legenda kolorów
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Legend(Color(0xFF2FD3F0), "trasa")
                    Legend(Color(0xFFF5A623), "wolniej")
                    Legend(Color(0xFFF26B1D), "korek")
                    Legend(Color(0xFFD9302B), "duży korek")
                    Legend(Color(0xFF7A0E0E), "stoi / zamknięte")
                }
                if (key.isBlank()) Text("Korki na mapie: dodaj klucz TomTom (Ustawienia → Korki w CAR OS)", fontSize = 12.sp, color = C.Muted)
            }
        }
    }
}

@Composable
private fun Pill(text: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Accent.copy(alpha = 0.2f) else C.Raised).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = if (on) C.Accent else C.Text2, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Text else C.Text2, maxLines = 1)
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width = 14.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(label, fontSize = 11.sp, color = C.Muted, maxLines = 1)
    }
}

private fun jamColor(j: RoutePreview.Jam): Color = when {
    j.kind == "ROAD_CLOSURE" || j.magnitude == 4 -> Color(0xFF7A0E0E)
    j.magnitude == 3 -> Color(0xFFD9302B)
    j.magnitude == 2 -> Color(0xFFF26B1D)
    else -> Color(0xFFF5A623)
}

private fun fmtKm(km: Double) = if (km >= 1) "%.1f km".format(km).replace('.', ',') else "${(km * 1000).roundToInt()} m"

private fun m(a: LatLng, b: LatLng): Double {
    val dy = (b.latitude - a.latitude) * 111_320.0
    val dx = (b.longitude - a.longitude) * 111_320.0 * cos(Math.toRadians((a.latitude + b.latitude) / 2))
    return sqrt(dx * dx + dy * dy)
}

/**
 * Lista punktów trasy jak w Mapach Google: start (stały), punkty po drodze i cel.
 * Przeciągnij za uchwyt ═, żeby zmienić kolejność (ostatni = cel), × usuwa punkt.
 */
@Composable
fun WaypointList(
    startLabel: String?, points: List<CarData.Place>, onPoints: (List<CarData.Place>) -> Unit,
    onAdd: () -> Unit, canAdd: Boolean,
) {
    val rowH = 50.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragIdx by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Raised).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // start
        Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF7CF06B)))
            }
            Text("Start " + (startLabel ?: "– brak"), fontSize = 14.sp, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val target = if (dragIdx >= 0) (dragIdx + (dragDy / rowPx).roundToInt()).coerceIn(0, points.lastIndex) else -1
        points.forEachIndexed { i, p ->
            val isDest = i == points.lastIndex
            val shift = when {
                dragIdx < 0 || i == dragIdx -> 0f
                i in (dragIdx + 1)..target -> -rowPx
                i in target until dragIdx -> rowPx
                else -> 0f
            }
            Row(
                Modifier.fillMaxWidth().height(rowH)
                    .zIndex(if (i == dragIdx) 1f else 0f)
                    .graphicsLayer { translationY = if (i == dragIdx) dragDy else shift }
                    .then(if (i == dragIdx) Modifier.shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(C.Dialog) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    // po kolejności: pośrednie A, B, C…; cel – pinezka
                    if (isDest) Icon(Icons.Rounded.Place, null, tint = Color(0xFFE5483F), modifier = Modifier.size(24.dp))
                    else Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFFFB347)), contentAlignment = Alignment.Center) {
                        Text(('A' + i).toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1205))
                    }
                }
                Text(p.name, fontSize = 15.sp, fontWeight = if (isDest) FontWeight.SemiBold else FontWeight.Normal, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (points.size > 1) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).pointerInput(points, i) {
                            detectDragGestures(
                                onDragStart = { dragIdx = i; dragDy = 0f },
                                onDragEnd = {
                                    val to = (i + (dragDy / rowPx).roundToInt()).coerceIn(0, points.lastIndex)
                                    if (to != i) onPoints(points.toMutableList().apply { add(to, removeAt(i)) })
                                    dragIdx = -1; dragDy = 0f
                                },
                                onDragCancel = { dragIdx = -1; dragDy = 0f },
                                onDrag = { ch, amount -> ch.consume(); dragDy += amount.y },
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.DragHandle, "Przeciągnij, żeby zmienić kolejność", tint = C.Muted, modifier = Modifier.size(24.dp)) }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).clickable { onPoints(points.filterIndexed { k, _ -> k != i }) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "Usuń punkt", tint = C.Muted, modifier = Modifier.size(20.dp)) }
                }
            }
        }
        if (points.size < 4) Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = canAdd, onClick = onAdd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = if (canAdd) C.Accent else C.Muted, modifier = Modifier.size(22.dp))
            }
            Text(
                if (canAdd) "Dodaj punkt" else "Dodaj punkt (po wyznaczeniu trasy)",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (canAdd) C.Accent else C.Muted,
            )
        }
    }
}
