package com.starringbox.staragent.core.model

enum class MemoryCategory(val label: String) {
    Preference("偏好"),
    Fact("事实"),
    Habit("习惯"),
    Project("项目"),
}

/**
 * 长期记忆。记忆必须可查看、可编辑、可删除——这是隐私底线，
 * 也是"高自由度"的应有之义。
 */
data class MemoryItem(
    val id: String,
    val agentId: String,
    val category: MemoryCategory,
    val text: String,
)
