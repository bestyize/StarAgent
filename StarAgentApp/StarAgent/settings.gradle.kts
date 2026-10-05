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
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa.sherpa-onnx") }
        }
        ivy {
            name = "SherpaOnnxOfficialReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.k2-fsa.sherpa-onnx", "sherpa-onnx-static-link-onnxruntime") }
        }
    }
}

// edge-tts 是独立发布的 Kotlin Multiplatform 库。开发时用同仓库的独立构建替换
// 坐标依赖，应用代码只依赖它的公共 API，不依赖项目路径。
includeBuild("../edge-tts") {
    dependencySubstitution {
        substitute(module("com.starringbox.edge:edge-tts"))
            .using(project(":"))
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
// platform     平台能力层：本地存储 / 选图保存 / 语音（内部分包，三端实现各在自己的源集）
// llm          模型接入：协议实现 + 流式解析（无 Compose 依赖）
// data         应用状态与数据源（一期为内存实现 + 本地偏好存储）
include(":core:model")
include(":core:designsystem")
include(":core:platform")
// speech-contracts 语音契约：识别/合成/录放接口、PCM/WAV 与准入规则（纯 Kotlin）
include(":core:speech-contracts")
include(":core:speech-local")
// speech-cloud 云端语音：OpenAI 兼容 / 火山 / MiMo 的识别与合成
include(":core:speech-cloud")
include(":core:llm")
// llm-contracts 模型契约，运行时无需依赖 HTTP、语音或平台实现
include(":core:llm-contracts")
include(":core:data")
// bootstrap    装配层：启动时把各端的平台能力一次装起来
//              （各端实现在 core:platform 里，这里只管"什么时候装"）
include(":core:bootstrap")
// tools  工具执行：契约 + 注册表 + 内置工具（只依赖 core:model）
include(":core:tools")
// tool-host 工具宿主适配：设备操作 / 命令 / MCP / 权限的各端实现（依赖 core:tools）
include(":core:tool-host")
// agent-runtime 通用 Agent 运行时（无 Compose / UI 依赖）
include(":core:agent-runtime")

// 无头示例与依赖边界验证
include(":samples:headless")

// ── 公共层 ─────────────────────────────────────
// ── 功能层 ─────────────────────────────────────
// agents   智能体：列表、创建、配置
// chat     对话：消息流、任务草稿卡片、产物卡片、输入坞
// tasks    任务中心：托管任务的查看与管理
// settings 我的：模型服务、IM 渠道、记忆、技能
include(":feature:agents")
include(":feature:chat")
include(":feature:tasks")
include(":feature:settings")
