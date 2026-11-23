package pl.mati.carosconnect

import pl.mati.caros.core.Model3D
import android.location.Location
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Wygląd 2.0: dwa ekrany główne do wyboru (Ustawienia → Wygląd → Ekran główny):
 *  - „car” – auto 3D na pierwszym planie (obracane palcem), duży zasięg, okrągłe szybkie akcje,
 *  - „map” – mapa z autem w tle, cała reszta w panelu wysuwanym od dołu.
 * Wspólne elementy: pływający pasek zakładek z przesuwanym wskaźnikiem, liczby „nabijane” przy zmianie,
 * pierścień zasięgu, sprężyste wejścia kart.
 */

private val PLN = Locale("pl")

/** Ujęcie auta na ekranie głównym: ¾ z przodu. */
private const val HERO_YAW = 212f

private fun thousandsSep(v: Int): String = "%,d".format(v).replace(',', ' ')

// ---------- auto 3D ----------

/**
 * Model auta z CAR OS (typ nadwozia i lakier z radia) – przesuń palcem w bok, żeby obrócić;
 * po puszczeniu sprężyście wraca do ujęcia ¾. Przy wejściu podjeżdża bokiem.
 */
@Composable
fun CarHero3D(modifier: Modifier = Modifier, height: Dp = 200.dp) {
    val body by CarData.carBody.collectAsState()
    val paintOpt by CarData.carPaint.collectAsState()
    val paint = paintOpt ?: CarIcon.DEFAULT_COLOR
    val density = LocalDensity.current
    val sizePx = with(density) { (height * 1.35f).toPx() }.roundToInt().coerceIn(240, 1000)
    val faces = remember(body, paint) { try { Model3D.car(body, paint) } catch (_: Exception) { null } }
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    val yaw = remember { Animatable(if (still) HERO_YAW else HERO_YAW + 70f) }
    val scope = rememberCoroutineScope()
    var bmp by remember {
        mutableStateOf(
            if (still && faces != null) try { Model3D.carIcon(body, paint, HERO_YAW.toDouble(), 20.0, sizePx, faces, outline = false).asImageBitmap() } catch (_: Exception) { null } else null,
        )
    }
    val enter = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(body, paint) {
        launch { enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 120f)) }
        yaw.animateTo(HERO_YAW, spring(dampingRatio = 0.62f, stiffness = 45f))
    }
    // rysowanie poza wątkiem głównym, co 3° – płynnie przy obracaniu palcem
    LaunchedEffect(faces, sizePx) {
        val f = faces ?: return@LaunchedEffect
        snapshotFlow { (yaw.value / 3f).roundToInt() * 3 }.distinctUntilChanged().collectLatest { y ->
            val b = withContext(Dispatchers.Default) {
                try { Model3D.carIcon(body, paint, y.toDouble(), 20.0, sizePx, f, outline = false) } catch (_: Exception) { null }
            }
            if (b != null) bmp = b.asImageBitmap()
        }
    }
    // lekkie „oddychanie” – auto delikatnie unosi się nad cieniem
    val t = rememberInfiniteTransition(label = "hover")
    val hover by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "hoverF")
    Box(
        modifier.fillMaxWidth().height(height).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragEnd = { scope.launch { yaw.animateTo(HERO_YAW, spring(dampingRatio = 0.45f, stiffness = 35f)) } },
                onDragCancel = { scope.launch { yaw.animateTo(HERO_YAW, spring(dampingRatio = 0.45f, stiffness = 35f)) } },
            ) { change, dx ->
                change.consume()
                scope.launch { yaw.snapTo(yaw.value - dx * 0.45f) }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        val acc = C.Accent
        // cień i poświata pod autem
        Canvas(Modifier.fillMaxSize()) {
            // podłoga pod kołami (auto zajmuje górne ~2/3 obrazka)
            val cy = size.height * 0.66f
            val r = minOf(size.width * 0.36f, size.height * 0.75f)
            drawOval(
                Brush.radialGradient(listOf(acc.copy(alpha = 0.30f), Color.Transparent), center = Offset(size.width / 2, cy), radius = r),
                topLeft = Offset(size.width / 2 - r, cy - r * 0.28f), size = Size(r * 2, r * 0.56f),
            )
            drawOval(
                Brush.radialGradient(listOf(Color.Black.copy(alpha = if (C.isLight) 0.22f else 0.55f), Color.Transparent), center = Offset(size.width / 2, cy), radius = r * 0.6f),
                topLeft = Offset(size.width / 2 - r * 0.6f, cy - r * 0.1f), size = Size(r * 1.2f, r * 0.2f),
            )
        }
        val b = bmp
        val e = enter.value
        if (b != null) Image(
            b, "Twoje auto",
            Modifier.fillMaxHeight().aspectRatio(1f).graphicsLayer {
                translationX = (1f - e) * size.width * 0.6f
                translationY = -hover * 5.dp.toPx()
                alpha = e.coerceIn(0f, 1f)
            },
        ) else Icon(Icons.Rounded.DirectionsCar, null, tint = C.Text2, modifier = Modifier.size(height * 0.5f))
    }
}

// ---------- drobne elementy ----------

/** Okrągła szybka akcja z podpisem (sprężyste wciśnięcie). */
@Composable
fun RoundAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(src, 0.88f).clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(58.dp).clip(CircleShape).then(
                if (primary) Modifier.background(Brush.linearGradient(listOf(C.Accent, C.Accent.copy(alpha = 0.72f))))
                else Modifier.surface(CircleShape, C.Raised, border = C.Border),
            ),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (primary) C.Bg else C.Text, modifier = Modifier.size(26.dp)) }
        Text(label, fontSize = 12.sp, color = C.Text2, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Liczba „nabijana” od poprzedniej wartości (np. zasięg po odświeżeniu). */
@Composable
fun CountUpText(value: Int, suffix: String, size: Int, color: Color = C.Text, weight: FontWeight = FontWeight.Bold) {
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    val a = remember { Animatable(if (still) value.toFloat() else 0f) }
    LaunchedEffect(value) { a.animateTo(value.toFloat(), tween(1100, easing = FastOutSlowInEasing)) }
    Text(thousandsSep(a.value.roundToInt()) + suffix, fontSize = size.sp, fontWeight = weight, color = color, maxLines = 1)
}

/** Pierścień (np. paliwo w baku) – wypełnia się przy wejściu. */
@Composable
fun RangeRing(fraction: Float, modifier: Modifier = Modifier, stroke: Dp = 10.dp, color: Color = C.Accent) {
    val still = androidx.compose.ui.platform.LocalInspectionMode.current
    val p = remember { Animatable(if (still) fraction.coerceIn(0f, 1f) else 0f) }
    LaunchedEffect(fraction) { p.animateTo(fraction.coerceIn(0f, 1f), tween(1200, easing = FastOutSlowInEasing)) }
    val track = C.Muted.copy(alpha = 0.18f)
    Canvas(modifier) {
        val w = stroke.toPx()
        val inset = w / 2
        val sz = Size(size.width - w, size.height - w)
        drawArc(track, 135f, 270f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        drawArc(
            Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color)),
            135f, 270f * p.value, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round),
        )
    }
}

// ---------- pasek zakładek ----------

/** Pływający pasek zakładek: kapsuła, wskaźnik przesuwa się sprężyście za palcem. */
@Composable
fun FloatingTabBar(items: List<Pair<ImageVector, String>>, tab: Int, onTab: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp))
                .surface(RoundedCornerShape(32.dp), C.Card, border = C.Border).padding(5.dp),
        ) {
            val w = maxWidth / items.size
            val x by animateDpAsState(w * tab, spring(dampingRatio = 0.72f, stiffness = 420f), label = "tabX")
            Box(
                Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(27.dp))
                    .background(C.Accent.copy(alpha = if (C.isLight) 0.14f else 0.18f)),
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, (icon, label) ->
                    val on = i == tab
                    val src = remember { MutableInteractionSource() }
                    val sc by animateFloatAsState(if (on) 1.14f else 1f, spring(dampingRatio = 0.42f, stiffness = 480f), label = "tabS")
                    val tint = if (on) C.Accent else C.Muted
                    Column(
                        Modifier.weight(1f).fillMaxHeight().pressScale(src, 0.9f).clip(RoundedCornerShape(27.dp))
                            .clickable(interactionSource = src, indication = null) { onTab(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = sc; scaleY = sc })
                        Text(label, fontSize = 11.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------- ekran główny ----------

/** Szybkie akcje: prowadź do auta, trasa do radia, jazda na żywo, udostępnij miejsce. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActions(perms: Perms, actions: Actions, spot: Store.Spot?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairing by Store.pairing.collectAsState()
    val car by CarData.car.collectAsState()
    val share by TripShare.info.collectAsState()
    var route by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    val canShare = pairing != null && car?.supports(pl.mati.caros.core.Proto.Cmd.SHARE) == true
    Row(Modifier.fillMaxWidth().stagger(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Rounded.DirectionsWalk, "Prowadź", primary = spot != null) {
            if (spot == null) { android.widget.Toast.makeText(ctx, "Brak zapisanego miejsca auta", android.widget.Toast.LENGTH_SHORT).show(); return@RoundAction }
            if (!perms.loc) actions.requestLoc()
            WalkNav.start()
        }
        if (pairing != null) RoundAction(Icons.Rounded.Navigation, "Trasa") { route = true }
        if (canShare) RoundAction(Icons.Rounded.WifiTethering, if (share != null && share!!.until > System.currentTimeMillis()) "Na żywo ●" else "Na żywo") {
            val i = share
            if (i != null && i.until > System.currentTimeMillis()) try { ctx.startActivity(TripShare.chooser(i.link)) } catch (_: Exception) {}
            else live = true
        }
        if (spot != null) RoundAction(Icons.Rounded.Share, "Udostępnij") { shareSpot(ctx, spot) }
        else RoundAction(Icons.Rounded.LocationOn, "Zapisz") {
            if (!perms.loc) { actions.requestLoc(); return@RoundAction }
            android.widget.Toast.makeText(ctx, "Ustalam pozycję…", android.widget.Toast.LENGTH_SHORT).show()
            scope.launch {
                val l = withContext(Dispatchers.IO) { Locator.freshFix(ctx, 15) }
                if (l == null) android.widget.Toast.makeText(ctx, "Brak sygnału GPS – spróbuj na zewnątrz", android.widget.Toast.LENGTH_LONG).show()
                else {
                    Store.saveSpot(ctx, Store.Spot(l.latitude, l.longitude, System.currentTimeMillis(), l.accuracy, manual = true))
                    android.widget.Toast.makeText(ctx, "Zapisano miejsce", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    if (route) RouteSheet { route = false }
    if (live) LiveSharePicker { live = false }
}

/** Wyszukiwarka celu w wysuwanym panelu (trasa do nawigacji w aucie / ulubione). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSheet(onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        containerColor = C.Dialog, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Wyślij trasę do auta", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Text)
            SendPlaceCard()
        }
    }
}

/** Status auta jednym zdaniem: w drodze / stoi X od Ciebie / gdzie. */
@Composable
private fun statusLine(spot: Store.Spot?, me: Location?, driving: Boolean): String = when {
    driving -> "Jesteś w aucie"
    spot == null -> "Miejsce zapisze się po zgaszeniu auta"
    me != null -> {
        val d = WalkNav.dist(me.latitude, me.longitude, spot.lat, spot.lon)
        "Stoi ${WalkNav.distText(d)} od Ciebie · " + (spot.address?.substringBefore(',') ?: "zaparkowane")
    }
    else -> "Zaparkowane · " + (spot.address?.substringBefore(',') ?: agoText(spot.time))
}

/** Zasięg / paliwo jednym rzutem oka (z ostatniego statusu z radia). */
@Composable
private fun RangeLine(center: Boolean) {
    val st by CarData.status.collectAsState()
    val f = st?.fuel
    val range = f?.range?.roundToInt()
    val left = f?.left
    if (range == null && st?.odo == null) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (range != null) {
            CountUpText(range, " km", 40)
            Text(
                " zasięgu" + (left?.let { String.format(PLN, " · %.0f l", it) } ?: ""),
                fontSize = 16.sp, color = C.Text2, modifier = Modifier.padding(bottom = 7.dp, start = 4.dp),
            )
        } else st?.odo?.let {
            CountUpText(it, " km", 34)
            Text(" przebiegu", fontSize = 16.sp, color = C.Text2, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
        }
    }
}

/** Ekran główny „Auto na pierwszym planie”: nazwa, status, auto 3D, zasięg, szybkie akcje. */
@Composable
fun HomeHero(perms: Perms, actions: Actions, spot: Store.Spot?, me: Location?, driving: Boolean) {
    val pairing by Store.pairing.collectAsState()
    val carName by Store.carName.collectAsState()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CAR OS", fontSize = 12.sp, color = C.Accent, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(pairing?.car ?: "Moje auto", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(statusLine(spot, me, driving), fontSize = 14.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(driving, carName)
        }
        CarHero3D(Modifier.padding(top = 4.dp), 210.dp)
        RangeLine(center = true)
        Spacer(Modifier.height(14.dp))
        QuickActions(perms, actions, spot)
    }
}

/** Nagłówek w panelu (ekran „Mapa w tle”): status auta, zasięg i szybkie akcje. */
@Composable
fun MapSheetHeader(perms: Perms, actions: Actions, spot: Store.Spot?, me: Location?, driving: Boolean) {
    val pairing by Store.pairing.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text(pairing?.car ?: "Moje auto", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusLine(spot, me, driving), fontSize = 14.sp, color = C.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        RangeLine(center = false)
        QuickActions(perms, actions, spot)
    }
}

/** Pigułka wyszukiwania nad mapą (ekran „Mapa w tle”). */
@Composable
fun MapSearchPill(modifier: Modifier = Modifier) {
    var route by remember { mutableStateOf(false) }
    val pairing by Store.pairing.collectAsState()
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(src, 0.97f).height(52.dp).clip(RoundedCornerShape(26.dp)).surface(RoundedCornerShape(26.dp), C.Card, border = C.Border)
            .clickable(interactionSource = src, indication = null) { if (pairing != null) route = true }.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = C.Muted, modifier = Modifier.size(22.dp))
        Text(if (pairing != null) "Dokąd jedziemy?" else "Połącz z CAR OS, żeby wysyłać trasy", fontSize = 16.sp, color = C.Muted, maxLines = 1)
    }
    if (route) RouteSheet { route = false }
}

/** Schowany panel (ekran „Mapa w tle”): pasek z autem – dotknij albo pociągnij w górę, żeby wysunąć. */
@Composable
fun MiniSheetBar(onOpen: () -> Unit) {
    val pairing by Store.pairing.collectAsState()
    val st by CarData.status.collectAsState()
    val range = st?.fuel?.range?.roundToInt()
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
        Text(pairing?.car ?: "Moje auto", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (range != null) Text("$range km", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Accent)
    }
}

// ---------- ustawienia ----------

/** Wybór ekranu głównego z miniaturkami. */
@Composable
fun LayoutChooser() {
    val mode by Store.layout.collectAsState()
    Text("Ekran główny", fontSize = 14.sp, color = C.Text2, modifier = Modifier.padding(horizontal = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("car" to "Auto na pierwszym planie", "map" to "Mapa w tle").forEach { (k, label) ->
            val on = mode == k
            val src = remember { MutableInteractionSource() }
            val bw by animateDpAsState(if (on) 2.dp else 1.dp, label = "lcB")
            Column(
                Modifier.weight(1f).pressScale(src, 0.96f).clip(RoundedCornerShape(18.dp)).surface(RoundedCornerShape(18.dp), C.Card)
                    .border(bw, if (on) C.Accent else C.Border, RoundedCornerShape(18.dp))
                    .clickable(interactionSource = src, indication = null) { Store.setLayout(k) }.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LayoutThumb(k)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Accent else C.Text, modifier = Modifier.weight(1f), maxLines = 2)
                    if (on) Icon(Icons.Rounded.Check, null, tint = C.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Miniaturka układu (rysowana – bez obrazków). */
@Composable
private fun LayoutThumb(kind: String) {
    val acc = C.Accent
    val raised = C.Raised
    val text = C.Text2
    val card = C.Bg
    Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(card)) {
        val w = size.width
        val h = size.height
        if (kind == "map") {
            // mapa: drogi, auto, panel od dołu
            drawRect(raised.copy(alpha = 0.55f))
            drawLine(text.copy(alpha = 0.25f), Offset(0f, h * 0.35f), Offset(w, h * 0.15f), strokeWidth = 7f)
            drawLine(text.copy(alpha = 0.18f), Offset(w * 0.3f, 0f), Offset(w * 0.45f, h), strokeWidth = 5f)
            drawCircle(acc, 7f, Offset(w * 0.42f, h * 0.28f))
            drawRoundRect(card, Offset(0f, h * 0.52f), Size(w, h * 0.48f), androidx.compose.ui.geometry.CornerRadius(18f))
            drawRoundRect(text.copy(alpha = 0.35f), Offset(w * 0.4f, h * 0.56f), Size(w * 0.2f, 4f), androidx.compose.ui.geometry.CornerRadius(2f))
            drawRoundRect(text.copy(alpha = 0.5f), Offset(w * 0.1f, h * 0.66f), Size(w * 0.5f, 8f), androidx.compose.ui.geometry.CornerRadius(4f))
            for (i in 0..3) drawCircle(if (i == 0) acc else raised, 9f, Offset(w * (0.2f + i * 0.2f), h * 0.86f))
        } else {
            // auto: nazwa, duże auto, zasięg, okrągłe akcje
            drawRoundRect(text.copy(alpha = 0.5f), Offset(w * 0.1f, h * 0.08f), Size(w * 0.45f, 9f), androidx.compose.ui.geometry.CornerRadius(4f))
            drawOval(acc.copy(alpha = 0.25f), Offset(w * 0.22f, h * 0.46f), Size(w * 0.56f, h * 0.12f))
            drawRoundRect(acc, Offset(w * 0.27f, h * 0.27f), Size(w * 0.46f, h * 0.22f), androidx.compose.ui.geometry.CornerRadius(16f))
            drawRoundRect(acc.copy(alpha = 0.7f), Offset(w * 0.35f, h * 0.2f), Size(w * 0.28f, h * 0.12f), androidx.compose.ui.geometry.CornerRadius(12f))
            drawRoundRect(text.copy(alpha = 0.6f), Offset(w * 0.3f, h * 0.64f), Size(w * 0.4f, 10f), androidx.compose.ui.geometry.CornerRadius(5f))
            for (i in 0..3) drawCircle(if (i == 0) acc else raised, 9f, Offset(w * (0.2f + i * 0.2f), h * 0.86f))
        }
    }
}

// ---------- zakładka Auto ----------

/** Pulpit na górze zakładki Auto: pierścień paliwa, zasięg, litry, przebieg. */
@Composable
fun CarDashboard(s: CarData.Status) {
    val f: CarData.Fuel? = s.fuel
    val left: Double? = f?.left
    val tank: Int = f?.tank ?: 0
    val frac: Float? = if (left != null && tank > 0) (left / tank).toFloat() else null
    Row(
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(24.dp)).surface(RoundedCornerShape(24.dp), C.Card, border = C.Border).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
            RangeRing(frac ?: 0f, Modifier.fillMaxSize(), 10.dp, if ((frac ?: 1f) < 0.15f) C.Orange else C.Accent)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (frac != null) CountUpText((frac * 100).roundToInt(), "%", 22) else Icon(Icons.Rounded.DirectionsCar, null, tint = C.Accent, modifier = Modifier.size(32.dp))
                Text(if (frac != null) "w baku" else "auto", fontSize = 11.sp, color = C.Muted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val r = f?.range?.roundToInt()
            if (r != null) {
                val src = if (f?.fromCan == true) " · czujnik auta" else if (f?.estimated == true) " · szacunek" else ""
                Text("Zasięg$src", fontSize = 12.sp, color = C.Muted)
                CountUpText(r, " km", 30)
            }
            if (left != null) Text(String.format(PLN, "%.1f l z %d l", left, tank), fontSize = 14.sp, color = C.Text2)
            val odo = s.odo
            if (s.odoSet && odo != null) Text("Przebieg ${thousandsSep(odo)} km", fontSize = 14.sp, color = C.Text2)
        }
    }
}
