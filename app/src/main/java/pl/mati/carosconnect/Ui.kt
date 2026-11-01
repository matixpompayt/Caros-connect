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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
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
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) C.Accent.copy(alpha = 0.16f) else C.Card)
                    .border(1.dp, if (on) C.Accent else C.Border, RoundedCornerShape(14.dp))
                    .clickable { Store.setTheme(k) }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) C.Accent else C.Text) }
        }
    }
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
    val light = when (themeMode) { "light" -> true; "dark" -> false; else -> !sysDark }
    if (C.pal.light != light) C.pal = if (light) C.LIGHT else C.DARK
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }
    val scheme = if (light) androidx.compose.material3.lightColorScheme(primary = C.Accent, background = C.Bg, surface = C.Card)
    else darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Card)
    MaterialTheme(colorScheme = scheme) {
        val ctx = LocalContext.current
        val driving by Store.driving.collectAsState()
        val car by Store.carName.collectAsState()
        val pairing by Store.pairing.collectAsState()
        val skipped by Store.skippedPairing.collectAsState()
        val share by Share.incoming.collectAsState()
        var picker by remember { mutableStateOf(false) }
        var unpairAsk by remember { mutableStateOf(false) }

        if (pairing == null && !skipped) {
            WelcomeScreen(actions)
            share?.let { ShareDialog(it, actions) { Share.incoming.value = null } }
            return@MaterialTheme
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var trip by remember { mutableStateOf<CarData.Trip?>(null) }
        BackHandler(enabled = trip != null || tab != 0) { if (trip != null) trip = null else tab = 0 }

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
        Column(Modifier.fillMaxSize().background(C.Bg)) {
            Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(pull.nestedScrollConnection)) {
                val scroll = remember(tab, trip) { ScrollState(0) }
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // nagłówek
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("CAR OS CONNECT", fontSize = 13.sp, color = C.Accent, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                            Text(pairing?.car ?: "Moje auto", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            pairing?.driver?.takeIf { it.isNotBlank() }?.let { Text("Cześć, $it", fontSize = 15.sp, color = C.Muted) }
                        }
                        StatusPill(driving, car)
                    }
                    AnimatedContent(
                        targetState = tab to trip,
                        transitionSpec = {
                            val (t0, tr0) = initialState
                            val (t1, tr1) = targetState
                            // w prawo/lewo zgodnie z kolejnością zakładek; szczegóły trasy wjeżdżają z prawej
                            val dir = when {
                                t1 != t0 -> if (t1 > t0) 1 else -1
                                tr1 != null && tr0 == null -> 1
                                else -> -1
                            }
                            (fadeIn(tween(250, delayMillis = 60)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { w -> dir * w / 6 }) togetherWith
                                (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { w -> -dir * w / 8 })
                        },
                        label = "tab",
                    ) { (t, tr) ->
                        CompositionLocalProvider(LocalStagger provides remember { StaggerCounter() }) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                when (t) {
                                    0 -> ParkingTab(perms, actions) { picker = true }
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
                    containerColor = C.Raised,
                    contentColor = C.Accent,
                )
            }
            BottomBar(tab) { tab = it; trip = null }
        }

        if (picker) DevicePicker(actions, onDismiss = { picker = false })
        if (unpairAsk) AlertDialog(
            onDismissRequest = { unpairAsk = false },
            containerColor = C.Card,
            title = { Text("Rozłączyć z CAR OS?", color = C.Text) },
            text = { Text("Zapisane miejsca zostaną w telefonie. Żeby połączyć ponownie, zeskanuj kod z radia.", color = C.Muted) },
            confirmButton = { TextButton(onClick = { Store.setPairing(null); unpairAsk = false }) { Text("Rozłącz") } },
            dismissButton = { TextButton(onClick = { unpairAsk = false }) { Text("Anuluj") } },
        )
        share?.let { ShareDialog(it, actions) { Share.incoming.value = null } }
        WhatsNewDialog()
    }
}

@Composable
private fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(
        Icons.Rounded.LocalParking to "Parking",
        Icons.Rounded.DirectionsCar to "Auto",
        Icons.Rounded.Map to "Trasy",
        Icons.Rounded.Settings to "Ustawienia",
    )
    Row(
        Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        items.forEachIndexed { i, (icon, label) ->
            val on = i == tab
            val src = remember { MutableInteractionSource() }
            val pill by animateColorAsState(if (on) C.Accent.copy(alpha = 0.16f) else Color.Transparent, tween(250), label = "pill")
            val tint by animateColorAsState(if (on) C.Accent else C.Muted, tween(250), label = "tint")
            val padH by animateDpAsState(if (on) 20.dp else 12.dp, spring(dampingRatio = 0.6f, stiffness = 500f), label = "padH")
            val iconScale by animateFloatAsState(if (on) 1.12f else 1f, spring(dampingRatio = 0.4f, stiffness = 450f), label = "iconScale")
            Column(
                Modifier.weight(1f).pressScale(src, 0.9f).clip(RoundedCornerShape(16.dp))
                    .clickable(interactionSource = src, indication = null) { onTab(i) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(pill).padding(horizontal = padH, vertical = 4.dp),
                ) {
                    Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale })
                }
                Text(label, fontSize = 12.sp, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Zakładka „Parking”: gdzie stoi auto, kompas, parkometr, pogoda przy aucie, historia. */
@Composable
private fun ParkingTab(perms: Perms, actions: Actions, onPickCar: () -> Unit) {
    val ctx = LocalContext.current
    val spot by Store.spot.collectAsState()
    val driving by Store.driving.collectAsState()
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

        if (!perms.allOk || car == null) SetupCard(perms, actions, car) { onPickCar() }

        // auto w drodze (ktoś jedzie autem) – pozycja co minutę z CAR OS
        val live by CarData.live.collectAsState()
        val lv = live
        val liveActive = lv != null && lv.moving && System.currentTimeMillis() + 0 * tick - lv.time < 10 * 60_000L &&
            (spot == null || lv.time > (spot?.time ?: 0L))
        LaunchedEffect(liveActive, visible) {
            while (liveActive && visible) {
                delay(30_000)
                actions.refreshFromCar()
            }
        }
        if (liveActive && lv != null) LiveCard(lv, me)

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
            ParkMap(s, me, Modifier.stagger().fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, C.Border, RoundedCornerShape(24.dp)))
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
            PrimaryButton("Prowadź do auta", Icons.Rounded.DirectionsWalk) {
                try { ctx.startActivity(Notifs.walkIntent(s)) } catch (_: Exception) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.mapsUrl)))
                }
            }
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
                    Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card)
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
private fun LiveCard(l: CarData.Live, me: android.location.Location?) {
    val ctx = LocalContext.current
    val spot = remember(l.time) { Store.Spot(l.lat, l.lon, l.time, 0f, src = Store.SRC_CAR) }
    ParkMap(spot, me, Modifier.stagger().fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, C.Border, RoundedCornerShape(24.dp)))
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(C.Green), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DirectionsCar, null, tint = C.Bg, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Auto w drodze · ${l.speed} km/h", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text("Pozycja ${agoText(l.time)} · odświeża się co pół minuty", fontSize = 14.sp, color = C.Muted)
            }
        }
        SecondaryButton("Pokaż w Mapach", Icons.Rounded.Map, Modifier.fillMaxWidth()) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spot.mapsUrl))) } catch (_: Exception) {}
        }
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
        var t by remember { mutableStateOf(note) }
        AlertDialog(
            onDismissRequest = { editNote = false },
            containerColor = C.Card,
            title = { Text("Notatka do parkowania", color = C.Text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = t, onValueChange = { t = it.take(200) },
                        placeholder = { Text("np. poziom -2, miejsce 47, przy windzie") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
                            focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
                        ),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { Store.setNote(t.trim()); editNote = false }) { Text("Zapisz") } },
            dismissButton = { TextButton(onClick = { editNote = false }) { Text("Anuluj") } },
        )
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
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C.Hero, C.Bg, C.Bg)),
        ).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(132.dp).clip(CircleShape).background(C.Card).border(1.dp, C.Border, CircleShape), contentAlignment = Alignment.Center) {
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
        Box(Modifier.size(26.dp).clip(CircleShape).background(C.Raised), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = C.Accent)
        }
        Text(text, fontSize = 15.sp, color = C.Text2, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun StatusPill(driving: Boolean, car: String?) {
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
            Box(Modifier.size(96.dp).clip(CircleShape).background(C.Raised), contentAlignment = Alignment.Center) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("Wybierz radio w aucie", color = C.Text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) Text("Brak sparowanych urządzeń. Sparuj telefon z radiem w ustawieniach Bluetooth.", color = C.Muted)
                devices.forEach { (name, mac) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Store.setCar(mac, name); onDismiss() }.padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Bluetooth, null, tint = C.Accent, modifier = Modifier.size(22.dp))
                        Column {
                            Text(name, fontSize = 16.sp, color = C.Text)
                            Text(mac, fontSize = 12.sp, color = C.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Co nowego w ${n.version}", fontSize = 13.sp, color = C.Accent, fontWeight = FontWeight.SemiBold)
            ChangelogBody(n)
        }
    }
    (st as? Updater.State.Downloading)?.let {
        LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth(), color = C.Accent)
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
fun ChangelogDialog(onDismiss: () -> Unit) {
    val list by Changelog.entries.collectAsState()
    val upd by Updater.state.collectAsState()
    val next = (upd as? Updater.State.Available)?.notes
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("Co nowego", color = C.Text) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (next != null && list.none { it.version == next.version }) {
                    ChangelogVersion(next, "do pobrania", C.Orange)
                }
                list.forEach { e ->
                    ChangelogVersion(e, if (e.version == BuildConfig.VERSION_NAME) "zainstalowana" else null, C.Accent)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

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
        ChangelogDialog { all = false; Changelog.markSeen() }
        return
    }
    AlertDialog(
        onDismissRequest = { Changelog.markSeen() },
        containerColor = C.Card,
        title = { Text("Nowość w wersji ${entry.version}", color = C.Text) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChangelogBody(entry)
            }
        },
        confirmButton = { TextButton(onClick = { Changelog.markSeen() }) { Text("Super") } },
        dismissButton = { TextButton(onClick = { all = true }) { Text("Wszystkie zmiany") } },
    )
}

@Composable
fun SettingItem(icon: ImageVector, title: String, sub: String, highlight: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.stagger().pressScale(src, 0.98f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (highlight) C.Accent.copy(alpha = 0.14f) else C.Card)
            .clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = C.Accent, modifier = Modifier.size(24.dp))
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
        Modifier.stagger().fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.Card).border(1.dp, border, RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 13.sp, letterSpacing = 1.5.sp, color = C.Muted, fontWeight = FontWeight.SemiBold, modifier = modifier.stagger())
}

@Composable
fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.stagger().pressScale(src).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(C.Accent)
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
        modifier.pressScale(src).height(h).clip(RoundedCornerShape(16.dp)).background(C.Raised)
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
fun carPin(): Bitmap {
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
    c.drawText("P", w / 2f, 57f, p)
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
private fun rememberPhoneLocation(enabled: Boolean): Location? {
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
private fun rememberAzimuth(me: Location?, enabled: Boolean): Float? {
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
