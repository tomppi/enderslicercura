package com.tomppi.enderslicer.nativebridge

object NativeSlicer {
    private val loadResult = runCatching { System.loadLibrary("enderslicer_native") }

    private external fun nativeStatus(): String
    private external fun nativeSlice(inputPath: String, outputPath: String, settingsJson: String): Int

    fun status(): String = loadResult.fold(
        onSuccess = { runCatching { nativeStatus() }.getOrElse { "JNI call failed: ${it.message}" } },
        onFailure = { "Native library failed to load: ${it.message}" },
    )

    fun slice(inputPath: String, outputPath: String, settingsJson: String): Result<Unit> {
        return runCatching {
            loadResult.getOrThrow()
            val result = nativeSlice(inputPath, outputPath, settingsJson)
            check(result == 0) { "CuraEngine adapter returned $result" }
        }
    }
}
