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
fun RouteFullScreen(title: String, r: RoutePreview.Result, onClose: () -> Unit) {
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
            RouteMap(r.pts, r.jams, Modifier.fillMaxSize(), flowUrl = flowUrl, focus = focus, padTop = 150, padBottom = 250, padSide = 36)

            // góra: cel, czas, zamknij
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp)).background(C.Dialog).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val mins = (r.travelS / 60).roundToInt()
                    val dur = if (mins >= 60) "${mins / 60} h ${"%02d".format(mins % 60)} min" else "$mins min"
                    val km = if (r.distanceM >= 10_000) "${(r.distanceM / 1000).roundToInt()} km" else "%.1f km".format(r.distanceM / 1000).replace('.', ',')
                    Text("Przyjazd ${RoutePreview.hhmm(r.arrive)} · $dur · $km", fontSize = 14.sp, color = C.Muted)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(C.Raised).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Zamknij", tint = C.Text, modifier = Modifier.size(22.dp))
                }
            }

            // dół: korki
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)).background(C.Dialog).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val d = r.delayS
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            d == null -> "Korki: brak danych"
                            d >= 60 -> "Korki +${(d / 60.0).roundToInt()} min"
                            else -> "Bez korków na trasie"
                        },
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        color = when { d == null -> C.Muted; d >= 600 -> Color(0xFFE5483F); d >= 60 -> C.Orange; else -> C.Green },
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
