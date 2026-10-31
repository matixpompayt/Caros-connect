package pl.mati.carosconnect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Perms(
    val bt: Boolean = true,
    val loc: Boolean = true,
    val bgLoc: Boolean = true,
    val notif: Boolean = true,
    val battery: Boolean = true,
) {
    val allOk get() = bt && loc && bgLoc && notif && battery
}

interface Actions {
    fun requestBt()
    fun requestLoc()
    fun requestBgLoc()
    fun requestNotif()
    fun requestBattery()
    fun openAutostart()
    fun pairedDevices(): List<Pair<String, String>>
    fun refresh()
    fun scanQr()
    fun refreshFromCar()
    /** Pełne odświeżenie (pociągnięcie w dół). Zwraca krótki komunikat dla użytkownika. */
    suspend fun syncNow(): String
}

class MainActivity : ComponentActivity(), Actions {

    private val perms = mutableStateOf(Perms())

    private val single = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh(); matchCarByName() }
    private val multi = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val scanner = registerForActivityResult(ScanContract()) { r -> onScanned(r.contents) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Notifs.channels(this)
        org.maplibre.android.MapLibre.getInstance(this)
        CarData.init(this)
        SyncWorker.schedule(this)
        if (savedInstanceState == null) handleShare(intent)
        setContent { ParkApp(perms.value, this) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        matchCarByName()
        startIfCarConnected()
        refreshFromCar()
        Updater.check(silent = true)
    }

    override fun scanQr() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Zeskanuj kod z CAR OS w aucie")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun onScanned(text: String?) {
        if (text == null) return
        val p = Link.parse(text)
        if (p == null) {
            Toast.makeText(this, "To nie jest kod CAR OS. W radiu: Ustawienia → Połączenia → CAR OS Connect", Toast.LENGTH_LONG).show()
            return
        }
        Store.setPairing(p)
        matchCarByName()
        Toast.makeText(this, "Połączono z ${p.car ?: "CAR OS"}", Toast.LENGTH_LONG).show()
        refreshFromCar()
    }

    override suspend fun syncNow(): String {
        refresh()
        matchCarByName()
        startIfCarConnected()
        Updater.check(silent = true)
        return Sync.run(this, ask = true)
    }

    /** Dane z CAR OS (miejsce, stan auta, trasy, kopia) – bez pytania radia. */
    override fun refreshFromCar() {
        lifecycleScope.launch { try { Sync.run(this@MainActivity, ask = false) } catch (_: Exception) {} }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** „Udostępnij → Wyślij do auta” (tekst z Map Google) albo adres geo:. */
    private fun handleShare(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { Share.incoming.value = it to null }
            Intent.ACTION_VIEW -> i.data?.takeIf { it.scheme == "geo" }?.let { Share.incoming.value = null to it }
        }
    }

    /** Kod QR zawiera nazwę Bluetooth radia – znajdź je wśród sparowanych urządzeń. */
    @SuppressLint("MissingPermission")
    private fun matchCarByName() {
        if (Store.carMac.value != null || !perms.value.bt) return
        val name = Store.pairing.value?.btName ?: return
        try {
            val d = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.firstOrNull { it.name == name } ?: return
            Store.setCar(d.address, d.name ?: name)
        } catch (_: Exception) {
        }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        perms.value = Perms(
            bt = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT),
            loc = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            bgLoc = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            battery = pm?.isIgnoringBatteryOptimizations(packageName) ?: true,
        )
    }

    /** Aplikacja otwarta w aucie: jeśli telefon już jest połączony z radiem, od razu śledź pozycję. */
    @SuppressLint("MissingPermission")
    private fun startIfCarConnected() {
        val mac = Store.carMac.value ?: return
        if (DriveService.running || !perms.value.bt || !perms.value.loc) return
        try {
            val d = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.firstOrNull { it.address.equals(mac, true) } ?: return
            val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean ?: false
            if (connected) DriveService.start(this)
        } catch (_: Exception) {
        }
    }

    override fun requestBt() {
        if (Build.VERSION.SDK_INT >= 31) single.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }


    override fun requestLoc() {
        multi.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun requestBgLoc() {
        if (!perms.value.loc) { requestLoc(); return }
        if (Build.VERSION.SDK_INT >= 29) single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    override fun requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) single.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("BatteryLife")
    override fun requestBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    /** Xiaomi / HyperOS: bez „Autostartu” system nie budzi aplikacji po połączeniu Bluetooth. */
    override fun openAutostart() {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    override fun pairedDevices(): List<Pair<String, String>> = try {
        val list = getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList().orEmpty()
        list.sortedByDescending {
            val c = it.bluetoothClass?.deviceClass
            c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || c == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        }.map { (it.name ?: it.address) to it.address }
    } catch (_: Exception) {
        emptyList()
    }
}
