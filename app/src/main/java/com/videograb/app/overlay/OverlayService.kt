package com.videograb.app.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/**
 * حباب شناور روی همه اپ‌ها. 
 * اندروید اجازه نمی‌دهد اپ دیگری ویدیوی داخل اینستاگرام را «ببیند»؛
 * پس روال این است: در اینستاگرام «Copy link» → لمس حباب → لینک از کلیپ‌بورد خوانده می‌شود
 * و در مرورگر داخلی برای تشخیص ویدیو باز می‌شود.
 */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "حباب شناور", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "overlay")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("حباب دانلود فعال است")
            .setOngoing(true)
            .addAction(0, "حذف حباب", stop)
            .build()
        ServiceCompat.startForeground(this, 2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") stopSelf()
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()
        val tv = TextView(this).apply {
            text = "⬇"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xEEE91E63.toInt()) }
            elevation = 12f
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = (200 * d).toInt() }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt()
                    wm.updateViewLayout(tv, lp)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        startActivity(Intent(this, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else { // چسبیدن به لبه
                        val w = resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + size / 2 < w / 2) 0 else w - size
                        wm.updateViewLayout(tv, lp)
                    }
                }
            }
            true
        }
        wm.addView(tv, lp)
        bubble = tv
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}
