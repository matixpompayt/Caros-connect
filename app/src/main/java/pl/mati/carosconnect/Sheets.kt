package pl.mati.carosconnect

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Sterowanie panelem z jego treści: [close] chowa panel animacją i dopiero potem zamyka. */
class SheetCtl internal constructor(private val hide: () -> Unit) {
    fun close() = hide()
}

/**
 * Panel wysuwany od dołu w stylu ekranu Start: uchwyt, ikona i duży tytuł, przewijana treść
 * i (opcjonalnie) duży przycisk w kapsule na dole. Zamykanie: pociągnij w dół, ✕ albo „wstecz”.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarSheet(
    title: String, onDismiss: () -> Unit,
    subtitle: String? = null, icon: ImageVector? = null,
    bottom: (@Composable (SheetCtl) -> Unit)? = null,
    content: @Composable ColumnScope.(SheetCtl) -> Unit,
) {
    if (LocalInspectionMode.current) {
        // zrzuty ekranu: okno panelu nie rysuje się w testach – panel na przyciemnionym tle
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(sheetBg()),
            ) {
                Handle()
                SheetBody(title, subtitle, icon, SheetCtl {}, onDismiss, bottom, content)
            }
        }
        return
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ctl = remember(state) {
        SheetCtl { scope.launch { try { state.hide() } catch (_: Exception) {} }.invokeOnCompletion { onDismiss() } }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = sheetBg(), contentColor = C.Text, tonalElevation = 0.dp,
        dragHandle = { Handle() },
    ) {
        SheetBody(title, subtitle, icon, ctl, { ctl.close() }, bottom, content)
    }
}

private fun sheetBg() = if (C.isGlass) C.Dialog else C.Bg

@Composable
private fun Handle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(42.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(C.Muted.copy(alpha = 0.45f)))
    }
}

@Composable
private fun SheetBody(
    title: String, subtitle: String?, icon: ImageVector?, ctl: SheetCtl, onClose: () -> Unit,
    bottom: (@Composable (SheetCtl) -> Unit)?, content: @Composable ColumnScope.(SheetCtl) -> Unit,
) {
    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(C.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = C.Accent, modifier = Modifier.size(26.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 14.sp, color = C.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).surface(CircleShape, C.Raised).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Zamknij", tint = C.Text2, modifier = Modifier.size(20.dp)) }
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content(ctl) }
        if (bottom != null) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) { bottom(ctl) }
        else Box(Modifier.navigationBarsPadding().height(12.dp))
    }
}

/** Duży przycisk w kapsule – jak „Nawiguj w aucie” (zamiast paska zakładek). */
@Composable
fun SheetButton(
    text: String, icon: ImageVector, enabled: Boolean = true, busy: Boolean = false, sub: String? = null,
    color: Color = C.Accent, onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(33.dp))
            .surface(RoundedCornerShape(33.dp), C.Card, border = C.Border).padding(5.dp),
    ) {
        Row(
            Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.45f).pressScale(src, 0.97f).clip(RoundedCornerShape(28.dp))
                .background(Brush.horizontalGradient(listOf(color, lerp(color, Color.White, 0.22f))))
                .clickable(enabled = enabled && !busy, interactionSource = src, indication = null, onClick = onClick)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (busy) CircularProgressIndicator(color = C.Bg, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else Icon(icon, null, tint = C.Bg, modifier = Modifier.size(24.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Bg, maxLines = 1)
                if (!sub.isNullOrBlank()) Text(sub, fontSize = 12.sp, color = C.Bg.copy(alpha = 0.8f), maxLines = 1)
            }
        }
    }
}

/** Mniejszy przycisk tekstowy pod kapsułą / w treści panelu (np. „Usuń”). */
@Composable
fun SheetTextButton(text: String, icon: ImageVector, color: Color = C.Text2, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Pole tekstowe w stylu paneli. */
@Composable
fun SheetField(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    minLines: Int = 1, keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(placeholder, color = C.Muted) },
        modifier = modifier.fillMaxWidth(), minLines = minLines, singleLine = minLines == 1,
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = keyboard,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
            focusedContainerColor = C.Card, unfocusedContainerColor = C.Card,
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
        ),
    )
}

/** Mały wybór (pigułka) – szybkie wartości / podpowiedzi. */
@Composable
fun SheetChip(label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(14.dp))
            .then(if (on) Modifier.background(C.Accent) else Modifier.surface(RoundedCornerShape(14.dp), C.Raised))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Bg else C.Text, maxLines = 1) }
}

// ======================= panele =======================

/** Pojemność baku: duża liczba z −/+, szybkie wartości. Zapis w telefonie i w CAR OS. */
@Composable
fun TankSheet(cur: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var v by remember { mutableStateOf(cur.coerceIn(20, 150)) }
    CarSheet(
        "Pojemność baku", onDismiss, "Od niej zależy zasięg i ile paliwa zostało", Icons.Rounded.LocalGasStation,
        bottom = { ctl -> SheetButton("Zapisz $v l", Icons.Rounded.Check, sub = if (v != cur) "było $cur l" else null) { onSave(v); ctl.close() } },
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            StepBtn(Icons.Rounded.Remove, "Mniej", v > 20) { v = (v - 1).coerceAtLeast(20) }
            Row(Modifier.width(170.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text("$v", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1)
                Text(" l", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = C.Text2, modifier = Modifier.padding(bottom = 12.dp))
            }
            StepBtn(Icons.Rounded.Add, "Więcej", v < 150) { v = (v + 1).coerceAtMost(150) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(40, 45, 50, 55, 60, 65, 70, 80, 90).forEach { n -> SheetChip("$n l", v == n) { v = n } }
        }
        Text("Touran 2.0 TDI ma bak 60 l. Zasięg liczy się od pełnego baku i średniego spalania z auta.", fontSize = 13.sp, color = C.Muted)
    }
}

@Composable
private fun StepBtn(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(58.dp).alpha(if (enabled) 1f else 0.4f).pressScale(src, 0.88f).clip(CircleShape).surface(CircleShape, C.Raised, border = C.Border)
            .clickable(enabled = enabled, interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = C.Text, modifier = Modifier.size(28.dp)) }
}

/** Notatka do parkowania (+ zdjęcie miejsca): pole, szybkie podpowiedzi, zdjęcie. */
@Composable
fun NoteSheet(
    note: String, address: String?, photo: ImageBitmap?, onDismiss: () -> Unit,
    onShoot: () -> Unit, onDeletePhoto: () -> Unit, onSave: (String) -> Unit,
) {
    var t by remember { mutableStateOf(note) }
    CarSheet(
        "Notatka do parkowania", onDismiss, address ?: "Gdzie dokładnie stoi auto", Icons.Rounded.EditNote,
        bottom = { ctl -> SheetButton("Zapisz notatkę", Icons.Rounded.Check) { onSave(t.trim()); ctl.close() } },
    ) {
        SheetField(t, { t = it.take(200) }, "np. poziom -2, miejsce 47, przy windzie", minLines = 3)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Poziom -1", "Poziom -2", "Miejsce ", "Sektor ", "Przy windzie", "Przy wejściu", "Pod drzewem").forEach { s ->
                SheetChip(s.trim()) { t = (if (t.isBlank()) s else t.trimEnd().trimEnd(',') + ", " + s.replaceFirstChar { it.lowercase() }).take(200) }
            }
        }
        // zdjęcie miejsca
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).surface(RoundedCornerShape(20.dp), C.Card, border = C.Border).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Zdjęcie miejsca", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
            if (photo != null) androidx.compose.foundation.Image(
                photo, "Zdjęcie miejsca", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(14.dp)),
            ) else Text("Zdjęcie słupka albo numeru miejsca pomaga na dużym parkingu", fontSize = 13.sp, color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(if (photo == null) "Zrób zdjęcie" else "Nowe zdjęcie", Icons.Rounded.PhotoCamera, Modifier.weight(1f), h = 48.dp, onClick = onShoot)
                if (photo != null) SecondaryButton("Usuń", Icons.Rounded.Delete, Modifier.weight(0.6f), h = 48.dp, onClick = onDeletePhoto)
            }
        }
    }
}

/** Wybór radia w aucie (sparowane urządzenia Bluetooth). */
@Composable
fun RadioPickerSheet(devices: List<Pair<String, String>>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val curMac by Store.carMac.collectAsState()
    CarSheet(
        "Wybierz radio w aucie", onDismiss, "Po nim telefon rozpozna jazdę i zapisze miejsce parkowania", Icons.Rounded.Bluetooth,
    ) { ctl ->
        if (devices.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).surface(RoundedCornerShape(20.dp), C.Card, border = C.Border).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.BluetoothSearching, null, tint = C.Muted, modifier = Modifier.size(40.dp))
                Text("Brak sparowanych urządzeń", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text("Sparuj telefon z radiem w ustawieniach Bluetooth i wróć tutaj.", fontSize = 14.sp, color = C.Muted)
            }
        }
        // najpierw te, które wyglądają na radio / auto
        val carLike = Regex("(?i)car|auto|radio|ts10|topway|android|mazda|vw|audi|skoda|seat|ford|opel|bmw|toyota|kia|hyundai|renault|peugeot|citro|fiat|dodge|uconnect|mmi|media|head")
        devices.sortedByDescending { (n, m) -> (if (m == curMac) 2 else 0) + (if (carLike.containsMatchIn(n)) 1 else 0) }.forEach { (name, mac) ->
            val on = mac == curMac
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().pressScale(src, 0.98f).clip(RoundedCornerShape(20.dp))
                    .then(if (on) Modifier.background(C.Accent.copy(alpha = 0.14f)).border(1.5.dp, C.Accent, RoundedCornerShape(20.dp)) else Modifier.surface(RoundedCornerShape(20.dp), C.Card, border = C.Border))
                    .clickable(interactionSource = src, indication = null) {
                        Store.setCar(mac, name)
                        Toast.makeText(ctx, "Radio w aucie: $name", Toast.LENGTH_SHORT).show()
                        ctl.close()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) C.Accent else C.Raised), contentAlignment = Alignment.Center) {
                    Icon(if (carLike.containsMatchIn(name)) Icons.Rounded.DirectionsCar else Icons.Rounded.Bluetooth, null, tint = if (on) C.Bg else C.Accent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (on) "wybrane · $mac" else mac, fontSize = 12.sp, color = if (on) C.Accent else C.Muted, maxLines = 1)
                }
                if (on) Icon(Icons.Rounded.CheckCircle, null, tint = C.Accent, modifier = Modifier.size(24.dp))
            }
        }
        SheetTextButton("Ustawienia Bluetooth", Icons.Rounded.Settings, C.Accent) {
            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (_: Exception) {}
        }
    }
}

/** Pełna lista zmian – każda wersja na osobnej karcie; dostępna aktualizacja na górze. */
@Composable
fun ChangelogSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val list by Changelog.entries.collectAsState()
    val upd by Updater.state.collectAsState()
    val avail = upd as? Updater.State.Available
    val next = avail?.notes
    CarSheet(
        "Co nowego", onDismiss, "CAR OS Connect ${BuildConfig.VERSION_NAME}", Icons.Rounded.NewReleases,
        bottom = if (avail != null) {
            { _ -> SheetButton("Zainstaluj ${avail.name}", Icons.Rounded.SystemUpdate, sub = "nowa wersja jest gotowa") { Updater.downloadAndInstall(ctx) } }
        } else null,
    ) {
        if (next != null && list.none { it.version == next.version }) VersionCard(next, "do pobrania", C.Orange)
        list.forEach { e -> VersionCard(e, if (e.version == BuildConfig.VERSION_NAME) "zainstalowana" else null, C.Accent) }
    }
}

/** Raz po aktualizacji: co weszło w tej wersji. */
@Composable
fun WhatsNewSheet(entry: Changelog.Entry, onAll: () -> Unit, onDone: () -> Unit) {
    CarSheet(
        "Nowość w wersji ${entry.version}", onDone, entry.date, Icons.Rounded.NewReleases,
        bottom = { ctl ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SheetButton("Super", Icons.Rounded.Check) { ctl.close() }
                SheetTextButton("Wszystkie zmiany", Icons.Rounded.NewReleases, C.Accent, Modifier.padding(top = 4.dp), onAll)
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).surface(RoundedCornerShape(20.dp), C.Card, border = C.Border).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { ChangelogBody(entry) }
    }
}

@Composable
private fun VersionCard(e: Changelog.Entry, badge: String?, badgeColor: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .then(if (badge != null) Modifier.background(badgeColor.copy(alpha = 0.08f)).border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp)) else Modifier.surface(RoundedCornerShape(20.dp), C.Card, border = C.Border))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.version, fontSize = 15.sp, color = C.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
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
