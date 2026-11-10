package pl.mati.carosconnect

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/** Ustawienia motywu Glass: odcień szkła, tło (gotowe / kolor / mieszanka / zdjęcie / AI), przyciemnienie, akcent. */
@Composable
fun GlassSettings() {
    val ctx = LocalContext.current
    val s by GlassTheme.spec.collectAsState()
    val aiBusy by GlassTheme.aiBusy.collectAsState()
    val aiErr by GlassTheme.aiError.collectAsState()
    val photoBusy by GlassTheme.photoBusy.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(if (s.kind == "image") (if (s.imageSrc == "ai") "ai" else "photo") else s.kind) }
    var aiDialog by remember { mutableStateOf(false) }
    fun upd(f: (GlassTheme.Spec) -> GlassTheme.Spec) = GlassTheme.update(ctx, f)
    fun generate(prompt: String) {
        scope.launch {
            if (GlassTheme.generateAi(ctx, prompt, GlassState.size)) Toast.makeText(ctx, "Nowe tło gotowe", Toast.LENGTH_SHORT).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!GlassTheme.importPhoto(ctx, uri)) Toast.makeText(ctx, "Nie udało się wczytać zdjęcia", Toast.LENGTH_LONG).show()
        }
    }

    Card {
        SubTitle("Szkło")
        val tones = listOf("dark" to "Ciemne", "light" to "Jasne", "auto" to "Jak telefon")
        LiquidSegmented(tones.map { it.second }, tones.indexOfFirst { it.first == s.tone }, { i -> upd { it.copy(tone = tones[i].first) } })

        SubTitle("Tło")
        val tabs = listOf("preset" to "Gotowe", "solid" to "Kolor", "mix" to "Mieszanka", "photo" to "Zdjęcie", "ai" to "AI")
        LiquidSegmented(tabs.map { it.second }, tabs.indexOfFirst { it.first == tab }, { i -> tab = tabs[i].first }, fontSize = 12.sp)

        when (tab) {
            "preset" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTheme.PRESETS.forEach { p ->
                    WallTile(p.name, s.kind == "preset" && s.preset == p.id, Brush.linearGradient(p.colors.map { Color(it) })) {
                        upd { it.copy(kind = "preset", preset = p.id) }
                    }
                }
            }
            "solid" -> Swatches(if (s.kind == "solid") listOf(s.solid) else emptyList(), numbered = false) { c ->
                upd { it.copy(kind = "solid", solid = c) }
            }
            "mix" -> {
                Text("Wybierz od 2 do 4 kolorów – zmieszają się w tło", fontSize = 13.sp, color = C.Muted)
                Swatches(s.mix, numbered = true) { c ->
                    upd { cur ->
                        val m = cur.mix.toMutableList()
                        if (c in m) { if (m.size > 2) m.remove(c) } else { if (m.size >= 4) m.removeAt(m.lastIndex); m.add(c) }
                        cur.copy(kind = "mix", mix = m)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallBtn("Losuj układ", Icons.Rounded.Casino) { upd { it.copy(kind = "mix", seed = Random.nextInt(1, 99_999)) } }
                    if (s.kind != "mix") SmallBtn("Użyj mieszanki", Icons.Rounded.Check) { upd { it.copy(kind = "mix") } }
                }
            }
            "photo" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val cur = GlassState.sharp
                if (s.kind == "image" && s.imageSrc == "photo" && cur != null) {
                    Image(cur, "Obecne tło", contentScale = ContentScale.Crop, modifier = Modifier.size(width = 60.dp, height = 104.dp).clip(RoundedCornerShape(12.dp)))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Dowolne zdjęcie z galerii jako tapeta – karty rozmyją je jak w iPhonie.", fontSize = 13.sp, color = C.Muted)
                    if (photoBusy) Busy("Wczytuję zdjęcie…")
                    else SmallBtn("Wybierz zdjęcie", Icons.Rounded.Image) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val cur = GlassState.sharp
                    if (s.kind == "image" && s.imageSrc == "ai" && cur != null) {
                        Image(cur, "Obecne tło AI", contentScale = ContentScale.Crop, modifier = Modifier.size(width = 60.dp, height = 104.dp).clip(RoundedCornerShape(12.dp)))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Opisz tło, a AI je narysuje – albo wybierz gotowy pomysł.", fontSize = 13.sp, color = C.Muted)
                        if (s.kind == "image" && s.imageSrc == "ai" && s.aiPrompt.isNotBlank()) {
                            val label = GlassTheme.AI_IDEAS.firstOrNull { it.second == s.aiPrompt }?.first ?: s.aiPrompt
                            Text("Obecne: $label", fontSize = 12.sp, color = C.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (aiBusy) Busy("Generuję tło… zwykle 10–30 s")
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallBtn("Opisz i wygeneruj", Icons.Rounded.AutoAwesome) { aiDialog = true }
                    SmallBtn("Losowe", Icons.Rounded.Casino) { generate(GlassTheme.AI_IDEAS.random().second) }
                }
                aiErr?.let { Text(it, fontSize = 13.sp, color = C.Orange) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTheme.AI_IDEAS.forEach { (name, prompt) ->
                        Text(
                            name, fontSize = 13.sp, color = C.Text, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).surface(RoundedCornerShape(12.dp), C.Raised)
                                .clickable(enabled = !aiBusy) { generate(prompt) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Text("Obraz tworzy darmowy serwis Pollinations.ai – wysyłany jest tylko opis tła.", fontSize = 11.sp, color = C.Muted)
            }
        }

        // przyciemnienie – zapis po puszczeniu suwaka (tło renderuje się raz, nie przy każdym ruchu)
        var dim by remember(s.dim) { mutableFloatStateOf(s.dim) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Przyciemnienie tła", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.weight(1f))
            Text("${(dim * 100).roundToInt()}%", fontSize = 14.sp, color = C.Muted)
        }
        LiquidSlider(
            value = dim, onValueChange = { dim = it }, valueRange = 0f..0.7f,
            onValueChangeFinished = { upd { it.copy(dim = dim) } },
        )

        // animacja kropli – można wyłączyć, wygląd Glass zostaje
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Animacja kropli w pasku", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text)
                Text(
                    if (s.anim) "Kropla przelewa się między zakładkami i powiększa ikony"
                    else "Wyłączona – kropla od razu przeskakuje, wygląd Glass zostaje",
                    fontSize = 12.sp, color = C.Muted,
                )
            }
            LiquidSwitch(s.anim, { v -> upd { it.copy(anim = v) } })
        }

        SubTitle("Kolor akcentu")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val auto = s.accent == null
            Text(
                "Z tła", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (auto) C.Bg else C.Text,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).then(if (auto) Modifier.background(C.Accent) else Modifier.surface(RoundedCornerShape(16.dp), C.Raised))
                    .clickable { upd { it.copy(accent = null) } }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            GlassTheme.SWATCHES.forEach { c ->
                val on = s.accent == c
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(GlassTheme.accentFor(Color(c), C.isLight))
                        .border(if (on) 3.dp else 1.dp, if (on) C.Text else Color.White.copy(alpha = 0.3f), CircleShape)
                        .clickable { upd { it.copy(accent = c) } },
                )
            }
        }
    }

    if (aiDialog) AiPromptDialog(s.aiPrompt.takeIf { p -> GlassTheme.AI_IDEAS.none { it.second == p } } ?: "", { aiDialog = false }) { generate(it) }
}

@Composable
private fun SubTitle(t: String) {
    Text(t, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Text, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Pills(items: List<Pair<String, String>>, sel: String, scroll: Boolean = false, onSel: (String) -> Unit) {
    Row(
        (if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (k, label) ->
            val on = k == sel
            val shape = RoundedCornerShape(16.dp)
            Box(
                (if (scroll) Modifier else Modifier.weight(1f)).clip(shape)
                    .then(if (on) Modifier.background(C.Accent) else Modifier.surface(shape, C.Raised))
                    .clickable { onSel(k) }.padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Bg else C.Text, maxLines = 1) }
        }
    }
}

@Composable
private fun WallTile(name: String, on: Boolean, brush: Brush, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(width = 64.dp, height = 112.dp).clip(RoundedCornerShape(14.dp)).background(brush)
                .border(if (on) 3.dp else 1.dp, if (on) C.Text else Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // mini „szklana karta” na podglądzie
            Box(
                Modifier.size(width = 44.dp, height = 22.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.22f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(7.dp)),
            )
        }
        Text(name, fontSize = 12.sp, color = if (on) C.Text else C.Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun Swatches(selected: List<Long>, numbered: Boolean, onTap: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val rows = GlassTheme.SWATCHES.chunked(6) + listOf(emptyList<Long>()) + GlassTheme.DARK_SWATCHES.chunked(6)
        rows.forEach { row ->
            if (row.isEmpty()) { Text("Ciemne", fontSize = 12.sp, color = C.Muted); return@forEach }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { c ->
                    val idx = selected.indexOf(c)
                    val on = idx >= 0
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                            .border(if (on) 3.dp else 1.dp, if (on) C.Text else Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable { onTap(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) {
                            if (numbered) Text("${idx + 1}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            else Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallBtn(text: String, icon: ImageVector, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.height(42.dp).clip(shape).surface(shape, C.Raised).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = C.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Text, maxLines = 1)
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(42.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), color = C.Accent, strokeWidth = 2.5.dp)
        Text(text, fontSize = 14.sp, color = C.Text2)
    }
}

@Composable
private fun AiPromptDialog(initial: String, onDismiss: () -> Unit, onGenerate: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    LaunchedEffect(Unit) { GlassTheme.aiError.value = null }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.DialogBg, modifier = Modifier.glassDialog(),
        title = { Text("Tło z AI", color = C.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = t, onValueChange = { t = it.take(300) },
                    placeholder = { Text("np. VW Touran nocą w deszczu, neonowe światła miasta") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.Accent, unfocusedBorderColor = C.Border,
                        focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
                    ),
                )
                Text("Im dokładniejszy opis (kolory, pora dnia, styl), tym lepsze tło. Po angielsku działa najlepiej, po polsku też.", fontSize = 12.sp, color = C.Muted)
            }
        },
        confirmButton = { TextButton(enabled = t.isNotBlank(), onClick = { onGenerate(t.trim()); onDismiss() }) { Text("Generuj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
