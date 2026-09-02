package com.tomppi.enderslicer.ui

import android.content.Context

/**
 * The slicing engine the user chose. Each engine has its own theme accent
 * (Cura blue, PrusaSlicer orange), its own profile formats, its own G-code
 * dialect and its own engine binary. Profiles are NEVER combined: the user
 * picks one engine and the app behaves like that product.
 */
enum class SlicerEngine(val label: String) {
    CURA("Cura"),
    PRUSA("PrusaSlicer"),
}

/** Persisted engine selection, independent of profiles and settings. */
class SlicerEngineStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): SlicerEngine {
        val name = preferences.getString(KEY_ENGINE, null) ?: return SlicerEngine.CURA
        return runCatching { SlicerEngine.valueOf(name) }.getOrDefault(SlicerEngine.CURA)
    }

    fun save(engine: SlicerEngine) {
        preferences.edit().putString(KEY_ENGINE, engine.name).apply()
    }

    private companion object {
        const val PREFERENCES = "slicer-engine"
        const val KEY_ENGINE = "engine"
    }
}
