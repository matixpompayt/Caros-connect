package pl.mati.carosconnect

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Raport danych z radia (CAN) przysłany z CAR OS – do udostępnienia (np. do analizy). */
object RadioReport {
    /** Czas raportu (0 = brak). */
    val time = MutableStateFlow(0L)

    private fun file(ctx: Context) = File(File(ctx.filesDir, "reports").apply { mkdirs() }, "CarOS-dane-radia.txt")

    fun init(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) time.value = ctx.getSharedPreferences("radio_report", Context.MODE_PRIVATE).getLong("t", f.lastModified())
    }

    fun save(ctx: Context, text: String, t: Long) {
        val sp = ctx.getSharedPreferences("radio_report", Context.MODE_PRIVATE)
        if (sp.getLong("t", 0L) >= t && file(ctx).exists()) return
        file(ctx).writeText(text)
        sp.edit().putLong("t", t).apply()
        time.value = t
        Notifs.service(ctx, "Raport z radia odebrany", "Otwórz Ustawienia → Raport danych z radia, żeby go udostępnić")
    }

    fun share(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "CAR OS – dane z radia (CAN)")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Udostępnij raport").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun RadioReportCard() {
    val t by RadioReport.time.collectAsState()
    if (t == 0L) return
    val ctx = LocalContext.current
    Label("Raport danych z radia", Modifier)
    Card {
        Text("Odebrany ${whenText(t)} – wyślij go Claude'owi do analizy danych z CAN.", fontSize = 15.sp, color = C.Muted)
        SecondaryButton("Udostępnij raport", Icons.Rounded.Share, Modifier.fillMaxWidth(), h = 50.dp) { RadioReport.share(ctx) }
    }
}
