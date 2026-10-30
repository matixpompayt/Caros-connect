package pl.mati.carosconnect

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Udostępnione z innej aplikacji (Mapy Google itp.): tekst albo adres geo:. */
object Share {
    val incoming = MutableStateFlow<Pair<String?, Uri?>?>(null)
}

private val PL = Locale("pl")
private fun km(m: Double): String = if (m < 1000) "${m.roundToInt()} m" else String.format(PL, "%.1f km", m / 1000)
private fun thousands(v: Int): String = "%,d".format(v).replace(',', ' ')

// ======================= AUTO =======================

@Composable
fun CarTab(actions: Actions) {
    val st by CarData.status.collectAsState()
    val pairing by Store.pairing.collectAsState()
    if (pairing == null) {
        Card {
            Text("Połącz z CAR OS", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text("Stan auta, serwis, trasy i wysyłanie celu do nawigacji działają po połączeniu z radiem kodem QR.", fontSize = 15.sp, color = C.Muted)
        }
        PrimaryButton("Skanuj kod z CAR OS", Icons.Rounded.QrCodeScanner) { actions.scanQr() }
        return
    }
    val s = st
    if (s == null) {
        Card {
            Text("Czekam na dane z auta", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text("Włącz radio z CAR OS (z internetem) i pociągnij ekran w dół, żeby odświeżyć.", fontSize = 15.sp, color = C.Muted)
        }
    } else {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = C.Accent, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("PRZEBIEG", fontSize = 12.sp, letterSpacing = 1.5.sp, color = C.Muted, fontWeight = FontWeight.SemiBold)
                    Text(s.odo?.let { "${thousands(it)} km" } ?: "nie ustawiono", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("Dziś", km(s.todayM), Modifier.weight(1f))
                StatBox("Tydzień", km(s.weekM), Modifier.weight(1f))
                StatBox("Miesiąc", km(s.monthM), Modifier.weight(1f))
            }
            Text("Dane z auta: ${whenText(s.time)}" + (if (s.version.isNotBlank()) " · CAR OS ${s.version}" else ""), fontSize = 12.sp, color = C.Muted)
        }

        Label("Serwis")
        val known = s.service.filter { it.known }
        if (known.isEmpty()) {
            Card { Text("Brak danych serwisowych – uzupełnij w radiu: Serwis → kiedy ostatnio.", fontSize = 15.sp, color = C.Muted) }
        }
        known.forEach { ServiceRow(it) }
        val unknown = s.service.size - known.size
        if (unknown > 0) Text("$unknown poz. bez danych – uzupełnij w radiu", fontSize = 13.sp, color = C.Muted)
    }

    Label("Wyślij cel do auta", Modifier.padding(top = 6.dp))
    SendPlaceCard()

    val favs = s?.favs.orEmpty()
    Label("Ulubione w aucie", Modifier.padding(top = 6.dp))
    if (favs.isEmpty()) {
        Text("Brak – dodaj gwiazdką przy wyszukanym miejscu albo udostępniając miejsce z Map Google.", fontSize = 14.sp, color = C.Muted)
    }
    favs.forEach { f -> FavRow(f) }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.Raised).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, fontSize = 12.sp, color = C.Muted)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1)
    }
}

@Composable
private fun ServiceRow(s: CarData.Service) {
    val col = when {
        s.overdue -> Color(0xFFFF5A5F)
        s.soon -> C.Orange
        else -> C.Green
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card)
            .border(1.dp, if (s.overdue) col.copy(alpha = 0.6f) else C.Border, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Build, null, tint = col, modifier = Modifier.size(20.dp))
            Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(s.text, fontSize = 14.sp, color = if (s.overdue || s.soon) col else C.Muted)
        LinearProgressIndicator(
            progress = { s.fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = col, trackColor = C.Raised,
        )
    }
}

/** Wyszukiwarka celu → nawigacja w aucie albo ulubione w aucie. */
@Composable
private fun SendPlaceCard() {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CarData.Place>>(emptyList()) }
    var chosen by remember { mutableStateOf<CarData.Place?>(null) }

    fun search() {
        if (q.isBlank()) return
        focus.clearFocus()
        busy = true
        scope.launch {
            results = withContext(Dispatchers.IO) { Places.search(q, Locator.lastKnown(ctx)) }
            busy = false
            if (results.isEmpty()) Toast.makeText(ctx, "Nic nie znaleziono", Toast.LENGTH_SHORT).show()
        }
    }

    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it },
                placeholder = { Text("Dokąd jedziemy?") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
                    focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
                ),
            )
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(C.Accent).clickable { search() },
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(color = C.Bg, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Icon(Icons.Rounded.Search, "Szukaj", tint = C.Bg, modifier = Modifier.size(26.dp))
            }
        }
        Text("Albo w Mapach Google: Udostępnij → Wyślij do auta", fontSize = 13.sp, color = C.Muted)
        results.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { chosen = r }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Navigation, null, tint = C.Accent, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontSize = 15.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.detail.isNotBlank()) Text(r.detail, fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
    chosen?.let { PlaceSheet(it, resolving = false, error = null) { chosen = null; results = emptyList(); q = "" } }
}

@Composable
private fun FavRow(f: CarData.Place) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC53D), modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(f.name, fontSize = 16.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.detail.isNotBlank()) Text(f.detail, fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconBtn(Icons.Rounded.Navigation, "Nawiguj w aucie") {
            scope.launch { sendToCar(ctx, "route", f) }
        }
        IconBtn(Icons.Rounded.Delete, "Usuń z ulubionych") {
            CarData.removeFavLocal(f)
            scope.launch { sendToCar(ctx, "fav_del", f) }
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = C.Text2, modifier = Modifier.size(22.dp))
    }
}

/** Wysyła miejsce do radia (trasa / ulubione). */
suspend fun sendToCar(ctx: android.content.Context, type: String, p: CarData.Place): Boolean {
    val pairing = Store.pairing.value
    if (pairing == null) {
        Toast.makeText(ctx, "Najpierw połącz z CAR OS kodem QR", Toast.LENGTH_LONG).show()
        return false
    }
    val ok = withContext(Dispatchers.IO) {
        try {
            Link.send(pairing, JSONObject().put("type", type).put("n", p.name).put("d", p.detail).put("lat", p.lat).put("lon", p.lon))
            true
        } catch (_: Exception) {
            false
        }
    }
    Toast.makeText(
        ctx,
        if (!ok) "Nie udało się wysłać – sprawdź internet"
        else when (type) {
            "route" -> "Wysłano do auta: ${p.name}. Trasa pojawi się w CAR OS (radio musi mieć internet)"
            "fav_add" -> "Dodano do ulubionych w aucie"
            else -> "Usunięto z ulubionych w aucie"
        },
        Toast.LENGTH_LONG,
    ).show()
    return ok
}

/** Okno: co zrobić z miejscem (nawigacja w aucie / ulubione). */
@Composable
fun PlaceSheet(place: CarData.Place?, resolving: Boolean, error: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text(if (place != null) place.name else "Wyślij do auta", color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    resolving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        Text("Odczytuję miejsce…", color = C.Muted)
                    }
                    place == null -> Text(error ?: "Nie udało się odczytać miejsca", color = C.Orange)
                    else -> {
                        if (place.detail.isNotBlank()) Text(place.detail, color = C.Muted, fontSize = 14.sp)
                        PrimaryButton(if (sending) "Wysyłam…" else "Nawiguj w aucie", Icons.Rounded.Navigation) {
                            if (sending) return@PrimaryButton
                            sending = true
                            scope.launch { if (sendToCar(ctx, "route", place)) onDismiss(); sending = false }
                        }
                        SecondaryButton("Dodaj do ulubionych w aucie", Icons.Rounded.StarBorder, Modifier.fillMaxWidth()) {
                            if (sending) return@SecondaryButton
                            sending = true
                            scope.launch {
                                if (sendToCar(ctx, "fav_add", place)) { CarData.addFavLocal(place); onDismiss() }
                                sending = false
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

/** Udostępnienie z Map Google → odczyt miejsca → okno wysyłki. */
@Composable
fun ShareDialog(share: Pair<String?, Uri?>, actions: Actions, onDismiss: () -> Unit) {
    var place by remember(share) { mutableStateOf<CarData.Place?>(null) }
    var resolving by remember(share) { mutableStateOf(true) }
    LaunchedEffect(share) {
        place = withContext(Dispatchers.IO) { try { Places.resolve(share.first, share.second) } catch (_: Exception) { null } }
        resolving = false
    }
    if (Store.pairing.value == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = C.Card,
            title = { Text("Połącz z CAR OS", color = C.Text) },
            text = { Text("Żeby wysyłać cele do auta, połącz telefon z radiem kodem QR (radio: Ustawienia → Połączenia → CAR OS Connect).", color = C.Muted) },
            confirmButton = { TextButton(onClick = { onDismiss(); actions.scanQr() }) { Text("Skanuj kod") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
        )
        return
    }
    PlaceSheet(place, resolving, "Nie znalazłem współrzędnych w udostępnionym linku", onDismiss)
}

// ======================= TRASY =======================

private fun dayLabel(t: Long): String {
    val c = Calendar.getInstance()
    val today = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    c.timeInMillis = t
    val d = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    return when (today - d) {
        0 -> "Dziś"
        1 -> "Wczoraj"
        else -> SimpleDateFormat("EEEE, d MMMM", PL).format(Date(t)).replaceFirstChar { it.uppercase() }
    }
}

private fun dur(s: Long): String = if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else "${(s / 60).coerceAtLeast(1)} min"
private val HM = SimpleDateFormat("HH:mm", PL)

@Composable
fun TripsTab(sel: CarData.Trip?, onSelect: (CarData.Trip?) -> Unit) {
    val trips by CarData.trips.collectAsState()
    if (sel != null) {
        TripDetail(sel) { onSelect(null) }
        return
    }
    val weekStart = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        if (timeInMillis > System.currentTimeMillis()) add(Calendar.WEEK_OF_YEAR, -1)
    }.timeInMillis
    val week = trips.filter { it.start >= weekStart }
    Card {
        Text("Ten tydzień", fontSize = 13.sp, color = C.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("Dystans", km(week.sumOf { it.distanceM }), Modifier.weight(1f))
            StatBox("Czas", dur(week.sumOf { it.durationS }), Modifier.weight(1f))
            StatBox("Jazdy", "${week.size}", Modifier.weight(1f))
        }
    }
    if (trips.isEmpty()) {
        Card {
            Text("Brak tras", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text("CAR OS w radiu zapisuje każdą jazdę i wysyła ją tutaj razem z mapą trasy.", fontSize = 14.sp, color = C.Muted)
        }
        return
    }
    var lastDay = ""
    trips.take(100).forEach { t ->
        val day = dayLabel(t.start)
        if (day != lastDay) {
            Label(day, Modifier.padding(top = 4.dp))
            lastDay = day
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).clickable { onSelect(t) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.Raised), contentAlignment = Alignment.Center) {
                Icon(if (t.path != null) Icons.Rounded.Map else Icons.Rounded.DirectionsCar, null, tint = C.Accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${HM.format(Date(t.start))} – ${HM.format(Date(t.end))}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text("${km(t.distanceM)} · ${dur(t.durationS)} · śr. ${t.avgKmh.roundToInt()} km/h", fontSize = 13.sp, color = C.Muted)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun TripDetail(t: CarData.Trip, onBack: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.ArrowBack, null, tint = C.Text2, modifier = Modifier.size(22.dp))
        Text("Trasy", fontSize = 16.sp, color = C.Text2)
    }
    Text("${dayLabel(t.start)}, ${HM.format(Date(t.start))} – ${HM.format(Date(t.end))}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
    val pts = remember(t.path) { t.path?.let { decodePolyline(it) }.orEmpty() }
    if (pts.size >= 2) {
        TripMap(pts, Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, C.Border, RoundedCornerShape(24.dp)))
    } else {
        Card { Text("Brak śladu GPS dla tej jazdy (starsze trasy albo brak sygnału).", fontSize = 14.sp, color = C.Muted) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox("Dystans", km(t.distanceM), Modifier.weight(1f))
        StatBox("Czas", dur(t.durationS), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox("Średnia", "${t.avgKmh.roundToInt()} km/h", Modifier.weight(1f))
        StatBox("Maks.", "${t.maxSpeed} km/h", Modifier.weight(1f))
    }
    StatBox("Czas w ruchu", dur(t.movingS), Modifier.fillMaxWidth())
}

@Composable
private fun TripMap(pts: List<GeoPoint>, modifier: Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            overlayManager.tilesOverlay.setLoadingBackgroundColor(android.graphics.Color.rgb(17, 22, 28))
            val casing = Polyline(this).apply {
                setPoints(pts)
                outlinePaint.color = android.graphics.Color.rgb(10, 12, 15)
                outlinePaint.strokeWidth = 16f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            }
            val line = Polyline(this).apply {
                setPoints(pts)
                outlinePaint.color = android.graphics.Color.rgb(47, 211, 240)
                outlinePaint.strokeWidth = 9f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.isAntiAlias = true
            }
            val start = Polyline(this).apply {
                setPoints(listOf(pts.first(), GeoPoint(pts.first().latitude + 0.000001, pts.first().longitude)))
                outlinePaint.color = android.graphics.Color.rgb(124, 240, 107)
                outlinePaint.strokeWidth = 26f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }
            val end = Polyline(this).apply {
                setPoints(listOf(pts.last(), GeoPoint(pts.last().latitude + 0.000001, pts.last().longitude)))
                outlinePaint.color = android.graphics.Color.WHITE
                outlinePaint.strokeWidth = 26f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            }
            overlays.add(casing); overlays.add(line); overlays.add(start); overlays.add(end)
            addOnFirstLayoutListener { _, _, _, _, _ ->
                try { zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.25f), false) } catch (_: Exception) {}
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = modifier)
}

fun decodePolyline(s: String): List<GeoPoint> {
    val out = ArrayList<GeoPoint>()
    var i = 0
    var lat = 0L
    var lon = 0L
    try {
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += d else lon += d
            }
            out += GeoPoint(lat / 1e5, lon / 1e5)
        }
    } catch (_: Exception) {
    }
    return out
}

// ======================= PARKING: parkometr, pogoda =======================

@Composable
fun MeterCard() {
    val ctx = LocalContext.current
    val end by CarData.meterEnd.collectAsState()
    val start by CarData.meterStart.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) {
        while (end > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Timer, null, tint = C.Accent, modifier = Modifier.size(22.dp))
            Text("Płatne parkowanie", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
        }
        if (end > now) {
            val left = (end - now) / 1000
            val txt = if (left >= 3600) String.format(PL, "%d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60)
            else String.format(PL, "%02d:%02d", left / 60, left % 60)
            Text(txt, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (left < Meter.WARN_MIN * 60) C.Orange else C.Text)
            Text("do ${HM.format(Date(end))} · przypomnę ${Meter.WARN_MIN} min przed końcem", fontSize = 13.sp, color = C.Muted)
            if (end > start && start > 0) {
                LinearProgressIndicator(
                    progress = { ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = C.Accent, trackColor = C.Raised,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("+15 min", Modifier.weight(1f)) { Meter.extend(ctx, 15); CarWidget.updateAll(ctx) }
                Chip("+30 min", Modifier.weight(1f)) { Meter.extend(ctx, 30); CarWidget.updateAll(ctx) }
                Chip("Zakończ", Modifier.weight(1f)) { Meter.stop(ctx); CarWidget.updateAll(ctx) }
            }
        } else {
            Text("Ustaw opłacony czas – przypomnę ${Meter.WARN_MIN} min przed końcem.", fontSize = 14.sp, color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h", 180 to "3 h").forEach { (m, l) ->
                    Chip(l, Modifier.weight(1f)) {
                        Meter.start(ctx, m)
                        CarWidget.updateAll(ctx)
                        Toast.makeText(ctx, "Parkometr: $l", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(C.Raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1) }
}

@Composable
fun CarWeatherCard() {
    val w by CarData.weather.collectAsState()
    val spot by Store.spot.collectAsState()
    val ww = w ?: return
    if (spot == null || System.currentTimeMillis() - ww.time > 3 * 3_600_000L) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (ww.frost) Color(0xFF15324A) else C.Card).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Thermostat, null, tint = if (ww.frost) Color(0xFF6CC4FF) else C.Accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text("Przy aucie: ${String.format(PL, "%.0f", ww.temp)}°C", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text(
                if (ww.frost) "Możliwy szron – szyby mogą być oblodzone" else "Bez ryzyka szronu",
                fontSize = 13.sp, color = if (ww.frost) Color(0xFF6CC4FF) else C.Muted,
            )
        }
    }
}

// ======================= USTAWIENIA =======================

@Composable
fun SettingsTab(perms: Perms, actions: Actions, onPickCar: () -> Unit, onUnpair: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairing by Store.pairing.collectAsState()
    val car by Store.carName.collectAsState()
    val nService by CarData.notifService.collectAsState()
    val nFrost by CarData.notifFrost.collectAsState()
    val frostHour by CarData.frostHour.collectAsState()
    val bkTime by CarData.backupTime.collectAsState()
    val bkCar by CarData.backupCar.collectAsState()
    var restoreAsk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Label("Połączenie")
    SettingItem(
        Icons.Rounded.QrCode2, "Połączenie z CAR OS",
        pairing?.let { "Połączono: ${it.car ?: "CAR OS"} – dotknij, aby rozłączyć" } ?: "Niepołączono – zeskanuj kod z radia",
    ) { if (pairing != null) onUnpair() else actions.scanQr() }
    SettingItem(Icons.Rounded.DirectionsCar, "Auto (Bluetooth radia)", car ?: "Nie wybrano") {
        if (!perms.bt) actions.requestBt() else onPickCar()
    }
    SettingItem(Icons.Rounded.PlayCircle, "Autostart (Xiaomi)", "Włącz, żeby aplikacja budziła się po połączeniu z autem") { actions.openAutostart() }

    Label("Powiadomienia", Modifier.padding(top = 6.dp))
    ToggleRow("Przypomnienia serwisowe", "Olej, przegląd, ubezpieczenie – z danych CAR OS", nService) { CarData.setNotifService(it) }
    ToggleRow("Ostrzeżenie o mrozie", "Rano, gdy przy aucie może być szron", nFrost) { CarData.setNotifFrost(it) }
    if (nFrost) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Godzina ostrzeżenia", fontSize = 16.sp, color = C.Text, modifier = Modifier.weight(1f))
            Chip("−", Modifier.size(width = 48.dp, height = 42.dp)) { CarData.setFrostHour(frostHour - 1) }
            Text(String.format(PL, "%02d:00", frostHour), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.padding(horizontal = 12.dp))
            Chip("+", Modifier.size(width = 48.dp, height = 42.dp)) { CarData.setFrostHour(frostHour + 1) }
        }
    }

    Label("Kopia ustawień CAR OS", Modifier.padding(top = 6.dp))
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = CarData.backupText()
        val ok = text != null && try {
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
        } catch (_: Exception) {
            false
        }
        Toast.makeText(ctx, if (ok) "Zapisano kopię do pliku" else "Nie udało się zapisać", Toast.LENGTH_SHORT).show()
    }
    Card {
        Text(
            if (bkTime > 0) "Kopia z ${whenText(bkTime)}" else "Brak kopii",
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text,
        )
        Text(
            if (bkTime > 0) "${bkCar.ifBlank { "CAR OS" }} · profil, wygląd, serwis, dziennik tras, ulubione"
            else "Radio wysyła kopię raz dziennie. Po wymianie albo resecie radia przywrócisz z niej wszystko.",
            fontSize = 13.sp, color = C.Muted,
        )
        SecondaryButton(if (busy) "Pobieram…" else "Pobierz kopię teraz", Icons.Rounded.CloudDownload, Modifier.fillMaxWidth()) {
            if (busy || pairing == null) return@SecondaryButton
            busy = true
            scope.launch {
                Sync.run(ctx, ask = true, wantBackup = true)
                busy = false
                Toast.makeText(ctx, if (CarData.backupTime.value > 0) "Kopia z ${whenText(CarData.backupTime.value)}" else "Brak odpowiedzi z radia – czy jest włączone i ma internet?", Toast.LENGTH_LONG).show()
            }
        }
        if (bkTime > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Przywróć w aucie", Icons.Rounded.Restore, Modifier.weight(1f)) { restoreAsk = true }
                SecondaryButton("Do pliku", Icons.Rounded.Save, Modifier.weight(1f)) {
                    exporter.launch("CarOS-kopia-" + SimpleDateFormat("yyyy-MM-dd", PL).format(Date(bkTime)) + ".json")
                }
            }
        }
    }

    Label("Aplikacja", Modifier.padding(top = 6.dp))
    UpdateItem()
    Text("CAR OS Connect ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))

    if (restoreAsk) AlertDialog(
        onDismissRequest = { restoreAsk = false },
        containerColor = C.Card,
        title = { Text("Przywrócić ustawienia w aucie?", color = C.Text) },
        text = {
            Text(
                "Kopia z ${whenText(bkTime)} trafi do radia. Na ekranie radia pojawi się pytanie – potwierdź tam. Radio musi być włączone i mieć internet.",
                color = C.Muted,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                restoreAsk = false
                val p = Store.pairing.value
                val text = CarData.backupText()
                if (p == null || text == null) return@TextButton
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { try { Link.sendRestore(p, text); true } catch (_: Exception) { false } }
                    Toast.makeText(ctx, if (ok) "Wysłano – potwierdź przywrócenie na ekranie radia" else "Nie udało się wysłać", Toast.LENGTH_LONG).show()
                }
            }) { Text("Wyślij do radia") }
        },
        dismissButton = { TextButton(onClick = { restoreAsk = false }) { Text("Anuluj") } },
    )
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).clickable { onChange(!on) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text(sub, fontSize = 13.sp, color = C.Muted)
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg),
        )
    }
}
