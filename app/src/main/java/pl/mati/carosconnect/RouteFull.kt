package pl.mati.carosconnect

import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
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

/** Legenda kolorów korków. */
@Composable
internal fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width = 14.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(label, fontSize = 11.sp, color = C.Muted, maxLines = 1)
    }
}

internal fun jamColor(j: RoutePreview.Jam): Color = when {
    j.kind == "ROAD_CLOSURE" || j.magnitude == 4 -> Color(0xFF7A0E0E)
    j.magnitude == 3 -> Color(0xFFD9302B)
    j.magnitude == 2 -> Color(0xFFF26B1D)
    else -> Color(0xFFF5A623)
}

internal fun fmtKm(km: Double) = if (km >= 1) "%.1f km".format(km).replace('.', ',') else "${(km * 1000).roundToInt()} m"

/** Odległość w metrach (przybliżenie płaskie – wystarcza na kilometry trasy). */
internal fun distM(a: LatLng, b: LatLng): Double {
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
    modifier: Modifier = Modifier, bg: Color = C.Raised, rowH: androidx.compose.ui.unit.Dp = 50.dp,
) {
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    var dragIdx by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // start
        Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(0xFF7CF06B)))
            }
            Text("Start " + (startLabel ?: "– brak"), fontSize = 14.sp, color = C.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val labels = stopLabels(points.map { it.kind })
        val target = if (dragIdx >= 0) (dragIdx + (dragDy / rowPx).roundToInt()).coerceIn(0, points.lastIndex) else -1
        // dużo punktów (stacje i postoje z planu) – lista się przewija, nagłówek nie zasłania mapy
        val many = points.size > 5
        val listScroll = rememberScrollState()
        Column(
            if (many) Modifier.fillMaxWidth().heightIn(max = rowH * 5.5f).verticalScroll(listScroll) else Modifier.fillMaxWidth(),
        ) {
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
                        when (p.kind) {
                            "fuel" -> Icon(Icons.Rounded.LocalGasStation, "Stacja", tint = Color(0xFF1A1205), modifier = Modifier.size(14.dp))
                            "break" -> Icon(Icons.Rounded.Coffee, "Postój", tint = Color(0xFF1A1205), modifier = Modifier.size(14.dp))
                            else -> {
                                val lb = labels[i]
                                Text(lb, fontSize = if (lb.length > 1) 10.sp else 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1A1205))
                            }
                        }
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
        } // punkty
        if (points.size <= pl.mati.caros.core.Proto.MAX_STOPS) Row(
            Modifier.fillMaxWidth().height(rowH - 6.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = canAdd, onClick = onAdd),
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

/** Etykieta punktu po drodze: A–Z, potem numery (27, 28…). */
fun stopLabel(i: Int): String = if (i < 26) ('A' + i).toString() else (i + 1).toString()

/** Etykiety punktów: litery kolejno tylko dla zwykłych punktów; stacje i postoje ("") mają ikonę. */
fun stopLabels(kinds: List<String?>): List<String> {
    var n = 0
    return kinds.map { if (it == null) stopLabel(n++) else "" }
}

/**
 * Najlepsze miejsce nowego punktu w kolejności (indeks w [points], zawsze przed celem): najmniejszy
 * objazd w linii prostej między sąsiednimi punktami trasy (start → punkty → cel).
 */
fun bestInsertIndex(start: org.maplibre.android.geometry.LatLng?, points: List<CarData.Place>, np: CarData.Place): Int {
    if (points.size <= 1) return 0
    fun d(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val x = Math.toRadians(bLon - aLon) * Math.cos(Math.toRadians((aLat + bLat) / 2))
        val y = Math.toRadians(bLat - aLat)
        return Math.sqrt(x * x + y * y)
    }
    var best = points.lastIndex
    var bestCost = Double.MAX_VALUE
    for (k in 0..points.lastIndex) {
        val prev = if (k == 0) start?.let { it.latitude to it.longitude } ?: continue else points[k - 1].lat to points[k - 1].lon
        val next = points[k]
        val cost = d(prev.first, prev.second, np.lat, np.lon) + d(np.lat, np.lon, next.lat, next.lon) - d(prev.first, prev.second, next.lat, next.lon)
        if (cost < bestCost) { bestCost = cost; best = k }
    }
    return best
}

/** 1 punkt, 2–4 punkty, 5 punktów, 22 punkty… */
fun punktow(n: Int): String = when {
    n == 1 -> "punkt"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "punkty"
    else -> "punktów"
}
