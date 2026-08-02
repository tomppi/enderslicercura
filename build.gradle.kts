plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

if (System.getenv("GITHUB_ACTIONS") == "true") {
    val prepareScript = rootDir.resolve("scripts/prepare_handoff_patcher.py")
    val patchScript = rootDir.resolve("scripts/apply_handoff_lifecycle_patch.py")
    val finalizeScript = rootDir.resolve("scripts/finalize_handoff_patched_sources.py")
    val uiDraftScript = rootDir.resolve("scripts/finalize_handoff_ui_drafts.py")
    val packageScript = rootDir.resolve("scripts/package_handoff_patched_sources.py")
    val archive = rootDir.resolve("handoff-patched-sources.b64")
    val errorFile = rootDir.resolve("handoff-patch-error.txt")
    val mainViewModel = rootDir.resolve(
        "app/src/main/java/com/tomppi/enderslicer/ui/MainViewModel.kt",
    )
    val requiresPatch = patchScript.isFile && mainViewModel.readText().contains(
        "private val initialStartGcode = readAsset(\"gcode/start.gcode\")",
    )
    if (requiresPatch && !errorFile.isFile) {
        fun runGuardedScript(script: java.io.File): Boolean {
            val process = ProcessBuilder("python3", script.absolutePath)
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            print(output)
            if (exitCode != 0) {
                errorFile.writeText(
                    buildString {
                        appendLine("Script: ${script.name}")
                        appendLine("Exit code: $exitCode")
                        append(output)
                    },
                )
                return false
            }
            return true
        }
        if (
            runGuardedScript(prepareScript) &&
            runGuardedScript(patchScript) &&
            runGuardedScript(finalizeScript) &&
            runGuardedScript(uiDraftScript)
        ) {
            runGuardedScript(packageScript)
        }
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
