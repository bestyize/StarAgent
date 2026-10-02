package com.starringbox.staragent.core.platform.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps a user-started phone-operation turn alive and exposes its controls above the target app.
 *
 * The accessibility service reports the foreground package so the bubble is hidden in StarAgent
 * and system settings, then shown over the requested target app. Without overlay access the
 * ongoing notification remains the fallback entry point.
 */
class AndroidDeviceOperationService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var foregroundStarted = false
    private var sessionActive = false
    private var targetPackage: String? = null
    private var foregroundPackage: String? = null
    private var expanded = false
    private var touchSlop = 8
    private var overlayRoot: LinearLayout? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var notificationText = "星环正在操作手机。"

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OPERATION) {
            if (!foregroundStarted) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            AndroidAccessibilityController.requestStopFromUser()
            hideOverlay()
            notificationText = "手机操作已停止，Agent 正在结束本轮。"
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
            return START_NOT_STICKY
        }

        if (finishRequestedBeforeStart) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        finishRequestedBeforeStart = false
        sessionActive = true
        intent?.getStringExtra(EXTRA_TARGET_PACKAGE)?.let { targetPackage = it }
        notificationText = if (canDrawOverlay()) {
            "星环正在操作手机。点击通知可返回星环。"
        } else {
            "悬浮窗未授权；操作期间可从通知返回星环。"
        }

        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted = true
        } else {
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
        }
        updateOverlayForForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sessionActive = false
        hideOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "星环手机操作",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示当前手机操作状态，并提供返回和结束入口"
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                OPEN_REQUEST_CODE,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, AndroidDeviceOperationService::class.java).setAction(ACTION_STOP_OPERATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("星环智能 Agent")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "结束手机操作",
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun updateOverlayForForeground() {
        if (!sessionActive) {
            hideOverlay()
            return
        }
        val target = targetPackage ?: return
        val visible = foregroundPackage == target && !isSystemSettingsPackage(target)
        if (visible) showOverlay() else hideOverlay()
    }

    private fun isSystemSettingsPackage(value: String): Boolean =
        value == "com.android.settings" ||
            value == "com.android.permissioncontroller" ||
            value == "com.google.android.permissioncontroller" ||
            value == "com.android.systemui"

    private fun showOverlay() {
        if (!canDrawOverlay()) return
        if (overlayRoot != null) return

        val bubbleView = TextView(this).apply {
            text = "✦"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "星环操作控制"
            background = roundedBackground(Color.rgb(87, 78, 225), dp(28), Color.rgb(177, 172, 255))
            elevation = dp(8).toFloat()
            setOnClickListener { setExpanded(!expanded) }
        }

        val panelView = createControlPanel()
        panelView.visibility = if (expanded) View.VISIBLE else View.GONE

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(
            bubbleView,
            LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                bottomMargin = if (expanded) dp(8) else 0
            },
        )
        root.addView(panelView, LinearLayout.LayoutParams(dp(248), LinearLayout.LayoutParams.WRAP_CONTENT))

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            if (expanded) dp(248) else dp(56),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(160)
        }

        installDragHandler(bubbleView)
        try {
            windowManager.addView(root, params)
            overlayRoot = root
            bubble = bubbleView
            panel = panelView
            overlayParams = params
        } catch (_: RuntimeException) {
            overlayRoot = null
            bubble = null
            panel = null
            overlayParams = null
        }
    }

    private fun createControlPanel(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = roundedBackground(Color.rgb(31, 33, 43), dp(18), Color.rgb(73, 76, 92))
            elevation = dp(8).toFloat()
        }
        val title = TextView(this).apply {
            text = "星环正在操作"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(this).apply {
            text = "悬浮球可拖动，手机操作仍由你授权"
            textSize = 12f
            setTextColor(Color.rgb(190, 193, 204))
            setPadding(0, dp(4), 0, dp(10))
        }
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(
            actionButton("返回星环") { openStarAgent() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply {
                bottomMargin = dp(8)
            },
        )
        card.addView(
            actionButton("结束手机操作") { requestStopOperation() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)),
        )
        return card
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(58, 60, 74), dp(12), Color.rgb(94, 97, 115))
            isClickable = true
            isFocusable = false
            setOnClickListener { action() }
        }

    private fun installDragHandler(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { touched, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        val maxX = max(0, resources.displayMetrics.widthPixels - params.width)
                        val maxY = max(0, resources.displayMetrics.heightPixels - dp(56))
                        params.x = (startX + (event.rawX - downX).toInt()).coerceIn(0, maxX)
                        params.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, maxY)
                        overlayRoot?.let { root -> runCatching { windowManager.updateViewLayout(root, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) touched.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        val panelView = panel ?: return
        val params = overlayParams ?: return
        panelView.visibility = if (expanded) View.VISIBLE else View.GONE
        val screenWidth = resources.displayMetrics.widthPixels
        params.width = if (expanded) dp(248) else dp(56)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.x = params.x.coerceIn(0, max(0, screenWidth - params.width))
        overlayRoot?.let { root -> runCatching { windowManager.updateViewLayout(root, params) } }
    }

    private fun openStarAgent() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun requestStopOperation() {
        AndroidAccessibilityController.requestStopFromUser()
        hideOverlay()
        notificationText = "手机操作已停止，Agent 正在结束本轮。"
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
    }

    private fun hideOverlay() {
        val view = overlayRoot ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        overlayRoot = null
        bubble = null
        panel = null
        overlayParams = null
        expanded = false
    }

    private fun roundedBackground(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun stopSession() {
        sessionActive = false
        hideOverlay()
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foregroundStarted = false
        }
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "staragent_device_operation"
        private const val NOTIFICATION_ID = 7301
        private const val OPEN_REQUEST_CODE = 7302
        private const val STOP_REQUEST_CODE = 7303
        private const val ACTION_STOP_OPERATION = "com.starringbox.staragent.action.STOP_DEVICE_OPERATION"
        private const val EXTRA_TARGET_PACKAGE = "target_package"

        @Volatile
        private var instance: AndroidDeviceOperationService? = null

        @Volatile
        private var finishRequestedBeforeStart = false

        fun start(context: Context, targetPackage: String): Boolean {
            finishRequestedBeforeStart = false
            return runCatching {
                val intent = Intent(context, AndroidDeviceOperationService::class.java)
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.isSuccess
        }

        fun finishSession() {
            finishRequestedBeforeStart = true
            val service = instance ?: return
            service.mainHandler.post { service.stopSession() }
        }

        fun onForegroundPackageChanged(packageName: String?) {
            val service = instance ?: return
            service.mainHandler.post {
                if (packageName != null) service.foregroundPackage = packageName
                service.updateOverlayForForeground()
            }
        }
    }
}
