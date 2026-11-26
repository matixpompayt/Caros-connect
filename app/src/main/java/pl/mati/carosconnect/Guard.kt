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
    /** Sprawdzenie po 45 s – osobno dla każdego auta. */
    private val pending = HashMap<String, Runnable>()
    private val seen = LinkedHashSet<String>()

    /** Alarm włączony i jest choć jedno auto sparowane kodem QR. */
    fun enabled(ctx: Context): Boolean = CarData.notifLive.value && Garage.paired(ctx).isNotEmpty()

    /** Ty jesteś w tym aucie (wybrane auto + usługa jazdy albo Bluetooth jego radia). */
    private fun inCar(ctx: Context, car: String): Boolean =
        car == Garage.active.value && (DriveService.running || Store.linked.value || Store.btLinked(ctx) == true)

    /**
     * Włącz / wyłącz czuwanie: włączone w ustawieniach i telefonu nie ma w aucie – albo jest kilka aut
     * (wtedy pozostałe pilnujemy także w czasie jazdy jednym z nich).
     */
    fun sync(ctx: Context, leftCar: Boolean = false) {
        val app = ctx.applicationContext
        Store.init(app)
        CarData.init(app)
        val inActive = DriveService.running || Store.linked.value
        val want = enabled(app) && (leftCar || !inActive || Garage.paired(app).size > 1)
        try {
            if (want && !GuardService.running) GuardService.start(app)
            else if (!want && GuardService.running) app.stopService(Intent(app, GuardService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "sync: $e")
        }
    }

    private fun sp(ctx: Context, car: String): android.content.SharedPreferences? =
        if (car == Garage.active.value) CarData.prefs else Garage.prefs(ctx, "car", car)

    /** Nowa pozycja auta [car] (z czuwania na żywo). */
    fun onLive(ctx: Context, car: String, l: CarData.Live) {
        if (car == Garage.active.value) CarData.setLive(l)
        val p = sp(ctx, car) ?: return
        if (car != Garage.active.value) {
            // pozycja innego auta – do pinezki na mapie
            p.edit().putString(
                "live",
                org.json.JSONObject().put("lat", l.lat).put("lon", l.lon).put("spd", l.speed).put("t", l.time).put("m", l.moving).toString(),
            ).apply()
        }
        val state = p.getString("guard_state", "") ?: ""
        if (!l.moving) {
            // auto stanęło – koniec tej jazdy
            pending.remove(car)?.let { handler.removeCallbacks(it) }
            if (state == "alarm") stopped(ctx, car, l)
            if (state.isNotEmpty()) p.edit().putString("guard_state", "").apply()
            return
        }
        if (System.currentTimeMillis() - l.time > 5 * 60_000L) return
        when (state) {
            "alarm" -> update(ctx, car, l)
            "mute", "you" -> Unit
            else -> if (!pending.containsKey(car) && enabled(ctx)) {
                if (inCar(ctx, car)) { p.edit().putString("guard_state", "you").apply(); return }
                val r = Runnable {
                    pending.remove(car)
                    when {
                        inCar(ctx, car) -> p.edit().putString("guard_state", "you").apply()
                        else -> alarm(ctx, car, l)
                    }
                }
                pending[car] = r
                handler.postDelayed(r, GRACE_MS)
            }
        }
    }

    /** „To ktoś znajomy” – cisza do końca tej jazdy. */
    fun mute(ctx: Context, car: String) {
        sp(ctx, car)?.edit()?.putString("guard_state", "mute")?.apply()
        cancelAlarm(ctx, car)
        EventLog.add(EventLog.Cat.CAR, "Alarm wyciszony – ${Garage.label(ctx, car)} prowadzi ktoś znajomy")
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
        val cars = Garage.paired(ctx)
        val title = if (cars.size > 1) "Czuwanie: ${cars.size} auta" else "Czuwanie: ${cars.firstOrNull()?.let { Garage.label(ctx, it.first.id) } ?: "auto"}"
        return Notification.Builder(ctx, CH_GUARD)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText("Alarm, gdy auto ruszy bez Ciebie")
            .setContentIntent(open(ctx, 0, null, null))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun open(ctx: Context, req: Int, action: String?, car: String?): PendingIntent = PendingIntent.getActivity(
        ctx, 100 + req + 20 * (car?.let { Garage.index(it) } ?: 0),
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("tab", 0).apply {
                if (action != null) putExtra("guard_action", action)
                if (car != null) putExtra("car", car)
            },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmId(car: String) = Notifs.carId(ID_ALARM, car)

    private fun alarmNotif(ctx: Context, car: String, l: CarData.Live, first: Boolean): Notification {
        channels(ctx)
        val name = Garage.label(ctx, car)
        val where = try { Locator.address(ctx, l.lat, l.lon) } catch (_: Exception) { null }
        val text = "$name jedzie ${l.speed} km/h" + (where?.let { " · $it" } ?: "") + ". Telefonu nie ma w aucie."
        val mute = PendingIntent.getBroadcast(
            ctx, 201 + Garage.index(car), Intent(ctx, GuardReceiver::class.java).setAction(ACTION_MUTE).putExtra("car", car),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (Garage.cars.value.size > 1) "$name jedzie bez Ciebie!" else "Auto jedzie bez Ciebie!")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open(ctx, 1, "map", car))
            .setFullScreenIntent(open(ctx, 2, "map", car), true)
            .setOnlyAlertOnce(!first)
            .setOngoing(true)
            .setColor(0xFFFF5A5F.toInt())
            .addAction(Notification.Action.Builder(null, "Pokaż na mapie", open(ctx, 3, "map", car)).build())
            .addAction(Notification.Action.Builder(null, "Udostępnij link", open(ctx, 4, "share", car)).build())
            .addAction(Notification.Action.Builder(null, "To ktoś znajomy", mute).build())
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX)
        val n = b.build()
        // dzwoni jak budzik, aż dotkniesz powiadomienia
        if (first) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    private fun alarm(ctx: Context, car: String, l: CarData.Live) {
        sp(ctx, car)?.edit()?.putString("guard_state", "alarm")?.putBoolean("live_notified", true)?.apply()
        EventLog.add(EventLog.Cat.CAR, "ALARM: ${Garage.label(ctx, car)} jedzie bez Ciebie – ${l.speed} km/h")
        thread {
            val n = alarmNotif(ctx, car, l, true)
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(alarmId(car), n) } catch (_: SecurityException) {}
        }
    }

    private fun update(ctx: Context, car: String, l: CarData.Live) {
        thread {
            val n = alarmNotif(ctx, car, l, false)
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(alarmId(car), n) } catch (_: SecurityException) {}
        }
    }

    private fun stopped(ctx: Context, car: String, l: CarData.Live) {
        thread {
            channels(ctx)
            val name = Garage.label(ctx, car)
            val where = try { Locator.address(ctx, l.lat, l.lon) } catch (_: Exception) { null }
            val text = "$name stoi" + (where?.let { ": $it" } ?: "") + ". Dotknij, żeby zobaczyć na mapie."
            val n = Notification.Builder(ctx, Notifs.CH_LIVE)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(if (Garage.cars.value.size > 1) "$name stanęło" else "Auto stanęło")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(ctx, 5, "map", car))
                .setAutoCancel(true)
                .build()
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(alarmId(car), n) } catch (_: SecurityException) {}
        }
        EventLog.add(EventLog.Cat.CAR, "${Garage.label(ctx, car)} po alarmie stanęło")
    }

    /** Otwarto aplikację z alarmu – przestań dzwonić, powiadomienie zostaje (ciche), dopóki auto jedzie. */
    fun quiet(ctx: Context, car: String) {
        if (sp(ctx, car)?.getString("guard_state", "") != "alarm") return
        val l = if (car == Garage.active.value) CarData.live.value else null
        cancelAlarm(ctx, car)
        if (l != null) update(ctx, car, l)
    }

    /** „Udostępnij link” z alarmu – aplikacja otwiera panel udostępniania jazdy na żywo. */
    val askShare = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun cancelAlarm(ctx: Context, car: String) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(alarmId(car)) } catch (_: Exception) {}
    }

    // ---------- nasłuch na żywo ----------

    /**
     * Jedna wiadomość z przekaźnika (linia JSON ntfy, kilka aut naraz) – tylko pozycje aut.
     * [cars]: kanał przekaźnika → (auto, klucz). Zwraca czas wiadomości (do „since”).
     */
    internal fun onRelayLine(ctx: Context, line: String, cars: Map<String, Pair<String, ByteArray>>): Long? {
        val o = try { JSONObject(line) } catch (_: Exception) { return null }
        if (o.optString("event") != "message") return null
        val time = o.optLong("time").takeIf { it > 0 }
        // „since” po czasie zwraca też ostatnią już przeczytaną wiadomość – pomiń powtórki
        val id = o.optString("id")
        synchronized(seen) {
            if (id.isNotEmpty() && !seen.add(id)) return time
            while (seen.size > 200) seen.remove(seen.first())
        }
        val (car, key) = cars[o.optString("topic")] ?: return time
        val plain = Link.decrypt(key, o.optString("message")) ?: return time
        val m = try { JSONObject(plain) } catch (_: Exception) { return time }
        if (m.optString("type") == "live") {
            try {
                val l = CarData.Live(m.getDouble("lat"), m.getDouble("lon"), m.optInt("spd"), m.optInt("brg"), m.getLong("t"), m.optBoolean("m", true))
                handler.post { onLive(ctx, car, l) }
            } catch (_: Exception) {
            }
        }
        return time
    }
}

/** Przyciski z powiadomienia alarmu. */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        CarData.init(ctx)
        when (intent.action) {
            Guard.ACTION_MUTE -> Guard.mute(ctx, intent.getStringExtra("car") ?: Garage.active.value)
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
        val gp = getSharedPreferences("guard", MODE_PRIVATE)
        var since: Long = gp.getLong("since", System.currentTimeMillis() / 1000 - 120)
        var backoff = 5_000L
        while (alive) {
            // kanały wszystkich sparowanych aut – jedno połączenie z przekaźnikiem
            val ver = Garage.version.value
            val cars = Garage.paired(this).associate { (c, p) -> Link.topicOf(p.key) to (c.id to p.key) }
            if (cars.isEmpty()) { sleep(60_000); continue }
            try {
                val c = URL("https://ntfy.sh/" + cars.keys.joinToString(",") + "/json?since=" + since).openConnection() as HttpURLConnection
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
                        Guard.onRelayLine(this, line, cars)?.let { t ->
                            if (t >= since) {
                                since = t
                                gp.edit().putLong("since", t).apply()
                            }
                        }
                        // dodano / usunięto auto – połącz od nowa z nową listą
                        if (Garage.version.value != ver && Garage.paired(this).associate { (c2, p2) -> Link.topicOf(p2.key) to c2.id }.keys != cars.keys) break
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
