package pl.mati.carosconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Nazwa wybranego auta (odświeża się przy zmianie auta, parowania i nazwy). */
@Composable
fun activeCarLabel(): String {
    val ctx = LocalContext.current
    val active by Garage.active.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by Garage.version.collectAsState()
    @Suppress("UNUSED_VARIABLE") val p by Store.pairing.collectAsState()
    @Suppress("UNUSED_VARIABLE") val bt by Store.carName.collectAsState()
    val cars by Garage.cars.collectAsState()
    return if (cars.size <= 1 && Store.pairing.value?.car == null && cars.firstOrNull()?.name.isNullOrBlank()) "Moje auto" else Garage.label(ctx, active)
}

/** Nazwa auta na Starcie – przy kilku autach ze strzałką: dotknij, żeby wybrać inne. */
@Composable
fun CarTitle(fontSize: TextUnit, actions: Actions, modifier: Modifier = Modifier) {
    val cars by Garage.cars.collectAsState()
    var pick by remember { mutableStateOf(false) }
    val many = cars.size > 1
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).then(if (many) Modifier.clickable { pick = true } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(activeCarLabel(), fontSize = fontSize, fontWeight = FontWeight.Bold, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (many) Icon(Icons.Rounded.KeyboardArrowDown, "Wybierz auto", tint = C.Accent, modifier = Modifier.size((fontSize.value * 1.1f).dp))
    }
    if (pick) CarPickerSheet(actions, manage = false) { pick = false }
}

/** Krótki opis auta: gdzie stoi / jedzie, zasięg. */
private fun carLine(ctx: android.content.Context, id: String): String {
    val active = id == Garage.active.value
    val spot = Garage.spotOf(ctx, id)
    val status = if (active) CarData.status.value
    else Garage.prefs(ctx, "car", id).getString("status", null)?.let { CarData.parse(it) }
    val range = status?.fuel?.range?.roundToInt()
    val live = if (active) CarData.live.value?.takeIf { it.moving && System.currentTimeMillis() - it.time < 10 * 60_000L }
    else Garage.prefs(ctx, "car", id).getString("live", null)?.let {
        try {
            val o = org.json.JSONObject(it)
            if (o.optBoolean("m") && System.currentTimeMillis() - o.optLong("t") < 10 * 60_000L) o.optInt("spd") else null
        } catch (_: Exception) { null }
    }
    val paired = Garage.pairingOf(ctx, id) != null
    val where = when {
        live != null -> "w drodze"
        spot != null -> spot.address?.substringBefore(',') ?: "zaparkowane"
        !paired -> "niepołączone z CAR OS"
        else -> "miejsce zapisze się po zgaszeniu"
    }
    return listOfNotNull(where, range?.let { "$it km zasięgu" }).joinToString(" · ")
}

/** Lista aut: dotknij, żeby wybrać; [manage] – też zmiana nazwy i usuwanie. */
@Composable
fun CarPickerSheet(actions: Actions, manage: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cars by Garage.cars.collectAsState()
    val active by Garage.active.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by Garage.version.collectAsState()
    var rename by remember { mutableStateOf<Garage.Car?>(null) }
    var remove by remember { mutableStateOf<Garage.Car?>(null) }
    CarSheet(
        "Moje auta", onDismiss,
        subtitle = if (manage) "Każde auto ma swoje miejsce, dane z radia i trasy. Alarm i powiadomienia działają dla wszystkich"
        else "Wybierz auto – cała aplikacja pokaże jego dane",
        icon = Icons.Rounded.DirectionsCar,
        bottom = { ctl ->
            SheetButton("Dodaj auto", Icons.Rounded.Add, sub = "Zeskanuj kod QR z radia CAR OS w tym aucie") {
                ctl.close()
                actions.addCar()
            }
        },
    ) { ctl ->
        cars.forEach { c ->
            val sel = c.id == active
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (sel) C.Accent.copy(alpha = 0.14f) else C.Card)
                    .border(if (sel) 2.dp else 1.dp, if (sel) C.Accent else C.Border, shape)
                    .clickable {
                        Garage.select(ctx, c.id)
                        if (!manage) ctl.close()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(if (sel) C.Accent else C.Raised),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.DirectionsCar, null, tint = if (sel) C.Bg else C.Text2, modifier = Modifier.size(22.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(Garage.label(ctx, c.id), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (sel) C.Accent else C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(carLine(ctx, c.id), fontSize = 13.sp, color = C.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (manage) {
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { rename = c }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Edit, "Zmień nazwę", tint = C.Text2, modifier = Modifier.size(20.dp))
                    }
                    if (cars.size > 1) Box(Modifier.size(40.dp).clip(CircleShape).clickable { remove = c }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DeleteOutline, "Usuń", tint = C.Text2, modifier = Modifier.size(20.dp))
                    }
                } else if (sel) Icon(Icons.Rounded.Check, null, tint = C.Accent, modifier = Modifier.size(24.dp))
            }
        }
    }
    rename?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name.ifBlank { Garage.label(ctx, c.id) }) }
        CarSheet(
            "Nazwa auta", { rename = null }, subtitle = "Np. „Touran”, „Auto żony”", icon = Icons.Rounded.Edit,
            bottom = { ctl -> SheetButton("Zapisz", Icons.Rounded.Check) { Garage.rename(c.id, name); ctl.close() } },
        ) {
            SheetField(name, { name = it.take(40) }, "Nazwa")
        }
    }
    remove?.let { c ->
        AlertDialog(
            onDismissRequest = { remove = null },
            containerColor = C.DialogBg, modifier = Modifier.glassDialog(),
            title = { Text("Usunąć ${Garage.label(ctx, c.id)}?", color = C.Text) },
            text = { Text("Z telefonu znikną dane tego auta (miejsce, trasy, połączenie z jego radiem). CAR OS w aucie zostaje bez zmian.", color = C.Muted) },
            confirmButton = { TextButton(onClick = { Garage.remove(ctx, c.id); remove = null }) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Anuluj") } },
        )
    }
}

/** Ustawienia: „Moje auta” – lista, wybór, nazwa, usuwanie, dodawanie. */
@Composable
fun CarsSettingItem(actions: Actions) {
    val ctx = LocalContext.current
    val cars by Garage.cars.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v by Garage.version.collectAsState()
    var open by remember { mutableStateOf(false) }
    SettingItem(
        Icons.Rounded.DirectionsCar, "Moje auta (${cars.size})",
        (if (cars.size > 1) cars.joinToString(", ") { Garage.label(ctx, it.id) } + " – dotknij, żeby wybrać, nazwać, usunąć"
        else "Masz więcej aut z CAR OS? Dotknij i dodaj kolejne (kod QR z jego radia)"),
    ) { open = true }
    if (open) CarPickerSheet(actions, manage = true) { open = false }
}
