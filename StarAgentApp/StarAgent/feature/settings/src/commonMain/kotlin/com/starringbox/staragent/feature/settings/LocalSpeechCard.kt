package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.AppState
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import kotlinx.coroutines.launch

@Composable
internal fun LocalSpeechCard(state: AppState) {
    val scope = rememberCoroutineScope()

    StarCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("离线语音", style = MaterialTheme.typography.titleSmall, color = StarPalette.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                "模型保存在这台设备上，首次使用时下载。",
                style = MaterialTheme.typography.bodySmall,
                color = StarPalette.TextFaint,
            )
            Spacer(Modifier.height(12.dp))
            LocalModelRow(
                title = "离线识别 · 约 240 MB",
                supported = state.localAsrSupported,
                installed = state.localAsrInstalled,
                busy = state.localModelBusy,
                onInstall = { scope.launch { state.installLocalAsr() } },
                onRemove = { scope.launch { state.removeLocalAsr() } },
            )
            Spacer(Modifier.height(8.dp))
            LocalModelRow(
                title = "离线朗读 · 约 21 MB",
                supported = state.localTtsSupported,
                installed = state.localTtsInstalled,
                busy = state.localModelBusy,
                onInstall = { scope.launch { state.installLocalTts() } },
                onRemove = { scope.launch { state.removeLocalTts() } },
            )
            if (state.localModelBusy) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.localModelProgress.ifBlank { "正在准备…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    ActionLabel("取消", enabled = true, onClick = state::cancelLocalModelDownload)
                }
            }
            state.localModelError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LocalModelRow(
    title: String,
    supported: Boolean,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = StarPalette.Text)
            Text(
                text = when {
                    installed -> "已下载"
                    supported -> "尚未下载"
                    else -> "这台设备暂不支持"
                },
                style = MaterialTheme.typography.labelSmall,
                color = StarPalette.TextFaint,
            )
        }
        when {
            installed -> ActionLabel("删除", enabled = !busy, onClick = onRemove)
            supported -> ActionLabel("下载", enabled = !busy, onClick = onInstall)
        }
    }
}

@Composable
internal fun ActionLabel(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) StarPalette.Indigo.copy(alpha = 0.12f) else StarPalette.SurfaceSunken)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) StarPalette.Indigo else StarPalette.TextFaint,
    )
}
