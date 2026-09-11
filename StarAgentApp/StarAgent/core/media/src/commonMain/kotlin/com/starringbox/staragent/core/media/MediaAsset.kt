package com.starringbox.staragent.core.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 一份媒体资源。目前只用到图片，但刻意不叫 ImageAsset——
 * 将来要加音频、文件时不用改类型名，也不用再开一层。
 *
 * 用普通 class 而不是 data class：内部是 ByteArray，
 * 自动生成的 equals 是按引用比的，既误导调用方，也会让 Compose 漏掉重组。
 *
 * **持久化说明**：字节本体不进 JSON（base64 塞进去会让存档膨胀好几倍），
 * 由持久化层按 [id] 单独存成文件，读档时再填回来。
 * 所以 [bytes] 标了 [Transient] 并给了默认值。
 */
@Serializable
class MediaAsset(
    val id: String,
    val mimeType: String,
    @Transient val bytes: ByteArray = ByteArray(0),
) {
    val sizeBytes: Int get() = bytes.size

    /** 裸 base64，不带任何前缀。Anthropic 的 image 块要的正是这个形式。 */
    @OptIn(ExperimentalEncodingApi::class)
    fun base64(): String = Base64.Default.encode(bytes)

    /** data URL 形式。OpenAI 兼容协议（含 DeepSeek、千问、智谱、Kimi 等）要的是这个。 */
    fun dataUrl(): String = "data:$mimeType;base64,${base64()}"

    override fun toString(): String = "MediaAsset($id, $mimeType, ${sizeBytes}B)"
}
