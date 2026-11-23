package pl.mati.carosconnect

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/** Zmiana adresu domu / pracy – mapa na cały ekran z pinezką (jak „Wyślij do auta”). */
@Composable
internal fun PlaceEditDialog(work: Boolean, current: CarData.Addr, onDismiss: () -> Unit) {
    // wejście i wyjście z animacją: okno zamyka się dopiero, gdy elementy „odlecą”
    val vis = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    if (vis.isIdle && !vis.currentState && !vis.targetState) LaunchedEffect(Unit) { onDismiss() }
    Dialog(
        onDismissRequest = { vis.targetState = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            vis,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.ExitTransition.None,
        ) { AddressPickerScreen(work, current, { vis.targetState = false }, p = screenProgress()) }
    }
}

/**
 * Na górze wyszukiwarka, na środku mapy pinezka (przesuwasz mapę pod nią – adres czyta się sam),
 * w panelu: adres pinezki, wyniki wyszukiwania, Twoja lokalizacja / auto / usunięcie,
 * na dole duży przycisk „Zapisz”.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddressPickerScreen(
    work: Boolean, current: CarData.Addr, onDismiss: () -> Unit,
    /** Zrzuty ekranu: wyniki wyszukiwania i adres pinezki bez sieci. */
    previewResults: List<CarData.Place> = emptyList(), previewAddr: String? = null,
    /** Postęp wejścia / wyjścia ekranu (0..1). */
    p: androidx.compose.runtime.State<Float> = SHOWN,
) {
    val ctx = LocalContext.current
    val still = LocalInspectionMode.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val spot by Store.spot.collectAsState()
    val label = if (work) "pracy" else "domu"

    val me = remember { if (still) null else try { Locator.lastKnown(ctx)?.let { LatLng(it.latitude, it.longitude) } } catch (_: Exception) { null } }
    val saved = if (current.lat != null && current.lon != null) LatLng(current.lat, current.lon) else null
    val start = remember { saved ?: me ?: spot?.let { LatLng(it.lat, it.lon) } ?: LatLng(52.2297, 21.0122) }

    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(previewResults) }
    var sel by remember { mutableStateOf<CarData.Place?>(null) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var centerText by remember { mutableStateOf(previewAddr) }
    var reading by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var moveTo by remember { mutableStateOf<Pair<LatLng, Int>?>(null) }
    var headerH by remember { mutableStateOf(90.dp) }
    fun go(ll: LatLng) { moveTo = ll to ((moveTo?.second ?: 0) + 1) }

    // adres pod pinezką – czytany, gdy mapa stanie
    LaunchedEffect(center) {
        val c = center ?: return@LaunchedEffect
        if (still) return@LaunchedEffect
        // wybrany wynik wyszukiwania pod pinezką – jego nazwa zamiast odczytu
        val s = sel
        if (s != null && WalkNav.dist(s.lat, s.lon, c.latitude, c.longitude) < 25) {
            centerText = addrText(s)
            return@LaunchedEffect
        }
        sel = null
        reading = true
        delay(450)
        centerText = withContext(Dispatchers.IO) { try { Places.reverse(c.latitude, c.longitude) } catch (_: Exception) { null } }
        reading = false
    }

    fun save(a: CarData.Addr) {
        if (Store.pairing.value == null) {
            Toast.makeText(ctx, "Najpierw połącz z CAR OS kodem QR", Toast.LENGTH_LONG).show()
            return
        }
        busy = "Zapisuję…"
        scope.launch {
            val item = try {
                Outbox.sendAsync(
                    ctx,
                    JSONObject().put("type", "place_set").put("slot", if (work) "work" else "home")
                        .put("a", a.address).put("lat", a.lat ?: JSONObject.NULL).put("lon", a.lon ?: JSONObject.NULL),
                    (if (work) "Adres pracy" else "Adres domu") + if (a.isSet) ": ${a.address}" else " – usunięcie",
                )
            } catch (_: Exception) {
                null
            }
            busy = null
            if (item != null) {
                CarData.setPlaceLocal(work, a)
                Toast.makeText(
                    ctx,
                    Outbox.sentText(item, if (a.isSet) "Zapisano – CAR OS w aucie zmieni adres, gdy będzie online" else "Usunięto – CAR OS zaktualizuje się, gdy będzie online"),
                    Toast.LENGTH_LONG,
                ).show()
                onDismiss()
            } else {
                Toast.makeText(ctx, "Nie udało się wysłać – sprawdź internet", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun saveHere() {
        val c = center ?: return
        if (busy != null) return
        busy = "Zapisuję…"
        scope.launch {
            val text = centerText ?: withContext(Dispatchers.IO) { try { Places.reverse(c.latitude, c.longitude) } catch (_: Exception) { null } }
                ?: String.format(java.util.Locale.US, "%.5f, %.5f", c.latitude, c.longitude)
            busy = null
            save(CarData.Addr(text.take(100), c.latitude, c.longitude))
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

    fun here() {
        busy = "Ustalam lokalizację…"
        scope.launch {
            val loc = withContext(Dispatchers.IO) { try { Locator.freshFix(ctx, 20) } catch (_: Exception) { null } }
            busy = null
            if (loc == null) Toast.makeText(ctx, "Brak lokalizacji – włącz GPS i zezwól aplikacji na lokalizację", Toast.LENGTH_LONG).show()
            else { sel = null; go(LatLng(loc.latitude, loc.longitude)) }
        }
    }

    val scaffold = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = true),
    )
    // wyniki wyszukiwania → panel wyżej
    LaunchedEffect(results) { if (results.isNotEmpty() && !still) try { scaffold.bottomSheetState.partialExpand() } catch (_: Exception) {} }

    BoxWithConstraints(Modifier.fillMaxSize().reveal(p, 0f, 0.3f).background(C.Bg)) {
        val peek = minOf(330.dp, maxHeight * 0.45f)
        val sheetH = maxHeight * 0.85f
        BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = peek,
            sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            sheetContainerColor = if (C.isGlass) C.Dialog else C.Bg,
            sheetContentColor = C.Text,
            sheetShadowElevation = 18.dp,
            sheetTonalElevation = 0.dp,
            sheetDragHandle = {
                Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(42.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(C.Muted.copy(alpha = 0.45f)))
            },
            containerColor = C.Bg,
            sheetContent = {
                Column(
                    Modifier.fillMaxWidth().height(sheetH).reveal(p, 0.2f, 0.85f, dy = 70.dp).verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp).padding(bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column {
                        Text("Adres $label", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Text)
                        Text(
                            if (current.isSet) "Teraz: ${current.address}" else "Jeszcze nie ustawiony – przesuń mapę albo wyszukaj",
                            fontSize = 14.sp, color = C.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // adres pod pinezką
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).surface(RoundedCornerShape(20.dp), C.Card, border = C.Border).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(C.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Place, null, tint = C.Accent, modifier = Modifier.size(24.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Pinezka na mapie", fontSize = 12.sp, color = C.Muted)
                            Text(
                                when {
                                    moving -> "Przesuwasz mapę…"
                                    reading -> "Czytam adres…"
                                    else -> centerText ?: center?.let { String.format(java.util.Locale.US, "%.5f, %.5f", it.latitude, it.longitude) } ?: "—"
                                },
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (reading) CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                    // szybkie akcje jak na Start
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction(Icons.Rounded.MyLocation, "Tu jestem") { if (busy == null) here() }
                        val sp = spot
                        if (sp != null) RoundAction(Icons.Rounded.DirectionsCar, "Przy aucie") { sel = null; go(LatLng(sp.lat, sp.lon)) }
                        if (current.isSet) RoundAction(Icons.Rounded.Delete, "Usuń adres") { if (busy == null) save(CarData.Addr("", null, null)) }
                    }
                    if (results.isNotEmpty()) {
                        Text("Wyniki", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.padding(top = 4.dp))
                        results.take(8).forEach { r ->
                            val on = sel == r
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                    .then(if (on) Modifier.background(C.Accent.copy(alpha = 0.14f)) else Modifier.surface(RoundedCornerShape(18.dp), C.Raised))
                                    .clickable { sel = r; go(LatLng(r.lat, r.lon)) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(if (work) Icons.Rounded.Work else Icons.Rounded.Home, null, tint = C.Accent, modifier = Modifier.size(22.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (r.detail.isNotBlank()) Text(r.detail, fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    } else {
                        Text("Przesuń mapę palcem tak, żeby pinezka stała na budynku – numery domów widać na mapie.", fontSize = 13.sp, color = C.Muted)
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                PickPointMap(
                    start, Modifier.fillMaxSize().reveal(p, 0f, 0.8f, scale = 1.1f, fade = false), moveTo, padTop = headerH, padBottom = peek,
                    saved = saved, me = me,
                    onMoving = { moving = it },
                    onIdle = { center = it },
                    pinIn = p,
                )
                // góra: wyszukiwarka (jak „Dokąd jedziemy?”)
                Row(
                    Modifier.align(Alignment.TopCenter).reveal(p, 0.1f, 0.7f, dy = (-90).dp).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
                        .onSizeChanged { with(density) { headerH = it.height.toDp() + 36.dp } }
                        .height(56.dp).clip(RoundedCornerShape(28.dp)).surface(RoundedCornerShape(28.dp), C.Card, border = C.Border)
                        .padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Wróć", tint = C.Text, modifier = Modifier.size(24.dp))
                    }
                    BasicTextField(
                        q, { q = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = C.Text),
                        cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (q.isEmpty()) Text("Szukaj adresu $label", fontSize = 16.sp, color = C.Muted, maxLines = 1)
                                inner()
                            }
                        },
                    )
                    if (q.isNotEmpty() && busy != "Szukam…") Box(Modifier.size(40.dp).clip(CircleShape).clickable { q = ""; results = emptyList(); sel = null }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Wyczyść", tint = C.Muted, modifier = Modifier.size(20.dp))
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(C.Accent).clickable { search() }, contentAlignment = Alignment.Center) {
                        if (busy == "Szukam…") CircularProgressIndicator(color = C.Bg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        else Icon(Icons.Rounded.Search, "Szukaj", tint = C.Bg, modifier = Modifier.size(22.dp))
                    }
                }
                // prawa strona nad panelem: Twoja pozycja, obecny adres
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = peek + 14.dp).reveal(p, 0.5f, 1f, scale = 0.5f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (me != null) PickFloatBtn(Icons.Rounded.MyLocation, "Moja pozycja") { sel = null; go(me) }
                    if (saved != null) PickFloatBtn(if (work) Icons.Rounded.Work else Icons.Rounded.Home, "Obecny adres") { sel = null; go(saved) }
                }
            }
        }
        // dół: zapisz (w miejscu paska zakładek)
        Box(Modifier.align(Alignment.BottomCenter).reveal(p, 0.3f, 0.95f, dy = 120.dp).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
            SheetButton(
                "Zapisz jako adres $label", Icons.Rounded.Check,
                enabled = center != null && !moving, busy = busy == "Zapisuję…" || busy == "Ustalam lokalizację…",
                sub = if (moving || reading) null else centerText,
            ) { saveHere() }
        }
    }
}

/** Nazwa wyniku do zapisania: nazwa + pierwsza część adresu (dla samego numeru – adres z mapy). */
private suspend fun addrText(r: CarData.Place): String =
    if (r.name.any { it.isLetter() }) {
        listOf(r.name, r.detail.split(", ").firstOrNull { it.isNotBlank() && it != r.name }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(", ")
    } else {
        withContext(Dispatchers.IO) { try { Places.reverse(r.lat, r.lon) } catch (_: Exception) { null } }
            ?: listOf(r.name, r.detail).filter { it.isNotBlank() }.joinToString(", ")
    }

@Composable
private fun PickFloatBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xE60A0C0F)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp)) }
}
