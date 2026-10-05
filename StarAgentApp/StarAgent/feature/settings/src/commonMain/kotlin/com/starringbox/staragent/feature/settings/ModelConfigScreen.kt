package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.component.StarTag
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.navigation.Route
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.model.ModelProvider

/**
 * 模型服务列表。
 *
 * 一期不提供官方通道，用户需要自己填一家的 API Key——
 * 所以这里最重要的是把"哪家已经能用了、哪家还没配"说清楚，
 * 而不是把可选项堆成一堆开关。
 */
@Composable
fun ModelConfigScreen(navigator: Navigator) {
    val state = LocalAppState.current
    val configured = state.providers.filter { it.configured }
    val pending = state.providers.filterNot { it.configured }

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = "模型服务",
            subtitle = "${configured.size} / ${state.providers.size} 家已配置",
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(StarPalette.SurfaceAlt)
                        .padding(14.dp),
                ) {
                    Text(
                        text = "填好 API Key 之后就能真正对话了。Key 只保存在这台设备上；" +
                            "所有智能体都用这里选的默认模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                    )
                }
            }

            if (configured.isNotEmpty()) {
                item { SectionLabel("已配置") }
                items(configured, key = { it.id }) { provider ->
                    ProviderRow(provider) { navigator.push(Route.ProviderDetail(provider.id)) }
                }
            }

            if (pending.isNotEmpty()) {
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel("待配置") }
                items(pending, key = { it.id }) { provider ->
                    ProviderRow(provider) { navigator.push(Route.ProviderDetail(provider.id)) }
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(StarPalette.Surface)
                        .clickable { navigator.push(Route.ProviderDetail(null)) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(11.dp))
                            .background(StarPalette.Indigo.copy(alpha = 0.10f))
                            .padding(9.dp),
                    ) {
                        StarIcon(StarIconGlyph.Plus, iconSize = 16.dp, tint = StarPalette.Indigo)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "添加自定义服务商",
                            style = MaterialTheme.typography.bodyLarge,
                            color = StarPalette.Text,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "支持 OpenAI 兼容与 Anthropic 两种协议",
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextMuted,
                        )
                    }
                    StarIcon(
                        icon = StarIconGlyph.ChevronRight,
                        iconSize = 15.dp,
                        tint = StarPalette.TextFaint,
                        strokeWidth = 2f,
                    )
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ProviderRow(provider: ModelProvider, onClick: () -> Unit) {
    StarCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { onClick() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = StarPalette.Text,
                    )
                    Spacer(Modifier.width(8.dp))
                    StarTag(
                        text = provider.protocol.label,
                        contentColor = StarPalette.TextMuted,
                        containerColor = StarPalette.SurfaceAlt,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (provider.configured) {
                        "${provider.models.size} 个模型 · ${provider.maskedKey}"
                    } else {
                        "${provider.models.size} 个模型 · 还没填 API Key"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StarPalette.TextMuted,
                )
            }
            if (provider.configured) {
                StarTag("可用", contentColor = StarPalette.Success, containerColor = Color(0xFFE8F7F1))
            } else {
                StarTag("待配置", contentColor = StarPalette.TextMuted, containerColor = StarPalette.SurfaceAlt)
            }
            Spacer(Modifier.width(8.dp))
            StarIcon(
                icon = StarIconGlyph.ChevronRight,
                iconSize = 15.dp,
                tint = StarPalette.TextFaint,
                strokeWidth = 2f,
            )
        }
    }
}
