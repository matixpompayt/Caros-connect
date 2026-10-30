package pl.mati.carosconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Locale

object Notifs {
    const val CH_DRIVE = "drive"
    const val CH_PARK = "park"
    const val CH_SERVICE = "service"
    const val CH_WEATHER = "weather"
    const val CH_METER = "meter"
    const val ID_DRIVE = 1
    const val ID_PARK = 2
    const val ID_SERVICE = 3
    const val ID_WEATHER = 4
    const val ID_METER = 5

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_DRIVE, "Jazda (śledzenie pozycji)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Widoczne, gdy telefon jest połączony z autem"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PARK, "Zaparkowano", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Gdzie zostawiłeś auto"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Serwis auta", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Przypomnienia z CAR OS: olej, przegląd, ubezpieczenie…"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WEATHER, "Mróz i szron", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Poranne ostrzeżenie o oblodzonych szybach"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_METER, "Płatne parkowanie", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Koniec opłaconego czasu parkowania"
                enableVibration(true)
            },
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun walkIntent(s: Store.Spot): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=w", s.lat, s.lon)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun driving(ctx: Context): Notification {
        channels(ctx)
        val car = Store.carName.value ?: "autem"
        return Notification.Builder(ctx, CH_DRIVE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Połączono z $car")
            .setContentText("Zapamiętam, gdzie zaparkujesz")
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    fun parked(ctx: Context, s: Store.Spot) {
        channels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val walk = PendingIntent.getActivity(ctx, 1, walkIntent(s), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH_PARK)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Zaparkowano")
            .setContentText(s.address ?: s.coords)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Prowadź do auta", walk).build())
            .build()
        try { nm.notify(ID_PARK, n) } catch (_: SecurityException) {}
    }

    private fun simple(ctx: Context, ch: String, id: Int, icon: Int, title: String, text: String) {
        channels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(ctx, ch)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }

    fun service(ctx: Context, title: String, text: String) =
        simple(ctx, CH_SERVICE, ID_SERVICE, android.R.drawable.ic_dialog_info, title, text)

    fun weather(ctx: Context, title: String, text: String) =
        simple(ctx, CH_WEATHER, ID_WEATHER, android.R.drawable.ic_dialog_alert, title, text)

    fun meter(ctx: Context, title: String, text: String) =
        simple(ctx, CH_METER, ID_METER, android.R.drawable.ic_lock_idle_alarm, title, text)
}
