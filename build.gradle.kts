plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

val filaSimCommit = "e7485ec22d4ebe8baca04190404fbb877c90e031"
val filaSimFormat = 8
val filaSimScript = layout.projectDirectory.file("scripts/prepare-filasim-assets-with-pinch.py")
val filaSimBaseScript = layout.projectDirectory.file("scripts/prepare-filasim-assets.py")
val filaSimManifestFinalizer = layout.projectDirectory.file("scripts/finalize-filasim-apk-manifest.py")
val filaSimBridge = layout.projectDirectory.file("app/src/main/filasim/android-bridge.js")

// Keep Gradle invalidation aligned with the executable v13 dependency graph.
// Historical preparers and unrelated filaSim JavaScript files no longer force a
// complete Rust/WASM/React rebuild when they are edited.
val filaSimActiveInputPaths = listOf(
    "scripts/prepare-filasim-assets-with-pinch.py",
    "scripts/finalize-filasim-apk-manifest.py",
    "scripts/prepare-filasim-assets-with-annealing-v13.py",
    "scripts/prepare-filasim-assets-with-thermal-integrity-v12.py",
    "scripts/prepare-filasim-assets-with-thermal-integrity-v11.py",
    "scripts/prepare-filasim-assets-with-thermal-integrity-v10.py",
    "scripts/prepare-filasim-assets-with-thermal-integrity-v9.py",
    "scripts/prepare-filasim-assets-with-thermal-integrity-v8.py",
    "scripts/prepare-filasim-assets-with-pinch-v8-base.py",
    "scripts/prepare-filasim-assets.py",
    "scripts/filasim-thermal-integrity-patch.py",
    "scripts/filasim-thermal-integrity-hardening.py",
    "scripts/filasim-thermal-integrity-audit-fixes.py",
    "scripts/filasim-thermal-integrity-progress.py",
    "scripts/filasim-thermal-integrity-react-tab.py",
    "scripts/filasim-thermal-integrity-bugfix-round1.py",
    "scripts/filasim-thermal-integrity-bugfix-round2.py",
    "scripts/filasim-thermal-integrity-linear-fast-path.py",
    "scripts/filasim-thermal-integrity-physical-model-v1.py",
    "scripts/filasim-thermal-integrity-physical-contract-fix.py",
    "scripts/filasim-annealing-cycle.py",
    "scripts/filasim-annealing-material-source.py",
    "scripts/filasim-annealing-3d-result-fix.py",
    "scripts/filasim-annealing-partial-duration.py",
    "scripts/filasim-annealing-short-duration-stability.py",
    "scripts/filasim-annealing-thermal-only.py",
    "scripts/filasim_annealing_common.py",
    "scripts/filasim_annealing_core.py",
    "scripts/filasim_annealing_web.py",
    "app/src/main/filasim/android-bridge.js",
    "app/src/main/filasim/thermal-integrity.js",
    "app/src/main/filasim/thermal-integrity-guard.js",
    "app/src/main/filasim/thermal-integrity-workspace.js",
    "app/src/main/filasim/thermal-integrity-live-progress.js",
    "app/src/main/filasim/material-profile-source.js",
    "app/src/main/filasim/thermal-material-profile-adapter.js",
    "app/src/main/filasim/annealing-calculator-observer-guard.js",
    "app/src/main/filasim/annealing-step-budget-guard.js",
    "app/src/main/filasim/annealing-calculator-01-core.js",
    "app/src/main/filasim/annealing-calculator-02-ui.js",
    "app/src/main/filasim/annealing-calculator-03-cycle.js",
    "app/src/main/filasim/annealing-calculator-03a-workload-preflight.js",
    "app/src/main/filasim/annealing-calculator-03b-materials.js",
    "app/src/main/filasim/annealing-calculator-03c-partial-duration.js",
    "app/src/main/filasim/annealing-calculator-03d-thermal-only.js",
    "app/src/main/filasim/annealing-calculator-04-report.js",
)
val filaSimPatchSources = files(
    filaSimActiveInputPaths.map { path -> layout.projectDirectory.file(path) },
)
val filaSimAssetsDirectory = layout.projectDirectory.dir("app/src/main/assets/filasim")
val bumpMeshVerifier = layout.projectDirectory.file("scripts/verify-bumpmesh-assets.py")
val bumpMeshAssetsDirectory = layout.projectDirectory.dir("app/src/main/assets/bumpmesh")

project(":app") {
    val prepareFilaSimAssets = tasks.register<org.gradle.api.tasks.Exec>("prepareFilaSimAssets") {
        group = "build setup"
        description = "Builds the pinned offline filaSim WASM workspace for Android"
        inputs.property("filaSimCommit", filaSimCommit)
        inputs.property("filaSimFormat", filaSimFormat)
        inputs.file(filaSimScript)
        inputs.file(filaSimBaseScript)
        inputs.file(filaSimManifestFinalizer)
        inputs.file(filaSimBridge)
        inputs.files(filaSimPatchSources)
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

    val verifyBumpMeshAssets = tasks.register<org.gradle.api.tasks.Exec>("verifyBumpMeshAssets") {
        group = "verification"
        description = "Verifies every generated BumpMesh runtime asset before APK packaging"
        dependsOn("prepareBumpMeshAssets")
        inputs.file(bumpMeshVerifier)
        inputs.files(
            fileTree(bumpMeshAssetsDirectory) {
                exclude("SHA256SUMS")
            },
        )
        outputs.file(bumpMeshAssetsDirectory.file("SHA256SUMS"))
        workingDir(rootProject.projectDir)
        commandLine(
            "python3",
            bumpMeshVerifier.asFile.absolutePath,
            "--root",
            bumpMeshAssetsDirectory.asFile.absolutePath,
        )
    }

    plugins.withId("com.android.application") {
        tasks.named("preBuild").configure {
            dependsOn(prepareFilaSimAssets)
            dependsOn(verifyBumpMeshAssets)
        }
    }
}
