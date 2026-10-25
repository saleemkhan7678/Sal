package com.focuslock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Runs for as long as FocusLock is the device owner. The moment any app is installed or updated it
 * re-applies every block, so uninstalling and reinstalling a locked app or game gets it suspended again at once.
 * It also re-checks whenever the screen turns on or the phone is unlocked (alarms can be deferred while the phone
 * sleeps), and every 30 seconds while a daily limit is counting down.
 */
class WatchService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Enforcer.enforce(context)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (fastTick) Enforcer.enforce(this@WatchService)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "FocusLock", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "watch")
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle("FocusLock is active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        val pkgs = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // Screen events carry no data, so they need their own filter (a filter with a data scheme would never match them).
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // System broadcasts are delivered to NOT_EXPORTED receivers; the flag only blocks other apps.
        for (f in listOf(pkgs, screen)) {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, f)
            }
        }
        handler.postDelayed(tick, TICK_MS)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        try { unregisterReceiver(receiver) } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 30_000L
        @Volatile var running = false
        @Volatile var fastTick = false

        fun ensure(c: Context) {
            if (running) return
            try {
                c.startForegroundService(Intent(c, WatchService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}
