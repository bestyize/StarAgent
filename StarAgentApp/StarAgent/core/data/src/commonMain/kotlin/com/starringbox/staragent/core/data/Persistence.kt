package com.starringbox.staragent.core.data

import com.starringbox.staragent.core.model.MediaAsset
import com.starringbox.staragent.core.model.Agent
import com.starringbox.staragent.core.model.AgentGroup
import com.starringbox.staragent.core.model.AgentTask
import com.starringbox.staragent.core.model.Channel
import com.starringbox.staragent.core.model.ChatMessage
import com.starringbox.staragent.core.model.MemoryItem
import com.starringbox.staragent.core.model.MessageBody
import com.starringbox.staragent.core.model.Skill
import com.starringbox.staragent.core.platform.storage.LocalStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 落盘的那一份应用状态。
 *
 * 只存"内容"——智能体、任务、记忆、技能、渠道、群组、对话。
 * 模型服务配置（含 API Key）走 SettingsStore，两套各管各的。
 *
 * **新增字段一律要带默认值**：旧存档里没有这个键时，解码器会填默认值，数据照常恢复。
 * 反过来，改动或删除已有字段会让旧存档解码失败，而 [StatePersister.load] 只能返回 null，
 * AppState 于是退回种子数据——用户内容会**无声消失**。这是本文件最需要守住的约束。
 */
@Serializable
internal data class PersistedState(
    val agents: List<Agent> = emptyList(),
    val tasks: List<AgentTask> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val groups: List<AgentGroup> = emptyList(),
    val conversations: Map<String, List<ChatMessage>> = emptyMap(),
)

/**
 * 状态的读写。
 *
 * **图片字节不进 JSON**：base64 塞进去会让存档膨胀约三分之一，
 * 而且每次改动都要重写整份 JSON，代价太大。图片按 [MediaAsset.id] 单独存文件，
 * 读档时再填回来——列表里的 MediaAsset 只留 id 与 mimeType。
 */
internal class StatePersister(private val store: LocalStore) {

    fun load(): PersistedState? {
        val raw = store.readText(STATE_FILE) ?: return null
        // 解析不了就退化为种子数据，而不是崩在启动路径上。
        // 但退化的代价是"用户内容看起来消失了"，所以先把原始字节另存一份再放弃——
        // 真出事时至少还能人工找回，不至于被种子数据盖掉。
        val decoded = runCatching { json.decodeFromString<PersistedState>(raw) }.getOrNull()
            ?: run {
                runCatching { store.writeText(CORRUPT_FILE, raw) }
                return null
            }
        return decoded.copy(conversations = decoded.conversations.mapValues { (_, messages) ->
            messages.map { it.withMediaRestored(store) }
        })
    }

    fun save(state: PersistedState) {
        runCatching {
            // 图片先写，再写索引——中途失败也不会留下指向空文件的记录
            state.mediaAssets().forEach { asset ->
                if (asset.bytes.isNotEmpty()) {
                    store.writeBytes(mediaFileName(asset.id), asset.bytes)
                }
            }
            store.writeText(STATE_FILE, Json.encodeToString(state))
        }
    }

    /** 删掉某个智能体时，顺手清掉它名下的图片文件，避免越积越多。 */
    fun deleteMedia(assets: List<MediaAsset>) {
        assets.forEach { runCatching { store.delete(mediaFileName(it.id)) } }
    }

    private companion object {
        const val STATE_FILE = "state.json"

        /** 存档读不出来时，把原始内容挪到这里，留一条人工找回的路。 */
        const val CORRUPT_FILE = "state.corrupt.json"

        /**
         * `ignoreUnknownKeys`：存档里多出来的字段不该让整份数据读不出来。
         * 主要防的是回滚——用旧版本跑新版本写下的存档。
         */
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** 图片文件的命名规则。读写两侧共用，避免哪天改了一边忘了另一边。 */
private fun mediaFileName(id: String) = "media/$id.bin"

/** 把这条消息里的图片字节从文件读回来。 */
private fun ChatMessage.withMediaRestored(store: LocalStore): ChatMessage {
    val body = body
    if (body !is MessageBody.Image) return this

    val restored = body.assets.map { asset ->
        MediaAsset(
            id = asset.id,
            mimeType = asset.mimeType,
            bytes = store.readBytes(mediaFileName(asset.id)) ?: ByteArray(0),
        )
    }
    return copy(body = MessageBody.Image(assets = restored, caption = body.caption))
}

/** 摊平出这份状态里所有的图片，供保存与清理使用。 */
private fun PersistedState.mediaAssets(): List<MediaAsset> =
    conversations.values
        .flatten()
        .mapNotNull { (it.body as? MessageBody.Image)?.assets }
        .flatten()
