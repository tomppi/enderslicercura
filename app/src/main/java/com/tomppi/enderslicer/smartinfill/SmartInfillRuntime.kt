package com.tomppi.enderslicer.smartinfill

import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable package generation captured once at slice entry. Package files are
 * app-private immutable snapshots, so every request can validate, stage and
 * resolve settings from one generation even if the UI later activates or
 * removes another package.
 */
data class SmartInfillSliceSnapshot internal constructor(
    val generation: Long,
    val packageValue: SmartInfillPackage,
) {
    val packageId: String get() = packageValue.id

    fun effective(settings: SlicerSettings): SlicerSettings = packageValue.applyTo(settings)

    fun requireMatchesSource(source: File) = packageValue.requireMatchesSource(source)

    fun stageModifiers(destination: File): List<SmartInfillModifier> =
        packageValue.stageModifiers(destination)
}

/**
 * Process-local selector for the package shown in the UI. Slicing code must use
 * [snapshot] once and pass that snapshot explicitly; repeated [current] reads
 * are intentionally reserved for UI/status checks.
 */
object SmartInfillRuntime {
    private data class State(
        val generation: Long,
        val packageValue: SmartInfillPackage?,
    )

    private val state = AtomicReference(State(generation = 0L, packageValue = null))

    fun activate(packageValue: SmartInfillPackage?) {
        while (true) {
            val previous = state.get()
            val next = State(previous.generation + 1L, packageValue)
            if (state.compareAndSet(previous, next)) return
        }
    }

    fun current(): SmartInfillPackage? = state.get().packageValue

    fun snapshot(): SmartInfillSliceSnapshot? {
        val captured = state.get()
        return captured.packageValue?.let { packageValue ->
            SmartInfillSliceSnapshot(captured.generation, packageValue)
        }
    }

    fun isCurrent(snapshot: SmartInfillSliceSnapshot): Boolean {
        val current = state.get()
        return current.generation == snapshot.generation &&
            current.packageValue?.id == snapshot.packageId
    }
}
