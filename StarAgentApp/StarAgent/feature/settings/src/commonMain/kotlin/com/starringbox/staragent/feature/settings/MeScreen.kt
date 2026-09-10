package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.AgentAvatar
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.SettingsRow
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.component.StarDivider
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette

@Composable
fun MeScreen(navigator: Navigator) {
    val state = LocalAppState.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StarTopBar(title = "我的")

        StarCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentAvatar(glyph = "星", accentIndex = 0, size = 52.dp, cornerRadius = 17.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "星环用户",
                        style = MaterialTheme.typography.titleMedium,
                        color = StarPalette.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "本地模式 · 一期原型",
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            SectionLabel("智能")
            StarCard {
                Column {
                    SettingsRow(
                        glyph = StarIconGlyph.Chip,
                        title = "模型服务",
                        subtitle = "${state.modelLabel(state.defaultModelId)} · " +
                            "${state.configuredProviders().size} 家服务商可用",
                        onClick = { navigator.push(Route.ModelConfig) },
                    )
                    StarDivider(inset = 60.dp)
                    SettingsRow(
                        glyph = StarIconGlyph.Link,
                        title = "IM 渠道",
                        subtitle = state.activeChannelLabel() + "，与 App 内共用同一条会话",
                        onClick = { navigator.push(Route.Channels) },
                    )
                    StarDivider(inset = 60.dp)
                    SettingsRow(
                        glyph = StarIconGlyph.Book,
                        title = "记忆",
                        subtitle = "${state.memories.size} 条，可随时查看与删除",
                        onClick = { navigator.push(Route.Memories) },
                    )
                    StarDivider(inset = 60.dp)
                    SettingsRow(
                        glyph = StarIconGlyph.Bolt,
                        title = "技能",
                        subtitle = "${state.skills.count { it.installed }} 个已安装，可持续扩展能力",
                        onClick = { navigator.push(Route.Skills) },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("关于")
            StarCard {
                SettingsRow(
                    glyph = StarIconGlyph.Info,
                    title = "关于星环",
                    subtitle = "v1.0 · 一期交互原型",
                    tint = StarPalette.TextMuted,
                    containerColor = StarPalette.SurfaceAlt,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
