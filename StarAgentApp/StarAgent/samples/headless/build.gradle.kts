import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(project(":core:agent-runtime"))
    implementation(libs.kotlinx.coroutines.core)
}

application { mainClass = "com.starringbox.staragent.samples.headless.MainKt" }

// 编译边界验收：示例通过核心契约运行，不携带 App、图形、网络或语音实现。
val verifyRuntimeBoundary = tasks.register("verifyRuntimeBoundary") {
    doLast {
        val forbiddenProjects = setOf(":core:data", ":core:platform", ":core:llm", ":core:speech-local", ":shared")
        val forbidden = configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents.filter {
            when (val id = it.id) {
                is ProjectComponentIdentifier -> id.projectPath in forbiddenProjects
                is ModuleComponentIdentifier -> id.group.startsWith("org.jetbrains.compose") ||
                    id.group.startsWith("androidx.compose") || id.group.startsWith("io.ktor")
                else -> false
            }
        }.map { it.id.displayName }
        check(forbidden.isEmpty()) { "Unexpected headless dependencies: $forbidden" }
        println("Headless runtime boundary verified")
    }
}

tasks.named("check") { dependsOn(verifyRuntimeBoundary) }
tasks.named("run") { dependsOn(verifyRuntimeBoundary) }
