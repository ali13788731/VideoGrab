package com.videograb.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.videograb.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "دانلودها", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, ID, build(0, 0, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) DownloadEngine.pauseAll()
        if (watcher == null) {
            watcher = scope.launch {
                combine(DownloadEngine.active, DownloadEngine.live) { a, l -> a to l }.collect { (active, live) ->
                    if (active == 0) {
                        delay(1500)
                        if (DownloadEngine.active.value == 0) stopSelf()
                        return@collect
                    }
                    val done = live.values.sumOf { it.downloaded }
                    val total = live.values.sumOf { it.total.coerceAtLeast(0) }
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    getSystemService(NotificationManager::class.java).notify(ID, build(active, done, pct))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(active: Int, done: Long, pct: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active > 0) "$active دانلود فعال" else "آماده دانلود")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "توقف همه", pause)
        if (pct >= 0) b.setProgress(100, pct, false).setContentText("$pct٪")
        else b.setProgress(0, 0, active > 0)
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CH = "downloads"
        const val ID = 1
        const val ACTION_PAUSE_ALL = "pause_all"
    }
}
