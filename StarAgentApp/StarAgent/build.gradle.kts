plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}
// iOS simulator tests need an installed Xcode simulator runtime. Keep them
// opt-in so configuration-cache builds also work on machines that only have
// Xcode command-line tools or no simulator runtimes.
val runIosSimulatorTests = providers.gradleProperty("staragent.runIosSimulatorTests")
    .map(String::toBoolean)
    .orElse(false)

subprojects {
    tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach {
        onlyIf {
            if (runIosSimulatorTests.get()) true
            else {
                logger.lifecycle("Skipping $path: set -Pstaragent.runIosSimulatorTests=true to run iOS simulator tests")
                false
            }
        }
    }
}
