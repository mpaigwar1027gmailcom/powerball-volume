package com.powerball.volume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    private data class Category(val label: String, val stream: Int)

    // STREAM_BLUETOOTH_SCO is a hidden constant (6) in the SDK.
    private val categories = listOf(
        Category("Media", AudioManager.STREAM_MUSIC),
        Category("Ringer", AudioManager.STREAM_RING),
        Category("Notification", AudioManager.STREAM_NOTIFICATION),
        Category("Alarm", AudioManager.STREAM_ALARM),
        Category("System", AudioManager.STREAM_SYSTEM),
        Category("In-call", AudioManager.STREAM_VOICE_CALL),
        Category("Bluetooth", 6)
    )

    private lateinit var wm: WindowManager
    private lateinit var am: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { showBubble() }

    private var bubble: View? = null
    private var panel: View? = null
    private var bubbleX = 0
    private var bubbleY = 300

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startAsForeground()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(collapseRunnable)
        removeView(bubble); bubble = null
        removeView(panel); panel = null
        super.onDestroy()
    }

    private fun removeView(v: View?) {
        if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun startAsForeground() {
        val channelId = "powerball"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "PowerBall", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, channelId)
            .setContentTitle("PowerBall Volume is running")
            .setContentText("Tap to open settings")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------------------------------------------------------------- Bubble

    private fun showBubble() {
        handler.removeCallbacks(collapseRunnable)
        removeView(panel); panel = null
        if (bubble != null) return

        val size = dp(52)
        val ball = TextView(this).apply {
            text = "🔊"
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC3D5AFE"))
                setStroke(dp(2), Color.parseColor("#99FFFFFF"))
            }
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX; y = bubbleY
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        ball.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        bubbleX = lp.x; bubbleY = lp.y
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { removeView(bubble); bubble = null; showPanel() }
                    true
                }
                else -> false
            }
        }
        bubble = ball
        try { wm.addView(ball, lp) } catch (_: Exception) { bubble = null }
    }

    // ----------------------------------------------------------------- Panel

    private fun showPanel() {
        removeView(panel)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Volume"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { showBubble() }
        })
        content.addView(header)

        for (c in categories) addRow(content, c)

        val scroll = ScrollView(this).apply {
            addView(content)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#EE202124"))
            }
            // Tapping outside collapses back to the ball.
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) showBubble()
                false
            }
        }

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85f).toInt().coerceAtMost(dp(360)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        panel = scroll
        try { wm.addView(scroll, lp) } catch (_: Exception) { panel = null; showBubble(); return }
        scheduleCollapse(4000)
    }

    private fun addRow(parent: LinearLayout, c: Category) {
        val max = try { am.getStreamMaxVolume(c.stream) } catch (_: Exception) { 0 }
        if (max <= 0) return
        val cur = try { am.getStreamVolume(c.stream) } catch (_: Exception) { 0 }

        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        fun setLabel(v: Int) { label.text = "${c.label}   $v / $max" }
        setLabel(cur)

        val bar = SeekBar(this).apply {
            this.max = max
            progress = cur
            progressTintList = ColorStateList.valueOf(Color.parseColor("#8C9EFF"))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    try {
                        am.setStreamVolume(c.stream, p, 0)
                    } catch (e: SecurityException) {
                        Toast.makeText(
                            this@OverlayService,
                            "Grant Do Not Disturb access in the app to change ${c.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (_: Exception) {}
                    val actual = try { am.getStreamVolume(c.stream) } catch (_: Exception) { p }
                    setLabel(actual)
                    scheduleCollapse(4000)
                }
                override fun onStartTrackingTouch(s: SeekBar) {
                    handler.removeCallbacks(collapseRunnable)
                }
                override fun onStopTrackingTouch(s: SeekBar) { scheduleCollapse(3000) }
            })
        }
        parent.addView(label)
        parent.addView(bar)
    }

    private fun scheduleCollapse(delayMs: Long) {
        handler.removeCallbacks(collapseRunnable)
        handler.postDelayed(collapseRunnable, delayMs)
    }
}
