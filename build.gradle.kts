plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

val filaSimCommit = "e7485ec22d4ebe8baca04190404fbb877c90e031"
val filaSimFormat = 7
val filaSimScript = layout.projectDirectory.file("scripts/prepare-filasim-assets-with-pinch.py")
val filaSimBaseScript = layout.projectDirectory.file("scripts/prepare-filasim-assets.py")
val filaSimBridge = layout.projectDirectory.file("app/src/main/filasim/android-bridge.js")
val filaSimAssetsDirectory = layout.projectDirectory.dir("app/src/main/assets/filasim")

project(":app") {
    val prepareFilaSimAssets = tasks.register<org.gradle.api.tasks.Exec>("prepareFilaSimAssets") {
        group = "build setup"
        description = "Builds the pinned offline filaSim WASM workspace for Android"
        inputs.property("filaSimCommit", filaSimCommit)
        inputs.property("filaSimFormat", filaSimFormat)
        inputs.file(filaSimScript)
        inputs.file(filaSimBaseScript)
        inputs.file(filaSimBridge)
        outputs.dir(filaSimAssetsDirectory)
        workingDir(rootProject.projectDir)
        environment("NPM_CONFIG_ENGINE_STRICT", "true")
        commandLine(
            "python3",
            filaSimScript.asFile.absolutePath,
            "--project-root",
            rootProject.projectDir.absolutePath,
        )
    }

    plugins.withId("com.android.application") {
        tasks.named("preBuild").configure {
            dependsOn(prepareFilaSimAssets)
        }
    }
}
