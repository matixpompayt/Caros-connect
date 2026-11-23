package pl.mati.carosconnect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import kotlin.math.sin

/** Zrzuty nowego wyglądu (bez emulatora) – ./gradlew :app:recordPaparazziDebug -Pscreens */
class ScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, theme = "android:Theme.Material.NoActionBar", maxPercentDifference = 1.0)

    private val perms = Perms()
    private val actions = object : Actions {
        override fun requestBt() {}
        override fun requestLoc() {}
        override fun requestBgLoc() {}
        override fun requestNotif() {}
        override fun requestBattery() {}
        override fun openAutostart() {}
        override fun pairedDevices(): List<Pair<String, String>> = emptyList()
        override fun refresh() {}
        override fun scanQr() {}
        override fun refreshFromCar() {}
        override suspend fun syncNow(): String = ""
    }
    private val now = System.currentTimeMillis()
    private val spot get() = Store.Spot(52.5884, 14.6456, now - 25 * 60_000, 8f, "ul. Kostrzyńska 12, Dębno", src = Store.SRC_CAR)

    @Before
    fun data() {
        Store.pairing.value = Link.Pairing(ByteArray(32), "Touran", "VW Touran", "Mati")
        Store.carName.value = "Touran"
        Store.spot.value = spot
        CarData.carBody.value = "van"
        CarData.carPaint.value = 0xFF1F4E8C.toInt()
        CarData.status.value = CarData.Status(
            now, "VW Touran", "Mati", 214_380, 18_400.0, 96_000.0, 412_000.0, emptyList(), emptyList(), "2.0-beta.4",
            fuel = CarData.Fuel(60, emptyList(), 6.4, 540.0, 0.42, false, 34.6, true),
        )
    }

    private fun pal(light: Boolean) { C.pal = if (light) C.LIGHT else C.DARK }

    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(C.Bg)) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
            FloatingTabBar(TAB_ITEMS, 0) {}
        }
    }

    private fun home(light: Boolean) {
        pal(light)
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            Screen {
                HomeHero(perms, actions, spot, null, false)
                Label("Gdzie stoi auto")
                Card {
                    Text("ul. Kostrzyńska 12, Dębno", fontSize = 20.sp, color = C.Text)
                    Text("dziś, 14:05 · 25 min temu", fontSize = 14.sp, color = C.Muted)
                }
            }
        }
    }}

    @Test fun homeDark() = home(false)
    @Test fun homeLight() = home(true)

    private fun map(light: Boolean) {
        pal(light)
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            Box(Modifier.fillMaxSize().background(C.Bg)) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Accent.copy(alpha = 0.25f), C.Raised, C.Bg))))
                MapSearchPill(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 40.dp).fillMaxWidth())
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(520.dp)
                        .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(C.Bg).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp).height(5.dp).fillMaxWidth(0.12f).clip(RoundedCornerShape(3.dp)).background(C.Muted))
                    MapSheetHeader(perms, actions, spot, null, false)
                    CarDashboard(CarData.status.value!!)
                }
                Box(Modifier.align(Alignment.BottomCenter)) { FloatingTabBar(TAB_ITEMS, 0) {} }
            }
        }
    }}

    @Test fun mapDark() = map(false)
    @Test fun mapLight() = map(true)

    @Test fun autoTab() {
        pal(false)
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            Column(Modifier.fillMaxSize().background(C.Bg)) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Auto", fontSize = 32.sp, color = C.Text)
                    CarDashboard(CarData.status.value!!)
                    Label("Serwis")
                    SettingItem(Icons.Rounded.Info, "Olej i filtry", "za 2 400 km albo 3 miesiące") {}
                    PrimaryButton("Prowadź do auta", Icons.Rounded.Info) {}
                }
                FloatingTabBar(TAB_ITEMS, 1) {}
            }
        }
    }}

    @Test fun settingsLook() {
        pal(false)
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            Column(Modifier.fillMaxSize().background(C.Bg)) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label("Wygląd")
                    LayoutChooser()
                    SettingItem(Icons.Rounded.Palette, "Motyw", "Ciemny") {}
                }
                FloatingTabBar(TAB_ITEMS, 3) {}
            }
        }
    }}

    @Test fun modelsGallery() {
        pal(false)
        val types = listOf("hatchback", "sedan", "kombi", "suv", "minivan", "van", "pickup", "coupe")
        val paints = listOf(0xFF1F4E8C, 0xFFB71C1C, 0xFFE8EAED, 0xFF2E2F33, 0xFF1F4E8C, 0xFF8A9199, 0xFF2E5E3E, 0xFFF2A900).map { it.toInt() }
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            Column(Modifier.fillMaxSize().background(C.Bg).padding(8.dp)) {
                types.chunked(2).forEachIndexed { r, row ->
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEachIndexed { c, t ->
                            val b = pl.mati.caros.core.Model3D.carIcon(t, paints[r * 2 + c], 212.0, 20.0, 420, outline = false)
                            androidx.compose.foundation.Image(b.asImageBitmap(), t, Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
            }
        }}
    }

    @Test fun mapHidden() {
        pal(false)
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(C.Accent.copy(alpha = 0.25f), C.Raised, C.Bg)))) {
                MapSearchPill(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 40.dp).fillMaxWidth())
                Column(Modifier.align(Alignment.BottomCenter)) {
                    MiniSheetBar {}
                    FloatingTabBar(TAB_ITEMS, 0) {}
                }
            }
        }}
    }

    /** „Wyślij do auta” na pełnym ekranie – trasa Cychry → Berlin z wariantami, korkami i pogodą. */
    private fun planner(light: Boolean) {
        pal(light)
        fun line(n: Int, bend: Double) = (0..n).map { i ->
            val t = i / n.toDouble()
            LatLng(52.744 + (52.520 - 52.744) * t + sin(t * Math.PI) * bend, 14.602 + (13.405 - 14.602) * t)
        }
        val main = line(60, 0.05)
        val alt1 = line(60, 0.16)
        val alt2 = line(60, -0.10)
        val jams = listOf(
            RoutePreview.Jam(main.subList(30, 35), 240, 2, "JAM"),
            RoutePreview.Jam(main.subList(50, 53), 90, 1, "JAM"),
        )
        val r = RoutePreview.Result(
            main, 125_000.0, 6_500.0, 6_780, 280, jams, null,
            listOf(RoutePreview.Wx("frost", "Mróz", 0.0, 125.0, now + 3_600_000, 0, 0.0)), 1.0, now,
            alts = listOf(
                RoutePreview.Alt(main, 125_000.0, 6_500.0, 6_780, 280, jams, null),
                RoutePreview.Alt(alt1, 143_000.0, 7_600.0, 7_620, 60, emptyList(), null),
                RoutePreview.Alt(alt2, 161_000.0, 8_100.0, 8_160, 0, emptyList(), null),
            ),
            altIndex = 0, savedS = 240,
        )
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            RoutePlannerScreen(CarData.Place("Berlin", "Niemcy", 52.520, 13.405), false, null, emptyList(), null, {}, preview = r)
        } }
    }

    @Test fun plannerDark() = planner(false)
    @Test fun plannerLight() = planner(true)

    // ---------- panele od dołu ----------

    private fun sheet(content: @Composable () -> Unit) {
        pal(false)
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            Box(Modifier.fillMaxSize().background(C.Bg)) { content() }
        } }
    }

    @Test fun tankSheet() = sheet { TankSheet(60, {}) {} }

    @Test fun noteSheet() = sheet { NoteSheet("Poziom -2, miejsce 47", "ul. Kostrzyńska 12, Dębno", null, {}, {}, {}) {} }

    @Test fun radioSheet() {
        Store.carMac.value = "41:42:43:44:45:46"
        sheet {
            RadioPickerSheet(
                listOf("JBL Flip 5" to "11:22:33:44:55:66", "TS10 CAR" to "41:42:43:44:45:46", "Galaxy Buds2" to "AA:BB:CC:DD:EE:FF"), {},
            )
        }
    }

    @Test fun changelogSheet() {
        Changelog.entries.value = listOf(
            Changelog.Entry("2.0-beta.10", "23.11.2026", "Nowe „Wyślij do auta”", listOf("Trasa na mapie na cały ekran", "Panel do przeciągania jak na Start")),
            Changelog.Entry("2.0-beta.9", "22.11.2026", "Modele aut w jakości studyjnej", listOf("Gładkie cieniowanie i odbicia lakieru")),
        )
        sheet { ChangelogSheet {} }
    }

    @Test fun whatsNewSheet() = sheet {
        WhatsNewSheet(Changelog.Entry("2.0-beta.10", "23.11.2026", "Nowe „Wyślij do auta”", listOf("Trasa na mapie na cały ekran", "Panel do przeciągania jak na Start", "Duży przycisk „Nawiguj w aucie”")), {}) {}
    }

    @Test fun addressPicker() {
        pal(false)
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            AddressPickerScreen(
                false, CarData.Addr("ul. Kostrzyńska 12, Dębno", 52.7440, 14.6020), {},
                previewResults = listOf(
                    CarData.Place("Jana Pawła II 77", "Cychry, gmina Dębno, zachodniopomorskie", 52.7440, 14.6020),
                    CarData.Place("Jana Pawła II 77", "Dębno, zachodniopomorskie", 52.7378, 14.6990),
                ),
                previewAddr = "Jana Pawła II 77, Cychry",
            )
        } }
    }

    @Test fun walkNav() {
        pal(false)
        Store.spotNote.value = "Poziom -2, miejsce 47, przy windzie"
        val pts = (0..20).map { LatLng(52.5884 - (20 - minOf(it, 10) - 10) * 0.0002 - 0.002 + minOf(it, 10) * 0.0002, 14.6456 - (20 - it).coerceAtMost(10) * 0.0003) }
        val cum = DoubleArray(pts.size) { it * 22.0 }
        val steps = listOf(
            WalkNav.Step(0, "depart", "", "Jana Pawła II", pts[0].latitude, pts[0].longitude),
            WalkNav.Step(10, "turn", "right", "Wincentego Witosa", pts[10].latitude, pts[10].longitude),
            WalkNav.Step(20, "arrive", "", "", pts[20].latitude, pts[20].longitude),
        )
        val route = WalkNav.Route(pts, cum, steps)
        val me = android.location.Location("gps").apply { latitude = pts[0].latitude; longitude = pts[0].longitude; accuracy = 6f }
        paparazzi.snapshot { CompositionLocalProvider(LocalInspectionMode provides true) {
            WalkNavUi(
                spot, false, me, 20f, route, pts, emptyList(), true, {}, false, 380.0, 440.0, steps[1], 220.0,
                routingFirst = false, noRoute = false, error = "", along = 0.0, source = "chodniki OSM", voice = true, onVoice = {},
            )
        } }
    }
}
