plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

val filaSimCommit = "e7485ec22d4ebe8baca04190404fbb877c90e031"
val filaSimFormat = 9
val filaSimScript = layout.projectDirectory.file("scripts/prepare-filasim-assets-with-thermal-integrity.py")
val filaSimPinchScript = layout.projectDirectory.file("scripts/prepare-filasim-assets-with-pinch.py")
val filaSimBaseScript = layout.projectDirectory.file("scripts/prepare-filasim-assets.py")
val filaSimThermalPatch = layout.projectDirectory.file("scripts/filasim-thermal-integrity-patch.py")
val filaSimThermalHardening = layout.projectDirectory.file("scripts/filasim-thermal-integrity-hardening.py")
val filaSimThermalAuditFixes = layout.projectDirectory.file("scripts/filasim-thermal-integrity-audit-fixes.py")
val filaSimBridge = layout.projectDirectory.file("app/src/main/filasim/android-bridge.js")
val filaSimThermalWorkspace = layout.projectDirectory.file("app/src/main/filasim/thermal-integrity.js")
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
        inputs.file(filaSimPinchScript)
        inputs.file(filaSimBaseScript)
        inputs.file(filaSimThermalPatch)
        inputs.file(filaSimThermalHardening)
        inputs.file(filaSimThermalAuditFixes)
        inputs.file(filaSimBridge)
        inputs.file(filaSimThermalWorkspace)
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
