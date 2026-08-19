package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.StlMesh

/**
 * Automatic overhang-strategy resolver.
 *
 * When [SlicerSettings.smartOverhangStrategy] is enabled the app runs
 * [OverhangStrategyPlanner] on the positioned mesh before slicing and lets the
 * result drive arc fill instead of relying only on the manual toggle. The
 * resolver only forces changes when it has evidence:
 *
 * - A flat roof is present and (when CurviSlicer is active) stays flat through
 *   the flatten/un-flatten cycle, so arc fill is enabled and wave fill is
 *   disabled to preserve the arc/wave exclusivity.
 * - CurviSlicer is active but would curve the roofs, so arc fill is forced off
 *   even if the user toggled it on; running both would emit curved arc paths.
 * - No evidence either way: the explicit user setting is preserved.
 *
 * CurviSlicer itself is never auto-enabled: it is an explicit non-planar
 * pipeline that warps the whole model.
 */
internal object SmartOverhangStrategy {

    data class Resolution(
        val settings: SlicerSettings,
        val message: String?,
    )

    fun resolve(
        settings: SlicerSettings,
        curviSettings: NonPlanarSettings,
        mesh: StlMesh,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): Resolution {
        if (!settings.smartOverhangStrategy) {
            return Resolution(settings, null)
        }
        val curviActive = curviSettings.enabled
        val plan = OverhangStrategyPlanner.plan(mesh, curviSettings, layerHeightMm, nozzleDiameterMm)

        val roofsCurvedByCurvi = curviActive && plan.arcUseful && !plan.combinedSafe
        val arcOn = when {
            roofsCurvedByCurvi -> false
            plan.arcUseful -> true
            else -> settings.arcOverhangEnabled
        }
        val effective = settings.copy(
            arcOverhangEnabled = arcOn,
            waveOverhangEnabled = if (arcOn) false else settings.waveOverhangEnabled,
            brickWallEnabled = if (arcOn) false else settings.brickWallEnabled,
        )
        return Resolution(effective, message(plan, curviActive, roofsCurvedByCurvi, arcOn))
    }

    private fun message(
        plan: OverhangStrategyPlan,
        curviActive: Boolean,
        roofsCurvedByCurvi: Boolean,
        arcOn: Boolean,
    ): String = buildString {
        append("Smart overhangs: ")
        when {
            roofsCurvedByCurvi -> {
                append(
                    "arc fill disabled because the flat roofs would be curved by non-planar layers; " +
                        "CurviSlicer handles %.0f mm² of slopes".format(plan.slopedUpperAreaMm2),
                )
            }
            arcOn && curviActive -> {
                append(
                    "arc fill kept for %.0f mm² of flat roofs together with CurviSlicer".format(plan.flatRoofAreaMm2),
                )
                if (plan.lowReliefRoofFraction < 1.0) {
                    append(" (%.0f%% of the roofs stay flat)".format(plan.lowReliefRoofFraction * 100.0))
                }
            }
            arcOn -> {
                append("arc fill auto-enabled for %.0f mm² of flat roofs".format(plan.flatRoofAreaMm2))
                if (plan.curviUseful && !curviActive) {
                    append("; CurviSlicer would also help %.0f mm² of slopes — enable it in Non Planar for the combined result".format(plan.slopedUpperAreaMm2))
                }
            }
            curviActive && plan.curviUseful -> {
                append("no flat roofs for arc fill; CurviSlicer handles %.0f mm² of slopes".format(plan.slopedUpperAreaMm2))
            }
            else -> append(plan.summary.removePrefix("Smart overhangs: "))
        }
    }
}
