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
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
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

    private var bubble: ArcView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null

    // Remembered position of the arc: which side and how far down.
    private var onRight = true
    private var arcY = 300

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startAsForeground()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen rotated: re-stick the arc to the edge.
        val lp = bubbleLp ?: return
        val v = bubble ?: return
        lp.x = if (onRight) screenW() - lp.width else 0
        lp.y = arcY.coerceIn(0, (screenH() - lp.height).coerceAtLeast(0))
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

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

    // ------------------------------------------------------------ Edge arc

    /**
     * Idle look: a thin white "(" curve near the screen edge (no fill).
     * While being dragged: a hollow ring with a faint dark centre.
     */
    private inner class ArcView(ctx: Context) : View(ctx) {
        var onRightSide = true
        var ringMode = false

        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
            alpha = 215
            strokeWidth = dp(2).toFloat()
        }
        private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
            alpha = 90
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cy = h / 2f
            if (ringMode) {
                val r = dp(13).toFloat()
                c.drawCircle(w / 2f, cy, r, ringFill)
                c.drawCircle(w / 2f, cy, r, line)
                return
            }
            // Thin curve: part of a circle whose centre lies just outside the screen edge.
            val r = dp(22).toFloat()
            val gap = dp(12).toFloat()          // distance of the curve's tip from the edge
            if (onRightSide) {
                val cx = w - gap + r
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, 130f, 100f, false, line)
            } else {
                val cx = gap - r
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, -50f, 100f, false, line)
            }
        }
    }

    private fun showBubble() {
        handler.removeCallbacks(collapseRunnable)
        removeView(panel); panel = null
        if (bubble != null) return

        val w = dp(32)
        val h = dp(84)
        val arc = ArcView(this).apply { onRightSide = onRight }
        val lp = WindowManager.LayoutParams(
            w, h, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onRight) screenW() - w else 0
            y = arcY.coerceIn(0, (screenH() - h).coerceAtLeast(0))
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        arc.setOnTouchListener { v, e ->
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
                        if (!arc.ringMode) { arc.ringMode = true; arc.invalidate() }
                        lp.x = startX + dx.toInt()
                        lp.y = (startY + dy.toInt()).coerceIn(0, (screenH() - h).coerceAtLeast(0))
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // Snap to the closest side.
                        onRight = (lp.x + w / 2) > screenW() / 2
                        arcY = lp.y
                        lp.x = if (onRight) screenW() - w else 0
                        arc.onRightSide = onRight
                        arc.ringMode = false
                        arc.invalidate()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    } else {
                        arcY = lp.y
                        removeView(bubble); bubble = null; bubbleLp = null
                        showPanel()
                    }
                    true
                }
                else -> false
            }
        }
        bubble = arc
        bubbleLp = lp
        try { wm.addView(arc, lp) } catch (_: Exception) { bubble = null; bubbleLp = null }
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
            // Tapping outside collapses back to the arc.
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) showBubble()
                false
            }
        }

        val lp = WindowManager.LayoutParams(
            (screenW() * 0.85f).toInt().coerceAtMost(dp(360)),
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

    /**
     * Sets a stream volume and makes sure it really changed. Some phones ignore
     * setStreamVolume, so if the value didn't change we step it with the same
     * call the hardware volume keys use. Returns the real resulting volume.
     */
    private fun applyVolume(stream: Int, target: Int): Int {
        try {
            am.setStreamVolume(stream, target, 0)
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
        }
        var actual = am.getStreamVolume(stream)
        var guard = 0
        while (actual != target && guard++ < 40) {
            val dir = if (target > actual) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            try {
                am.adjustStreamVolume(stream, dir, 0)
            } catch (_: Exception) {
                break
            }
            val now = am.getStreamVolume(stream)
            if (now == actual) break
            actual = now
        }
        return actual
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
                    var actual = p
                    try {
                        actual = applyVolume(c.stream, p)
                    } catch (e: SecurityException) {
                        Toast.makeText(
                            this@OverlayService,
                            "Grant Do Not Disturb access in the app to change ${c.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                        actual = try { am.getStreamVolume(c.stream) } catch (_: Exception) { p }
                    } catch (_: Exception) {}
                    setLabel(actual)
                    // If the phone refused, show the real value on the slider.
                    if (actual != p) s.progress = actual
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
