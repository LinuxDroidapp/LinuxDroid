package com.linuxdroid.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.linuxdroid.app.R
import com.linuxdroid.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * System-Wide Floating Terminal Overlay Service.
 *
 * Provides a draggable system overlay window (TYPE_APPLICATION_OVERLAY)
 * allowing users to monitor terminal / build status while multitasking across Android apps.
 *
 * Session preservation guarantee:
 * Stopping or minimizing the overlay NEVER terminates the underlying Linux shell session or PRoot process.
 */
@AndroidEntryPoint
class FloatingTerminalService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2002
        const val ACTION_STOP = "com.linuxdroid.app.ACTION_STOP_FLOATING"
        const val EXTRA_ENVIRONMENT_ID = "com.linuxdroid.app.EXTRA_ENVIRONMENT_ID"

        fun canDrawOverlay(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun start(context: Context, environmentId: String? = null) {
            val intent = Intent(context, FloatingTerminalService::class.java).apply {
                if (environmentId != null) putExtra(EXTRA_ENVIRONMENT_ID, environmentId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingTerminalService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var environmentId: String? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("FloatingTerminalService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.i("Stop action received in FloatingTerminalService")
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        environmentId = intent?.getStringExtra(EXTRA_ENVIRONMENT_ID)
        startForeground(NOTIFICATION_ID, buildNotification())

        if (canDrawOverlay(this)) {
            showFloatingOverlay()
        } else {
            Timber.w("SYSTEM_ALERT_WINDOW permission not granted; stopping FloatingTerminalService")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun showFloatingOverlay() {
        if (overlayView != null) return

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density

            val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (40 * density).toInt()
                y = (120 * density).toInt()
            }

            // Create pill overlay view programmatically for robust lightweight presentation
            val pillContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = 24 * density
                    setStroke((1.5f * density).toInt(), Color.parseColor("#3B82F6"))
                }
                background = bg
                setPadding(
                    (14 * density).toInt(),
                    (8 * density).toInt(),
                    (12 * density).toInt(),
                    (8 * density).toInt()
                )
                elevation = 16 * density
            }

            // Green terminal status indicator dot
            val dot = View(this).apply {
                val dotBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#22C55E"))
                }
                background = dotBg
                val dotSize = (8 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            pillContainer.addView(dot)

            // Label
            val label = TextView(this).apply {
                text = "LinuxDroid Terminal"
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (12 * density).toInt()
                }
            }
            pillContainer.addView(label)

            // Maximize / Return to fullscreen button
            val maximizeBtn = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_send)
                setColorFilter(Color.parseColor("#60A5FA"))
                val btnSize = (18 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    openAppTerminal()
                }
            }
            pillContainer.addView(maximizeBtn)

            // Close overlay button (does not stop session)
            val closeBtn = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                setColorFilter(Color.parseColor("#94A3B8"))
                val btnSize = (18 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
                setOnClickListener {
                    removeOverlay()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            pillContainer.addView(closeBtn)

            // Draggable touch handling
            pillContainer.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                                isDragging = true
                            }
                            if (isDragging) {
                                params.x = initialX + dx
                                params.y = initialY + dy
                                windowManager?.updateViewLayout(pillContainer, params)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                openAppTerminal()
                            }
                            return true
                        }
                    }
                    return false
                }
            })

            overlayView = pillContainer
            windowManager?.addView(overlayView, params)
            Timber.i("Floating overlay added to WindowManager")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create floating overlay window")
        }
    }

    private fun openAppTerminal() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("navigate_to_terminal", true)
            if (environmentId != null) {
                putExtra("environment_id", environmentId)
            }
        }
        startActivity(launchIntent)
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
                Timber.i("Floating overlay removed from WindowManager")
            } catch (e: Exception) {
                Timber.w(e, "Error removing overlay view")
            }
            overlayView = null
        }
    }

    override fun onDestroy() {
        Timber.i("FloatingTerminalService destroyed; shell session preserved")
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val closeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FloatingTerminalService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, getString(R.string.session_notification_channel_id))
            .setContentTitle("LinuxDroid Floating Terminal")
            .setContentText("Overlay active - tap to return to terminal")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Close Overlay",
                closeIntent,
            )
            .build()
    }
}
