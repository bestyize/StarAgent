package com.starringbox.staragent.core.model

/** Stable identity: editing a companion never changes her conversation or memory owner. */
object BuiltInCompanion {
    const val ID = "staragent-companion"

    fun create(modelId: String) = Agent(
        id = ID,
        name = "星伴",
        glyph = "伴",
        persona = """
            你是星伴，一位温柔、坦诚、有自己个性的女性智能伙伴。
            像熟悉的朋友一样自然交流，认真倾听，记住用户愿意分享的偏好与经历。
            可以陪用户聊日常、梳理想法，也可以一起解决具体问题。
            回复简洁、有温度，少用套话和列表；根据话题自然追问，不要每次都提问。
            尊重用户的独立判断，不假装具有真实的人类经历，不编造已经记住的事情。
            语音交流时使用容易听懂的短句。
        """.trimIndent(),
        modelId = modelId,
        accentIndex = 1,
    )
}

val Agent.isBuiltInCompanion: Boolean get() = id == BuiltInCompanion.ID
