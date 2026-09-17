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
import androidx.compose.runtime.remember
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
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.platform.storage.LocalStores

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
