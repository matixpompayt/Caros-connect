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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
        paparazzi.snapshot {
            Screen {
                HomeHero(perms, actions, spot, null, false)
                Label("Gdzie stoi auto")
                Card {
                    Text("ul. Kostrzyńska 12, Dębno", fontSize = 20.sp, color = C.Text)
                    Text("dziś, 14:05 · 25 min temu", fontSize = 14.sp, color = C.Muted)
                }
            }
        }
    }

    @Test fun homeDark() = home(false)
    @Test fun homeLight() = home(true)

    private fun map(light: Boolean) {
        pal(light)
        paparazzi.snapshot {
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
    }

    @Test fun mapDark() = map(false)
    @Test fun mapLight() = map(true)

    @Test fun autoTab() {
        pal(false)
        paparazzi.snapshot {
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
    }

    @Test fun settingsLook() {
        pal(false)
        paparazzi.snapshot {
            Column(Modifier.fillMaxSize().background(C.Bg)) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label("Wygląd")
                    LayoutChooser()
                    SettingItem(Icons.Rounded.Palette, "Motyw", "Ciemny") {}
                }
                FloatingTabBar(TAB_ITEMS, 3) {}
            }
        }
    }
}
