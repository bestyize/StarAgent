# searx-kmp Kotlin Multiplatform

`searx-kmp` 是一个很小的 Kotlin Multiplatform 元搜索库：**并发向几个公开搜索引擎提问，
把结果合并、按链接去重，返回一个统一的列表**。灵感来自 [SearXNG](https://github.com/searxng/searxng)，
但只保留"搜索"这一件事——不建索引、不要 API Key、不做权重排序。

一次搜索的流程：

```
SearchRequest ──►  每个引擎并发查  ──►  按 URL 去重 + 交错排序  ──►  List<SearchResult>
   (query)        (DuckDuckGo/Bing/         (每个引擎轮流出一条)
                   Wikipedia/Mojeek)
```

## 支持目标

- JVM / Desktop
- Android
- iOS Arm64 与 iOS Simulator Arm64

## 最小用法

```kotlin
val searx = Searx() // 默认四个源：DuckDuckGo、Bing、Wikipedia、Mojeek

val results = searx.search(SearchRequest(query = "kotlin multiplatform", limit = 10))
results.forEach { println("${it.title}\n  ${it.url}\n  ${it.snippet}") }
```

也可以只要某几个源：

```kotlin
val searx = Searx(listOf(WikipediaEngine, DuckDuckGoEngine))
```

## 设计取舍

- **引擎只做解析，不做编排。** 每个 `SearchEngine` 把请求变成 HTTP、把响应解析成 `List<SearchResult>`；
  失败**抛异常**，由 `Searx` 逐个兜住。一个引擎改版或限流，不影响另外几个。
- **去重按链接归一化后的键**（去 scheme、`www.`、结尾斜杠，保留 query 和 fragment）。
  同一篇文章被多家收录时，靠前的源那条胜出。
- **结果交错而不是打分。** 轮流从每个引擎取一条，让每个源都有机会出现在前面——
  SearXNG 那套引擎权重是另一回事，这里不做。
- **只用字符串扫描解析 HTML。** 不引 HTML 解析库（多一份依赖、跨平台还麻烦），
  代价是页面结构一变就要回来改对应的 `parse`——所以每个 `parse` 都单独拆出来，用固定的
  HTML 片段在测试里钉住。

## 已知局限

- 公开搜索页**没有稳定契约**：HTML 结构、反爬策略随时可能变，`DuckDuckGoEngine` /
  `BingEngine` / `MojeekEngine` 的解析是尽力而为，失效时返回空而不是报错。
- Bing / Mojeek 对无 Cookie 的自动请求可能返回精简页；Wikipedia 走官方 JSON 接口，最稳。
- 不做分页、不做 `categories` / `time_range` 等高级参数；`limit` 是"每个源最多取几条"，
  聚合去重后最终条数可能更少。

## 与 StarAgent 的关系

StarAgent 通过 Gradle composite build 把本库的坐标替换成同仓库的构建
（见 `StarAgent/settings.gradle.kts` 的 `includeBuild`），并只依赖它的公共 API：
`AppSearchPort` 调用 `Searx.search(...)`，把 `SearchResult` 映射成工具层的 `SearchItem`。
应用代码不认识这里的任何协议细节。
