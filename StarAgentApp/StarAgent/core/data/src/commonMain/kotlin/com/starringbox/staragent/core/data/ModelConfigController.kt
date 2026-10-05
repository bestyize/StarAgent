package com.starringbox.staragent.core.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.starringbox.staragent.core.llm.LlmEndpoint
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.ExecutionPolicy
import com.starringbox.staragent.core.model.LlmProtocol
import com.starringbox.staragent.core.model.ModelProvider
import com.starringbox.staragent.core.model.ResponseMode
import com.starringbox.staragent.core.tools.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray

/**
 * 模型服务：服务商清单与 Key、模型解析、默认模型、回复模式与执行策略。
 *
 * 所有智能体共用 [defaultModelId]，不再按智能体单独选模型。
 */
class ModelConfigController(
    private val systemPromptFor: (Agent) -> String,
    private val registryFor: (Agent) -> ToolRegistry,
    private val newId: (String) -> String,
) {

    val providers = mutableStateListOf<ModelProvider>()

    var responseMode by mutableStateOf(ResponseMode.Standard)
        private set

    /** 工具执行权限策略：Auto（自动）或 OnDemand（按需二次确认）。 */
    var executionPolicy by mutableStateOf(ExecutionPolicy.Auto)
        private set

    var defaultModelId by mutableStateOf("")
        private set


    fun providerById(id: String): ModelProvider? = providers.firstOrNull { it.id == id }

    fun modelKey(providerId: String, index: Int): String = "$providerId:$index"

    /** 把 "provider:index" 解析回服务商与该服务商下的模型名。 */
    fun resolveModel(modelId: String): Pair<ModelProvider, String>? {
        val separator = modelId.lastIndexOf(':')
        if (separator <= 0) return null
        val provider = providerById(modelId.substring(0, separator)) ?: return null
        val index = modelId.substring(separator + 1).toIntOrNull() ?: return null
        val model = provider.models.getOrNull(index) ?: return null
        return provider to model
    }

    fun modelLabel(modelId: String): String = resolveModel(modelId)?.second ?: modelId

    fun configuredProviders(): List<ModelProvider> = providers.filter { it.configured }

    /** 这个智能体当前能不能真正对话——不能的话界面要给出明确的去配置入口。 */
    fun endpointFor(agent: Agent): LlmEndpoint? =
        endpointFor(agent, systemPromptFor(agent), toolsAvailable = true)

    /**
     * 同上，但由调用方给系统提示。群聊走这条：模型与 Key 的解析完全一样，
     * 只是提示里要写群上下文而不是单聊人设。
     *
     * [toolsAvailable] 决定这次请求带不带工具定义。**现在是三个调用点全传 true**
     * ——单聊、群聊、定时执行都走工具循环了。这个开关留着，是给降级路径
     * （服务商拒收 `tools` 字段时退回文本块协议）用的，那个还没做；
     * 传 false 时提示与请求**两头**都不带工具，两套指令才不会同时给出去。
     */
    fun endpointFor(
        agent: Agent,
        systemPrompt: String,
        toolsAvailable: Boolean = true,
    ): LlmEndpoint? {
        // 所有智能体统一用全局默认模型。Agent.modelId 还留在数据里，但不再参与解析：
        // 单独选模型的入口已经去掉，留着读它只会让老存档里写死的模型压过用户的默认设置。
        val (provider, model) = resolveModel(defaultModelId) ?: return null
        if (!provider.configured) return null
        // 地址按当前协议取：同一家两种协议的路径往往不同
        val baseUrl = provider.baseUrlFor(provider.protocol) ?: return null
        return LlmEndpoint(
            protocol = provider.protocol,
            baseUrl = baseUrl,
            apiKey = provider.apiKey,
            model = model,
            systemPrompt = systemPrompt,
            tools = if (toolsAvailable) registryFor(agent).definitions() else emptyList(),
        )
    }


    fun updateProvider(updated: ModelProvider) {
        val index = providers.indexOfFirst { it.id == updated.id }
        if (index < 0) return
        providers[index] = updated
        persistProvider(updated)

        // 换掉模型列表后，原先选中的模型可能已经不存在了，回退到第一个可用的。
        if (resolveModel(defaultModelId) == null) {
            setDefaultModel(modelKey(updated.id, 0))
        }
    }

    /** 新增一个自定义服务商。两种协议的地址分开填，用不到的那个可以留空。 */
    fun addCustomProvider(
        name: String,
        protocol: LlmProtocol,
        openAiBaseUrl: String,
        anthropicBaseUrl: String,
        models: List<String>,
        apiKey: String,
    ): ModelProvider {
        val provider = ModelProvider(
            id = newId("custom"),
            name = name.trim(),
            protocol = protocol,
            openAiBaseUrl = openAiBaseUrl.trim(),
            anthropicBaseUrl = anthropicBaseUrl.trim(),
            models = models,
            apiKey = apiKey.trim(),
            builtIn = false,
        )
        providers.add(provider)
        persistProvider(provider)

        val ids = readCustomIds()
        writeCustomIds(ids + provider.id)
        return provider
    }

    fun removeProvider(id: String) {
        val provider = providerById(id) ?: return
        if (provider.builtIn) return

        providers.removeAll { it.id == id }
        writeCustomIds(readCustomIds().filterNot { it == id })
        listOf(
            keyOf(id), modelsOf(id), "custom:$id:name", "custom:$id:protocol",
            "custom:$id:baseUrl", "custom:$id:models", "custom:$id:key",
        ).forEach { SettingsStore.remove(it) }

        if (resolveModel(defaultModelId) == null) {
            // 内置服务商删不掉，这里本该不会为空；但"删一个服务商"不该变成崩溃，
            // 没有可选的就把默认模型留空。
            providers.firstOrNull()?.let { setDefaultModel(modelKey(it.id, 0)) }
        }
    }

    fun setDefaultModel(modelId: String) {
        defaultModelId = modelId
        SettingsStore.put(KEY_DEFAULT_MODEL, modelId)
    }

    /** 注意：不能叫 setResponseMode——那会跟属性自带的 setter 撞 JVM 签名。 */
    fun selectResponseMode(mode: ResponseMode) {
        responseMode = mode
        SettingsStore.put(KEY_RESPONSE_MODE, mode.name)
    }

    /** 切换执行权限策略。自动 vs 按需。 */
    fun selectExecutionPolicy(policy: ExecutionPolicy) {
        executionPolicy = policy
        SettingsStore.put(KEY_EXECUTION_POLICY, policy.name)
    }

    private fun keyOf(id: String) = "apikey:$id"
    private fun modelsOf(id: String) = "models:$id"

    private fun persistProvider(provider: ModelProvider) {
        SettingsStore.put(keyOf(provider.id), provider.apiKey)
        SettingsStore.put(modelsOf(provider.id), encodeList(provider.models))
        if (!provider.builtIn) {
            SettingsStore.put("custom:${provider.id}:name", provider.name)
            SettingsStore.put("custom:${provider.id}:protocol", provider.protocol.name)
            SettingsStore.put("custom:${provider.id}:openAiBaseUrl", provider.openAiBaseUrl)
            SettingsStore.put("custom:${provider.id}:anthropicBaseUrl", provider.anthropicBaseUrl)
            SettingsStore.put("custom:${provider.id}:models", encodeList(provider.models))
            SettingsStore.put("custom:${provider.id}:key", provider.apiKey)
        }
    }

    private fun restoreSettings() {
        SettingsStore.get(KEY_RESPONSE_MODE)
            ?.let { saved -> ResponseMode.entries.firstOrNull { it.name == saved } }
            ?.let { responseMode = it }

        SettingsStore.get(KEY_EXECUTION_POLICY)
            ?.let { saved -> ExecutionPolicy.entries.firstOrNull { it.name == saved } }
            ?.let { executionPolicy = it }

        // 预置模型清单更新后，要把本地缓存的那份丢掉，否则用户永远看不到新模型。
        // API Key 必须保留——只重置模型名。
        val savedSeedVersion = SettingsStore.get(KEY_SEED_VERSION)?.toIntOrNull() ?: 0
        val presetsOutdated = savedSeedVersion < SEED_VERSION

        providers.indices.forEach { index ->
            val provider = providers[index]
            val savedKey = SettingsStore.get(keyOf(provider.id)).orEmpty()
            val savedModels = if (presetsOutdated) {
                emptyList()
            } else {
                decodeList(SettingsStore.get(modelsOf(provider.id)))
            }
            providers[index] = provider.copy(
                apiKey = savedKey,
                models = savedModels.ifEmpty { provider.models },
            )
        }

        SettingsStore.put(KEY_SEED_VERSION, SEED_VERSION.toString())

        readCustomIds().forEach { id ->
            if (providerById(id) != null) return@forEach
            val name = SettingsStore.get("custom:$id:name") ?: return@forEach
            val protocol = SettingsStore.get("custom:$id:protocol")
                ?.let { saved -> LlmProtocol.entries.firstOrNull { it.name == saved } }
                ?: LlmProtocol.OpenAi
            val openAiBaseUrl = SettingsStore.get("custom:$id:openAiBaseUrl").orEmpty()
            val anthropicBaseUrl = SettingsStore.get("custom:$id:anthropicBaseUrl").orEmpty()
            val models = decodeList(SettingsStore.get("custom:$id:models"))
            val apiKey = SettingsStore.get("custom:$id:key").orEmpty()
            providers.add(
                ModelProvider(
                    id = id,
                    name = name,
                    protocol = protocol,
                    openAiBaseUrl = openAiBaseUrl,
                    anthropicBaseUrl = anthropicBaseUrl,
                    models = models,
                    apiKey = apiKey,
                    builtIn = false,
                )
            )
        }

        val savedDefault = SettingsStore.get(KEY_DEFAULT_MODEL)
        if (savedDefault != null && resolveModel(savedDefault) != null) {
            defaultModelId = savedDefault
        }
    }

    private fun readCustomIds(): List<String> = decodeList(SettingsStore.get(KEY_CUSTOM_IDS))

    private fun writeCustomIds(ids: List<String>) {
        SettingsStore.put(KEY_CUSTOM_IDS, encodeList(ids))
    }

    /**
     * 列表按 JSON 数组存。用分隔符拼字符串看着更省事，
     * 但模型名里会出现什么字符不由我们决定，JSON 是唯一不会被撑坏的写法。
     */
    private fun encodeList(values: List<String>): String =
        buildJsonArray { values.forEach { add(it) } }.toString()

    private fun decodeList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw)
                .jsonArray
                .mapNotNull { (it as? JsonPrimitive)?.content }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * 从 [SettingsStore] 读回服务商、Key、模型清单、默认模型与两类模式。
     *
     * 兜底默认模型取第一家的第一个；没有服务商时留空，交给 SettingsStore 里用户自己的选择。
     */
    fun restore() {
        SettingsStore.get(KEY_RESPONSE_MODE)
            ?.let { saved -> ResponseMode.entries.firstOrNull { it.name == saved } }
            ?.let { responseMode = it }

        SettingsStore.get(KEY_EXECUTION_POLICY)
            ?.let { saved -> ExecutionPolicy.entries.firstOrNull { it.name == saved } }
            ?.let { executionPolicy = it }

        // 预置模型清单更新后，要把本地缓存的那份丢掉，否则用户永远看不到新模型。
        // API Key 必须保留——只重置模型名。
        val savedSeedVersion = SettingsStore.get(KEY_SEED_VERSION)?.toIntOrNull() ?: 0
        val presetsOutdated = savedSeedVersion < SEED_VERSION

        providers.indices.forEach { index ->
            val provider = providers[index]
            val savedKey = SettingsStore.get(keyOf(provider.id)).orEmpty()
            val savedModels = if (presetsOutdated) {
                emptyList()
            } else {
                decodeList(SettingsStore.get(modelsOf(provider.id)))
            }
            providers[index] = provider.copy(
                apiKey = savedKey,
                models = savedModels.ifEmpty { provider.models },
            )
        }

        SettingsStore.put(KEY_SEED_VERSION, SEED_VERSION.toString())

        readCustomIds().forEach { id ->
            if (providerById(id) != null) return@forEach
            val name = SettingsStore.get("custom:$id:name") ?: return@forEach
            val protocol = SettingsStore.get("custom:$id:protocol")
                ?.let { saved -> LlmProtocol.entries.firstOrNull { it.name == saved } }
                ?: LlmProtocol.OpenAi
            val openAiBaseUrl = SettingsStore.get("custom:$id:openAiBaseUrl").orEmpty()
            val anthropicBaseUrl = SettingsStore.get("custom:$id:anthropicBaseUrl").orEmpty()
            val models = decodeList(SettingsStore.get("custom:$id:models"))
            val apiKey = SettingsStore.get("custom:$id:key").orEmpty()
            providers.add(
                ModelProvider(
                    id = id,
                    name = name,
                    protocol = protocol,
                    openAiBaseUrl = openAiBaseUrl,
                    anthropicBaseUrl = anthropicBaseUrl,
                    models = models,
                    apiKey = apiKey,
                    builtIn = false,
                )
            )
        }

        val savedDefault = SettingsStore.get(KEY_DEFAULT_MODEL)
        if (savedDefault != null && resolveModel(savedDefault) != null) {
            defaultModelId = savedDefault
        } else {
            providers.firstOrNull()?.let { defaultModelId = modelKey(it.id, 0) }
        }
    }

    private companion object {
        const val KEY_DEFAULT_MODEL = "defaultModel"
        const val KEY_CUSTOM_IDS = "customIds"
        const val KEY_RESPONSE_MODE = "responseMode"
        const val KEY_EXECUTION_POLICY = "executionPolicy"
        const val KEY_SEED_VERSION = "seedVersion"

        /**
         * 预置服务商/模型清单的版本。
         * **每次改动 Seed 里的预置模型，就把这个数字 +1**——
         * 否则老用户本机缓存的那份清单会一直盖住新预设。
         */
        const val SEED_VERSION = 6
    }
}
