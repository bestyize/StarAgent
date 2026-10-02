package com.starringbox.staragent.core.platform.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.starringbox.staragent.core.tools.ports.DeviceActionResult
import com.starringbox.staragent.core.tools.ports.DeviceAppsResult
import com.starringbox.staragent.core.tools.ports.DeviceElement
import com.starringbox.staragent.core.tools.ports.DevicePort
import com.starringbox.staragent.core.tools.ports.DeviceScope
import com.starringbox.staragent.core.tools.ports.DeviceScreenResult
import com.starringbox.staragent.core.tools.ports.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Android Accessibility 的受控适配器。
 *
 * 不保留 AccessibilityNodeInfo 引用，只保存树路径和 screenId：
 * 页面变化后旧句柄自然失效，避免把一个旧控件误用到新页面。
 */
object AndroidAccessibilityController : DevicePort {
    private const val MAX_NODES = 240
    private const val HANDLE_TTL_MILLIS = 15_000L

    private data class Target(val screenId: String, val packageName: String?, val path: List<Int>, val expiresAt: Long)

    private var service: AccessibilityService? = null
    private var appContext: Context? = null
    private val sequence = AtomicLong(0)
    private val operationStopped = AtomicBoolean(false)
    private val operationSessionStarted = AtomicBoolean(false)
    private val targets = mutableMapOf<String, Target>()

    fun installContext(context: Context) {
        appContext = context.applicationContext
    }

    fun attach(value: AccessibilityService) {
        service = value
    }

    fun detach(value: AccessibilityService) {
        if (service === value) service = null
        targets.clear()
    }

    fun isConnected(): Boolean = service != null

    /** The floating control can stop further phone actions while the Agent finishes its turn. */
    fun requestStopFromUser() {
        operationStopped.set(true)
    }

    override fun finishOperationSession() {
        if (!operationSessionStarted.getAndSet(false)) return
        operationStopped.set(false)
        AndroidDeviceOperationService.finishSession()
    }

    override suspend fun inspect(scope: DeviceScope): DeviceScreenResult = withContext(Dispatchers.Main.immediate) {
        if (operationStopped.get()) return@withContext DeviceScreenResult(false, error = "用户已结束本轮手机操作")
        val current = service ?: return@withContext DeviceScreenResult(false, error = "无障碍服务未连接，请先在系统设置中启用 StarAgent")
        if (!current.serviceInfo.canRetrieveWindowContent) {
            return@withContext DeviceScreenResult(false, error = "无障碍服务没有读取窗口内容权限")
        }
        val root = current.rootInActiveWindow
            ?: return@withContext DeviceScreenResult(false, error = "当前没有可读取的前台窗口")
        val screenId = "screen-${sequence.incrementAndGet()}"
        targets.clear()
        val packageName = root.packageName?.toString()
        val result = mutableListOf<DeviceElement>()
        walk(root, emptyList(), screenId, packageName, result, scope == DeviceScope.FullTree)
        DeviceScreenResult(true, screenId, packageName, result)
    }

    override suspend fun click(screenId: String, elementId: String): DeviceActionResult = withContext(Dispatchers.Main.immediate) {
        if (operationStopped.get()) return@withContext DeviceActionResult(false, "用户已结束本轮手机操作")
        val node = resolve(screenId, elementId) ?: return@withContext DeviceActionResult(false, "控件句柄已失效，请重新 inspect")
        if (!node.isEnabled) return@withContext DeviceActionResult(false, "控件当前不可用")
        if (!node.isClickable) return@withContext DeviceActionResult(false, "控件不可点击")
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) DeviceActionResult(true, "已点击控件")
        else DeviceActionResult(false, "控件拒绝点击操作")
    }

    override suspend fun type(screenId: String, elementId: String?, text: String): DeviceActionResult = withContext(Dispatchers.Main.immediate) {
        if (operationStopped.get()) return@withContext DeviceActionResult(false, "用户已结束本轮手机操作")
        val node = if (elementId == null) {
            service?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } else {
            resolve(screenId, elementId)
        } ?: return@withContext DeviceActionResult(false, "输入控件句柄已失效，请重新 inspect")
        if (!node.isEnabled || !node.isEditable) return@withContext DeviceActionResult(false, "控件不可输入")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) DeviceActionResult(true, "已输入文本")
        else DeviceActionResult(false, "控件拒绝输入操作")
    }

    override suspend fun swipe(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMillis: Long,
    ): DeviceActionResult {
        if (operationStopped.get()) return DeviceActionResult(false, "用户已结束本轮手机操作")
        val current = service ?: return DeviceActionResult(false, "无障碍服务未连接")
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMillis.coerceIn(80L, 2_000L)))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val accepted = current.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(DeviceActionResult(true, "已完成滑动"))
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(DeviceActionResult(false, "滑动被系统取消"))
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) continuation.resume(DeviceActionResult(false, "系统拒绝手势操作"))
        }
    }

    override suspend fun pressBack(): DeviceActionResult = withContext(Dispatchers.Main.immediate) {
        if (operationStopped.get()) return@withContext DeviceActionResult(false, "用户已结束本轮手机操作")
        val current = service ?: return@withContext DeviceActionResult(false, "无障碍服务未连接")
        if (current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) DeviceActionResult(true, "已返回")
        else DeviceActionResult(false, "系统拒绝返回操作")
    }

    override suspend fun listApps(query: String?): DeviceAppsResult = withContext(Dispatchers.Default) {
        val ctx = service ?: appContext ?: return@withContext DeviceAppsResult(false, emptyList(), "无法获取应用上下文")
        val pm = ctx.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        val apps = resolveList.mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(pm).toString().trim()
            InstalledApp(name = label, packageName = pkg)
        }.distinctBy { it.packageName }.sortedBy { it.name }

        val filtered = if (!query.isNullOrBlank()) {
            val q = query.trim()
            apps.filter { it.name.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        } else {
            apps
        }

        DeviceAppsResult(true, filtered)
    }

    private data class AppMatch(val packageName: String, val label: String)

    private fun resolveApp(context: Context, query: String): AppMatch? {
        val trimmed = query.trim()
        val pm = context.packageManager

        // 1. 如果本身是一个有效的包名直接能拿 launch intent
        val directIntent = pm.getLaunchIntentForPackage(trimmed)
        if (directIntent != null) {
            val label = runCatching {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(trimmed, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(trimmed, 0)
                }
                appInfo.loadLabel(pm).toString()
            }.getOrDefault(trimmed)
            return AppMatch(trimmed, label)
        }

        // 2. 遍历桌面启动项进行中文名/拼音/别名匹配
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val allApps = resolveList.mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            val label = resolveInfo.loadLabel(pm).toString().trim()
            AppMatch(pkg, label)
        }

        // 2.1 精确匹配显示名称（忽略大小写）
        allApps.firstOrNull { it.label.equals(trimmed, ignoreCase = true) }?.let { return it }

        // 2.2 精确匹配包名
        allApps.firstOrNull { it.packageName.equals(trimmed, ignoreCase = true) }?.let { return it }

        // 2.3 常用别名映射
        val alias = when (trimmed.lowercase()) {
            "b站", "bilibili" -> "哔哩哔哩"
            "网易云" -> "网易云音乐"
            "高德" -> "高德地图"
            "百度" -> "百度地图"
            "qq音乐" -> "QQ音乐"
            else -> null
        }
        if (alias != null) {
            allApps.firstOrNull { it.label.contains(alias, ignoreCase = true) }?.let { return it }
        }

        // 2.4 子串模糊匹配
        allApps.firstOrNull { it.label.contains(trimmed, ignoreCase = true) || trimmed.contains(it.label, ignoreCase = true) }?.let { return it }

        return null
    }

    override suspend fun launchApp(packageOrBundleId: String): DeviceActionResult = withContext(Dispatchers.Main.immediate) {
        if (operationStopped.get()) return@withContext DeviceActionResult(false, "用户已结束本轮手机操作")
        val ctx = service ?: appContext ?: return@withContext DeviceActionResult(false, "无法获取应用上下文")

        val match = resolveApp(ctx, packageOrBundleId)
        val targetPackage = match?.packageName ?: packageOrBundleId
        val targetLabel = match?.label ?: packageOrBundleId

        val intent = ctx.packageManager.getLaunchIntentForPackage(targetPackage)
            ?: return@withContext DeviceActionResult(false, "找不到应用「$packageOrBundleId」（匹配包名：$targetPackage）。请确认是否已安装或使用 list_apps 查询已安装应用。")

        val externalTarget = targetPackage != ctx.packageName
        if (externalTarget) {
            val started = AndroidDeviceOperationService.start(ctx, targetPackage)
            if (started) {
                operationSessionStarted.set(true)
            }
        }

        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }.fold(
            onSuccess = {
                val hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
                val hasAccessibility = service != null
                val controlHint = when {
                    externalTarget && hasOverlay -> "操作期间会显示星环悬浮控制球。"
                    externalTarget -> "悬浮窗权限未开启，可使用持续通知返回星环。"
                    else -> ""
                }
                val accessibilityHint = if (externalTarget && !hasAccessibility) {
                    "（提示：无障碍服务未开启，目前仅完成应用启动；如需智能体后续自动点击屏幕，请前往系统设置开启星环无障碍服务）"
                } else {
                    ""
                }
                DeviceActionResult(
                    true,
                    listOf("已启动应用：$targetLabel ($targetPackage)", controlHint, accessibilityHint)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                )
            },
            onFailure = { DeviceActionResult(false, "启动应用失败：${it.message ?: "未知错误"}") },
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        screenId: String,
        packageName: String?,
        result: MutableList<DeviceElement>,
        fullTree: Boolean,
    ) {
        if (result.size >= MAX_NODES) return
        val id = "node-${result.size + 1}"
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val password = node.isPassword
        val label = node.contentDescription?.toString().orEmpty().trim()
        val text = if (password) "" else node.text?.toString().orEmpty().trim()
        val role = node.className?.toString()?.substringAfterLast('.') ?: "View"
        if (label.isNotBlank() || text.isNotBlank() || node.isClickable || node.isEditable || node.isFocusable) {
            result += DeviceElement(
                id = id,
                role = role,
                label = label.take(200),
                text = text.take(500),
                className = node.className?.toString().orEmpty().take(200),
                clickable = node.isClickable,
                editable = node.isEditable,
                enabled = node.isEnabled,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
            )
            targets[id] = Target(screenId, packageName, path, System.currentTimeMillis() + HANDLE_TTL_MILLIS)
        }
        if (!fullTree && path.size >= 8) return
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            walk(child, path + index, screenId, packageName, result, fullTree)
        }
    }

    private fun resolve(screenId: String, elementId: String): AccessibilityNodeInfo? {
        val target = targets[elementId] ?: return null
        if (target.screenId != screenId || target.expiresAt < System.currentTimeMillis()) return null
        val root = service?.rootInActiveWindow ?: return null
        if (target.packageName != null && root.packageName?.toString() != target.packageName) return null
        var node = root
        for (index in target.path) node = node.getChild(index) ?: return null
        return node
    }
}
