rootProject.name = "StarAgent"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// ── 应用入口（三端）──────────────────────────────
include(":androidApp")
include(":desktopApp")
include(":shared")

// ── 核心层 ─────────────────────────────────────
// model        领域模型，纯 Kotlin，不依赖 Compose
// designsystem 设计系统：主题、图标、通用组件、导航契约
// media        多模态基础能力：媒体资源的表示、编码与选取
// llm          模型接入：协议实现 + 流式解析（无 Compose 依赖）
// data         应用状态与数据源（一期为内存实现 + 本地偏好存储）
include(":core:model")
include(":core:designsystem")
include(":core:media")
include(":core:llm")
include(":core:data")

// ── 公共层 ─────────────────────────────────────
include(":common:apptheme")

// ── 功能层 ─────────────────────────────────────
// agents   智能体：列表、创建、配置
// chat     对话：消息流、任务草稿卡片、产物卡片、输入坞
// tasks    任务中心：托管任务的查看与管理
// settings 我的：模型服务、IM 渠道、记忆、技能
include(":feature:agents")
include(":feature:chat")
include(":feature:tasks")
include(":feature:settings")
