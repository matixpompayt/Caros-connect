package pl.mati.carosconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Alarm „auto jedzie bez Ciebie”.
 *
 * Gdy telefonu nie ma w aucie, [GuardService] trzyma otwarte połączenie z przekaźnikiem (ten sam
 * zaszyfrowany kanał, którym pisze radio) i dostaje pozycję auta od razu, a nie co 15 min.
 * Auto ruszyło, a telefon nie jest połączony z radiem → po 45 s jeszcze raz sprawdza Bluetooth
 * (mogłeś dopiero wsiąść) → głośny alarm jak budzik: mapa, link na żywo, „to ktoś znajomy”.
 * W aucie (Bluetooth radia) czuwanie się wyłącza, po zaparkowaniu włącza samo.
 */
object Guard {
    private const val TAG = "CarOsGuard"
    const val CH_GUARD = "guard"
    const val CH_ALARM = "guard_alarm"
    const val ID_GUARD = 12
    const val ID_ALARM = 13
    const val ACTION_MUTE = "pl.mati.carosconnect.GUARD_MUTE"

    /** Ile czekać po pierwszej pozycji „jedzie”, zanim uznamy, że to nie Ty (Bluetooth łączy się chwilę). */
    private const val GRACE_MS = 45_000L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var pending: Runnable? = null

    fun enabled(): Boolean = CarData.notifLive.value && Store.pairing.value != null

    /** Ty jesteś w aucie (usługa jazdy albo Bluetooth radia). */
    private fun inCar(ctx: Context): Boolean = DriveService.running || Store.linked.value || Store.btLinked(ctx) == true

    /** Włącz / wyłącz czuwanie stosownie do stanu: włączone w ustawieniach i telefonu nie ma w aucie. */
    fun sync(ctx: Context, leftCar: Boolean = false) {
        val app = ctx.applicationContext
        val want = enabled() && (leftCar || (!DriveService.running && !Store.linked.value))
        try {
            if (want && !GuardService.running) GuardService.start(app)
            else if (!want && GuardService.running) app.stopService(Intent(app, GuardService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "sync: $e")
        }
    }

    private fun sp() = CarData.prefs

    /** Nowa pozycja auta (z czuwania na żywo albo z odświeżenia). */
    fun onLive(ctx: Context, l: CarData.Live) {
        CarData.setLive(l)
        val p = sp() ?: return
        val now = System.currentTimeMillis()
        val state = p.getString("guard_state", "") ?: ""
        if (!l.moving) {
            // auto stanęło – koniec tej jazdy
            pending?.let { handler.removeCallbacks(it) }
            pending = null
            if (state == "alarm") stopped(ctx, l)
            if (state.isNotEmpty()) p.edit().putString("guard_state", "").apply()
            return
        }
        if (now - l.time > 5 * 60_000L) return
        when (state) {
            "alarm" -> update(ctx, l)
            "mute", "you" -> Unit
            else -> if (pending == null && enabled()) {
                if (inCar(ctx)) { p.edit().putString("guard_state", "you").apply(); return }
                val r = Runnable {
                    pending = null
                    val cur = CarData.live.value ?: l
                    when {
                        inCar(ctx) -> p.edit().putString("guard_state", "you").apply()
                        !cur.moving -> Unit
                        else -> alarm(ctx, cur)
                    }
                }
                pending = r
                handler.postDelayed(r, GRACE_MS)
            }
        }
    }

    /** „To ktoś znajomy” – cisza do końca tej jazdy. */
    fun mute(ctx: Context) {
        sp()?.edit()?.putString("guard_state", "mute")?.apply()
        cancelAlarm(ctx)
        EventLog.add(EventLog.Cat.CAR, "Alarm wyciszony – jedzie ktoś znajomy")
    }

    // ---------- powiadomienia ----------

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_GUARD, "Czuwanie (alarm auta)", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Stałe ciche powiadomienie, gdy telefon czuwa nad autem"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Alarm: auto jedzie bez Ciebie", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Głośno, jak budzik – gdy auto ruszy, a Ciebie w nim nie ma"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 900, 400, 900, 400, 900)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            },
        )
    }

    fun ongoing(ctx: Context): Notification {
        channels(ctx)
        val car = Store.carName.value ?: "auto"
        return Notification.Builder(ctx, CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Czuwanie: $car")
            .setContentText("Alarm, gdy auto ruszy bez Ciebie")
            .setContentIntent(open(ctx, 0, null))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun open(ctx: Context, req: Int, action: String?): PendingIntent = PendingIntent.getActivity(
        ctx, 100 + req,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("tab", 0).apply { if (action != null) putExtra("guard_action", action) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmNotif(ctx: Context, l: CarData.Live, first: Boolean): Notification {
        channels(ctx)
        val car = Store.carName.value ?: "Auto"
        val where = try { Locator.address(ctx, l.lat, l.lon) } catch (_: Exception) { null }
        val text = "$car jedzie ${l.speed} km/h" + (where?.let { " · $it" } ?: "") + ". Telefonu nie ma w aucie."
        val mute = PendingIntent.getBroadcast(
            ctx, 201, Intent(ctx, GuardReceiver::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Auto jedzie bez Ciebie!")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open(ctx, 1, "map"))
            .setFullScreenIntent(open(ctx, 2, "map"), true)
            .setOnlyAlertOnce(!first)
            .setOngoing(true)
            .setColor(0xFFFF5A5F.toInt())
            .addAction(Notification.Action.Builder(null, "Pokaż na mapie", open(ctx, 3, "map")).build())
            .addAction(Notification.Action.Builder(null, "Udostępnij link", open(ctx, 4, "share")).build())
            .addAction(Notification.Action.Builder(null, "To ktoś znajomy", mute).build())
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX)
        val n = b.build()
        // dzwoni jak budzik, aż dotkniesz powiadomienia
        if (first) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    private fun alarm(ctx: Context, l: CarData.Live) {
        sp()?.edit()?.putString("guard_state", "alarm")?.putBoolean("live_notified", true)?.apply()
        EventLog.add(EventLog.Cat.CAR, "ALARM: auto jedzie bez Ciebie – ${l.speed} km/h")
        thread {
            val n = alarmNotif(ctx, l, true)
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(ID_ALARM, n) } catch (_: SecurityException) {}
        }
    }

    private fun update(ctx: Context, l: CarData.Live) {
        thread {
            val n = alarmNotif(ctx, l, false)
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(ID_ALARM, n) } catch (_: SecurityException) {}
        }
    }

    private fun stopped(ctx: Context, l: CarData.Live) {
        thread {
            channels(ctx)
            val where = try { Locator.address(ctx, l.lat, l.lon) } catch (_: Exception) { null }
            val text = "Auto stoi" + (where?.let { ": $it" } ?: "") + ". Dotknij, żeby zobaczyć na mapie."
            val n = Notification.Builder(ctx, Notifs.CH_LIVE)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("Auto stanęło")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(ctx, 5, "map"))
                .setAutoCancel(true)
                .build()
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(ID_ALARM, n) } catch (_: SecurityException) {}
        }
        EventLog.add(EventLog.Cat.CAR, "Auto po alarmie stanęło")
    }

    /** Otwarto aplikację z alarmu – przestań dzwonić, powiadomienie zostaje (ciche), dopóki auto jedzie. */
    fun quiet(ctx: Context) {
        if (sp()?.getString("guard_state", "") != "alarm") return
        val l = CarData.live.value ?: return
        cancelAlarm(ctx)
        update(ctx, l)
    }

    /** „Udostępnij link” z alarmu – aplikacja otwiera panel udostępniania jazdy na żywo. */
    val askShare = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun cancelAlarm(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID_ALARM) } catch (_: Exception) {}
    }

    // ---------- nasłuch na żywo ----------

    /** Jedna wiadomość z przekaźnika (linia JSON ntfy) – tylko pozycje auta. */
    internal fun onRelayLine(ctx: Context, line: String): String? {
        val o = try { JSONObject(line) } catch (_: Exception) { return null }
        if (o.optString("event") != "message") return null
        val id = o.optString("id").ifBlank { null }
        val key = Store.pairing.value?.key ?: return id
        val plain = Link.decrypt(key, o.optString("message")) ?: return id
        val m = try { JSONObject(plain) } catch (_: Exception) { return id }
        if (m.optString("type") == "live") {
            try {
                val l = CarData.Live(m.getDouble("lat"), m.getDouble("lon"), m.optInt("spd"), m.optInt("brg"), m.getLong("t"), m.optBoolean("m", true))
                handler.post { onLive(ctx, l) }
            } catch (_: Exception) {
            }
        }
        return id
    }
}

/** Przyciski z powiadomienia alarmu. */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        CarData.init(ctx)
        when (intent.action) {
            Guard.ACTION_MUTE -> Guard.mute(ctx)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Guard.sync(ctx)
        }
    }
}

/**
 * Czuwanie: stałe (ciche) powiadomienie i otwarte połączenie z przekaźnikiem. Zrywa się samo
 * przy braku sieci – wtedy wraca, gdy sieć wróci (i co kilkanaście sekund próbuje ponownie).
 */
class GuardService : Service() {

    companion object {
        @Volatile var running = false
            private set

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            } catch (e: Exception) {
                Log.w("CarOsGuard", "start: $e")
                Store.log(ctx, "System nie pozwolił włączyć czuwania (${e.javaClass.simpleName})")
            }
        }
    }

    @Volatile private var alive = false
    @Volatile private var conn: HttpURLConnection? = null
    private var worker: Thread? = null
    private val lock = Object()
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        CarData.init(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(Guard.ID_GUARD, Guard.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else startForeground(Guard.ID_GUARD, Guard.ongoing(this))
        } catch (e: Exception) {
            Log.w("CarOsGuard", "startForeground: $e")
            Store.log(this, "Czuwanie zablokowane przez system (${e.javaClass.simpleName})")
            stopSelf()
            return
        }
        running = true
        alive = true
        Store.log(this, "Czuwanie włączone – alarm, gdy auto ruszy bez Ciebie")
        // sieć wróciła → połącz od razu
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { synchronized(lock) { lock.notifyAll() } }
            }
            cm?.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Exception) {
        }
        worker = thread(name = "caros-guard") { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun loop() {
        val sp = CarData.prefs
        var since: String = sp?.getString("guard_since", null) ?: (System.currentTimeMillis() / 1000 - 120).toString()
        var backoff = 5_000L
        while (alive) {
            val p = Store.pairing.value
            if (p == null) { sleep(60_000); continue }
            try {
                val c = URL("https://ntfy.sh/" + Link.topicOf(p.key) + "/json?since=" + since).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15_000
                // ntfy wysyła „keepalive” co ok. 45 s – brak czegokolwiek przez 2 min = zerwane połączenie
                c.readTimeout = 120_000
                c.setRequestProperty("User-Agent", "CarOS-Connect")
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                backoff = 5_000L
                c.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!alive) break
                        Guard.onRelayLine(this, line)?.let { id ->
                            since = id
                            sp?.edit()?.putString("guard_since", id)?.apply()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!alive) break
                Log.d("CarOsGuard", "połączenie: $e")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                conn = null
            }
            if (!alive) break
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun sleep(ms: Long) {
        synchronized(lock) { try { lock.wait(ms) } catch (_: InterruptedException) {} }
    }

    override fun onDestroy() {
        running = false
        alive = false
        synchronized(lock) { lock.notifyAll() }
        try { conn?.disconnect() } catch (_: Exception) {}
        try { netCb?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        Store.log(this, "Czuwanie wyłączone")
        super.onDestroy()
    }
}
