package com.starringbox.staragent.core.model

/**
 * 记忆分类，与设计稿「我的记忆」的五个标签一一对应。
 * 单位也放在这里，因为界面上要显示成"12 条 / 8 位"。
 */
enum class MemoryCategory(val label: String, val unit: String) {
    Preference("我的喜好", "条"),
    Person("重要的人", "位"),
    Habit("生活习惯", "条"),
    Experience("经历历史", "条"),
    Decision("重要决策", "条"),
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
