package com.tomppi.enderslicer.smartinfill

import java.util.concurrent.atomic.AtomicReference

/**
 * One immutable Smart Infill package may be active for the single foreground
 * slice operation. MainViewModel serializes operations with its busy state;
 * the atomic reference additionally makes reads safe on the IO worker.
 */
object SmartInfillRuntime {
    private val active = AtomicReference<SmartInfillPackage?>(null)

    fun activate(packageValue: SmartInfillPackage?) {
        active.set(packageValue)
    }

    fun current(): SmartInfillPackage? = active.get()
}
