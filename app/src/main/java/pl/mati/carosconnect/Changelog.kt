package pl.mati.carosconnect

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Lista zmian (assets/changelog.txt – dołączona do aplikacji, działa bez internetu)
 * i okno „Co nowego” pokazywane raz po aktualizacji.
 */
object Changelog {

    data class Entry(val version: String, val date: String?, val title: String?, val points: List<String>)

    val entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Wpis do pokazania po aktualizacji (null = nic). */
    val whatsNew = MutableStateFlow<Entry?>(null)

    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences("changelog", Context.MODE_PRIVATE)
        sp = p
        val text = try { ctx.assets.open("changelog.txt").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
        val list = parse(text).filter { it.version != "next" }
        entries.value = list
        val seen = p.getInt("seen_code", 0)
        if (seen == 0) {
            // pierwsza instalacja – bez okna, od teraz pokazujemy nowości po aktualizacjach
            p.edit().putInt("seen_code", BuildConfig.VERSION_CODE).apply()
        } else if (BuildConfig.VERSION_CODE > seen) {
            whatsNew.value = list.firstOrNull { it.version == BuildConfig.VERSION_NAME } ?: list.firstOrNull()
            if (whatsNew.value == null) markSeen()
        }
    }

    fun markSeen() {
        whatsNew.value = null
        sp?.edit()?.putInt("seen_code", BuildConfig.VERSION_CODE)?.apply()
    }

    /** „## 1.12 · 2026-11-01”, potem tytuł i punkty „- …”. Linie z # (komentarze) pomijane. */
    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var ver: String? = null
        var date: String? = null
        var title: String? = null
        val points = ArrayList<String>()
        fun flush() {
            val v = ver ?: return
            out += Entry(v, date, title, points.toList())
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("## ") -> {
                    flush()
                    val h = line.removePrefix("## ").split("·").map { it.trim() }
                    ver = h[0]
                    date = h.getOrNull(1)?.ifBlank { null }
                    title = null
                    points.clear()
                }
                line.isBlank() || line.startsWith("#") -> Unit
                ver == null -> Unit
                line.startsWith("- ") || line.startsWith("• ") -> points += line.drop(2).trim()
                title == null -> title = line
                else -> points += line
            }
        }
        flush()
        return out
    }

    /** Opis wydania z GitHub (tytuł + punkty) bez stopek commitów. */
    fun fromBody(version: String, body: String): Entry? {
        val lines = body.lines().map { it.trim() }.filter {
            it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") &&
                !it.startsWith("🤖") && !it.startsWith("https://claude.ai")
        }
        if (lines.isEmpty()) return null
        return parse("## $version\n" + lines.joinToString("\n")).firstOrNull()
    }
}
