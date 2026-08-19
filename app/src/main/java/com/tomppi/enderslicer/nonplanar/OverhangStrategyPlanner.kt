package com.tomppi.enderslicer.nonplanar

import com.tomppi.enderslicer.viewer.StlMesh
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Per-region overhang strategy classifier.
 *
 * The slicer owns arc overhangs, wave overhangs and CurviSlicer. Instead of
 * requiring the user to pick one globally, this planner walks the positioned
 * mesh once and classifies every triangle:
 *
 * - **Sloped upper surface** (normal points up, tilt within CurviSlicer's
 *   usable range): CurviSlicer candidate.
 * - **Flat roof underside** (normal points down, near-horizontal): arc-overhang
 *   candidate, because Cura treats exactly these as unsupported bottom skin.
 * - **Sloped underside** (normal points down, steep): a hard overhang that is
 *   risky for both strategies and is recorded for the safety decision.
 *
 * When CurviSlicer is enabled the planner also builds the relief field and
 * samples it at every flat-roof centroid. A roof that sits in a near-zero-relief
 * region stays flat through the flatten/slice/un-flatten pipeline, so its arc
 * paths remain valid; a roof in a high-relief region would be curved and must
 * not be combined with arc fill.
 */
internal enum class OverhangRecommendation { NONE, ARC_ONLY, CURVI_ONLY, ARC_AND_CURVI }

internal data class OverhangStrategyPlan(
    val recommendation: OverhangRecommendation,
    val slopedUpperAreaMm2: Double,
    val flatRoofAreaMm2: Double,
    val slopedUndersideAreaMm2: Double,
    val lowReliefRoofFraction: Double,
    val curviUseful: Boolean,
    val arcUseful: Boolean,
    val combinedSafe: Boolean,
    val summary: String,
)

internal object OverhangStrategyPlanner {

    fun plan(
        mesh: StlMesh,
        curviSettings: NonPlanarSettings,
        layerHeightMm: Double,
        nozzleDiameterMm: Double,
    ): OverhangStrategyPlan {
        var slopedUpperArea = 0.0
        var flatRoofArea = 0.0
        var lowReliefRoofArea = 0.0
        var slopedUndersideArea = 0.0

        val slopeLimitDegrees = curviSettings.effectiveSlopeLimitDegrees
        val bedContactMaxZ = BED_CONTACT_MM
        val vertices = mesh.interleavedVertices
        val triangleCount = mesh.triangleCount
        val centroidX = DoubleArray(triangleCount)
        val centroidY = DoubleArray(triangleCount)
        val projectedAreas = DoubleArray(triangleCount)
        val roofTriangleIndices = ArrayList<Int>()
        var flatRoofCount = 0
        var triangleIndex = 0
        var base = 0
        while (triangleIndex < triangleCount) {
            val ax = vertices[base].toDouble()
            val ay = vertices[base + 1].toDouble()
            val az = vertices[base + 2].toDouble()
            val bx = vertices[base + 6].toDouble()
            val by = vertices[base + 7].toDouble()
            val bz = vertices[base + 8].toDouble()
            val cx = vertices[base + 12].toDouble()
            val cy = vertices[base + 13].toDouble()
            val cz = vertices[base + 14].toDouble()

            val ux = bx - ax
            val uy = by - ay
            val uz = bz - az
            val vx = cx - ax
            val vy = cy - ay
            val vz = cz - az
            val crossX = uy * vz - uz * vy
            val crossY = uz * vx - ux * vz
            val crossZ = ux * vy - uy * vx
            val crossLength = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
            if (crossLength > 1e-12) {
                val normalZ = crossZ / crossLength
                val slopeDegrees = Math.toDegrees(acos(abs(normalZ).coerceIn(0.0, 1.0)))
                val projectedArea = 0.5 * crossLength * abs(normalZ)
                centroidX[triangleIndex] = (ax + bx + cx) / 3.0
                centroidY[triangleIndex] = (ay + by + cy) / 3.0
                projectedAreas[triangleIndex] = projectedArea
                if (normalZ > 0.05) {
                    if (slopeDegrees >= MIN_SLOPED_SURFACE_DEGREES && slopeDegrees <= slopeLimitDegrees) {
                        slopedUpperArea += projectedArea
                    }
                } else if (normalZ < -0.05) {
                    if (slopeDegrees <= FLAT_ROOF_MAX_DEGREES) {
                        val centroidZ = (az + bz + cz) / 3.0
                        if (centroidZ > bedContactMaxZ) {
                            flatRoofArea += projectedArea
                            roofTriangleIndices += triangleIndex
                            flatRoofCount++
                        }
                    } else {
                        slopedUndersideArea += projectedArea
                    }
                }
            }
            triangleIndex++
            base += VERTEX_FLOATS_PER_TRIANGLE
        }

        val field = runCatching {
            CurviSlicerFieldBuilder.build(mesh, curviSettings, layerHeightMm, nozzleDiameterMm).field
        }.getOrNull()
        if (field != null && flatRoofArea > 0.0) {
            // Only the actual roof triangles may decide the low-relief
            // fraction: mixing in the box faces used to drown the roofs in
            // low-relief area and report every combination as safe.
            for (index in roofTriangleIndices) {
                val area = projectedAreas[index]
                if (area <= 0.0) continue
                val displacement = abs(field.sampleRelief(centroidX[index], centroidY[index]) * field.strength)
                if (displacement <= LOW_RELIEF_MM) {
                    lowReliefRoofArea += area
                }
            }
        }

        val arcUseful = flatRoofArea >= MIN_USEFUL_AREA_MM2
        val curviUseful = slopedUpperArea >= MIN_USEFUL_AREA_MM2 &&
            field != null &&
            field.maximumDisplacementMm >= MIN_CURVI_DISPLACEMENT_MM
        val lowReliefRoofFraction = if (field == null || flatRoofArea <= 0.0) {
            0.0
        } else {
            lowReliefRoofArea / flatRoofArea
        }
        val slopedUndersideFraction = if (flatRoofArea + slopedUndersideArea > 0.0) {
            slopedUndersideArea / (flatRoofArea + slopedUndersideArea)
        } else {
            0.0
        }
        val combinedSafe = field != null &&
            arcUseful &&
            lowReliefRoofFraction >= MIN_LOW_RELIEF_FRACTION &&
            slopedUndersideFraction <= MAX_SLOPED_UNDERSIDE_FRACTION

        val recommendation = decide(arcUseful, curviUseful, combinedSafe)
        val summary = summary(recommendation, flatRoofArea, slopedUpperArea, flatRoofCount)
        return OverhangStrategyPlan(
            recommendation = recommendation,
            slopedUpperAreaMm2 = slopedUpperArea,
            flatRoofAreaMm2 = flatRoofArea,
            slopedUndersideAreaMm2 = slopedUndersideArea,
            lowReliefRoofFraction = lowReliefRoofFraction,
            curviUseful = curviUseful,
            arcUseful = arcUseful,
            combinedSafe = combinedSafe,
            summary = summary,
        )
    }

    internal fun decide(
        arcUseful: Boolean,
        curviUseful: Boolean,
        combinedSafe: Boolean,
    ): OverhangRecommendation = when {
        arcUseful && curviUseful && combinedSafe -> OverhangRecommendation.ARC_AND_CURVI
        arcUseful -> OverhangRecommendation.ARC_ONLY
        curviUseful -> OverhangRecommendation.CURVI_ONLY
        else -> OverhangRecommendation.NONE
    }

    private fun summary(
        recommendation: OverhangRecommendation,
        flatRoofAreaMm2: Double,
        slopedUpperAreaMm2: Double,
        flatRoofCount: Int,
    ): String = when (recommendation) {
        OverhangRecommendation.ARC_AND_CURVI ->
            "Smart overhangs: arc fill for %d flat roofs (%.0f mm²) + curved layers for slopes (%.0f mm²); safe to combine".format(
                flatRoofCount,
                flatRoofAreaMm2,
                slopedUpperAreaMm2,
            )
        OverhangRecommendation.ARC_ONLY ->
            "Smart overhangs: arc fill for %d flat roofs (%.0f mm²); no curvi-usable slopes".format(
                flatRoofCount,
                flatRoofAreaMm2,
            )
        OverhangRecommendation.CURVI_ONLY ->
            "Smart overhangs: curved layers for slopes (%.0f mm²); no flat roofs for arc fill".format(
                slopedUpperAreaMm2,
            )
        OverhangRecommendation.NONE ->
            "Smart overhangs: no flat roofs or curvi-usable slopes detected; standard slicing retained"
    }

    private const val VERTEX_FLOATS_PER_TRIANGLE = 18
    private const val MIN_SLOPED_SURFACE_DEGREES = 2.0
    private const val FLAT_ROOF_MAX_DEGREES = 15.0
    private const val MIN_USEFUL_AREA_MM2 = 50.0
    private const val MIN_CURVI_DISPLACEMENT_MM = 0.2
    private const val LOW_RELIEF_MM = 0.2
    private const val MIN_LOW_RELIEF_FRACTION = 0.8
    private const val MAX_SLOPED_UNDERSIDE_FRACTION = 0.3
    private const val BED_CONTACT_MM = 0.5
}
