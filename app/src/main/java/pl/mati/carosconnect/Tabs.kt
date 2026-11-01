package pl.mati.carosconnect

import org.maplibre.android.geometry.LatLng
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.text.input.KeyboardType
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
    // zakładka otwarta → co 20 s sprawdzaj zmiany z radia (np. serwis zmieniony w aucie)
    LaunchedEffect(pairing) {
        while (pairing != null) {
            delay(20_000)
            actions.refreshFromCar()
        }
    }
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
        var editOdo by remember { mutableStateOf(false) }
        var editItem by remember { mutableStateOf<CarData.SvcItem?>(null) }
        val editable = s.items.isNotEmpty()
        Card {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = editable) { editOdo = true },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = C.Accent, modifier = Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("PRZEBIEG", fontSize = 12.sp, letterSpacing = 1.5.sp, color = C.Muted, fontWeight = FontWeight.SemiBold)
                    Text(if (s.odoSet && s.odo != null) "${thousands(s.odo)} km" else "nie ustawiono", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text)
                }
                if (editable) Icon(Icons.Rounded.Edit, "Zmień przebieg", tint = C.Muted, modifier = Modifier.size(22.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("Dziś", km(s.todayM), Modifier.weight(1f))
                StatBox("Tydzień", km(s.weekM), Modifier.weight(1f))
                StatBox("Miesiąc", km(s.monthM), Modifier.weight(1f))
            }
            if (s.pending) {
                Text("Zmiany czekają na radio – zapiszą się, gdy CAR OS będzie online", fontSize = 12.sp, color = C.Orange)
            } else {
                Text("Dane z auta: ${whenText(s.time)}" + (if (s.version.isNotBlank()) " · CAR OS ${s.version}" else ""), fontSize = 12.sp, color = C.Muted)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Serwis", Modifier.weight(1f))
            if (editable) Text("dotknij, aby zmienić", fontSize = 12.sp, color = C.Muted)
        }
        if (editable) {
            val rows = s.items.map { it to ServiceCalc.due(it, s.odo, s.odoSet) }
                .sortedWith(compareByDescending<Pair<CarData.SvcItem, CarData.Service>> { it.second.overdue }
                    .thenByDescending { it.second.soon }.thenByDescending { it.second.known }.thenByDescending { it.second.fraction })
            rows.forEach { (item, due) -> ServiceRow(due) { editItem = item } }
        } else {
            val known = s.service.filter { it.known }
            if (known.isEmpty()) {
                Card { Text("Brak danych serwisowych – uzupełnij w radiu: Serwis → kiedy ostatnio.", fontSize = 15.sp, color = C.Muted) }
            }
            known.forEach { ServiceRow(it, null) }
            Text("Zaktualizuj CAR OS w radiu, żeby zmieniać serwis z telefonu", fontSize = 13.sp, color = C.Muted)
        }
        if (editOdo) OdoDialog(s.odo) { editOdo = false }
        editItem?.let { ServiceEditDialog(it) { editItem = null } }
    }

    Label("Wyślij cel do auta", Modifier.padding(top = 6.dp))
    SendPlaceCard()

    // dom i praca – te same co w CAR OS, zmiana w telefonie albo w radiu widoczna po obu stronach
    if (s != null) {
        Label("Dom i praca", Modifier.padding(top = 6.dp))
        val home = s.home
        if (home == null) {
            Text("Zaktualizuj CAR OS w radiu, żeby zmieniać dom i pracę z telefonu", fontSize = 13.sp, color = C.Muted)
        } else {
            var editPlace by remember { mutableStateOf<Boolean?>(null) } // false = dom, true = praca
            val work = s.work ?: CarData.Addr("", null, null)
            HomeWorkRow(false, home) { editPlace = false }
            HomeWorkRow(true, work) { editPlace = true }
            editPlace?.let { w -> PlaceEditDialog(w, if (w) work else home) { editPlace = null } }
        }
    }

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
private fun ServiceRow(s: CarData.Service, onClick: (() -> Unit)?) {
    val col = when {
        !s.known -> C.Muted
        s.overdue -> Color(0xFFFF5A5F)
        s.soon -> C.Orange
        else -> C.Green
    }
    Column(
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card)
            .border(1.dp, if (s.overdue) col.copy(alpha = 0.6f) else C.Border, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Build, null, tint = col, modifier = Modifier.size(20.dp))
            Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onClick != null) Icon(Icons.Rounded.Edit, null, tint = C.Muted, modifier = Modifier.size(18.dp))
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
private fun HomeWorkRow(work: Boolean, a: CarData.Addr, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = if (work) "Praca" else "Dom"
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).clickable(onClick = onEdit)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(C.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(if (work) Icons.Rounded.Work else Icons.Rounded.Home, null, tint = C.Accent, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text(
                if (a.isSet) a.address else "Nie ustawiono – dotknij, aby dodać",
                fontSize = 13.sp, color = if (a.isSet) C.Muted else C.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (a.isSet) {
            if (busy) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else IconBtn(Icons.Rounded.Navigation, "Nawiguj w aucie") {
                busy = true
                scope.launch {
                    val p = a.place(label) ?: withContext(Dispatchers.IO) {
                        try { Places.search(a.address, null).firstOrNull()?.copy(name = label, detail = a.address) } catch (_: Exception) { null }
                    }
                    if (p == null) Toast.makeText(ctx, "Nie znaleziono adresu – ustaw go ponownie", Toast.LENGTH_LONG).show()
                    else sendToCar(ctx, "route", p)
                    busy = false
                }
            }
        }
        IconBtn(Icons.Rounded.Edit, "Zmień adres", onEdit)
    }
}

/** Zmiana adresu domu / pracy: wyszukiwarka, obecna lokalizacja telefonu albo usunięcie. */
@Composable
private fun PlaceEditDialog(work: Boolean, current: CarData.Addr, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<CarData.Place>>(emptyList()) }

    fun save(a: CarData.Addr) {
        val pairing = Store.pairing.value
        if (pairing == null) {
            Toast.makeText(ctx, "Najpierw połącz z CAR OS kodem QR", Toast.LENGTH_LONG).show()
            return
        }
        busy = "Zapisuję…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    Link.send(
                        pairing,
                        JSONObject().put("type", "place_set").put("slot", if (work) "work" else "home")
                            .put("a", a.address).put("lat", a.lat ?: JSONObject.NULL).put("lon", a.lon ?: JSONObject.NULL),
                    )
                    true
                } catch (_: Exception) {
                    false
                }
            }
            busy = null
            if (ok) {
                CarData.setPlaceLocal(work, a)
                Toast.makeText(
                    ctx,
                    if (a.isSet) "Zapisano – CAR OS w aucie zmieni adres, gdy będzie online" else "Usunięto – CAR OS zaktualizuje się, gdy będzie online",
                    Toast.LENGTH_LONG,
                ).show()
                onDismiss()
            } else {
                Toast.makeText(ctx, "Nie udało się wysłać – sprawdź internet", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun search() {
        if (q.isBlank()) return
        focus.clearFocus()
        busy = "Szukam…"
        scope.launch {
            results = withContext(Dispatchers.IO) { try { Places.search(q, Locator.lastKnown(ctx)) } catch (_: Exception) { emptyList() } }
            busy = null
            if (results.isEmpty()) Toast.makeText(ctx, "Nic nie znaleziono", Toast.LENGTH_SHORT).show()
        }
    }

    fun pick(r: CarData.Place) {
        busy = "Zapisuję…"
        scope.launch {
            // nazwa miejsca (miejscowość, ulica, firma) albo – dla samego numeru domu – czytelny adres
            val text = if (r.name.any { it.isLetter() }) {
                listOf(r.name, r.detail.split(", ").firstOrNull { it.isNotBlank() && it != r.name }.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(", ")
            } else {
                withContext(Dispatchers.IO) { try { Places.reverse(r.lat, r.lon) } catch (_: Exception) { null } }
                    ?: listOf(r.name, r.detail).filter { it.isNotBlank() }.joinToString(", ")
            }
            save(CarData.Addr(text.take(100), r.lat, r.lon))
        }
    }

    fun here() {
        busy = "Ustalam lokalizację…"
        scope.launch {
            val loc = withContext(Dispatchers.IO) { try { Locator.freshFix(ctx, 20) } catch (_: Exception) { null } }
            if (loc == null) {
                busy = null
                Toast.makeText(ctx, "Brak lokalizacji – włącz GPS i zezwól aplikacji na lokalizację", Toast.LENGTH_LONG).show()
                return@launch
            }
            val text = withContext(Dispatchers.IO) { try { Places.reverse(loc.latitude, loc.longitude) } catch (_: Exception) { null } }
                ?: String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
            save(CarData.Addr(text.take(100), loc.latitude, loc.longitude))
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        containerColor = C.Card,
        title = { Text(if (work) "Adres pracy" else "Adres domu", color = C.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current.isSet) Text("Teraz: ${current.address}", fontSize = 14.sp, color = C.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it },
                        placeholder = { Text("Adres lub miejscowość") },
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
                        Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(C.Accent).clickable(enabled = busy == null) { search() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Search, "Szukaj", tint = C.Bg, modifier = Modifier.size(26.dp)) }
                }
                results.take(6).forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = busy == null) { pick(r) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(if (work) Icons.Rounded.Work else Icons.Rounded.Home, null, tint = C.Accent, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontSize = 15.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (r.detail.isNotBlank()) Text(r.detail, fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                SecondaryButton("Moja obecna lokalizacja", Icons.Rounded.MyLocation, Modifier.fillMaxWidth()) {
                    if (busy == null) here()
                }
                busy?.let { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text(b, fontSize = 14.sp, color = C.Muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = busy == null) { Text("Anuluj") } },
        dismissButton = {
            if (current.isSet) TextButton(onClick = { if (busy == null) save(CarData.Addr("", null, null)) }) { Text("Usuń adres", color = Color(0xFFFF5A5F)) }
        },
    )
}

@Composable
private fun FavRow(f: CarData.Place) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
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
            Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).clickable { onSelect(t) }
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

fun decodePolyline(s: String): List<LatLng> {
    val out = ArrayList<LatLng>()
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
            out += LatLng(lat / 1e5, lon / 1e5)
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
            val ending = left < Meter.WARN_MIN * 60
            val pulse = rememberPulse(ending, 0.45f, 600)
            Text(
                txt, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (ending) C.Orange else C.Text,
                modifier = Modifier.graphicsLayer { alpha = pulse },
            )
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
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.pressScale(src, 0.92f).height(42.dp).clip(RoundedCornerShape(12.dp)).background(C.Raised)
            .clickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
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
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp))
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
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).clickable { onChange(!on) }
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


// ======================= SERWIS: edycja z telefonu =======================

private val DATE = SimpleDateFormat("dd.MM.yyyy", PL)

/** Wysyła zmianę serwisu do radia, potem po chwili pobiera potwierdzony stan. */
private suspend fun sendSvc(ctx: android.content.Context, o: JSONObject) {
    val p = Store.pairing.value ?: return
    val ok = withContext(Dispatchers.IO) { try { Link.send(p, o); true } catch (_: Exception) { false } }
    Toast.makeText(
        ctx,
        if (ok) "Zapisano – zmiana trafi do radia" else "Zapisano w telefonie – wyślę, gdy będzie internet",
        Toast.LENGTH_SHORT,
    ).show()
    if (ok) Sync.later(ctx, 8000)
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter(Char::isDigit).take(7)) },
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
            focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceEditDialog(item: CarData.SvcItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lastKm by remember { mutableStateOf(if (item.lastKm >= 0) item.lastKm.toString() else "") }
    var lastDate by remember { mutableLongStateOf(item.lastDate) }
    var iKm by remember { mutableStateOf(if (item.intervalKm > 0) item.intervalKm.toString() else "") }
    var iMo by remember { mutableStateOf(if (item.intervalMonths > 0) item.intervalMonths.toString() else "") }
    var pickDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text(item.name, color = C.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("Ostatnio (km)", lastKm, { lastKm = it }, "np. 230000", Modifier.weight(1f))
                    Column(
                        Modifier.weight(1f).height(64.dp).padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, C.Border, RoundedCornerShape(12.dp)).clickable { pickDate = true }
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Ostatnio dnia", fontSize = 12.sp, color = C.Muted)
                        Text(if (lastDate > 0) DATE.format(Date(lastDate)) else "wybierz", fontSize = 16.sp, color = C.Text)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("Co ile km", iKm, { iKm = it.take(6) }, "0 = brak", Modifier.weight(1f))
                    NumField("Co ile mies.", iMo, { iMo = it.take(3) }, "0 = brak", Modifier.weight(1f))
                }
                SecondaryButton("Wykonano dziś", Icons.Rounded.Build, Modifier.fillMaxWidth()) {
                    CarData.markDoneLocal(item.id)
                    scope.launch { sendSvc(ctx, JSONObject().put("type", "svc_done").put("id", item.id)) }
                    onDismiss()
                }
                Text("„Wykonano dziś” zapisze dzisiejszą datę i aktualny przebieg z radia.", fontSize = 12.sp, color = C.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = item.copy(
                    lastKm = lastKm.toIntOrNull() ?: -1,
                    lastDate = lastDate,
                    intervalKm = iKm.toIntOrNull() ?: 0,
                    intervalMonths = iMo.toIntOrNull() ?: 0,
                )
                CarData.editItemLocal(n)
                scope.launch { sendSvc(ctx, JSONObject().put("type", "svc_item").put("item", n.toJson())) }
                onDismiss()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )

    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = if (lastDate > 0) lastDate else System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { lastDate = it + 12 * 3_600_000L } // południe – bez przesunięć strefy czasowej
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { lastDate = 0L; pickDate = false }) { Text("Wyczyść") }
            },
        ) { DatePicker(state = st) }
    }
}

@Composable
private fun OdoDialog(current: Int?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf(current?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("Przebieg auta", color = C.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Aktualny przebieg (km)", v, { v = it }, "np. 231500", Modifier.fillMaxWidth())
                Text("Radio dalej liczy kilometry samo z GPS.", fontSize = 12.sp, color = C.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val km = v.toIntOrNull()
                if (km != null && km > 0) {
                    CarData.setOdoLocal(km)
                    scope.launch { sendSvc(ctx, JSONObject().put("type", "svc_odo").put("km", km)) }
                }
                onDismiss()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
