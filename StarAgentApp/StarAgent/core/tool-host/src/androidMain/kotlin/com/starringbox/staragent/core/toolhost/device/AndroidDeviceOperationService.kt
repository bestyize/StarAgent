package com.starringbox.staragent.core.toolhost.device

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
import android.text.TextUtils
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
    private var foregroundPackage: String? = null
    private var expanded = false
    private var isPaused = false
    private var taskFinished = false
    private var currentStatusText = "正在准备操作..."
    private var touchSlop = 8
    private var overlayRoot: LinearLayout? = null
    private var bubbleIcon: TextView? = null
    private var statusTextView: TextView? = null
    private var actionMenuView: LinearLayout? = null
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
            // Android 8.0+ 要求 startForegroundService 后必须在超时前调用 startForeground，
            // 否则会抛出 ForegroundServiceDidNotStartInTimeException 崩溃。无论早退还是停止均须履约。
            ensureForeground()
            AndroidAccessibilityController.requestStopFromUser()
            hideOverlay()
            notificationText = "手机操作已停止，Agent 正在结束本轮。"
            notificationManager().notify(NOTIFICATION_ID, buildNotification())
            stopForeground(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (finishRequestedBeforeStart) {
            finishRequestedBeforeStart = false
            ensureForeground()
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        sessionActive = true
        taskFinished = false
        isPaused = false
        expanded = false
        currentStatusText = "正在准备操作..."
        notificationText = if (canDrawOverlay()) {
            "星环正在操作手机。点击通知可返回星环。"
        } else {
            "悬浮窗未授权；操作期间可从通知返回星环。"
        }

        ensureForeground()
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
        updateOverlayForForeground()
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, buildNotification())
            foregroundStarted = true
        }
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
        val currentFg = foregroundPackage
        val inStarAgent = currentFg != null && currentFg == packageName
        val inSettings = currentFg != null && isSystemSettingsPackage(currentFg)

        if (inStarAgent && taskFinished) {
            stopSession()
            return
        }

        val shouldShow = !inStarAgent && !inSettings
        if (shouldShow) showOverlay() else hideOverlay()
    }

    private fun isSystemSettingsPackage(value: String): Boolean =
        value == "com.android.settings" ||
            value == "com.android.permissioncontroller" ||
            value == "com.google.android.permissioncontroller" ||
            value == "com.android.systemui"

    private fun showOverlay() {
        if (!canDrawOverlay()) return
        if (overlayRoot != null) return

        // 1. 左球：42dp x 42dp 圆形半透明深色悬浮球，中间显示 ⏸ (运行中) 或 ▶ (已暂停) 或 ✓ (已完成)
        val ballView = TextView(this).apply {
            text = when {
                taskFinished -> "✓"
                isPaused -> "▶"
                else -> "⏸"
            }
            textSize = if (taskFinished) 18f else 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = if (taskFinished) "操作已完成，点击返回星环" else "暂停或继续操作"
            background = roundedBackground(
                if (taskFinished) Color.argb(225, 34, 102, 58) else Color.argb(215, 32, 35, 52),
                dp(21),
                if (taskFinished) Color.argb(160, 74, 222, 128) else Color.argb(140, 150, 155, 195),
            )
            elevation = dp(6).toFloat()
        }
        bubbleIcon = ballView

        // 2. 右侧单行文本胶囊：34dp 高度，半透明深色圆角矩形，包含当前实时操作文本
        val statusView = TextView(this).apply {
            text = when {
                taskFinished -> "操作已完成 · 点击返回星环"
                isPaused -> "已暂停 · 点击继续"
                else -> currentStatusText
            }
            textSize = 12f
            setTextColor(Color.WHITE)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(210)
            gravity = Gravity.CENTER_VERTICAL
        }
        statusTextView = statusView

        val capsuleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(
                Color.argb(190, 20, 22, 34),
                dp(17),
                Color.argb(100, 110, 115, 150),
            )
            elevation = dp(4).toFloat()
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // 3. 点击胶囊时展开的快捷菜单面板（返回星环 / 结束操作）
        val menuView = createControlPanel()
        menuView.visibility = if (expanded) View.VISIBLE else View.GONE
        actionMenuView = menuView

        // 4. 组装整行条目：左球 + 间距 + 右侧单行文本胶囊
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        topBar.addView(
            ballView,
            LinearLayout.LayoutParams(dp(42), dp(42)),
        )
        topBar.addView(
            capsuleBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34),
            ).apply {
                leftMargin = dp(6)
            },
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(
            topBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            menuView,
            LinearLayout.LayoutParams(
                dp(250),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
            },
        )

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }

        installDragHandler(ballView) {
            if (taskFinished) {
                openStarAgent()
            } else {
                AndroidAccessibilityController.togglePause()
            }
        }
        installDragHandler(capsuleBar) {
            if (taskFinished) {
                openStarAgent()
            } else {
                setExpanded(!expanded)
            }
        }

        try {
            windowManager.addView(root, params)
            overlayRoot = root
            overlayParams = params
        } catch (_: RuntimeException) {
            overlayRoot = null
            bubbleIcon = null
            statusTextView = null
            actionMenuView = null
            overlayParams = null
        }
    }

    private fun createControlPanel(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = roundedBackground(Color.argb(230, 26, 28, 38), dp(16), Color.argb(120, 80, 85, 115))
            elevation = dp(8).toFloat()
        }
        val title = TextView(this).apply {
            text = "星环手机操作中"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(this).apply {
            text = "左球可暂停/继续，整条可随处拖动"
            textSize = 11f
            setTextColor(Color.rgb(180, 185, 200))
            setPadding(0, dp(3), 0, dp(8))
        }
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(
            actionButton("返回星环") { openStarAgent() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply {
                bottomMargin = dp(6)
            },
        )
        card.addView(
            actionButton("结束手机操作") { requestStopOperation() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)),
        )
        return card
    }

    private fun actionButton(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.argb(200, 52, 54, 70), dp(10), Color.argb(120, 90, 95, 115))
            isClickable = true
            isFocusable = false
            setOnClickListener { action() }
        }

    private fun installDragHandler(view: View, onClick: () -> Unit) {
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
                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels
                        val currentWidth = overlayRoot?.width ?: dp(250)
                        val maxX = max(0, screenWidth - currentWidth)
                        val maxY = max(0, screenHeight - dp(56))
                        params.x = (startX + (event.rawX - downX).toInt()).coerceIn(0, maxX)
                        params.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, maxY)
                        overlayRoot?.let { root -> runCatching { windowManager.updateViewLayout(root, params) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        onClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        val menu = actionMenuView ?: return
        val params = overlayParams ?: return
        menu.visibility = if (expanded) View.VISIBLE else View.GONE
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
        stopSession()
    }

    private fun hideOverlay() {
        val view = overlayRoot ?: return
        runCatching { windowManager.removeViewImmediate(view) }
        overlayRoot = null
        bubbleIcon = null
        statusTextView = null
        actionMenuView = null
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
            val service = instance
            if (service == null) {
                finishRequestedBeforeStart = true
                return
            }
            service.mainHandler.post {
                service.taskFinished = true
                service.isPaused = false
                service.expanded = false
                service.currentStatusText = "操作已完成 · 点击返回星环"
                service.bubbleIcon?.apply {
                    text = "✓"
                    background = service.roundedBackground(
                        Color.argb(225, 34, 102, 58),
                        service.dp(21),
                        Color.argb(160, 74, 222, 128),
                    )
                }
                service.statusTextView?.text = "操作已完成 · 点击返回星环"
                service.actionMenuView?.visibility = View.GONE
                service.notificationText = "操作已完成。点击通知可返回星环。"
                service.notificationManager().notify(NOTIFICATION_ID, service.buildNotification())
                service.updateOverlayForForeground()
            }
        }

        fun onForegroundPackageChanged(packageName: String?) {
            val service = instance ?: return
            service.mainHandler.post {
                if (packageName != null) service.foregroundPackage = packageName
                service.updateOverlayForForeground()
            }
        }

        fun updateStatus(text: String) {
            val service = instance ?: return
            service.mainHandler.post {
                if (service.taskFinished) return@post
                service.currentStatusText = text
                if (!service.isPaused) {
                    service.statusTextView?.text = text
                }
            }
        }

        fun updatePauseState(paused: Boolean) {
            val service = instance ?: return
            service.mainHandler.post {
                if (service.taskFinished) return@post
                service.isPaused = paused
                service.bubbleIcon?.text = if (paused) "▶" else "⏸"
                service.statusTextView?.text = if (paused) "已暂停 · 点击继续" else service.currentStatusText
            }
        }
    }
}
