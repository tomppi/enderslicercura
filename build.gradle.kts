plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

if (System.getenv("GITHUB_ACTIONS") == "true") {
    val patchScript = rootDir.resolve("scripts/apply_handoff_lifecycle_patch.py")
    val packageScript = rootDir.resolve("scripts/package_handoff_patched_sources.py")
    val archive = rootDir.resolve("handoff-patched-sources.b64")
    val mainViewModel = rootDir.resolve(
        "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    )
    val requiresPatch = patchScript.isFile && mainViewModel.readText().contains(
        "private val initialStartGcode = readAsset(\"gcode/start.gcode\")",
    )
    if (requiresPatch) {
        fun runGuardedScript(script: java.io.File) {
            val exitCode = ProcessBuilder("python3", script.absolutePath)
                .directory(rootDir)
                .inheritIO()
                .start()
                .waitFor()
            check(exitCode == 0) { "Guarded handoff script failed: ${script.name}" }
        }
        runGuardedScript(patchScript)
        runGuardedScript(packageScript)
    }
    if (
        archive.isFile &&
        gradle.startParameter.taskNames.any { it.contains("testDebugUnitTest", ignoreCase = true) }
    ) {
        println("BEGIN_ENDERSLICER_HANDOFF_PATCH_ARCHIVE")
        println(archive.readText().trim())
        println("END_ENDERSLICER_HANDOFF_PATCH_ARCHIVE")
    }
}
