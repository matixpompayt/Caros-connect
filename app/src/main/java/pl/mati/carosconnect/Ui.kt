package pl.mati.carosconnect

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.material.icons.rounded.Home
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Kolory aplikacji: ciemne albo jasne (Ustawienia → Wygląd). */
data class Pal(
    val light: Boolean,
    val bg: Color, val card: Color, val raised: Color, val border: Color,
    val text: Color, val text2: Color, val muted: Color,
    val accent: Color, val green: Color, val orange: Color, val hero: Color, val frost: Color,
    val glass: Boolean = false,
)

object C {
    val DARK = Pal(
        false, Color(0xFF0A0C0F), Color(0xFF14181E), Color(0xFF1A1F26), Color(0xFF1E232B),
        Color(0xFFEEF1F4), Color(0xFFC3C9D0), Color(0xFF9AA3AE),
        Color(0xFF2FD3F0), Color(0xFF7CF06B), Color(0xFFFFB03A), Color(0xFF0E2A33), Color(0xFF15324A),
    )
    val LIGHT = Pal(
        true, Color(0xFFE9EDF1), Color(0xFFF8FAFC), Color(0xFFDFE5EB), Color(0xFFD2D9E0),
        Color(0xFF12161B), Color(0xFF353D47), Color(0xFF66707C),
        Color(0xFF0A97B4), Color(0xFF2E9440), Color(0xFFCC7A1E), Color(0xFFCDE9F0), Color(0xFFD3E6F5),
    )
    var pal by mutableStateOf(DARK)
    val isLight get() = pal.light
    val isGlass get() = pal.glass
    /** Tło okien dialogowych – w Glass prawie nieprzezroczyste, żeby tekst był czytelny. */
    /** Kolor kontenera AlertDialog: w Glass przezroczysty, tło rysuje Modifier.glassDialog(). */
    val DialogBg get() = if (pal.glass) Color.Transparent else Dialog
    val Dialog get() = if (pal.glass) (if (pal.light) Color(0xF7F4F6FA) else Color(0xF5171C33)) else pal.card
    val Bg get() = pal.bg
    val Card get() = pal.card
    val Raised get() = pal.raised
    val Border get() = pal.border
    val Text get() = pal.text
    val Text2 get() = pal.text2
    val Muted get() = pal.muted
    val Accent get() = pal.accent
    val Green get() = pal.green
    val Orange get() = pal.orange
    val Hero get() = pal.hero
    val Frost get() = pal.frost
}

/** Wybór wyglądu: auto (jak telefon) / jasny / ciemny. */
@Composable
fun ThemeChooser() {
    val mode by Store.theme.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "Jak telefon", "light" to "Jasny", "dark" to "Ciemny").forEach { (k, label) ->
            val on = mode == k
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .then(if (on) Modifier.background(C.Accent.copy(alpha = 0.16f)).border(1.dp, C.Accent, RoundedCornerShape(14.dp)) else Modifier.surface(RoundedCornerShape(14.dp), border = C.Border))
                    .clickable { Store.setTheme(k) }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) C.Accent else C.Text) }
        }
    }
    // Glass – podgląd kolorowego tła prosto w kafelku
    val on = mode == "glass"
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Glass.Violet, Glass.Sky, Glass.Teal, Glass.Pink)))
            .border(if (on) 2.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.3f), shape)
            .clickable { Store.setTheme("glass") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.padding(6.dp).weight(1f).clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.16f)).border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Glass", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("Szklane karty na kolorowym, ruchomym tle", fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
            }
            if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
    if (on) GlassSettings()
}

private val TIME = SimpleDateFormat("HH:mm", Locale("pl"))
private val DAY = SimpleDateFormat("d.MM, HH:mm", Locale("pl"))

fun agoText(t: Long): String {
    val min = ((System.currentTimeMillis() - t) / 60_000).toInt()
    return when {
        min < 1 -> "przed chwilą"
        min < 60 -> "$min min temu"
        min < 24 * 60 -> "${min / 60} h ${min % 60} min temu"
        else -> "${min / (24 * 60)} dni temu"
    }
}

fun whenText(t: Long): String {
    val today = SimpleDateFormat("yyyyMMdd", Locale.US)
    return if (today.format(Date(t)) == today.format(Date())) "dziś, " + TIME.format(Date(t)) else DAY.format(Date(t))
}

fun distText(m: Float): String = if (m >= 1000) String.format(Locale("pl"), "%.1f km", m / 1000) else "${(m / 5).roundToInt() * 5} m"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkApp(perms: Perms, actions: Actions) {
    val themeMode by Store.theme.collectAsState()
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val glass = themeMode == "glass"
    val gSpec by GlassTheme.spec.collectAsState()
    val light = when (themeMode) { "light" -> true; "dark" -> false; "glass" -> GlassTheme.isLight(gSpec, sysDark); else -> !sysDark }
    // kolor akcentu wybrany w telefonie albo z auta (wspólny wygląd)
    val lookAcc by LookSync.accent.collectAsState()
    val target = when {
        glass -> GlassTheme.pal(light, gSpec.accent?.let { Color(it) } ?: GlassState.autoAccent ?: GlassTheme.DEFAULT_ACCENT)
        light -> lookAcc?.let { C.LIGHT.copy(accent = GlassTheme.accentFor(Color(it), true)) } ?: C.LIGHT
        else -> lookAcc?.let { C.DARK.copy(accent = Color(it)) } ?: C.DARK
    }
    if (C.pal != target) C.pal = target
    // widżety w tym samym wyglądzie co aplikacja (motyw, Glass, akcent)
    val wctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(themeMode, gSpec, light) {
        kotlinx.coroutines.delay(800)
        Widgets.updateAll(wctx)
    }
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            // zawsze od krawędzi do krawędzi: mapa i tło także pod paskiem stanu i nawigacji
            // (wcześniej poza Glass pasek stanu był czarny nad mapą)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                w.isStatusBarContrastEnforced = false
                w.isNavigationBarContrastEnforced = false
            }
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
    val scheme = if (light) androidx.compose.material3.lightColorScheme(primary = C.Accent, background = C.Bg, surface = C.Dialog)
    else darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Dialog)
    MaterialTheme(colorScheme = scheme) {
      androidx.compose.runtime.CompositionLocalProvider(LocalIndication provides (if (glass) GlassIndication else LocalIndication.current)) {
        val ctx = LocalContext.current
        val inService by Store.driving.collectAsState()
        val linked by Store.linked.collectAsState()
        val driving = inService || linked
        // stan połączenia z radiem: „zaparkowane” tylko po rozłączeniu Bluetooth
        LaunchedEffect(Unit) {
            while (true) {
                Store.refreshLink(ctx, startService = true)
                delay(5000)
            }
        }
        val car by Store.carName.collectAsState()
        val pairing by Store.pairing.collectAsState()
        val skipped by Store.skippedPairing.collectAsState()
        val share by Share.incoming.collectAsState()
        var picker by remember { mutableStateOf(false) }
        var unpairAsk by remember { mutableStateOf(false) }

        if (glass) { GlassBackdrop(); GlassTiltSensor() }
        if (pairing == null && !skipped) {
            WelcomeScreen(actions)
            share?.let { ShareDialog(it, actions) { Share.incoming.value = null } }
            return@CompositionLocalProvider
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var trip by remember { mutableStateOf<CarData.Trip?>(null) }
        val openTab by OpenTab.collectAsState()
        val logOpen by LogPage.open.collectAsState()
        LaunchedEffect(openTab) { openTab?.let { tab = it; trip = null; LogPage.close(); OpenTab.value = null } }
        BackHandler(enabled = logOpen || trip != null || tab != 0) {
            when {
                logOpen -> LogPage.close()
                trip != null -> trip = null
                else -> tab = 0
            }
        }

        // pociągnij w dół = odśwież (dane z CAR OS, uprawnienia, Bluetooth, aktualizacje)
        val pull = rememberPullToRefreshState()
        if (pull.isRefreshing) {
            LaunchedEffect(true) {
                val t0 = System.currentTimeMillis()
                val msg = try { actions.syncNow() } catch (_: Exception) { "Nie udało się odświeżyć" }
                val wait = 700 - (System.currentTimeMillis() - t0)
                if (wait > 0) delay(wait)
                pull.endRefresh()
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
        val layout by Store.layout.collectAsState()
        val t0Home = layout == "car"
        val tabItems = TAB_ITEMS
        val onTab: (Int) -> Unit = { tab = it; trip = null; LogPage.close() }
        if (layout == "map") {
            // ekran „Mapa w tle”: mapa z autem pod spodem, zakładki w panelu wysuwanym od dołu
            MapShell(perms, actions, tab, onTab) {
                AnimatedContent(
                    targetState = Triple(tab, trip, logOpen),
                    transitionSpec = {
                        (fadeIn(tween(250, delayMillis = 60)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { h -> h / 10 }) togetherWith fadeOut(tween(140))
                    },
                    label = "sheetTab",
                ) { (t, tr, lg) ->
                    CompositionLocalProvider(LocalStagger provides remember { StaggerCounter() }) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (lg) EventLogPage { LogPage.close() } else when (t) {
                                0 -> ParkingTab(perms, actions, layout) { picker = true }
                                1 -> CarTab(actions)
                                2 -> TripsTab(tr) { trip = it }
                                else -> SettingsTab(perms, actions, onPickCar = { picker = true }, onUnpair = { unpairAsk = true })
                            }
                        }
                    }
                }
            }
        } else
        Column(Modifier.fillMaxSize().then(if (glass) Modifier else Modifier.background(C.Bg))) {
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(pull.nestedScrollConnection)) {
                val scroll = remember(tab, trip, logOpen) { ScrollState(0) }
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // nagłówek: na ekranie Start (auto na pierwszym planie) jest w karcie auta
                    if (!(t0Home && tab == 0 && !logOpen)) Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pairing?.car ?: "Moje auto", fontSize = 13.sp, color = C.Accent, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            AnimatedContent(
                                targetState = if (logOpen) "Dziennik" else tabItems[tab].second,
                                transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 }) togetherWith fadeOut(tween(120)) },
                                label = "title",
                            ) { Text(it, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = C.Text) }
                        }
                        StatusPill(driving, car)
                    }
                    AnimatedContent(
                        targetState = Triple(tab, trip, logOpen),
                        transitionSpec = {
                            val (t0, tr0, lg0) = initialState
                            val (t1, tr1, lg1) = targetState
                            // w prawo/lewo zgodnie z kolejnością zakładek; szczegóły trasy i dziennik wjeżdżają z prawej
                            val dir = when {
                                lg1 != lg0 -> if (lg1) 1 else -1
                                t1 != t0 -> if (t1 > t0) 1 else -1
                                tr1 != null && tr0 == null -> 1
                                else -> -1
                            }
                            (fadeIn(tween(250, delayMillis = 60)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> dir * w / 6 }) togetherWith
                                (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { w -> -dir * w / 8 })
                        },
                        label = "tab",
                    ) { (t, tr, lg) ->
                        CompositionLocalProvider(LocalStagger provides remember { StaggerCounter() }) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (lg) EventLogPage { LogPage.close() } else when (t) {
                                    0 -> ParkingTab(perms, actions, layout) { picker = true }
                                    1 -> CarTab(actions)
                                    2 -> TripsTab(tr) { trip = it }
                                    else -> SettingsTab(perms, actions, onPickCar = { picker = true }, onUnpair = { unpairAsk = true })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                PullToRefreshContainer(
                    state = pull,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                    containerColor = C.Dialog,
                    contentColor = C.Accent,
                )
            }
            BottomBar(tab, onTab)
        }

        if (picker) DevicePicker(actions, onDismiss = { picker = false })
        if (unpairAsk) AlertDialog(
            onDismissRequest = { unpairAsk = false },
            containerColor = C.DialogBg, modifier = Modifier.glassDialog(),
            title = { Text("Rozłączyć z CAR OS?", color = C.Text) },
            text = { Text("Zapisane miejsca zostaną w telefonie. Żeby połączyć ponownie, zeskanuj kod z radia.", color = C.Muted) },
            confirmButton = { TextButton(onClick = { Store.setPairing(null); unpairAsk = false }) { Text("Rozłącz") } },
            dismissButton = { TextButton(onClick = { unpairAsk = false }) { Text("Anuluj") } },
        )
        share?.let { ShareDialog(it, actions) { Share.incoming.value = null } }
        WhatsNewDialog()
        // „Udostępnij link” z alarmu „auto jedzie bez Ciebie”
        val askShare by Guard.askShare.collectAsState()
        if (askShare) LiveSharePicker { Guard.askShare.value = false }
        // nawigacja piesza do auta – na całym ekranie, nad wszystkim
        val walking by WalkNav.active.collectAsState()
        // wejście / wyjście: elementy wlatują po kolei (karta z góry, panel z dołu), przy końcu – odwrotnie
        androidx.compose.animation.AnimatedVisibility(
            walking,
            enter = androidx.compose.animation.EnterTransition.None,
            exit = androidx.compose.animation.ExitTransition.None,
        ) { WalkNavScreen(perms, actions, screenProgress()) }
      }
    }
}

/** Zakładki: Start (auto / parking), Auto (stan, paliwo, serwis), Trasy, Ustawienia. */
val TAB_ITEMS = listOf(
    Icons.Rounded.Home to "Start",
    Icons.Rounded.DirectionsCar to "Auto",
    Icons.Rounded.Map to "Trasy",
    Icons.Rounded.Settings to "Ustawienia",
)

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    // Glass: kapsuła z „kroplą wody” (LiquidTabBar.kt); inne motywy: pływająca kapsuła z przesuwanym wskaźnikiem
    if (C.isGlass) LiquidTabBar(TAB_ITEMS, tab, onTab) else FloatingTabBar(TAB_ITEMS, tab, onTab)
}

/** Ekran „Mapa w tle”: mapa z autem na całym ekranie, zakładki w panelu wysuwanym od dołu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapShell(perms: Perms, actions: Actions, tab: Int, onTab: (Int) -> Unit, content: @Composable () -> Unit) {
    val spot by Store.spot.collectAsState()
    val visible = rememberVisible()
    val me = rememberPhoneLocation(perms.loc && visible)
    val inService by Store.driving.collectAsState()
    val linked by Store.linked.collectAsState()
    val driving = inService || linked
    // auto w drodze – na mapie w tle zamiast zaparkowanego
    val liveCar = rememberLiveCar(me, driving, spot)
    // ostatnia pozycja w drodze – znaczek zanika płynnie, gdy auto stanie
    var lastLive by remember { mutableStateOf<CarData.Live?>(null) }
    if (liveCar != null && liveCar != lastLive) lastLive = liveCar
    val scaffold = androidx.compose.material3.rememberBottomSheetScaffoldState(
        // trzy pozycje: rozwinięty, do połowy, schowany (zostaje pasek z nazwą auta – pociągnij w górę)
        bottomSheetState = androidx.compose.material3.rememberStandardBottomSheetState(
            initialValue = androidx.compose.material3.SheetValue.PartiallyExpanded, skipHiddenState = false,
        ),
    )
    val sheet = scaffold.bottomSheetState
    val hidden = sheet.targetValue == androidx.compose.material3.SheetValue.Hidden
    val scope = rememberCoroutineScope()
    // inna zakładka – panel w górę; Start – z powrotem do połowy (widać mapę)
    LaunchedEffect(tab) {
        try { if (tab == 0) scaffold.bottomSheetState.partialExpand() else scaffold.bottomSheetState.expand() } catch (_: Exception) {}
    }
    val peek = 360.dp
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val sheetH = maxHeight * 0.9f
        androidx.compose.material3.BottomSheetScaffold(
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
                val scroll = remember(tab) { ScrollState(0) }
                Column(
                    Modifier.fillMaxWidth().height(sheetH).verticalScroll(scroll).padding(horizontal = 18.dp).padding(bottom = 112.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { content() }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                val lc = liveCar
                val s = spot ?: lc?.let { Store.Spot(it.lat, it.lon, it.time, 0f, src = Store.SRC_CAR) }
                if (s != null) ParkMap(s, me, Modifier.fillMaxSize(), bottomInset = if (hidden) 150.dp else peek + 4.dp, liveCar = lc, hideMe = driving && lc != null)
                else Box(
                    Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C.Accent.copy(alpha = 0.16f), C.Bg))),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(Modifier.padding(top = 150.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimatedLogo(64.dp)
                        Text("Mapa pokaże auto po pierwszym parkowaniu", fontSize = 14.sp, color = C.Muted)
                    }
                }
                // pod paskiem stanu delikatne przyciemnienie – ikony czytelne na jasnej mapie
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (C.isLight) 0.18f else 0.45f), Color.Transparent)))
                        .statusBarsPadding().height(18.dp),
                )
                MapSearchPill(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth())
                // auto w drodze: znaczek pod wyszukiwarką
                androidx.compose.animation.AnimatedVisibility(
                    liveCar != null,
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 76.dp),
                    enter = fadeIn() + androidx.compose.animation.slideInVertically { -it / 2 },
                    exit = fadeOut(),
                ) { lastLive?.let { LiveChip(it, driving) } }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter)) {
            androidx.compose.animation.AnimatedVisibility(
                hidden,
                enter = androidx.compose.animation.slideInVertically { it } + fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + fadeOut(),
            ) { MiniSheetBar { scope.launch { try { sheet.partialExpand() } catch (_: Exception) {} } } }
            BottomBar(tab, onTab)
        }
    }
}

/** Zakładka „Parking”: gdzie stoi auto, kompas, parkometr, pogoda przy aucie, historia. */
@Composable
private fun ParkingTab(perms: Perms, actions: Actions, layout: String, onPickCar: () -> Unit) {
    val ctx = LocalContext.current
    val spot by Store.spot.collectAsState()
    val inService by Store.driving.collectAsState()
    val linked by Store.linked.collectAsState()
    val driving = inService || linked
    val car by Store.carName.collectAsState()
    val history by Store.history.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    // GPS i kompas tylko gdy aplikacja jest na ekranie i jest zapisane miejsce auta
    val visible = rememberVisible()
    val active = visible && spot != null
    val me = rememberPhoneLocation(perms.loc && active)
    val azimuth = rememberAzimuth(me, active)

        // wygląd 2.0: auto 3D na pierwszym planie albo (mapa w tle) skrót w panelu
        if (layout == "map") MapSheetHeader(perms, actions, spot, me, driving)
        else HomeHero(perms, actions, spot, me, driving)

        if (!perms.allOk || car == null) SetupCard(perms, actions, car) { onPickCar() }

        TripPlanCard()
        val iv by LiveFeed.interval.collectAsState()
        val fb by LiveFeed.fbUrl.collectAsState()
        // 1) telefon w aucie (połączony z radiem) → pozycja auta = GPS telefonu, co wybrany czas
        var inCarPos by remember { mutableStateOf<Location?>(null) }
        LaunchedEffect(me, iv, driving) {
            val m = me ?: return@LaunchedEffect
            if (!driving) { inCarPos = null; return@LaunchedEffect }
            val cur = inCarPos
            if (cur == null || iv <= 0 || m.time - cur.time >= iv * 1000L - 300) inCarPos = m
        }
        // 2) telefonu nie ma w aucie, a auto jedzie → z Firebase na żywo, bez niego z przekaźnika co ok. minutę
        val live by CarData.live.collectAsState()
        val lv = live
        val liveActive = !driving && lv != null && lv.moving && System.currentTimeMillis() + 0 * tick - lv.time < 10 * 60_000L &&
            (spot == null || lv.time > (spot?.time ?: 0L))
        LaunchedEffect(visible, fb, driving) {
            if (visible && fb.isNotBlank() && !driving) LiveFeed.stream { CarData.setLive(it) }
        }
        LaunchedEffect(liveActive, visible, fb) {
            while (liveActive && visible && fb.isBlank()) {
                delay(30_000)
                actions.refreshFromCar()
            }
        }
        val icp = inCarPos
        if (driving && icp != null) {
            LiveCard(
                CarData.Live(icp.latitude, icp.longitude, if (icp.hasSpeed()) (icp.speed * 3.6f).roundToInt() else 0, icp.bearing.toInt(), icp.time, true),
                null, "jesteś w aucie · ${LiveFeed.label(iv)}", showMap = layout != "map",
            )
        } else if (liveActive && lv != null) {
            LiveCard(lv, me, if (fb.isNotBlank()) "na żywo · ${LiveFeed.label(iv)}" else "co ok. minutę (szybciej z Firebase – Ustawienia)", showMap = layout != "map")
        }

        val s = spot
        if (s == null) {
            Card {
                Text("Brak zapisanego miejsca", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text(
                    "Gdy zgasisz auto i telefon rozłączy się z radiem, aplikacja sama zapisze, gdzie stoi auto. Możesz też zapisać miejsce ręcznie.",
                    fontSize = 15.sp, color = C.Muted,
                )
            }
        } else {
            // mapa w tle – osobna karta z mapą zbędna
            if (layout != "map") ParkMap(s, me, Modifier.stagger().fillMaxWidth().height(260.dp).clip(RoundedCornerShape(26.dp)).border(1.dp, C.Border, RoundedCornerShape(26.dp)))
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(C.Accent), contentAlignment = Alignment.Center) {
                        Text("P", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Bg)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(s.address ?: s.coords, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val accTxt = if (s.accuracy > 0) " · ±${s.accuracy.roundToInt()} m" else ""
                        Text("${whenText(s.time)} · ${agoText(s.time + 0 * tick)}$accTxt", fontSize = 14.sp, color = C.Muted)
                    }
                }
                Text(
                    if (s.src == Store.SRC_CAR) "Zapisane przez CAR OS w aucie" else if (s.manual) "Zapisane ręcznie w telefonie" else "Zapisane przez telefon po zgaszeniu auta",
                    fontSize = 13.sp, color = C.Muted,
                )
                if (driving) Text("Jesteś teraz w aucie – to poprzednie miejsce parkowania.", fontSize = 13.sp, color = C.Orange)
            }
            NotePhotoCard()
            FinderCard(s, me, azimuth)
            // „Prowadź do auta” – w szybkich akcjach na górze
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Zapisz tutaj", Icons.Rounded.LocationOn, Modifier.weight(1f)) {
                if (!perms.loc) { actions.requestLoc(); return@SecondaryButton }
                Toast.makeText(ctx, "Ustalam pozycję…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val l = withContext(Dispatchers.IO) { Locator.freshFix(ctx, 15) }
                    if (l == null) {
                        Toast.makeText(ctx, "Brak sygnału GPS – spróbuj na zewnątrz", Toast.LENGTH_LONG).show()
                    } else {
                        Store.saveSpot(ctx, Store.Spot(l.latitude, l.longitude, System.currentTimeMillis(), l.accuracy, manual = true))
                        Toast.makeText(ctx, "Zapisano miejsce", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (s != null) SecondaryButton("Udostępnij", Icons.Rounded.Share, Modifier.weight(1f)) { shareSpot(ctx, s) }
        }
        ParkLogLink()

        CarWeatherCard()
        MeterCard()

        // historia
        val older = history.filter { it.time != s?.time }
        if (older.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("Poprzednie miejsca", Modifier.weight(1f))
                Text("Wyczyść", fontSize = 14.sp, color = C.Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Store.clearHistory() }.padding(8.dp))
            }
            older.take(8).forEach { h ->
                Row(
                    Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp)).surface(RoundedCornerShape(16.dp), C.Card)
                        .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.mapsUrl))) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.History, null, tint = C.Muted, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.address ?: h.coords, fontSize = 16.sp, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(whenText(h.time), fontSize = 13.sp, color = C.Muted)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(22.dp))
                }
            }
        }
}

/** Link do Map Google + notatka (+ zdjęcie, jeśli jest) – SMS, WhatsApp, Messenger… */
fun shareSpot(ctx: android.content.Context, s: Store.Spot) {
    val note = Store.spotNote.value
    val txt = "Moje auto stoi tu: ${s.address ?: s.coords}" + (if (note.isNotBlank()) "\n$note" else "") + "\n${s.mapsUrl}"
    val photo = Store.spotPhoto.value?.let { java.io.File(it) }?.takeIf { it.exists() }
    val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, txt)
    if (photo != null) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", photo)
        i.setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("", uri)
    } else {
        i.setType("text/plain")
    }
    try { ctx.startActivity(Intent.createChooser(i, "Udostępnij miejsce auta")) } catch (_: Exception) {}
}

/** Auto w drodze: mapa z autem i prędkość. */
@Composable
private fun LiveCard(l: CarData.Live, me: android.location.Location?, sub: String, showMap: Boolean = true) {
    val ctx = LocalContext.current
    val spot = remember(l.time, l.lat, l.lon) { Store.Spot(l.lat, l.lon, l.time, 0f, src = Store.SRC_CAR) }
    // wygląd „mapa w tle” – auto w drodze widać na dużej mapie, bez drugiej małej
    if (showMap) ParkMap(spot, me, Modifier.stagger().fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, C.Border, RoundedCornerShape(24.dp)), live = true)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(C.Green), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DirectionsCar, null, tint = C.Bg, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Auto w drodze · ${l.speed} km/h", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text("Pozycja ${agoText(l.time)} · $sub", fontSize = 14.sp, color = C.Muted)
            }
        }
        SecondaryButton("Pokaż w Mapach", Icons.Rounded.Map, Modifier.fillMaxWidth()) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spot.mapsUrl))) } catch (_: Exception) {}
        }
    }
}

/**
 * Gdzie jest auto w drodze (null – stoi / brak świeżych danych): telefon w aucie → GPS telefonu,
 * inaczej pozycja wysłana przez auto (Firebase / przekaźnik) – świeża i nowsza niż zapisane parkowanie.
 */
@Composable
internal fun rememberLiveCar(me: Location?, driving: Boolean, spot: Store.Spot?): CarData.Live? {
    val live by CarData.live.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    val lv = live
    val now = System.currentTimeMillis() + 0 * tick
    return when {
        driving && me != null -> CarData.Live(
            me.latitude, me.longitude, if (me.hasSpeed()) (me.speed * 3.6f).roundToInt() else 0,
            if (me.hasBearing()) me.bearing.toInt() else 0, me.time, true,
        )
        !driving && lv != null && lv.moving && now - lv.time < 10 * 60_000L && (spot == null || lv.time > spot.time) -> lv
        else -> null
    }
}

/** Znaczek na mapie w tle: auto w drodze, prędkość, jak świeża pozycja. */
@Composable
private fun LiveChip(l: CarData.Live, inCar: Boolean) {
    val pulse = rememberInfiniteTransition(label = "live")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot")
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xE60A0C0F)).padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(C.Green.copy(alpha = if (LocalInspectionMode.current) 1f else a)))
        Text(
            (if (inCar) "Jedziesz" else "Auto w drodze") + " · ${l.speed} km/h" + if (inCar) "" else " · ${agoText(l.time)}",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1,
        )
    }
}

/** Notatka („poziom -2, miejsce 47”) i zdjęcie do miejsca parkowania. */
@Composable
private fun NotePhotoCard() {
    val ctx = LocalContext.current
    val note by Store.spotNote.collectAsState()
    val photo by Store.spotPhoto.collectAsState()
    var editNote by remember { mutableStateOf(false) }
    var bigPhoto by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<java.io.File?>(null) }
    val take = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val f = pending
        if (ok && f != null && f.exists() && f.length() > 0) Store.setPhoto(f.absolutePath) else f?.delete()
        pending = null
    }
    fun shoot() {
        val dir = java.io.File(ctx.filesDir, "parking").apply { mkdirs() }
        val f = java.io.File(dir, "spot_${System.currentTimeMillis()}.jpg")
        pending = f
        try {
            take.launch(androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
        } catch (_: Exception) {
            Toast.makeText(ctx, "Nie mogę otworzyć aparatu", Toast.LENGTH_SHORT).show()
        }
    }
    val camPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shoot() else Toast.makeText(ctx, "Bez zgody na aparat nie zrobię zdjęcia", Toast.LENGTH_SHORT).show()
    }
    val bmp = remember(photo) {
        photo?.let { p ->
            try {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(p, o)
                var sample = 1
                while (o.outWidth / sample > 1200) sample *= 2
                android.graphics.BitmapFactory.decodeFile(p, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    Card {
        Text("Notatka i zdjęcie", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
        if (note.isNotBlank()) Text(note, fontSize = 17.sp, color = C.Text2, modifier = Modifier.clickable { editNote = true })
        else Text("Np. „poziom -2, miejsce 47” albo zdjęcie słupka na parkingu", fontSize = 14.sp, color = C.Muted)
        bmp?.let { b ->
            androidx.compose.foundation.Image(
                b, "Zdjęcie miejsca", contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).clickable { bigPhoto = true },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (note.isBlank()) "Notatka" else "Zmień", Icons.Rounded.Edit, Modifier.weight(1f)) { editNote = true }
            SecondaryButton(if (photo == null) "Zdjęcie" else "Nowe zdjęcie", Icons.Rounded.PhotoCamera, Modifier.weight(1f)) {
                val has = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (has) shoot() else camPerm.launch(android.Manifest.permission.CAMERA)
            }
        }
        if (photo != null) Text("Usuń zdjęcie", fontSize = 13.sp, color = C.Muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Store.setPhoto(null) }.padding(6.dp))
    }
    if (editNote) {
        val spot by Store.spot.collectAsState()
        NoteSheet(
            note, spot?.address, bmp, onDismiss = { editNote = false },
            onShoot = {
                val has = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (has) shoot() else camPerm.launch(android.Manifest.permission.CAMERA)
            },
            onDeletePhoto = { Store.setPhoto(null) },
        ) { Store.setNote(it) }
    }
    if (bigPhoto && bmp != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { bigPhoto = false }) {
            androidx.compose.foundation.Image(
                bmp, "Zdjęcie miejsca", contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { bigPhoto = false },
            )
        }
    }
}

/** Ekran powitalny: połączenie z CAR OS przez kod QR. */
@Composable
private fun WelcomeScreen(actions: Actions) {
    CompositionLocalProvider(LocalStagger provides remember { StaggerCounter() }) {
    Column(
        Modifier.fillMaxSize().then(
            if (C.isGlass) Modifier
            else Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C.Hero, C.Bg, C.Bg))),
        ).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(132.dp).clip(CircleShape).surface(CircleShape, C.Card, border = C.Border), contentAlignment = Alignment.Center) {
            AnimatedLogo(110.dp)
        }
        Spacer(Modifier.height(22.dp))
        Text("CAR OS", fontSize = 15.sp, color = C.Accent, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Text("Connect", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = C.Text)
        Text("Twoje auto w telefonie", fontSize = 17.sp, color = C.Muted)
        Spacer(Modifier.height(30.dp))

        Card {
            Feature(Icons.Rounded.LocalParking, "Gdzie zaparkowałem", "Telefon sam zapamięta miejsce, gdy zgasisz auto")
            Feature(Icons.Rounded.DirectionsWalk, "Prowadzenie do auta", "Kompas, odległość i trasa piesza")
            Feature(Icons.Rounded.AutoAwesome, "Więcej wkrótce", "Kolejne funkcje CAR OS w telefonie")
        }
        Spacer(Modifier.height(18.dp))
        Card {
            Text("Jak połączyć", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Step(1, "W aucie otwórz CAR OS: Ustawienia → Połączenia → CAR OS Connect")
            Step(2, "Dotknij „Skanuj kod” i skieruj aparat na kod na ekranie radia")
            Step(3, "Zezwól na Bluetooth i lokalizację – gotowe")
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Skanuj kod z CAR OS", Icons.Rounded.QrCodeScanner) { actions.scanQr() }
        Spacer(Modifier.height(10.dp))
        Text(
            "Połącz bez kodu (tylko Bluetooth)", fontSize = 15.sp, color = C.Muted,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { Store.skipPairing() }.padding(12.dp),
        )
    }
}
}

@Composable
private fun Feature(icon: ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(C.Accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.Accent, modifier = Modifier.size(24.dp))
        }
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text(sub, fontSize = 13.sp, color = C.Muted)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).surface(CircleShape, C.Raised), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.Accent)
        }
        Text(text, fontSize = 15.sp, color = C.Text2, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun StatusPill(driving: Boolean, car: String?) {
    val (txt, col) = when {
        car == null -> "Nie skonfigurowano" to C.Orange
        driving -> "W trasie" to C.Green
        else -> "Zaparkowane" to C.Accent
    }
    val c by animateColorAsState(col, tween(400), label = "pill")
    val dot = rememberPulse(driving)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.15f)).animateContentSize().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).graphicsLayer { alpha = dot }.clip(CircleShape).background(c))
        Text(txt, fontSize = 13.sp, color = c, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SetupCard(perms: Perms, actions: Actions, car: String?, onPickCar: () -> Unit) {
    Card(border = C.Orange.copy(alpha = 0.5f)) {
        Text("Konfiguracja", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
        Text("Żeby aplikacja sama zapisywała miejsce po zgaszeniu auta, zezwól na:", fontSize = 14.sp, color = C.Muted)
        SetupRow(Icons.Rounded.Bluetooth, "Bluetooth", "Rozpoznanie radia w aucie", perms.bt) { actions.requestBt() }
        SetupRow(Icons.Rounded.DirectionsCar, "Wybierz auto", car ?: "Radio, z którym łączy się telefon", car != null) {
            if (!perms.bt) actions.requestBt() else onPickCar()
        }
        SetupRow(Icons.Rounded.MyLocation, "Lokalizacja", "Pozycja auta", perms.loc) { actions.requestLoc() }
        SetupRow(Icons.Rounded.LocationOn, "Lokalizacja: zawsze", "Wybierz „Zezwalaj zawsze” – zapis po zgaszeniu auta", perms.bgLoc) { actions.requestBgLoc() }
        SetupRow(Icons.Rounded.Notifications, "Powiadomienia", "Informacja, gdzie zaparkowano", perms.notif) { actions.requestNotif() }
        SetupRow(Icons.Rounded.BatteryChargingFull, "Bateria bez ograniczeń", "Inaczej system może uśpić aplikację", perms.battery) { actions.requestBattery() }
    }
}

@Composable
private fun SetupRow(icon: ImageVector, title: String, sub: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !ok, onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = if (ok) C.Muted else C.Text, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (ok) C.Muted else C.Text)
            Text(sub, fontSize = 13.sp, color = C.Muted)
        }
        if (ok) Icon(Icons.Rounded.CheckCircle, "OK", tint = C.Green, modifier = Modifier.size(24.dp))
        else Text("Zezwól", fontSize = 15.sp, color = C.Accent, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FinderCard(s: Store.Spot, me: Location?, azimuth: Float?) {
    Card {
        if (me == null) {
            Text("Włącz lokalizację, żeby zobaczyć kierunek i odległość do auta.", fontSize = 15.sp, color = C.Muted)
            return@Card
        }
        val target = Location("car").apply { latitude = s.lat; longitude = s.lon }
        val dist = me.distanceTo(target)
        val bearing = me.bearingTo(target)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).surface(CircleShape, C.Raised), contentAlignment = Alignment.Center) {
                if (dist < 15) {
                    Icon(Icons.Rounded.DirectionsCar, null, tint = C.Green, modifier = Modifier.size(48.dp))
                } else if (azimuth != null) {
                    Icon(Icons.Rounded.Navigation, "Kierunek do auta", tint = C.Accent, modifier = Modifier.size(64.dp).rotate(bearing - azimuth))
                } else {
                    Icon(Icons.Rounded.Navigation, null, tint = C.Muted, modifier = Modifier.size(56.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(if (dist < 15) "Jesteś przy aucie" else distText(dist), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = C.Text)
                Text(
                    when {
                        dist < 15 -> "Auto powinno być obok"
                        azimuth != null -> "Idź w kierunku strzałki"
                        else -> "Kompas niedostępny"
                    },
                    fontSize = 14.sp, color = C.Muted,
                )
                if (me.accuracy > 25) Text("Słaby sygnał GPS (±${me.accuracy.roundToInt()} m)", fontSize = 13.sp, color = C.Orange)
            }
        }
    }
}

@Composable
private fun DevicePicker(actions: Actions, onDismiss: () -> Unit) {
    val devices = remember { actions.pairedDevices() }
    RadioPickerSheet(devices, onDismiss)
}

@Composable
fun UpdateItem() {
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val sub = when (val s = st) {
        Updater.State.Idle -> "Sprawdź, czy jest nowa wersja"
        Updater.State.Checking -> "Sprawdzam…"
        Updater.State.UpToDate -> "Masz najnowszą wersję"
        is Updater.State.Available -> "Dostępna wersja ${s.name} – dotknij, aby zainstalować"
        is Updater.State.Downloading -> "Pobieranie ${(s.progress * 100).roundToInt()}%"
        is Updater.State.Error -> s.msg
    }
    SettingItem(Icons.Rounded.SystemUpdate, "Aktualizacja", sub, highlight = st is Updater.State.Available) {
        if (st is Updater.State.Available) Updater.downloadAndInstall(ctx) else Updater.check(silent = false)
    }
    // co nowego w dostępnej wersji – zanim zainstalujesz
    (st as? Updater.State.Available)?.notes?.let { n ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).surface(RoundedCornerShape(16.dp), C.Card).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Co nowego w ${n.version}", fontSize = 13.sp, color = C.Accent, fontWeight = FontWeight.SemiBold)
            ChangelogBody(n)
        }
    }
    (st as? Updater.State.Downloading)?.let {
        LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth(), color = C.Accent)
    }
    // kanał beta 2.0
    val beta by Updater.beta.collectAsState()
    LaunchedEffect(Unit) { Updater.init(ctx) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).surface(RoundedCornerShape(16.dp), C.Card)
            .clickable { Updater.setBeta(ctx, !beta) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Wersja testowa 2.0 (beta)", fontSize = 16.sp, color = C.Text)
            Text(
                if (BuildConfig.BETA) "Masz wersję testową ${BuildConfig.VERSION_NAME}. Wyłączenie = aktualizacje stabilne; powrót do 1.x wymaga odinstalowania aplikacji"
                else "Nowe funkcje 2.0 wcześniej (mogą mieć błędy). Po włączeniu „Aktualizacja” pobierze wersję beta",
                fontSize = 13.sp, color = C.Muted,
            )
        }
        androidx.compose.material3.Switch(checked = beta, onCheckedChange = { Updater.setBeta(ctx, it) })
    }
    if (Store.pairing.collectAsState().value != null) CarCompatBanner(always = true)
}

/**
 * Zgodność z CAR OS w aucie (protokół 2): ostrzeżenie, gdy auto ma starszą / nowszą wersję
 * albo nie zna komendy wysłanej z telefonu.
 */
@Composable
fun CarCompatBanner(always: Boolean = false) {
    val car by CarData.car.collectAsState()
    val nack by CarData.nack.collectAsState()
    val c = car
    val (title, sub, warn) = when {
        nack != null -> Triple(
            "CAR OS w aucie nie zna funkcji „$nack”",
            "Zaktualizuj CAR OS w radiu: Ustawienia → Aktualizacja (dla 2.0 włącz „Wersja testowa 2.0”)", true,
        )
        c == null -> if (always) Triple("Auto", "Brak danych z CAR OS – odśwież połączenie", false) else return
        // stare CAR OS 1.x w zakładce Auto nie straszy – widać to w ustawieniach
        c.older && !always -> return
        c.older -> Triple(
            "CAR OS w aucie: ${c.app ?: "1.x"} (protokół ${c.pv})",
            "Telefon ma nowszą wersję ${BuildConfig.VERSION_NAME}. Część funkcji zadziała po aktualizacji CAR OS w radiu do 2.0", true,
        )
        c.newer -> Triple(
            "CAR OS w aucie: ${c.app} (protokół ${c.pv})",
            "Auto ma nowszą wersję – zaktualizuj CAR OS Connect (Ustawienia → Aktualizacja)", true,
        )
        else -> if (always) Triple("CAR OS w aucie: ${c.app ?: "?"}", "Protokół ${c.pv} – telefon i auto są zgodne", false) else return
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (warn) C.Orange.copy(alpha = 0.16f) else C.Green.copy(alpha = 0.12f))
            .border(1.dp, if (warn) C.Orange.copy(alpha = 0.6f) else C.Green.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (warn) Icons.Rounded.SystemUpdate else Icons.Rounded.Check, null, tint = if (warn) C.Orange else C.Green, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text(sub, fontSize = 12.sp, color = C.Text2)
        }
        if (nack != null) Text(
            "OK", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Accent,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { CarData.setNack(null) }.padding(8.dp),
        )
    }
}

/** Ostatnia mila: auto zaparkowane niedaleko celu – „Prowadź pieszo” (do godziny po zaparkowaniu). */
@Composable
fun LastMileCard() {
    val o by LastMile.offer.collectAsState()
    val of = o ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    if (now - of.parkTime > 3600_000L) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).surface(RoundedCornerShape(16.dp), C.Card).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.DirectionsWalk, null, tint = C.Accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("Do celu pieszo: ${of.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${WalkNav.distText(of.meters.toDouble())} od auta · ok. ${LastMile.minutes(of.meters)} min chodnikami", fontSize = 12.sp, color = C.Text2)
        }
        Text(
            "Prowadź", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Bg,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Accent).clickable { WalkNav.startToDest(of.name, of.lat, of.lon) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Icon(
            Icons.Rounded.Close, "Ukryj", tint = C.Muted,
            modifier = Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { LastMile.dismiss() }.padding(6.dp),
        )
    }
}

/**
 * Jazda na żywo przez link: włącz (do celu / 1 h / 4 h) – telefon od razu proponuje wysłanie linku;
 * gdy trwa – „Wyślij link” i „Zakończ”. Tylko z CAR OS 2.0 w aucie.
 */
@Composable
fun LiveShareCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val car by CarData.car.collectAsState()
    val pairing by Store.pairing.collectAsState()
    val info by TripShare.info.collectAsState()
    var pick by remember { mutableStateOf(false) }
    if (pairing == null || car?.supports(pl.mati.caros.core.Proto.Cmd.SHARE) != true) return
    val i = info?.takeIf { it.until > System.currentTimeMillis() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).surface(RoundedCornerShape(16.dp), C.Card)
            .clickable(enabled = i == null) { pick = true }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Share, null, tint = if (i != null) C.Green else C.Accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            if (i == null) {
                Text("Udostępnij jazdę na żywo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text("Link: bliscy widzą auto, trasę i przyjazd w przeglądarce", fontSize = 12.sp, color = C.Text2)
            } else {
                Text("Udostępniasz jazdę na żywo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text(
                    if (i.toDest) "Do dojechania do celu" else "Do " + java.text.SimpleDateFormat("H:mm", Locale("pl")).format(java.util.Date(i.until)),
                    fontSize = 12.sp, color = C.Text2,
                )
            }
        }
        if (i != null) {
            Text(
                "Wyślij link", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { try { ctx.startActivity(TripShare.chooser(i.link)) } catch (_: Exception) {} }.padding(8.dp),
            )
            Text(
                "Zakończ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF5A5F),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    scope.launch { withContext(kotlinx.coroutines.Dispatchers.IO) { try { TripShare.stop(ctx) } catch (_: Exception) {} } }
                }.padding(8.dp),
            )
        }
    }
    if (pick) LiveSharePicker { pick = false }
}

/** Panel: jazda na żywo do celu / 1 h / 4 h – potem od razu „Udostępnij” z linkiem. */
@Composable
fun LiveSharePicker(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // panel znika po wyborze – praca (klucz, wysyłka, „Udostępnij”) nie może zginąć razem z nim
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main) }
    var min by remember { mutableStateOf(0) }
    val now = remember { System.currentTimeMillis() }
    val hhmm = { ms: Long -> java.text.SimpleDateFormat("HH:mm", java.util.Locale("pl")).format(java.util.Date(ms)) }
    val options = listOf(
        Triple(0, Icons.Rounded.Flag, "Do celu" to "Kończy się samo po dojechaniu · bez trasy w aucie – 2 h"),
        Triple(60, Icons.Rounded.Timer, "Na 1 godzinę" to "do ${hhmm(now + 3600_000L)}"),
        Triple(240, Icons.Rounded.Schedule, "Na 4 godziny" to "do ${hhmm(now + 4 * 3600_000L)}"),
    )
    CarSheet(
        "Udostępnij jazdę na żywo", onDismiss, "Bliscy widzą auto, trasę i przyjazd w przeglądarce – bez aplikacji", Icons.Rounded.Share,
        bottom = { ctl ->
            SheetButton("Udostępnij link", Icons.Rounded.Share, sub = options.first { it.first == min }.third.first + " · SMS, WhatsApp…") {
                val m = min
                ctl.close()
                scope.launch {
                    val r = withContext(kotlinx.coroutines.Dispatchers.IO) { try { TripShare.start(ctx, m) } catch (_: Exception) { null } }
                    if (r == null) {
                        android.widget.Toast.makeText(ctx, "Nie udało się – połącz z CAR OS", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        if (r.second.state == Outbox.State.QUEUED) android.widget.Toast.makeText(ctx, "Brak internetu – auto zacznie udostępniać, gdy wróci", android.widget.Toast.LENGTH_LONG).show()
                        try { ctx.startActivity(TripShare.chooser(r.first)) } catch (_: Exception) {}
                    }
                }
            }
        },
    ) {
        Text("Jak długo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
        options.forEach { (m, icon, t) ->
            val on = m == min
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .then(if (on) Modifier.background(C.Accent.copy(alpha = 0.14f)).border(1.5.dp, C.Accent, shape) else Modifier.surface(shape, C.Card, border = C.Border))
                    .clickable { min = m }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) C.Accent else C.Raised), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (on) C.Bg else C.Accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(t.first, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                    Text(t.second, fontSize = 13.sp, color = C.Muted)
                }
                Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) C.Accent else C.Muted, modifier = Modifier.size(24.dp))
            }
        }
        // co zobaczy osoba z linkiem
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).surface(RoundedCornerShape(20.dp), C.Card, border = C.Border).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Co zobaczy osoba z linkiem", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            listOf(
                Icons.Rounded.DirectionsCar to "Auto na mapie na żywo i godzinę przyjazdu",
                Icons.Rounded.Route to "Trasę z nawigacji – znika, gdy zakończysz trasę",
                Icons.Rounded.Navigation to "„Jedź za autem” i „Prowadź do celu” – nawigacja w przeglądarce, głosem",
                Icons.Rounded.Lock to "Link zaszyfrowany – klucz jest tylko w linku, wygasa sam",
            ).forEach { (ic, txt) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(ic, null, tint = C.Accent, modifier = Modifier.size(20.dp))
                    Text(txt, fontSize = 14.sp, color = C.Text2)
                }
            }
        }
    }
}

/**
 * Ostatnio wysłane do auta (protokół 2): czeka na internet / wysłano / auto wykonało / błąd.
 * Pokazuje do 4 komend z ostatnich 12 h.
 */
@Composable
fun OutboxCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by Outbox.items.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val list = all.filter { now - it.time < 12 * 3600_000L }.take(4)
    if (list.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).surface(RoundedCornerShape(16.dp), C.Card).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Wysłane do auta", fontSize = 13.sp, color = C.Muted)
        list.forEach { it ->
            val (icon, tint) = when (it.state) {
                Outbox.State.QUEUED -> Icons.Rounded.CloudOff to C.Orange
                Outbox.State.SENT -> Icons.Rounded.Schedule to C.Accent
                Outbox.State.DONE -> Icons.Rounded.DoneAll to C.Green
                Outbox.State.FAILED -> Icons.Rounded.ErrorOutline to Color(0xFFFF5A5F)
                Outbox.State.UNKNOWN -> Icons.Rounded.SystemUpdate to C.Orange
                Outbox.State.NOACK -> Icons.Rounded.Check to C.Muted
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(it.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Outbox.stateText(it) + " · " + agoText(if (it.doneAt > 0) it.doneAt else it.time), fontSize = 12.sp, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (it.state == Outbox.State.QUEUED) Text(
                    "Wyślij", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = C.Accent,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        scope.launch {
                            val left = Outbox.flushAsync(ctx)
                            if (left > 0) android.widget.Toast.makeText(ctx, "Nadal brak internetu – wyślę sama, gdy wróci", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }.padding(8.dp),
                )
                else if (it.state != Outbox.State.SENT) Icon(
                    Icons.Rounded.Close, "Ukryj", tint = C.Muted,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable { Outbox.dismiss(it.mid) }.padding(6.dp),
                )
            }
        }
    }
}

/** Tytuł i punkty jednego wydania. */
@Composable
fun ChangelogBody(e: Changelog.Entry) {
    e.title?.let { Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text) }
    e.points.forEach { p ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("•", fontSize = 14.sp, color = C.Accent)
            Text(p, fontSize = 14.sp, color = C.Text2)
        }
    }
}

/** Pełna lista zmian. */
@Composable
fun ChangelogDialog(onDismiss: () -> Unit) = ChangelogSheet(onDismiss)

@Composable
private fun ChangelogVersion(e: Changelog.Entry, badge: String?, badgeColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wersja ${e.version}", fontSize = 13.sp, color = C.Muted, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            e.date?.let { Text(it, fontSize = 12.sp, color = C.Muted) }
            badge?.let {
                Text(
                    it, fontSize = 11.sp, color = badgeColor, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(badgeColor.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        ChangelogBody(e)
    }
}

/** Okno po aktualizacji: co zostało wprowadzone w tej wersji. */
@Composable
fun WhatsNewDialog() {
    val e by Changelog.whatsNew.collectAsState()
    val entry = e ?: return
    var all by remember { mutableStateOf(false) }
    if (all) {
        ChangelogSheet { all = false; Changelog.markSeen() }
        return
    }
    WhatsNewSheet(entry, onAll = { all = true }) { Changelog.markSeen() }
}

@Composable
fun SettingItem(icon: ImageVector, title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.stagger().pressScale(src, 0.98f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .then(if (highlight) Modifier.background(C.Accent.copy(alpha = 0.14f)) else Modifier.surface(RoundedCornerShape(20.dp), C.Card, border = C.Border))
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(C.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = C.Accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            Text(sub, fontSize = 13.sp, color = C.Muted)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun Card(border: Color = C.Border, content: @Composable () -> Unit) {
    Column(
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(24.dp)).surface(RoundedCornerShape(24.dp), C.Card, border = border).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { androidx.compose.runtime.CompositionLocalProvider(LocalGlassDepth provides LocalGlassDepth.current + 1) { content() } }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    // wygląd 2.0: nagłówek sekcji jak w iOS / Material 3 – zwykłe litery, większy, wyraźny
    Text(text, fontSize = 20.sp, color = C.Text, fontWeight = FontWeight.Bold, modifier = modifier.stagger().padding(top = 4.dp, start = 2.dp))
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.stagger().pressScale(src).fillMaxWidth().height(58.dp).clip(RoundedCornerShape(if (C.isGlass) 29.dp else 20.dp))
            .then(
                if (C.isGlass) Modifier.surface(RoundedCornerShape(29.dp), C.Raised).background(C.Accent.copy(alpha = 0.82f))
                else Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(C.Accent, androidx.compose.ui.graphics.lerp(C.Accent, Color.White, 0.22f)))),
            )
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = C.Bg, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Bg)
    }
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, h: Dp = 54.dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(src).height(h).clip(RoundedCornerShape(if (C.isGlass) h / 2 else 16.dp)).surface(RoundedCornerShape(if (C.isGlass) h / 2 else 16.dp), C.Raised)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = C.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
    }
}

// ---------- mapa ----------

/** Pinezka „P” w kolorze CAR OS. */
fun carPin(): Bitmap = pinBitmap("P")

/** Pinezka z literą / znakiem (P – auto, ⚑ – cel pieszo). */
fun pinBitmap(label: String): Bitmap {
    val w = 84
    val h = 104
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.rgb(47, 211, 240)
    val path = android.graphics.Path().apply {
        moveTo(w / 2f, h.toFloat())
        lineTo(w / 2f - 16, 70f)
        lineTo(w / 2f + 16, 70f)
        close()
    }
    c.drawPath(path, p)
    c.drawCircle(w / 2f, 42f, 40f, p)
    p.color = android.graphics.Color.rgb(10, 12, 15)
    c.drawCircle(w / 2f, 42f, 32f, p)
    p.color = android.graphics.Color.WHITE
    p.textSize = 42f
    p.isFakeBoldText = true
    p.textAlign = Paint.Align.CENTER
    c.drawText(label, w / 2f, 57f, p)
    return b
}

// ---------- pozycja i kompas ----------

/** Czy aplikacja jest na ekranie (nie w tle). Kompozycja żyje też w tle, więc sprawdzamy cykl życia. */
@Composable
private fun rememberVisible(): Boolean {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var v by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            v = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return v
}

@SuppressLint("MissingPermission")
@Composable
internal fun rememberPhoneLocation(enabled: Boolean): Location? {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val lm = ctx.getSystemService(LocationManager::class.java)
        loc = Locator.lastKnown(ctx)
        val l = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                val prev = loc
                if (prev == null || l.provider == LocationManager.GPS_PROVIDER || l.time - prev.time > 15_000) loc = l
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        try { lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, l, Looper.getMainLooper()) } catch (_: Exception) {}
        onDispose { try { lm?.removeUpdates(l) } catch (_: Exception) {} }
    }
    return loc
}

/** Azymut telefonu (stopnie od północy geograficznej) z czujnika obrotu. */
@Composable
internal fun rememberAzimuth(me: Location?, enabled: Boolean): Float? {
    val ctx = LocalContext.current
    var az by remember { mutableStateOf<Float?>(null) }
    var smooth by remember { mutableFloatStateOf(Float.NaN) }
    val declination = remember(me?.latitude?.toInt(), me?.longitude?.toInt()) {
        me?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination } ?: 0f
    }
    DisposableEffect(declination, enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                val deg = (Math.toDegrees(ori[0].toDouble()).toFloat() + declination + 360f) % 360f
                // wygładzanie z obsługą przejścia 359° → 0°
                smooth = if (smooth.isNaN()) deg else {
                    var d = deg - smooth
                    if (d > 180) d -= 360
                    if (d < -180) d += 360
                    (smooth + d * 0.15f + 360f) % 360f
                }
                az = smooth
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return az
}

/** „Co się działo przy parkowaniu” – otwiera dziennik zdarzeń z filtrem „Parkowanie”. */
@Composable
private fun ParkLogLink() {
    val log by Store.parkLog.collectAsState()
    Text(
        "Dziennik parkowania" + (log.firstOrNull()?.let { " · ostatnio: " + it.substringAfter("  ").take(40) } ?: ""),
        fontSize = 13.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { LogPage.show(EventLog.Cat.PARK) }.padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

/** Zaplanowany wyjazd (z „Wyślij do auta”): kiedy wyjechać, przypomnienie, odwołanie. */
@Composable
private fun TripPlanCard() {
    val ctx = LocalContext.current
    val p by TripPlan.plan.collectAsState()
    val pl = p ?: return
    if (pl.arrive < System.currentTimeMillis()) return
    Card(border = C.Accent.copy(alpha = 0.5f)) {
        Text("Zaplanowany wyjazd", fontSize = 13.sp, color = C.Muted)
        Text(pl.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "Wyjedź o ${RoutePreview.hhmm(pl.depart)} · przyjazd ${RoutePreview.hhmm(pl.arrive)}" +
                (pl.delayS?.let { if (it >= 60) " · korki +${it / 60} min" else "" } ?: ""),
            fontSize = 15.sp, color = C.Accent, fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pl.remindAt > System.currentTimeMillis()) "Przypomnienie o ${RoutePreview.hhmm(pl.remindAt)} – sprawdzę wtedy korki jeszcze raz" else "Przypomnienie wysłane",
                fontSize = 13.sp, color = C.Muted, modifier = Modifier.weight(1f),
            )
            Text("Odwołaj", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { TripPlan.cancel(ctx) }.padding(8.dp))
        }
    }
}

