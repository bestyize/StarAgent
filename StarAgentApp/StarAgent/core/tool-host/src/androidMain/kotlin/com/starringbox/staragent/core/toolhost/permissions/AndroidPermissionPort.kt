package com.starringbox.staragent.core.toolhost.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import com.starringbox.staragent.core.toolhost.device.AndroidAccessibilityService
import com.starringbox.staragent.core.tools.ports.PermissionCapability
import com.starringbox.staragent.core.tools.ports.PermissionPort
import com.starringbox.staragent.core.tools.ports.PermissionRequestResult
import com.starringbox.staragent.core.tools.ports.PermissionStatus
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android 权限桥接器。
 *
 * 运行时权限交给系统弹窗；无障碍和精确闹钟跳转到对应系统设置，
 * 用户返回应用后再检查实际状态。权限不会由应用自动打开。
 */
private class AndroidPermissionPort(
    context: Context,
    caller: ActivityResultCaller,
) : PermissionPort {
    private val appContext = context.applicationContext
    private val requestMutex = Mutex()
    private var pendingRuntimeRequest: CancellableContinuation<Boolean>? = null
    private var pendingSettingsRequest: CancellableContinuation<Unit>? = null

    private val runtimePermissionLauncher =
        caller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val continuation = pendingRuntimeRequest
            pendingRuntimeRequest = null
            if (continuation?.isActive == true) continuation.resume(granted)
        }

    private val settingsLauncher =
        caller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val continuation = pendingSettingsRequest
            pendingSettingsRequest = null
            if (continuation?.isActive == true) continuation.resume(Unit)
        }

    override fun status(capability: PermissionCapability): PermissionStatus =
        when (capability) {
            PermissionCapability.Microphone ->
                runtimeStatus(Manifest.permission.RECORD_AUDIO)

            PermissionCapability.Notifications ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) PermissionStatus.Granted
                else runtimeStatus(Manifest.permission.POST_NOTIFICATIONS)

            PermissionCapability.Accessibility ->
                if (isAccessibilityServiceEnabled()) PermissionStatus.Granted
                else PermissionStatus.RequiresSettings

            PermissionCapability.ExactAlarm ->
                if (canScheduleExactAlarms()) PermissionStatus.Granted
                else PermissionStatus.RequiresSettings

            PermissionCapability.Overlay ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)) {
                    PermissionStatus.Granted
                } else {
                    PermissionStatus.RequiresSettings
                }

            PermissionCapability.BatteryOptimization ->
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    PermissionStatus.Granted
                } else {
                    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true) {
                        PermissionStatus.Granted
                    } else {
                        PermissionStatus.RequiresSettings
                    }
                }
        }

    override suspend fun request(capability: PermissionCapability): PermissionRequestResult =
        requestMutex.withLock {
            if (status(capability) == PermissionStatus.Granted) {
                return@withLock PermissionRequestResult(
                    PermissionStatus.Granted,
                    label(capability) + "权限已经开启。",
                )
            }

            when (capability) {
                PermissionCapability.Microphone ->
                    requestRuntimePermission(
                        capability,
                        Manifest.permission.RECORD_AUDIO,
                    )

                PermissionCapability.Notifications ->
                    if (Build.VERSION.SDK_INT < 33) {
                        PermissionRequestResult(PermissionStatus.Granted, "当前 Android 版本不需要单独申请通知权限。")
                    } else {
                        val runtimeRes = requestRuntimePermission(
                            capability,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                        if (runtimeRes.status == PermissionStatus.Granted) {
                            runtimeRes
                        } else {
                            openSettings(
                                capability = capability,
                                intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName),
                            )
                        }
                    }

                PermissionCapability.Accessibility -> {
                    val targetComponent = ComponentName(appContext, AndroidAccessibilityService::class.java).flattenToString()
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        putExtra(":settings:fragment_args_key", targetComponent)
                        val bundle = Bundle().apply {
                            putString(":settings:fragment_args_key", targetComponent)
                        }
                        putExtra(":settings:show_fragment_args", bundle)
                    }
                    openSettings(
                        capability = capability,
                        intent = intent,
                    )
                }

                PermissionCapability.ExactAlarm ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        PermissionRequestResult(PermissionStatus.Granted, "当前 Android 版本不需要单独申请精确闹钟权限。")
                    } else {
                        openSettings(
                            capability = capability,
                            intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:" + appContext.packageName)),
                        )
                    }

                PermissionCapability.Overlay ->
                    openSettings(
                        capability = capability,
                        intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .setData(Uri.parse("package:" + appContext.packageName)),
                    )

                PermissionCapability.BatteryOptimization ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        PermissionRequestResult(PermissionStatus.Granted, "当前系统版本不需要单独设置忽略电池优化。")
                    } else {
                        openSettings(
                            capability = capability,
                            intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:" + appContext.packageName)),
                        )
                    }
            }
        }

    private fun runtimeStatus(permission: String): PermissionStatus =
        if (appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.NotGranted
        }

    private suspend fun requestRuntimePermission(
        capability: PermissionCapability,
        permission: String,
    ): PermissionRequestResult {
        var launchFailure: Throwable? = null
        val granted = try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    pendingRuntimeRequest = continuation
                    continuation.invokeOnCancellation {
                        if (pendingRuntimeRequest === continuation) pendingRuntimeRequest = null
                    }
                    runCatching {
                        runtimePermissionLauncher.launch(permission)
                    }.onFailure { error ->
                        launchFailure = error
                        pendingRuntimeRequest = null
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return PermissionRequestResult(
                PermissionStatus.NotGranted,
                "无法打开系统权限请求：" + (error.message ?: "未知错误"),
            )
        }

        if (launchFailure != null) {
            return PermissionRequestResult(
                PermissionStatus.NotGranted,
                "无法打开系统权限请求：" + (launchFailure?.message ?: "未知错误"),
            )
        }

        return if (granted || status(capability) == PermissionStatus.Granted) {
            PermissionRequestResult(PermissionStatus.Granted, label(capability) + "权限已开启。")
        } else {
            PermissionRequestResult(PermissionStatus.NotGranted, "你没有授予" + label(capability) + "权限。")
        }
    }

    private suspend fun openSettings(
        capability: PermissionCapability,
        intent: Intent,
    ): PermissionRequestResult {
        var launchFailure: Throwable? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    pendingSettingsRequest = continuation
                    continuation.invokeOnCancellation {
                        if (pendingSettingsRequest === continuation) pendingSettingsRequest = null
                    }
                    runCatching {
                        settingsLauncher.launch(intent)
                    }.onFailure { error ->
                        launchFailure = error
                        pendingSettingsRequest = null
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return PermissionRequestResult(
                PermissionStatus.NotGranted,
                "无法打开系统设置：" + (error.message ?: "未知错误"),
            )
        }

        if (launchFailure != null) {
            return PermissionRequestResult(
                PermissionStatus.NotGranted,
                "无法打开系统设置：" + (launchFailure?.message ?: "未知错误"),
            )
        }

        return if (status(capability) == PermissionStatus.Granted) {
            PermissionRequestResult(PermissionStatus.Granted, label(capability) + "权限已开启。")
        } else {
            PermissionRequestResult(
                PermissionStatus.RequiresSettings,
                settingsReturnMessage(capability),
            )
        }
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val globallyEnabled = Settings.Secure.getInt(
            appContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!globallyEnabled) return false

        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        val target = ComponentName(appContext, AndroidAccessibilityService::class.java)
        return enabledServices
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == target }
    }

    private fun label(capability: PermissionCapability): String =
        when (capability) {
            PermissionCapability.Microphone -> "麦克风"
            PermissionCapability.Notifications -> "通知"
            PermissionCapability.Accessibility -> "设备操作（无障碍服务）"
            PermissionCapability.ExactAlarm -> "精确闹钟"
            PermissionCapability.Overlay -> "悬浮窗（显示在其他应用上层）"
            PermissionCapability.BatteryOptimization -> "忽略电池优化（后台运行）"
        }

    private fun settingsReturnMessage(capability: PermissionCapability): String =
        when (capability) {
            PermissionCapability.Accessibility ->
                "已打开系统无障碍设置。进入后请点击「已下载的应用/服务」→ 找到「星环智能 Agent」手动启用；返回后我已检查，当前仍未开启。"
            PermissionCapability.ExactAlarm ->
                "已打开精确闹钟设置。请允许星环智能 Agent 设置精确闹钟，返回后我已检查，当前仍未开启。"
            PermissionCapability.Overlay ->
                "已打开「显示在其他应用上层」设置。请允许星环智能 Agent 显示操作悬浮球；返回后我已检查，当前仍未开启。"
            PermissionCapability.BatteryOptimization ->
                "已请求忽略电池优化。返回后我已检查，当前尚未加入白名单。"
            else -> label(capability) + "权限尚未开启。"
        }
}

/** 在 Activity 初始化阶段调用，以便合法注册系统权限与设置页回调。 */
fun installAndroidPermissions(context: Context, caller: ActivityResultCaller) {
    PermissionRequests.install(AndroidPermissionPort(context.applicationContext, caller))
}
