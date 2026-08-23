package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings

/**
 * App-owned settings consumed by the enderslicercura CuraEngine bead-chain
 * patch. Like the bead-angle/masonry keys they intentionally do not belong to
 * Cura's upstream definition tree and are appended to the resolved slice
 * snapshot after Cura evaluates its dependency graph.
 */
internal object BeadChainEngineSettings {
    const val ENABLED = "enderslicer_bead_chain_enabled"
    const val WELD_TARGET = "enderslicer_bead_chain_weld_target"
    const val FLOW_MIN = "enderslicer_bead_chain_flow_min"
    const val INNER_FLOW = "enderslicer_bead_chain_inner_flow"
    const val PRESS = "enderslicer_bead_chain_press"
    const val ALL_WALLS = "enderslicer_bead_chain_all_walls"
    const val MAX_ITERATIONS = "enderslicer_bead_chain_max_iterations"
    const val SPEED = "enderslicer_bead_chain_speed"
    const val FAN_SPEED = "enderslicer_bead_chain_fan_speed"

    fun values(settings: SlicerSettings): LinkedHashMap<String, String> {
        val chain = settings.beadChain
        return linkedMapOf(
            ENABLED to chain.enabled.toString(),
            WELD_TARGET to chain.weldTargetPercent.toString(),
            FLOW_MIN to chain.flowMinPercent.toString(),
            INNER_FLOW to chain.innerFlowPercent.toString(),
            PRESS to chain.pressPercent.toString(),
            ALL_WALLS to chain.allWalls.toString(),
            MAX_ITERATIONS to chain.maxIterations.toString(),
            SPEED to chain.speedMmPerSecond.toString(),
            FAN_SPEED to chain.fanSpeedPercent.toString(),
        )
    }

    fun validate(settings: SlicerSettings) {
        val chain = settings.beadChain
        check(chain.weldTargetPercent in 5.0..50.0) { "Bead-chain weld target outside 5..50%" }
        check(chain.flowMinPercent in 40.0..100.0) { "Bead-chain flow floor outside 40..100%" }
        check(chain.innerFlowPercent in 100.0..200.0) { "Bead-chain inner flow outside 100..200%" }
        check(chain.pressPercent in 1.0..20.0) { "Bead-chain press outside 1..20%" }
        check(chain.maxIterations in 1..20) { "Bead-chain max walls outside 1..20" }
        check(chain.speedMmPerSecond in 5.0..300.0) { "Bead-chain speed outside 5..300 mm/s" }
        check(chain.fanSpeedPercent in 0.0..100.0) { "Bead-chain fan outside 0..100%" }
    }
}