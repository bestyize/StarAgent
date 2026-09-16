package com.starringbox.staragent.core.platform.media

import com.starringbox.staragent.core.model.MediaAsset

/**
 * 媒体资源的准入规则。
 *
 * 各家服务商对图片的限制不完全一样（尺寸上限从 5MB 到 20MB 不等），
 * 这里取的是**交集**：宁可在本地就挡下来给出清晰提示，也不要发出去换一个 400。
 */
object MediaRules {

    /** 四家 OpenAI 兼容服务商与 Anthropic 都接受这几种格式。 */
    val supportedMimeTypes: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
    )

    /**
     * 上限取 5MB：base64 会让体积涨约三分之一，
     * 5MB 编码后约 6.7MB，仍在各家最保守的限制之内。
     */
    const val MAX_BYTES: Int = 5 * 1024 * 1024

    sealed interface Rejection {
        val message: String

        data class UnsupportedFormat(val mimeType: String) : Rejection {
            override val message: String get() = "不支持这种图片格式：$mimeType"
        }

        data object Empty : Rejection {
            override val message: String get() = "这个文件是空的"
        }

        data class TooLarge(val actualBytes: Int, val limitBytes: Int) : Rejection {
            override val message: String
                get() = "图片太大了（${actualBytes / 1024 / 1024} MB），上限 ${limitBytes / 1024 / 1024} MB"
        }
    }

    /** 通过则返回 null，否则给出可直接展示给用户的理由。 */
    fun rejection(asset: MediaAsset): Rejection? = when {
        asset.mimeType !in supportedMimeTypes -> Rejection.UnsupportedFormat(asset.mimeType)
        asset.bytes.isEmpty() -> Rejection.Empty
        asset.sizeBytes > MAX_BYTES -> Rejection.TooLarge(asset.sizeBytes, MAX_BYTES)
        else -> null
    }
}
