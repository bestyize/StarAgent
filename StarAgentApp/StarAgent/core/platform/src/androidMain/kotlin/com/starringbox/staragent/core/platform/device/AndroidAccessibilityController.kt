package com.starringbox.staragent.core.platform.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.starringbox.staragent.core.tools.ports.DeviceActionResult
import com.starringbox.staragent.core.tools.ports.DeviceElement
import com.starringbox.staragent.core.tools.ports.DevicePort
import com.starringbox.staragent.core.tools.ports.DeviceScope
import com.starringbox.staragent.core.tools.ports.DeviceScreenResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    private val sequence = AtomicLong(0)
    private val targets = mutableMapOf<String, Target>()

    fun attach(value: AccessibilityService) {
        service = value
    }

    fun detach(value: AccessibilityService) {
        if (service === value) service = null
        targets.clear()
    }

    fun isConnected(): Boolean = service != null

    override suspend fun inspect(scope: DeviceScope): DeviceScreenResult = withContext(Dispatchers.Main.immediate) {
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
        val node = resolve(screenId, elementId) ?: return@withContext DeviceActionResult(false, "控件句柄已失效，请重新 inspect")
        if (!node.isEnabled) return@withContext DeviceActionResult(false, "控件当前不可用")
        if (!node.isClickable) return@withContext DeviceActionResult(false, "控件不可点击")
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) DeviceActionResult(true, "已点击控件")
        else DeviceActionResult(false, "控件拒绝点击操作")
    }

    override suspend fun type(screenId: String, elementId: String?, text: String): DeviceActionResult = withContext(Dispatchers.Main.immediate) {
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
        val current = service ?: return@withContext DeviceActionResult(false, "无障碍服务未连接")
        if (current.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) DeviceActionResult(true, "已返回")
        else DeviceActionResult(false, "系统拒绝返回操作")
    }

    override suspend fun launchApp(packageOrBundleId: String): DeviceActionResult = withContext(Dispatchers.Main.immediate) {
        val current = service ?: return@withContext DeviceActionResult(false, "无障碍服务未连接")
        val intent = current.packageManager.getLaunchIntentForPackage(packageOrBundleId)
            ?: return@withContext DeviceActionResult(false, "找不到可启动的应用：$packageOrBundleId")
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            current.startActivity(intent)
        }.fold(
            onSuccess = { DeviceActionResult(true, "已启动应用：$packageOrBundleId") },
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
