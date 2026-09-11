# StarAgent · 星环智能 Agent（一期）

移动端 AI Agent。一期目标是把"支持自定义大模型的、完善的 AI 智能体"做扎实：
对话与多模态交互、长期记忆、任务托管、技能、渠道、模型自由。
真机操作作为二期目标。

技术栈：**Kotlin Multiplatform + Compose Multiplatform**，一套代码支撑 Android / iOS / Desktop。

---

## 先配一个模型

应用**不内置任何 API Key**，第一次打开需要自己配一个：

**我的 → 模型服务 → 选一家（或「添加自定义服务商」）→ 填 API Key → 获取模型列表 → 勾选模型 → 保存**

「获取模型列表」会真的去那家服务商拉一次当前可用的模型清单，不用手打模型名。
Key 只保存在本机（桌面 `java.util.prefs`、Android `SharedPreferences`、iOS `NSUserDefaults`），
配置好之后重启不用重填。

支持的协议：

| 协议 | 请求 | 鉴权 | 典型服务商 |
| --- | --- | --- | --- |
| OpenAI 兼容 | `POST {base}/chat/completions` | `Authorization: Bearer` | DeepSeek、通义千问、智谱 GLM、Kimi、多数自建端点 |
| Anthropic | `POST {base}/messages` | `x-api-key` + `anthropic-version` | Claude |

自定义服务商时由用户在这两种协议之间二选一。

---

## 模块结构

```
StarAgent/
├── core/                    核心层：不含业务界面，被所有功能模块依赖
│   ├── media/               多模态基础能力：资源表示、编码、校验、选取契约（零平台依赖）
│   ├── model/               领域模型（纯 Kotlin，不依赖 Compose）
│   ├── designsystem/        主题、图标、通用组件、导航契约
│   ├── llm/                 模型接入：协议实现 + 流式解析（不依赖 Compose）
│   └── data/                应用状态、模型服务配置、对话流
│
├── feature/                 功能层：一个模块 = 一块完整功能
│   ├── agents/              智能体：列表、创建、配置
│   ├── chat/                对话：消息流、内联卡片、输入坞
│   ├── tasks/               任务中心
│   └── settings/            我的：模型服务、IM 渠道、记忆、技能
│
├── shared/                  应用外壳：组装功能模块、导航宿主、三端共用入口
├── androidApp/              Android 入口
├── desktopApp/              桌面入口（开发预览用，窗口按手机比例）
└── iosApp/                  iOS 入口
```

### 依赖方向

```
                         ┌──────────────┐
                         │    shared    │  外壳 + 导航宿主
                         └──────┬───────┘
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
      ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
      │feature:agents│  │ feature:chat │  │ …:tasks/settings
      └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
             └─────────────────┼─────────────────┘
                               ▼
                    ┌────────────────────┐
                    │  core:designsystem │  主题 / 图标 / 组件 / 导航契约
                    │      core:data     │  状态 / 模型配置 / 对话流
                    │      core:llm      │  协议实现 + 流式解析
                    │     core:model     │  领域模型
                    │     core:media     │  多模态基础能力（叶子模块）
                    └────────────────────┘
```

规则：

- **功能模块之间互不依赖。** 需要跨模块跳转时，走 `core:designsystem` 里的 [Route](core/designsystem/src/commonMain/kotlin/com/starringbox/staragent/core/designsystem/navigation/Navigation.kt) 与 `Navigator`，不直接引用对方的界面。
- **`core:model` 与 `core:llm` 不依赖 Compose。** 协议层保持纯粹，将来可以独立复用或搬到服务端。
- **`core:designsystem` 不含业务逻辑。** 只有主题、图标、通用组件和导航契约。
- **界面层只读 `AppState` / 调它的方法**，不自己持有业务状态，也不直接发网络请求。

新增一个功能时：在 `feature/` 下建模块 → 在 `settings.gradle.kts` 注册 → 若需新页面，在 `Route` 里加一个目的地 → 在 `shared/App.kt` 的 `RouteHost` 里接上。

---

## 平台相关代码

一期几乎全是 `commonMain`，只有两处需要按平台实现：

| 位置 | 桌面 | Android | iOS |
| --- | --- | --- | --- |
| `core:llm` 的 HTTP 引擎 | CIO | OkHttp | Darwin |
| `core:data` 的本地键值存储 | `java.util.prefs` | `SharedPreferences` | `NSUserDefaults` |
| 选图实现（**放在各端入口模块**） | `desktopApp` | `androidApp` | 尚未接入 |

Android 上 `SettingsStore` 需要 Context，由 `MainActivity` 调 `AndroidAppContext.install(applicationContext)` 注入；
选图因为要挂在 Activity 的 result 回调上，也在同一处注入。

---

## 多模态

图片能力集中在 `:core:media`，它的职责边界划得很死——**只负责"拿到一张图，并把它变成可传输的形式"**：

```
core/media
├── MediaAsset   资源表示（bytes + mimeType），带 base64() / dataUrl() 两种编码
├── MediaRules   格式与体积准入规则，不合格的在本地就挡下来
└── MediaPicker  选取契约 + 注册表
```

**为什么选图不做成 `expect/actual`**：Android 的选图必须挂在 Activity 的 `registerForActivityResult` 上，
用 `expect` 函数凭空造一个出来是做不到的。所以改成"平台注册、上层取用"——
各端入口把实现推进 `MediaPickers`，媒体模块本身**不认识任何平台**。

依赖方向因此是单向且干净的：

```
core:media  ←  core:model  ←  core:llm / core:data / feature:*
   （叶子，只依赖协程）        （只管按协议把图编成 payload）
```

界面侧 `StarImage` 只接收 `ByteArray`、不接收 `MediaAsset`，
所以设计系统也不必反向依赖业务类型。

**两家协议的图片格式完全不同**，这是接多模态最容易踩的坑，`core:llm` 里分了两套实现并用测试钉住：

| | 块类型 | base64 形式 |
| --- | --- | --- |
| OpenAI 兼容系（含 DeepSeek、千问、智谱、Kimi、MiniMax、MiMo、火山、OpenRouter） | `image_url` | **data URL**，带 `data:image/png;base64,` 前缀 |
| Anthropic | `image` | **裸 base64**，MIME 类型单独放 `media_type`，带前缀会 400 |

---

## 运行

```bash
# 桌面端（开发预览，窗口按手机比例）
./gradlew :desktopApp:run

# Android
./gradlew :androidApp:assembleDebug

# iOS：用 Xcode 打开 iosApp/ 目录运行

# 测试
./gradlew :core:data:jvmTest
```

Android 本地构建需要 `local.properties` 中的 `sdk.dir` 指向本机 SDK。

---

## 一期交互形态

三 Tab（对话 / 任务 / 我的），首页是**智能体联系人列表**——产品终局是"住在 IM 里的 Agent"，
所以 App 本身按 IM 的心智来组织：你的联系人都是智能体。

三个关键设计：

1. **对话即任务创建（按智能体开关）。** 开启后，系统提示会要求模型在判断出可托管意图时，
   于回复末尾附一个 ` ```task ` JSON 块；解析器把它变成任务草稿卡片，点「创建」才真正落任务。
   关掉时提示里根本不出现任务指令，就只是聊天。**新智能体默认关闭**——
   只有"把事情交出去"才需要它，陪聊、答疑类的智能体不该动不动就弹卡片。
   解析失败按普通回复处理：模型没按格式输出，绝不能因此让用户看不到它的回答。
2. **产物沉淀。** 一期智能体没有"手"，交付感全靠产物卡片——
   把结果从"聊天流里的一段文字"变成"一个能带走的成果"。
3. **状态位常驻。** 对话页顶栏一直显示 `模型 · 已连接渠道`，
   把"模型自由"和"IM 连接"做成持续可见的状态，而不是藏在设置里的开关。

一个刻意的取舍：**响应模式在前，模型选择在后**。用户关心的是"这次要快还是要深"，
不是模型的名字；但"模型自由"是立身之本，所以两者都给，只是排序不同。

设计依据见仓库 `docs/` 目录下的《产品白皮书》与《一期交互形态设计》。

---

## 接入真实后端

对话已经直连用户自己配置的大模型服务。剩下需要接的：

| 位置 | 现状 | 替换为 |
| --- | --- | --- |
| [Seed](core/data/src/commonMain/kotlin/com/starringbox/staragent/core/data/Seed.kt) | 内存种子数据（智能体、任务、记忆） | 持久化仓储 |
| 任务调度 | 只有展示，没有真正的定时触发 | 平台侧的后台调度 |
| IM 渠道 | 开关式模拟绑定 | 真实的渠道接入 |
| 记忆 | 静态列表，没有参与推理 | 写入 / 召回链路 |

`AppState` 是界面的唯一数据源，界面层不需要改动。

---

## 已知待办

- `core` / `feature` 各模块的 `build.gradle.kts` 目前是逐模块复制的最小 KMP 配置，
  模块再多一些时值得抽成 `buildSrc` 约定插件。
- iOS 目标只写了代码，尚未在 Mac 环境编译验证过。
