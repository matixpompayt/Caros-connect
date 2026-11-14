package pl.mati.carosconnect

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Budzi aplikację przy połączeniu z autem; awaryjnie zapisuje miejsce, gdy usługa nie działała. */
class CarBtReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        @Suppress("DEPRECATION")
        val d: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (!Store.isCar(d.address)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Store.linked.value = true
                if (!DriveService.running) DriveService.start(ctx)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Store.linked.value = false
                if (DriveService.running) return // usługa sama zapisze miejsce
                Store.log(ctx, "Rozłączono z radiem (usługa jazdy nie działała) – zapis awaryjny")
                val pending = goAsync()
                val app = ctx.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val l = Locator.freshFix(app, 20)
                            ?: Locator.lastKnown(app)?.takeIf { System.currentTimeMillis() - it.time < 10 * 60_000L }
                        if (l != null) {
                            Store.saveSpot(app, Store.Spot(l.latitude, l.longitude, System.currentTimeMillis(), l.accuracy))
                            Store.log(app, "Zapisano miejsce parkowania (awaryjnie, dokładność ${l.accuracy.toInt()} m)")
                        } else {
                            Store.log(app, "Brak pozycji – brak zgody na lokalizację w tle? Miejsce weźmie się z radia")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
