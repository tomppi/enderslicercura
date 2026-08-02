pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

val filaSimCommit = "e7485ec22d4ebe8baca04190404fbb877c90e031"
val filaSimFormat = 1

gradle.beforeProject { project ->
    if (project.path == ":app") {
        val projectRoot = settingsDir
        val script = projectRoot.resolve("scripts/prepare-filasim-assets.py")
        val bridge = projectRoot.resolve("app/src/main/filasim/android-bridge.js")
        val marker = projectRoot.resolve("app/src/main/assets/filasim/.source-version")
        val prepareFilaSimAssets = project.tasks.register<org.gradle.api.tasks.Exec>("prepareFilaSimAssets") {
            group = "build setup"
            description = "Builds the pinned offline filaSim WASM workspace for Android"
            inputs.property("filaSimCommit", filaSimCommit)
            inputs.property("filaSimFormat", filaSimFormat)
            inputs.file(script)
            inputs.file(bridge)
            outputs.file(marker)
            workingDir(projectRoot)
            commandLine(
                "python3",
                script.absolutePath,
                "--project-root",
                projectRoot.absolutePath,
            )
        }
        project.tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(prepareFilaSimAssets)
        }
    }
}

rootProject.name = "EnderSlicer"
include(":app")
