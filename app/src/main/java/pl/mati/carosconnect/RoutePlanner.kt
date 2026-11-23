package pl.mati.carosconnect

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Traffic
import androidx.compose.material.icons.rounded.ZoomOutMap
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * „Wyślij do auta” – wygląd jak ekran Start z mapą: trasa na mapie na pełnym ekranie, na górze punkty
 * trasy (start, po drodze, cel), na dole panel do przeciągania (rozwinięty / połowa / schowany do paska)
 * i zamiast zakładek duży przycisk „Nawiguj w aucie”.
 */
@Composable
fun PlaceSheet(
    place0: CarData.Place?, resolving: Boolean, error: String?,
    others: List<CarData.Place> = emptyList(), shared: String? = null, onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        RoutePlannerScreen(place0, resolving, error, others, shared, onDismiss)
    }
}

/** Zawartość ekranu planowania (osobno od okna – do zrzutów ekranu). */
@Composable
internal fun RoutePlannerScreen(
    place0: CarData.Place?, resolving: Boolean, error: String?,
    others: List<CarData.Place>, shared: String?, onDismiss: () -> Unit,
    /** Zrzuty ekranu: gotowa trasa zamiast liczenia (bez sieci). */
    preview: RoutePreview.Result? = null,
) {
    val still = LocalInspectionMode.current
    var chosen by remember(place0) { mutableStateOf(place0) }
    val place = chosen
    // wejście: lekki wjazd od dołu
    var shown by remember { mutableStateOf(still) }
    LaunchedEffect(Unit) { shown = true }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        AnimatedVisibility(
            shown,
            enter = fadeIn(tween(220)) + slideInVertically(tween(340)) { it / 10 },
            exit = fadeOut(tween(150)),
        ) {
            if (place == null) PlannerMessage(resolving, error, shared, onDismiss)
            else key(place) {
                val alts = (listOfNotNull(place0) + others).distinctBy { "%.4f,%.4f".format(it.lat, it.lon) }.filter { it != place }
                Planner(place, alts, { chosen = it }, onDismiss, preview)
            }
        }
    }
}

/** Zanim jest cel: odczytywanie udostępnionego miejsca albo błąd. */
@Composable
private fun PlannerMessage(resolving: Boolean, error: String?, shared: String?, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Accent.copy(alpha = 0.16f), C.Bg)))) {
        Box(Modifier.statusBarsPadding().padding(12.dp)) { CircleBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Wróć", onDismiss) }
        Column(
            Modifier.align(Alignment.Center).padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .surface(RoundedCornerShape(26.dp), C.Card, border = C.Border).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (resolving) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    Text("Odczytuję miejsce…", fontSize = 17.sp, color = C.Text)
                }
            } else {
                Text("Nie znalazłem miejsca", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Text)
                Text(error ?: "Nie udało się odczytać miejsca", fontSize = 15.sp, color = C.Orange)
                if (!shared.isNullOrBlank()) Text("Udostępniono: " + shared.take(220), color = C.Muted, fontSize = 12.sp)
                SecondaryButton("Zamknij", Icons.Rounded.Close, Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Planner(
    place: CarData.Place, alts: List<CarData.Place>, onPickOther: (CarData.Place) -> Unit,
    onDismiss: () -> Unit, preview: RoutePreview.Result?,
) {
    val ctx = LocalContext.current
    val still = LocalInspectionMode.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val spot by Store.spot.collectAsState()
    val linked by Store.linked.collectAsState()
    val inService by Store.driving.collectAsState()
    val plan by TripPlan.plan.collectAsState()

    // trasa: punkty po drodze + cel (ostatni)
    var points by remember { mutableStateOf(listOf(place)) }
    // 0 – teraz / za chwilę, 1 – wyjazd o…, 2 – być na miejscu o…
    var mode by remember { mutableStateOf(0) }
    var offsetMin by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<Long?>(null) }
    var res by remember { mutableStateOf(preview) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(preview == null) }
    var sending by remember { mutableStateOf(false) }
    var faved by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    var is3d by remember { mutableStateOf(true) }
    val ttKey = remember { if (still) "" else RoutePreview.tomtomKey(ctx) }
    var flow by remember { mutableStateOf(false) }
    var jamSel by remember { mutableStateOf(-1) }
    var fitKey by remember { mutableStateOf(0) }
    var headerH by remember { mutableStateOf(150.dp) }

    // start: telefon w aucie → jego pozycja (auto), inaczej miejsce zaparkowanego auta
    val start = remember(spot, linked, inService) {
        val me = if (still) null else try { Locator.lastKnown(ctx) } catch (_: Exception) { null }
        val sp = spot
        when {
            (linked || inService) && me != null -> LatLng(me.latitude, me.longitude) to "z aktualnej pozycji auta"
            sp != null -> LatLng(sp.lat, sp.lon) to ("z miejsca parkowania" + (sp.address?.let { " · $it" } ?: ""))
            me != null -> LatLng(me.latitude, me.longitude) to "z Twojej pozycji"
            else -> null
        }
    }
    val dest = points.lastOrNull() ?: place
    val via = points.dropLast(1).map { LatLng(it.lat, it.lon) }

    LaunchedEffect(points, offsetMin, mode, picked, start?.first) {
        if (preview != null) return@LaunchedEffect
        val st = start
        if (st == null) {
            loading = false
            err = "Brak miejsca startu – zapisz parkowanie albo włącz lokalizację"
            return@LaunchedEffect
        }
        loading = true
        err = null
        jamSel = -1
        val to = LatLng(dest.lat, dest.lon)
        val now = System.currentTimeMillis()
        res = try {
            val t = picked
            when {
                mode == 1 && t != null -> RoutePreview.compute(ctx, st.first, to, t, via = via)
                mode == 2 && t != null -> {
                    // być o [t]: najpierw czas przejazdu teraz, potem z prognozą ruchu na wyliczoną godzinę wyjazdu
                    val r1 = RoutePreview.compute(ctx, st.first, to, now, withWeather = false, via = via)
                    val dep1 = t - (r1.travelS * 1000).toLong()
                    if (dep1 <= now + 2 * 60_000) RoutePreview.compute(ctx, st.first, to, now, via = via)
                    else {
                        val r2 = RoutePreview.compute(ctx, st.first, to, dep1, via = via)
                        r2.copy(depart = t - (r2.travelS * 1000).toLong())
                    }
                }
                else -> RoutePreview.compute(ctx, st.first, to, now + offsetMin * 60_000L, via = via)
            }
        } catch (e: Exception) {
            err = "Nie udało się wyznaczyć trasy" + (e.message?.let { ": $it" } ?: "")
            null
        }
        loading = false
    }

    fun pickTime(kind: Int) {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = picked ?: (System.currentTimeMillis() + 3600_000) }
        val act = generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } ?: ctx
        android.app.TimePickerDialog(act, { _, h, m ->
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            // godzina, która już minęła → jutro
            if (cal.timeInMillis < System.currentTimeMillis() - 60_000) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            picked = cal.timeInMillis
            mode = kind
        }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show()
    }

    fun send() {
        if (sending) return
        sending = true
        val shape = res?.let { RoutePreview.shape(it) }.orEmpty()
        scope.launch { if (sendToCar(ctx, "route", dest, points.dropLast(1), shape)) onDismiss(); sending = false }
    }

    fun fav() {
        if (sending || faved) return
        sending = true
        scope.launch {
            if (sendToCar(ctx, "fav_add", place)) { CarData.addFavLocal(place); faved = true }
            sending = false
        }
    }

    // panel: połowa na start, w dół – chowa się do paska z celem
    val scaffold = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false),
    )
    val sheet = scaffold.bottomSheetState
    val hidden = sheet.targetValue == SheetValue.Hidden
    // „Dodaj punkt”: panel w dół, mapa cała do wskazania miejsca
    LaunchedEffect(pickMode) {
        try {
            if (pickMode) sheet.hide()
            else if (sheet.currentValue == SheetValue.Hidden) sheet.partialExpand()
        } catch (_: Exception) {}
    }
    BackHandler(enabled = pickMode) { pickMode = false }

    // korki w kolejności na trasie, z kilometrem i długością
    val r = res
    val jams = remember(r) {
        if (r == null) emptyList()
        else {
            val cum = DoubleArray(r.pts.size)
            for (i in 1 until r.pts.size) cum[i] = cum[i - 1] + distM(r.pts[i - 1], r.pts[i])
            r.jams.filter { it.pts.size >= 2 }.map { j ->
                val near = r.pts.indices.minByOrNull { distM(r.pts[it], j.pts.first()) } ?: 0
                val len = (1 until j.pts.size).sumOf { distM(j.pts[it - 1], j.pts[it]) }
                Triple(j, cum[near] / 1000.0, len / 1000.0)
            }.sortedBy { it.second }
        }
    }
    val flowUrl = remember(flow, ttKey) {
        if (flow && ttKey.isNotBlank()) "https://api.tomtom.com/traffic/map/4/tile/flow/relative-delay/{z}/{x}/{y}.png?tileSize=512&thickness=8&key=$ttKey&t=" + (System.currentTimeMillis() / 300_000)
        else null
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val sheetH = maxHeight * 0.92f
        val peek = minOf(410.dp, maxHeight * 0.55f)
        // ile mapy zasłania dół (panel albo pasek + przycisk)
        val mapBottom = when {
            pickMode -> 120.dp
            hidden -> 150.dp
            else -> peek
        }
        BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = peek,
            sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            sheetContainerColor = if (C.isGlass) C.Dialog else C.Bg,
            sheetContentColor = C.Text,
            sheetShadowElevation = 18.dp,
            sheetTonalElevation = 0.dp,
            sheetSwipeEnabled = !pickMode,
            sheetDragHandle = {
                Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(42.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(C.Muted.copy(alpha = 0.45f)))
            },
            containerColor = C.Bg,
            sheetContent = {
                Column(
                    Modifier.fillMaxWidth().height(sheetH).verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp).padding(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // cel
                    Column {
                        Text(dest.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = dest.detail.ifBlank { if (points.size > 1) "przez ${points.size - 1} ${if (points.size == 2) "punkt" else "punkty"}" else "" }
                        if (sub.isNotBlank()) Text(sub, fontSize = 14.sp, color = C.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    when {
                        loading -> Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = C.Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                            Text("Wyznaczam trasę, sprawdzam korki i pogodę…", fontSize = 15.sp, color = C.Muted)
                        }
                        r == null -> Text(err ?: "Nie udało się wyznaczyć trasy", fontSize = 15.sp, color = C.Orange)
                        else -> RouteSummary(r, mode, picked)
                    }
                    // szybkie akcje jak na Start
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundAction(Icons.Rounded.Add, "Dodaj punkt") {
                            if (r != null && points.size < 4) pickMode = true
                            else Toast.makeText(ctx, if (points.size >= 4) "Najwięcej 3 punkty po drodze" else "Poczekaj na wyznaczenie trasy", Toast.LENGTH_SHORT).show()
                        }
                        RoundAction(if (faved) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (faved) "W ulubionych" else "Ulubione") { fav() }
                        RoundAction(Icons.Rounded.Schedule, "Wyjazd o…") { pickTime(1) }
                    }
                    if (r != null && !loading) {
                        // warianty trasy: najszybszy wybrany sam, dotknij inny, żeby go pokazać
                        if (r.alts.size > 1) {
                            SheetTitle("Warianty trasy")
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val fastest = r.alts.indices.minByOrNull { r.alts[it].travelS }
                                val shortest = r.alts.indices.minByOrNull { r.alts[it].distanceM }
                                r.alts.forEachIndexed { i, a ->
                                    val on = i == r.altIndex
                                    Column(
                                        Modifier.clip(RoundedCornerShape(16.dp))
                                            .then(if (on) Modifier.background(C.Accent.copy(alpha = 0.18f)).border(1.5.dp, C.Accent, RoundedCornerShape(16.dp)) else Modifier.surface(RoundedCornerShape(16.dp), C.Raised))
                                            .clickable(enabled = !on) {
                                                scope.launch {
                                                    loading = true
                                                    res = try { RoutePreview.choose(r, i) } catch (_: Exception) { r }
                                                    loading = false
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 9.dp),
                                    ) {
                                        Text(durText(a.travelS), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) C.Text else C.Text2)
                                        Text(
                                            "${(a.distanceM / 1000).roundToInt()} km" + when (i) { fastest -> " · najszybsza"; shortest -> " · najkrótsza"; else -> "" },
                                            fontSize = 12.sp, color = C.Muted, maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                        // godzina wyjazdu: szybkie wybory albo konkretna godzina wyjazdu / przyjazdu
                        SheetTitle("Kiedy jedziesz")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((m, l) in listOf(0 to "Teraz", 15 to "+15 min", 30 to "+30 min", 60 to "+1 h")) {
                                Chip(l, mode == 0 && m == offsetMin, Modifier.weight(1f)) { mode = 0; offsetMin = m }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val t = picked
                            Chip(if (mode == 1 && t != null) "Wyjazd o ${RoutePreview.hhmm(t)}" else "Wyjadę o…", mode == 1, Modifier.weight(1f)) { pickTime(1) }
                            Chip(if (mode == 2 && t != null) "Być o ${RoutePreview.hhmm(t)}" else "Chcę być o…", mode == 2, Modifier.weight(1f)) { pickTime(2) }
                        }
                        // korki: dotknij, żeby pokazać na mapie
                        if (jams.isNotEmpty()) {
                            SheetTitle("Korki na trasie")
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PillChip("Cała trasa", jamSel == -1, Icons.Rounded.Route) { jamSel = -1 }
                                jams.forEachIndexed { i, (j, atKm, lenKm) ->
                                    val col = jamColor(j)
                                    Row(
                                        Modifier.clip(RoundedCornerShape(14.dp))
                                            .background(if (jamSel == i) col.copy(alpha = 0.25f) else C.Raised)
                                            .border(if (jamSel == i) 1.5.dp else 0.dp, if (jamSel == i) col else Color.Transparent, RoundedCornerShape(14.dp))
                                            .clickable { jamSel = i }
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
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Legend(Color(0xFFF5A623), "wolniej")
                                Legend(Color(0xFFF26B1D), "korek")
                                Legend(Color(0xFFD9302B), "duży korek")
                                Legend(Color(0xFF7A0E0E), "stoi / zamknięte")
                            }
                        } else if (r.trafficNote != null && r.delayS == null) {
                            Text(r.trafficNote, fontSize = 12.sp, color = C.Muted)
                        }
                        // pogoda na trasie
                        val head = RoutePreview.headline(r)
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).surface(RoundedCornerShape(20.dp), C.Card, border = C.Border).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Pogoda na trasie", fontSize = 13.sp, color = C.Muted)
                            if (head != null) {
                                val bad = r.weather?.any { it.kind != "frost" } == true
                                Text(head, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (bad) Color(0xFF5AB4FF) else C.Text)
                                r.weather?.filter { it.kind != "frost" }?.drop(1)?.take(3)?.forEach { e ->
                                    Text("${e.name}: ${e.fromKm.roundToInt()}–${e.toKm.roundToInt().coerceAtLeast(e.fromKm.roundToInt() + 1)} km, ok. ${RoutePreview.hhmm(e.time)}", fontSize = 12.sp, color = C.Muted)
                                }
                            } else Text("brak danych", fontSize = 15.sp, color = C.Text2)
                            r.destTemp?.let { Text("Na miejscu ok. ${it.roundToInt()}°", fontSize = 13.sp, color = C.Muted) }
                        }
                        // inteligentny plan: omijanie korków, kolejność punktów, godzina wyjazdu, tankowanie i przerwy
                        SmartPlanCard(r, start?.first, points, { points = it }) { t -> picked = t; mode = 1 }
                        // przypomnienie 10 min przed wyjazdem
                        val now = System.currentTimeMillis()
                        val p = plan
                        val samePlace = p != null && abs(p.lat - dest.lat) < 1e-4 && abs(p.lon - dest.lon) < 1e-4
                        if (samePlace && p != null && p.depart > now - 60_000) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Przypomnienie o ${RoutePreview.hhmm(p.remindAt)} (wyjazd ${RoutePreview.hhmm(p.depart)})", fontSize = 13.sp, color = C.Green, modifier = Modifier.weight(1f))
                                Text("Odwołaj", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Accent,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { TripPlan.cancel(ctx) }.padding(6.dp))
                            }
                        }
                        if (r.depart - TripPlan.REMIND_MS > now + 60_000 && !(samePlace && p?.depart == r.depart)) {
                            val st = start
                            SecondaryButton("Przypomnij o ${RoutePreview.hhmm(r.depart - TripPlan.REMIND_MS)} (10 min przed wyjazdem)", Icons.Rounded.Alarm, Modifier.fillMaxWidth()) {
                                if (st == null) return@SecondaryButton
                                TripPlan.set(
                                    ctx,
                                    TripPlan.Plan(
                                        dest.name, dest.detail, dest.lat, dest.lon, st.first.latitude, st.first.longitude,
                                        r.depart, r.arrive, mode == 2, r.delayS,
                                    ),
                                )
                                Toast.makeText(ctx, "Przypomnę o ${RoutePreview.hhmm(r.depart - TripPlan.REMIND_MS)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    // inne pasujące miejsca (gdy wyszukiwanie po nazwie trafiło kilka)
                    if (alts.isNotEmpty()) {
                        SheetTitle("To nie to? Inne wyniki")
                        alts.take(4).forEach { o ->
                            Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).surface(RoundedCornerShape(14.dp), C.Raised)
                                    .clickable { onPickOther(o) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(o.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (o.detail.isNotBlank()) Text(o.detail, fontSize = 12.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                RoutePlanMap(
                    start?.first, LatLng(dest.lat, dest.lon), via, r, Modifier.fillMaxSize(),
                    flowUrl = flowUrl,
                    focus = jams.getOrNull(jamSel)?.first?.pts,
                    padTop = headerH, padBottom = mapBottom, is3d = is3d, fitKey = fitKey,
                    onLongPress = if (!pickMode) null else { ll: LatLng ->
                        pickMode = false
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { Locator.address(ctx, ll.latitude, ll.longitude) }
                            val np = CarData.Place(name?.substringBefore(",")?.ifBlank { null } ?: "Punkt na mapie", name ?: "", ll.latitude, ll.longitude)
                            // nowy punkt przed celem (kolejność można potem zmienić)
                            points = points.dropLast(1) + np + points.takeLast(1)
                            Toast.makeText(ctx, "Dodano punkt: ${np.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                // góra: punkty trasy (jak pasek wyszukiwania na Start)
                Row(
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
                        .onSizeChanged { with(density) { headerH = it.height.toDp() + 40.dp } }
                        .clip(RoundedCornerShape(26.dp)).surface(RoundedCornerShape(26.dp), C.Card, border = C.Border)
                        .padding(start = 4.dp, end = 2.dp, top = 4.dp, bottom = 2.dp),
                ) {
                    Box(Modifier.padding(top = 2.dp)) {
                        CircleBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Wróć", bg = Color.Transparent) { if (pickMode) pickMode = false else onDismiss() }
                    }
                    WaypointList(
                        start?.second, points, { points = it },
                        onAdd = { pickMode = true }, canAdd = r != null && !loading && points.size < 4,
                        modifier = Modifier.weight(1f), bg = Color.Transparent, rowH = 44.dp,
                    )
                }
                // prawa strona nad panelem: 3D, korki wszędzie, cała trasa
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = mapBottom + 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FloatBtn({ is3d = !is3d }) { Text(if (is3d) "3D" else "2D", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (is3d) C.Accent else Color.White) }
                    if (ttKey.isNotBlank()) FloatBtn({ flow = !flow }) {
                        Icon(Icons.Rounded.Traffic, if (flow) "Korki tylko na trasie" else "Korki na wszystkich drogach", tint = if (flow) C.Accent else Color.White, modifier = Modifier.size(22.dp))
                    }
                    FloatBtn({ jamSel = -1; fitKey++ }) { Icon(Icons.Rounded.ZoomOutMap, "Cała trasa", tint = Color.White, modifier = Modifier.size(22.dp)) }
                }
            }
        }

        // dół: pasek celu (gdy panel schowany) i przycisk „Nawiguj w aucie” zamiast zakładek
        Column(Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                hidden && !pickMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniRouteBar(dest.name, r?.takeIf { !loading }) { scope.launch { try { sheet.partialExpand() } catch (_: Exception) {} } }
            }
            if (pickMode) PickBanner { pickMode = false }
            else SendBar(sending, r?.takeIf { !loading }, mode) { send() }
        }
    }
}

/** Czas, km, przyjazd i korki – duże jak zasięg na Start. */
@Composable
private fun RouteSummary(r: RoutePreview.Result, mode: Int, picked: Long?) {
    val now = System.currentTimeMillis()
    val km = if (r.distanceM >= 10_000) "${(r.distanceM / 1000).roundToInt()} km" else "%.1f km".format(r.distanceM / 1000).replace('.', ',')
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(durText(r.travelS), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1)
            Text(" · $km", fontSize = 17.sp, color = C.Text2, modifier = Modifier.padding(bottom = 6.dp, start = 2.dp), maxLines = 1)
        }
        when {
            mode == 2 && r.depart < now - 60_000 -> Text(
                "Nie zdążysz na ${RoutePreview.hhmm(picked ?: r.arrive)} – wyjeżdżając teraz ok. ${RoutePreview.hhmm(now + (r.travelS * 1000).toLong())}",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Orange,
            )
            mode == 2 -> Text("Wyjedź o ${RoutePreview.hhmm(r.depart)}, żeby być o ${RoutePreview.hhmm(r.arrive)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Accent)
            mode == 0 && r.depart <= now + 60_000 -> Text("Przyjazd ${RoutePreview.hhmm(r.arrive)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Accent)
            else -> Text("Wyjazd ${RoutePreview.hhmm(r.depart)} → przyjazd ${RoutePreview.hhmm(r.arrive)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Accent)
        }
        val (tTxt, tLvl) = RoutePreview.trafficSummary(r)
        if (!(tLvl < 0 && r.trafficNote != null)) Text(
            tTxt, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = when (tLvl) { 0 -> C.Green; 1 -> Color(0xFFF5A623); 2 -> C.Orange; 3 -> Color(0xFFE5483F); else -> C.Muted },
        )
    }
}

/** Duży przycisk wysyłki w kapsule (w miejscu paska zakładek). */
@Composable
private fun SendBar(sending: Boolean, r: RoutePreview.Result?, mode: Int, onSend: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(33.dp))
                .surface(RoundedCornerShape(33.dp), C.Card, border = C.Border).padding(5.dp),
        ) {
            Row(
                Modifier.fillMaxSize().pressScale(src, 0.97f).clip(RoundedCornerShape(28.dp))
                    .background(Brush.horizontalGradient(listOf(C.Accent, lerp(C.Accent, Color.White, 0.22f))))
                    .clickable(interactionSource = src, indication = null, onClick = onSend)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (sending) CircularProgressIndicator(color = C.Bg, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else Icon(Icons.Rounded.Navigation, null, tint = C.Bg, modifier = Modifier.size(24.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(if (sending) "Wysyłam…" else "Nawiguj w aucie", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Bg, maxLines = 1)
                    if (r != null && !sending) Text(
                        if (mode == 0 && r.depart <= System.currentTimeMillis() + 60_000) "${durText(r.travelS)} · przyjazd ${RoutePreview.hhmm(r.arrive)}"
                        else "wyjazd ${RoutePreview.hhmm(r.depart)} · przyjazd ${RoutePreview.hhmm(r.arrive)}",
                        fontSize = 12.sp, color = C.Bg.copy(alpha = 0.8f), maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Schowany panel: pasek z celem i czasem – dotknij albo pociągnij w górę. */
@Composable
private fun MiniRouteBar(name: String, r: RoutePreview.Result?, onOpen: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    var drag by remember { mutableStateOf(0f) }
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp).fillMaxWidth().height(52.dp).pressScale(src, 0.97f)
            .clip(RoundedCornerShape(26.dp)).surface(RoundedCornerShape(26.dp), C.Card, border = C.Border)
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (drag < -24f) onOpen() }) { change, dy ->
                    change.consume()
                    drag += dy
                }
            }
            .clickable(interactionSource = src, indication = null, onClick = onOpen).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.KeyboardArrowUp, "Wysuń", tint = C.Accent, modifier = Modifier.size(26.dp))
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (r != null) Text(durText(r.travelS), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Accent, maxLines = 1)
    }
}

/** Tryb „Dodaj punkt”: podpowiedź na dole zamiast przycisku wysyłki. */
@Composable
private fun PickBanner(onCancel: () -> Unit) {
    Row(
        Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp).fillMaxWidth()
            .clip(RoundedCornerShape(28.dp)).background(C.Accent).padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.TouchApp, null, tint = C.Bg, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text("Przytrzymaj palec na mapie", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Bg)
            Text("w miejscu, przez które chcesz jechać – punkt doda się przed celem", fontSize = 13.sp, color = C.Bg.copy(alpha = 0.85f))
        }
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Anuluj", tint = C.Bg, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Chip(label: String, sel: Boolean, mod: Modifier, onClick: () -> Unit) {
    Box(
        mod.height(40.dp).clip(RoundedCornerShape(14.dp))
            .then(if (sel) Modifier.background(C.Accent) else Modifier.surface(RoundedCornerShape(14.dp), C.Raised))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) C.Bg else C.Text, maxLines = 1) }
}

@Composable
private fun PillChip(text: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Accent.copy(alpha = 0.2f) else C.Raised).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = if (on) C.Accent else C.Text2, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Text else C.Text2, maxLines = 1)
    }
}

/** Okrągły przycisk na mapie – jak 3D / auto / pozycja na Start. */
@Composable
private fun FloatBtn(onClick: () -> Unit, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(44.dp).pressScale(src, 0.88f).clip(CircleShape).background(Color(0xE60A0C0F))
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CircleBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) =
    CircleBtn(icon, desc, C.Raised, onClick)

@Composable
private fun CircleBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = C.Text, modifier = Modifier.size(24.dp))
    }
}

private fun durText(s: Double): String {
    val m = (s / 60).roundToInt()
    return if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)} min" else "$m min"
}
