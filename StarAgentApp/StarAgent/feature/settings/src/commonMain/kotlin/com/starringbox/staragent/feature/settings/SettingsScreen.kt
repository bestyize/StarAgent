package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppInfo
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.CircleIconButton
import com.starringbox.staragent.core.designsystem.component.SettingsRow
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarDivider
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.toolhost.permissions.PermissionRequests
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.tools.ports.PermissionCapability
import com.starringbox.staragent.core.tools.ports.PermissionStatus
import kotlinx.coroutines.launch

/**
 * 设置页。从「我的」右上角的齿轮进来，右上角再进**文件浏览器**。
 *
 * 这一页目前就两节：怎么去看自己的东西存在哪（存储），以及现在存了些什么（关于与统计）。
 * 「上次启动有几个文件读不出来」是这一节存在的一个具体理由——
 * 目录树让"坏一个只坏一个"成为可能，但这件事**得说出来**，
 * 否则坏了几个文件、内容少了几条，用户只会觉得"它怎么自己少东西了"。
 */
@Composable
fun SettingsScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val hasStorage = LocalStores.available
    val coroutineScope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableStateOf(0) }

    // 统计一次目录要走一遍树，别每次重组都来一遍。几个数量变了才重算——
    // 那是这页上唯一会变的东西（存档刚被改写）。
    val storage = remember(
        hasStorage,
        state.agents.size,
        state.tasks.size,
        state.memories.size,
        state.conversationCount,
    ) {
        readStorageStats()
    }

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "设置",
            onBack = { navigator.pop() },
            actions = {
                // 没有存储实现的平台（iOS 上还没有 LocalStore）连按钮都不摆，
                // 而不是给一个点了没反应的入口——"没有能力就别摆按钮"是这个项目一贯的约定。
                if (hasStorage) {
                    CircleIconButton(
                        glyph = StarIconGlyph.Folder,
                        onClick = { navigator.push(Route.StorageBrowser("")) },
                    )
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SectionLabel("执行权限") }
            item {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        val policy = state.executionPolicy
                        SettingsRow(
                            glyph = StarIconGlyph.Bolt,
                            title = ExecutionPolicy.Auto.label,
                            subtitle = ExecutionPolicy.Auto.summary,
                            containerColor = if (policy == ExecutionPolicy.Auto) Color(0xFFE7E8FF) else StarPalette.SurfaceAlt,
                            tint = if (policy == ExecutionPolicy.Auto) StarPalette.Indigo else StarPalette.TextFaint,
                            trailing = if (policy == ExecutionPolicy.Auto) {
                                {
                                    StarIcon(
                                        icon = StarIconGlyph.Check,
                                        iconSize = 16.dp,
                                        tint = StarPalette.Indigo,
                                        strokeWidth = 2.2f,
                                    )
                                }
                            } else null,
                            onClick = { state.selectExecutionPolicy(ExecutionPolicy.Auto) },
                        )
                        StarDivider(inset = 56.dp)
                        SettingsRow(
                            glyph = StarIconGlyph.Task,
                            title = ExecutionPolicy.OnDemand.label,
                            subtitle = ExecutionPolicy.OnDemand.summary,
                            containerColor = if (policy == ExecutionPolicy.OnDemand) Color(0xFFE7E8FF) else StarPalette.SurfaceAlt,
                            tint = if (policy == ExecutionPolicy.OnDemand) StarPalette.Indigo else StarPalette.TextFaint,
                            trailing = if (policy == ExecutionPolicy.OnDemand) {
                                {
                                    StarIcon(
                                        icon = StarIconGlyph.Check,
                                        iconSize = 16.dp,
                                        tint = StarPalette.Indigo,
                                        strokeWidth = 2.2f,
                                    )
                                }
                            } else null,
                            onClick = { state.selectExecutionPolicy(ExecutionPolicy.OnDemand) },
                        )
                    }
                }
            }

            item { SectionLabel("系统功能权限") }
            item {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SystemPermissionRow(
                            title = "自动化操作（无障碍服务）",
                            descGranted = "已开启 · 支持智能体在屏幕上点击、输入与阅读界面",
                            descRequiresSettings = "未开启 · 点击一键前往系统设置启用",
                            capability = PermissionCapability.Accessibility,
                            refreshTrigger = refreshTrigger,
                            onRequest = {
                                coroutineScope.launch {
                                    PermissionRequests.request(PermissionCapability.Accessibility)
                                    refreshTrigger++
                                }
                            },
                        )
                        StarDivider(inset = 56.dp)
                        SystemPermissionRow(
                            title = "跨应用悬浮控制球",
                            descGranted = "已开启 · 跨应用操作时展示进度胶囊与随时暂停",
                            descRequiresSettings = "未开启 · 点击前往允许在其他应用上层显示",
                            capability = PermissionCapability.Overlay,
                            refreshTrigger = refreshTrigger,
                            onRequest = {
                                coroutineScope.launch {
                                    PermissionRequests.request(PermissionCapability.Overlay)
                                    refreshTrigger++
                                }
                            },
                        )
                        StarDivider(inset = 56.dp)
                        SystemPermissionRow(
                            title = "任务与操作通知",
                            descGranted = "已开启 · 可在通知栏查看手机操作状态与任务提醒",
                            descRequiresSettings = "未开启 · 点击授权通知权限",
                            capability = PermissionCapability.Notifications,
                            refreshTrigger = refreshTrigger,
                            onRequest = {
                                coroutineScope.launch {
                                    PermissionRequests.request(PermissionCapability.Notifications)
                                    refreshTrigger++
                                }
                            },
                        )
                        StarDivider(inset = 56.dp)
                        SystemPermissionRow(
                            title = "定时任务准时唤醒",
                            descGranted = "已开启 · 支持精确闹钟唤醒执行计划任务",
                            descRequiresSettings = "未开启 · 点击前往允许设置精确闹钟",
                            capability = PermissionCapability.ExactAlarm,
                            refreshTrigger = refreshTrigger,
                            onRequest = {
                                coroutineScope.launch {
                                    PermissionRequests.request(PermissionCapability.ExactAlarm)
                                    refreshTrigger++
                                }
                            },
                        )
                        StarDivider(inset = 56.dp)
                        SystemPermissionRow(
                            title = "后台保活（忽略电池优化）",
                            descGranted = "已加入白名单 · 防止后台被系统休眠清理",
                            descRequiresSettings = "未加入 · 点击弹出系统授权加入白名单",
                            capability = PermissionCapability.BatteryOptimization,
                            refreshTrigger = refreshTrigger,
                            onRequest = {
                                coroutineScope.launch {
                                    PermissionRequests.request(PermissionCapability.BatteryOptimization)
                                    refreshTrigger++
                                }
                            },
                        )
                        StarDivider(inset = 56.dp)
                        SystemPermissionRow(
                            title = "语音对话（麦克风）",
                            descGranted = "已授权 · 支持语音输入与识别",
                            descRequiresSettings = "未授权 · 点击调起麦克风权限申请",
                            capability = PermissionCapability.Microphone,
                            refreshTrigger = refreshTrigger,
                            onRequest = {
                                coroutineScope.launch {
                                    PermissionRequests.request(PermissionCapability.Microphone)
                                    refreshTrigger++
                                }
                            },
                        )
                    }
                }
            }

            item { SectionLabel("存储") }
            item {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        SettingsRow(
                            glyph = StarIconGlyph.Folder,
                            title = "浏览文件结构",
                            subtitle = if (hasStorage && storage.root != null) {
                                "${storage.files} 个文件 · ${formatBytes(storage.bytes)}"
                            } else {
                                "这台设备上没有可浏览的存储"
                            },
                            onClick = if (hasStorage && storage.root != null) {
                                { navigator.push(Route.StorageBrowser("")) }
                            } else {
                                null
                            },
                        )
                        if (storage.root != null) {
                            SettingsRow(
                                glyph = StarIconGlyph.Info,
                                title = "存放位置",
                                subtitle = storage.root,
                                containerColor = StarPalette.SurfaceAlt,
                            )
                        }
                    }
                }
            }

            item { SectionLabel("关于与统计") }
            item {
                StarCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = AppInfo.NAME,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = StarPalette.Text,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "版本 ${AppInfo.VERSION}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextMuted,
                        )

                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCell("智能体", state.agents.size.toString(), Modifier.weight(1f))
                            StatCell("会话", state.conversationCount.toString(), Modifier.weight(1f))
                            StatCell("任务", state.tasks.size.toString(), Modifier.weight(1f))
                            StatCell("记忆", state.memories.size.toString(), Modifier.weight(1f))
                        }
                    }
                }
            }

            // 只在真的出过问题时才出现。平时不摆一个恒为 0 的行——
            // 那样它就成了背景噪音，真出事时反而没人会注意到。
            if (state.loadIssues > 0) {
                item {
                    StarCard(modifier = Modifier.fillMaxWidth()) {
                        SettingsRow(
                            glyph = StarIconGlyph.Info,
                            title = "上次启动有 ${state.loadIssues} 个文件读不出来",
                            subtitle = "已移到 backups/broken/，其余内容照常加载",
                            tint = StarPalette.Text,
                            containerColor = StarPalette.SurfaceSunken,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = StarPalette.TextFaint,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(StarPalette.SurfaceAlt)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = StarPalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = StarPalette.TextMuted,
        )
    }
}

/** 存储的规模：有多少个文件、一共多少字节、根目录在哪。 */
private data class StorageStats(val files: Int, val bytes: Long, val root: String?)

private fun readStorageStats(): StorageStats {
    if (!LocalStores.available) return StorageStats(0, 0, null)
    val store = LocalStores.current
    // 读不到就当空的：详情页少显示两个数字，不该让整页崩掉
    val paths = runCatching { store.list("") }.getOrDefault(emptyList())
    val bytes = paths.sumOf { path -> runCatching { store.size(path) ?: 0L }.getOrDefault(0L) }
    return StorageStats(files = paths.size, bytes = bytes, root = runCatching { store.rootPath }.getOrNull())
}

/** 文件大小给人看的样子。存档里的东西从几 KB 到几 MB 都有，一档到底不合适。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)}.${(bytes % (1024 * 1024)) * 10 / (1024 * 1024)} MB"
}

@Composable
private fun SystemPermissionRow(
    title: String,
    descGranted: String,
    descRequiresSettings: String,
    capability: PermissionCapability,
    refreshTrigger: Int,
    onRequest: () -> Unit,
) {
    val status = remember(refreshTrigger, capability) {
        PermissionRequests.status(capability)
    }
    val granted = status == PermissionStatus.Granted
    val unsupported = status == PermissionStatus.Unsupported

    if (unsupported) return

    SettingsRow(
        glyph = when (capability) {
            PermissionCapability.Accessibility -> StarIconGlyph.Task
            PermissionCapability.Overlay -> StarIconGlyph.Sparkle
            PermissionCapability.Notifications -> StarIconGlyph.Chat
            PermissionCapability.ExactAlarm -> StarIconGlyph.Repeat
            PermissionCapability.BatteryOptimization -> StarIconGlyph.Bolt
            PermissionCapability.Microphone -> StarIconGlyph.Mic
        },
        title = title,
        subtitle = if (granted) descGranted else descRequiresSettings,
        containerColor = if (granted) Color(0xFFF0FDF4) else StarPalette.SurfaceAlt,
        tint = if (granted) Color(0xFF16A34A) else StarPalette.TextFaint,
        trailing = {
            if (granted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StarIcon(
                        icon = StarIconGlyph.Check,
                        iconSize = 15.dp,
                        tint = Color(0xFF16A34A),
                        strokeWidth = 2.2f,
                    )
                    Text(
                        text = "已就绪",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFE0E3FF))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "去开启",
                        style = MaterialTheme.typography.labelSmall,
                        color = StarPalette.Indigo,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        onClick = if (!granted) onRequest else null,
    )
}

