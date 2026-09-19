package com.starringbox.staragent.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.data.LocalAppState
import com.starringbox.staragent.core.designsystem.component.SectionLabel
import com.starringbox.staragent.core.designsystem.component.StarCard
import com.starringbox.staragent.core.designsystem.icon.StarIcon
import com.starringbox.staragent.core.designsystem.component.StarTextField
import com.starringbox.staragent.core.designsystem.component.StarTopBar
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph
import com.starringbox.staragent.core.designsystem.navigation.Navigator
import com.starringbox.staragent.core.designsystem.theme.StarPalette
import com.starringbox.staragent.core.llm.ModelCatalog
import com.starringbox.staragent.core.model.LlmProtocol
import kotlinx.coroutines.launch

private sealed interface FetchState {
    data object Idle : FetchState
    data object Loading : FetchState
    data class Ready(val count: Int) : FetchState
    data class Failed(val message: String) : FetchState
}

/**
 * 服务商详情：配 API Key、维护该服务商可用的模型清单、勾选要用的模型。
 *
 * 模型有两处来源：「获取模型列表」拉这家服务商真实的清单——**有的服务商没有这个接口**
 * （火山方舟的 `/models` 就是 404），所以也支持手动输入一个模型名加进去。
 */
@Composable
fun ProviderDetailScreen(navigator: Navigator, providerId: String?) {
    val state = LocalAppState.current
    val existing = providerId?.let { state.providerById(it) }
    val isNew = existing == null
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: LlmProtocol.OpenAi) }
    // 两种协议的地址往往不同，分开存、分开填
    var openAiUrl by remember { mutableStateOf(existing?.openAiBaseUrl.orEmpty()) }
    var anthropicUrl by remember { mutableStateOf(existing?.anthropicBaseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var selected by remember { mutableStateOf(existing?.models.orEmpty().toSet()) }
    // 清单里现在有哪些模型。以已保存的为准起步，但要能整份清掉、也能手动往里加——
    // 两者正是「清除全部」和「手动添加」的直接目的：有的服务商没有 /models 可拉，
    // 预置的模型名还可能是猜的。existing.models 本身是只读的，所以这里另存一份工作态。
    var list by remember(existing?.id) { mutableStateOf(existing?.models.orEmpty()) }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var newModel by remember { mutableStateOf("") }
    var defaultPick by remember { mutableStateOf<String?>(existing?.models?.firstOrNull()) }
    var fetchState by remember { mutableStateOf<FetchState>(FetchState.Idle) }

    // 已保存的 + 手动加的 + 刚拉回来的，去重后一起列出来，勾选状态不受来源影响。
    val allModels = remember(list, fetched) {
        (list + fetched).distinct().sorted()
    }
    val ordered = allModels.filter { it in selected }
    val currentBaseUrl = when (protocol) {
        LlmProtocol.OpenAi -> openAiUrl
        LlmProtocol.Anthropic -> anthropicUrl
    }
    val canSave = name.isNotBlank() && currentBaseUrl.isNotBlank() && ordered.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        StarTopBar(
            title = if (isNew) "添加服务商" else existing!!.name,
            subtitle = if (isNew) null else protocol.label,
            onBack = { navigator.pop() },
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                StarCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        SectionLabel("名称")
                        StarTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "给这个服务商起个名字",
                        )

                        Spacer(Modifier.height(16.dp))
                        SectionLabel("协议")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LlmProtocol.entries.forEach { option ->
                                ProtocolChip(
                                    label = option.label,
                                    selected = protocol == option,
                                    onClick = { protocol = option },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = protocol.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextFaint,
                        )

                        Spacer(Modifier.height(16.dp))
                        SectionLabel("接口地址 · OpenAI 兼容")
                        StarTextField(
                            value = openAiUrl,
                            onValueChange = { openAiUrl = it },
                            placeholder = "https://api.deepseek.com/v1",
                        )

                        Spacer(Modifier.height(16.dp))
                        SectionLabel("接口地址 · Anthropic")
                        StarTextField(
                            value = anthropicUrl,
                            onValueChange = { anthropicUrl = it },
                            placeholder = "https://api.deepseek.com/anthropic",
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "同一家的两种协议地址常常不一样，所以分开填。" +
                                "用不到的那个可以留空，留空即表示这家不提供该协议。",
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextFaint,
                        )

                        Spacer(Modifier.height(16.dp))
                        SectionLabel("API Key")
                        StarTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = "sk-...",
                            // 默认藏起来，右侧眼睛点开才明文——这一栏在设置页里、
                            // 又是会被截图分享的位置，不该默认摊在屏幕上
                            masked = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "只保存在这台设备上，不会上传到任何服务器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = StarPalette.TextFaint,
                        )
                    }
                }
            }

            item {
                FetchModelsCard(
                    fetchState = fetchState,
                    onFetch = {
                        fetchState = FetchState.Loading
                        scope.launch {
                            // 用当前协议对应的那个地址去拉模型列表
                            val result = ModelCatalog.fetch(protocol, currentBaseUrl.trim(), apiKey.trim())
                            fetchState = result.fold(
                                onSuccess = { models ->
                                    fetched = models
                                    // 之前没勾过任何模型时，默认把刚拉到的第一个选上，省一步操作。
                                    if (selected.isEmpty()) {
                                        selected = models.take(1).toSet()
                                        defaultPick = models.firstOrNull()
                                    }
                                    FetchState.Ready(models.size)
                                },
                                onFailure = { FetchState.Failed(it.message ?: "获取失败") },
                            )
                        }
                    },
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(
                        text = "模型（勾选要用的）",
                        modifier = Modifier.weight(1f),
                    )
                    // 一键清空整份清单：预置的模型名是猜的、又拉不回来时，得能整份推倒重来
                    if (allModels.isNotEmpty()) {
                        Text(
                            text = "清除全部",
                            style = MaterialTheme.typography.labelMedium,
                            color = StarPalette.Danger,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    list = emptyList()
                                    fetched = emptyList()
                                    selected = emptySet()
                                    defaultPick = null
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            item {
                AddModelRow(
                    value = newModel,
                    onValueChange = { newModel = it },
                    onAdd = {
                        val name = newModel.trim()
                        if (name.isNotBlank() && name !in allModels) {
                            list = list + name
                            selected = selected + name
                            if (defaultPick == null) defaultPick = name
                        }
                        newModel = ""
                    },
                )
            }

            if (allModels.isEmpty()) {
                item {
                    Text(
                        text = "还没有模型。用「自定义模型」加一个名字，或填好 Key 后点「获取模型列表」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = StarPalette.TextFaint,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(allModels.size) { index ->
                    val model = allModels[index]
                    ModelRow(
                        name = model,
                        checked = model in selected,
                        isDefault = defaultPick == model,
                        onToggle = {
                            selected = if (model in selected) selected - model else selected + model
                            if (defaultPick == model && model !in selected) {
                                defaultPick = ordered.firstOrNull()
                            }
                            if (defaultPick == null) defaultPick = model
                        },
                        onMakeDefault = { defaultPick = model },
                    )
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (canSave) StarPalette.Indigo else StarPalette.SurfaceSunken)
                        .clickable(enabled = canSave) {
                            val chosenIndex = defaultPick
                                ?.let { pick -> ordered.indexOf(pick).takeIf { it >= 0 } }
                                ?: 0

                            if (isNew) {
                                val created = state.addCustomProvider(
                                    name = name,
                                    protocol = protocol,
                                    openAiBaseUrl = openAiUrl,
                                    anthropicBaseUrl = anthropicUrl,
                                    models = ordered,
                                    apiKey = apiKey,
                                )
                                if (defaultPick != null || ordered.isNotEmpty()) {
                                    state.setDefaultModel(state.modelKey(created.id, chosenIndex))
                                }
                            } else {
                                state.updateProvider(
                                    existing!!.copy(
                                        name = name.trim(),
                                        protocol = protocol,
                                        openAiBaseUrl = openAiUrl.trim(),
                                        anthropicBaseUrl = anthropicUrl.trim(),
                                        models = ordered,
                                        apiKey = apiKey.trim(),
                                    )
                                )
                                if (defaultPick != null) {
                                    state.setDefaultModel(state.modelKey(existing.id, chosenIndex))
                                }
                            }
                            navigator.pop()
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isNew) "添加" else "保存",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (canSave) Color.White else StarPalette.TextFaint,
                    )
                }
            }

            if (!isNew && !existing!!.builtIn) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(StarPalette.Surface)
                            .clickable {
                                state.removeProvider(existing.id)
                                navigator.pop()
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "删除这个服务商",
                            style = MaterialTheme.typography.labelLarge,
                            color = StarPalette.Danger,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun FetchModelsCard(fetchState: FetchState, onFetch: () -> Unit) {
    StarCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "获取模型列表",
                        style = MaterialTheme.typography.titleSmall,
                        color = StarPalette.Text,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = when (fetchState) {
                            FetchState.Idle -> "用上面的 Key 去这家服务商拉一次真实清单"
                            FetchState.Loading -> "正在获取…"
                            is FetchState.Ready -> "拉到 ${fetchState.count} 个模型"
                            is FetchState.Failed -> fetchState.message
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (fetchState) {
                            is FetchState.Failed -> StarPalette.Danger
                            is FetchState.Ready -> StarPalette.Success
                            else -> StarPalette.TextMuted
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(StarPalette.Indigo)
                        .clickable(enabled = fetchState !is FetchState.Loading) { onFetch() }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = if (fetchState is FetchState.Loading) "获取中" else "获取",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    name: String,
    checked: Boolean,
    isDefault: Boolean,
    onToggle: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    StarCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { onToggle() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (checked) StarPalette.Indigo else StarPalette.SurfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    StarIcon(
                        icon = StarIconGlyph.Check,
                        iconSize = 13.dp,
                        tint = Color.White,
                        strokeWidth = 2.8f,
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = StarPalette.Text,
                modifier = Modifier.weight(1f),
            )
            if (checked) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onMakeDefault() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (isDefault) "默认" else "设为默认",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDefault) StarPalette.Indigo else StarPalette.TextFaint,
                    )
                }
            }
        }
    }
}

/** 手动输入一个模型名加进清单。没有 /models 可拉的服务商（火山方舟）靠它。 */
@Composable
private fun AddModelRow(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    StarCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StarTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = "自定义模型",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (value.isBlank()) StarPalette.SurfaceSunken else StarPalette.Indigo)
                    .clickable(enabled = value.isNotBlank()) { onAdd() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "添加",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (value.isBlank()) StarPalette.TextFaint else Color.White,
                )
            }
        }
    }
}

/** 做成 RowScope 扩展，才能在这里用 weight 等分宽度。 */
@Composable
private fun RowScope.ProtocolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) StarPalette.Indigo else StarPalette.SurfaceAlt)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else StarPalette.TextMuted,
        )
    }
}
