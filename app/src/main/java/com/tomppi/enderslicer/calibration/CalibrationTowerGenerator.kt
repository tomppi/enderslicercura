package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.PlannedLayerEvent
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

object CalibrationTowerGenerator {
    private const val BASE_HEIGHT_MM = 0.8
    private const val SOLID_OVERLAP_MM = 0.20

    fun generate(
        spec: CalibrationTowerSpec,
        retractionSpeedMmPerSecond: Double,
    ): CalibrationTowerResult {
        require(spec.levels in 2..20) { "Calibration tower levels must be between 2 and 20" }
        require(spec.sectionHeightMm in 3.0..30.0) { "Section height must be between 3 and 30 mm" }
        require(spec.towerWidthMm in 12.0..45.0) { "Tower width must be between 12 and 45 mm" }
        val start = BigDecimal.valueOf(spec.startValue)
        val step = BigDecimal.valueOf(spec.stepValue)
        val values = List(spec.levels) { index ->
            start
                .add(step.multiply(BigDecimal.valueOf(index.toLong())))
                .stripTrailingZeros()
                .toDouble()
        }
        values.forEach { value ->
            require(value in spec.type.minimum..spec.type.maximum) {
                "${spec.type.displayName} value ${format(value)} ${spec.type.unit} is outside ${spec.type.minimum}..${spec.type.maximum}"
            }
        }
        if (spec.type == CalibrationTestType.RETRACTION) {
            require(retractionSpeedMmPerSecond in 0.1..1000.0) { "Retraction speed is outside 0.1..1000 mm/s" }
        }

        val builder = MeshBuilder()
        when (spec.type) {
            CalibrationTestType.TEMPERATURE -> buildTemperatureModel(builder, spec)
            CalibrationTestType.FLOW -> buildFlowModel(builder, spec)
            CalibrationTestType.SPEED,
            CalibrationTestType.JUNCTION_DEVIATION,
            -> buildSpeedModel(builder, spec)
            CalibrationTestType.PRESSURE_ADVANCE,
            CalibrationTestType.RETRACTION,
            -> buildRetractionModel(builder, spec)
            CalibrationTestType.FAN -> buildFanModel(builder, spec)
        }

        val events = values.mapIndexed { index, value ->
            PlannedLayerEvent(
                targetZMm = sectionStart(spec, index).toFloat(),
                type = spec.type.eventType,
                value = value,
                secondaryValue = if (spec.type == CalibrationTestType.RETRACTION) {
                    retractionSpeedMmPerSecond
                } else {
                    null
                },
                label = "Level ${index + 1}: ${format(value)} ${spec.type.unit}",
            )
        }

        val first = format(values.first())
        val last = format(values.last())
        val mesh = builder.finish("${spec.type.name.lowercase(Locale.US)}-calibration-$first-to-$last.stl")
        // MainViewModel activates temporary calibration behavior only after the
        // mesh, transformed preview and local STL have all been committed.
        return CalibrationTowerResult(
            mesh = mesh,
            plannedEvents = events,
            description = "${spec.type.displayName}: ${spec.levels} levels, $first to $last ${spec.type.unit}. ${spec.type.designDescription}",
            // CalibrationSliceState enables firmware retraction in the temporary
            // slice snapshot. Returning false here prevents the legacy UI path
            // from persisting that temporary requirement into user settings.
            requiresFirmwareRetraction = false,
            levelValues = values,
            modelFeatures = spec.type.modelFeatures,
        )
    }

    private fun buildTemperatureModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        val core = max(4.8, spec.towerWidthMm * 0.24)
        val post = max(1.8, spec.towerWidthMm * 0.09)
        val span = max(9.0, spec.towerWidthMm * 0.62)
        val bridgeY = core / 2.0 + max(4.0, spec.towerWidthMm * 0.22)
        val baseExtent = max(24.0, span + post + 5.0)
        addBase(builder, baseExtent, max(baseExtent, bridgeY * 2.0 + post + 5.0))

        builder.addBox(0.0, 0.0, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, core, core, totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM)
        listOf(-span / 2.0, span / 2.0).forEach { x ->
            builder.addBox(x, bridgeY, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, post, post, totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM)
        }

        val finLength = max(5.0, spec.towerWidthMm * 0.34)
        builder.addBox(
            centerX = -core / 2.0 - finLength / 2.0 + SOLID_OVERLAP_MM,
            centerY = -core * 0.30,
            minZ = BASE_HEIGHT_MM - SOLID_OVERLAP_MM,
            width = finLength,
            depth = 0.65,
            height = totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM,
        )

        repeat(spec.levels) { index ->
            val z = sectionStart(spec, index)
            val bridgeZ = z + spec.sectionHeightMm * 0.68
            builder.addBox(0.0, bridgeY, bridgeZ, span + post, post, 0.65)

            val bracketLength = minOf(4.8, max(3.2, spec.towerWidthMm * 0.22), spec.sectionHeightMm * 0.58)
            builder.addSteppedBracketX(
                rootX = core / 2.0 - SOLID_OVERLAP_MM,
                direction = 1,
                centerY = -core * 0.20,
                minZ = z + 0.25,
                length = bracketLength,
                depth = max(2.4, core * 0.62),
                rise = bracketLength,
                tipThickness = 0.55,
            )
        }
    }

    private fun buildFlowModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        val outer = spec.towerWidthMm
        val wall = (outer * 0.045).coerceIn(0.72, 1.15)
        val height = totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM
        addBase(builder, outer + 8.0, outer + 8.0)

        builder.addBox(0.0, -outer / 2.0 + wall / 2.0, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, outer, wall, height)
        builder.addBox(0.0, outer / 2.0 - wall / 2.0, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, outer, wall, height)
        builder.addBox(-outer / 2.0 + wall / 2.0, 0.0, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, wall, outer, height)
        builder.addBox(outer / 2.0 - wall / 2.0, 0.0, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, wall, outer, height)

        val ribDepth = 2.0
        builder.addBox(-outer * 0.24, -outer / 2.0 - ribDepth / 2.0 + SOLID_OVERLAP_MM, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, 0.8, ribDepth, height)
        builder.addBox(outer * 0.24, -outer / 2.0 - ribDepth / 2.0 + SOLID_OVERLAP_MM, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, 1.2, ribDepth, height)

        repeat(spec.levels) { index ->
            val z = sectionStart(spec, index)
            val couponZ = z + spec.sectionHeightMm * 0.70
            builder.addBox(
                centerX = 0.0,
                centerY = 0.0,
                minZ = couponZ,
                width = outer,
                depth = max(2.2, outer * 0.14),
                height = 0.6,
            )
            val bandZ = z + 0.15
            builder.addBox(0.0, -outer / 2.0 - 0.35 + SOLID_OVERLAP_MM, bandZ, outer * 0.72, 0.7, 0.55)
        }
    }

    private fun buildSpeedModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val outer = spec.towerWidthMm / 2.0
        val inner = spec.towerWidthMm * 0.27
        addBase(builder, spec.towerWidthMm + 5.0, spec.towerWidthMm + 5.0)
        repeat(spec.levels) { index ->
            val scale = if (index % 2 == 0) 1.0 else 0.94
            builder.addPolygonPrism(
                points = starPoints(outer * scale, inner * scale, 8, PI / 8.0),
                minZ = sectionStart(spec, index) - SOLID_OVERLAP_MM,
                height = spec.sectionHeightMm + SOLID_OVERLAP_MM * 2.0,
            )
        }
    }

    private fun buildFanModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        val core = max(5.0, spec.towerWidthMm * 0.24)
        val span = max(10.0, spec.towerWidthMm * 0.72)
        val post = max(1.8, spec.towerWidthMm * 0.09)
        val bridgeY = core / 2.0 + max(4.5, spec.towerWidthMm * 0.24)
        val baseExtent = max(26.0, span + post + 5.0)
        addBase(builder, baseExtent, max(baseExtent, bridgeY * 2.0 + post + 5.0))

        builder.addBox(0.0, 0.0, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, core, core, totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM)
        listOf(-span / 2.0, span / 2.0).forEach { x ->
            builder.addBox(x, bridgeY, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, post, post, totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM)
        }

        repeat(spec.levels) { index ->
            val z = sectionStart(spec, index)
            builder.addBox(0.0, bridgeY, z + spec.sectionHeightMm * 0.72, span + post, post, 0.6)

            val available = spec.sectionHeightMm * 0.62
            listOf(0.42, 0.62, 0.82).forEachIndexed { bracketIndex, fraction ->
                val length = minOf(available * fraction, max(2.4, spec.towerWidthMm * (0.12 + bracketIndex * 0.045)))
                builder.addSteppedBracketX(
                    rootX = core / 2.0 - SOLID_OVERLAP_MM,
                    direction = 1,
                    centerY = -core * 0.32 + bracketIndex * core * 0.32,
                    minZ = z + 0.20,
                    length = length,
                    depth = max(1.2, core * 0.22),
                    rise = length,
                    tipThickness = 0.50,
                )
            }
        }
    }

    private fun buildRetractionModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        addBase(builder, spec.towerWidthMm + 7.0, spec.towerWidthMm + 7.0)
        val radius = max(4.8, spec.towerWidthMm * 0.37)
        val postRadius = (spec.towerWidthMm * 0.055).coerceIn(0.9, 1.35)
        val postHeight = totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM
        val positions = (0 until 8).map { index ->
            val angle = 2.0 * PI * index / 8.0 + PI / 8.0
            Point2(cos(angle) * radius, sin(angle) * radius)
        }
        positions.forEachIndexed { index, point ->
            if (index % 2 == 0) {
                builder.addCylinder(point.x, point.y, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, postRadius, postHeight, 14)
            } else {
                builder.addBox(point.x, point.y, BASE_HEIGHT_MM - SOLID_OVERLAP_MM, postRadius * 1.85, postRadius * 1.85, postHeight)
            }
        }
        val referencePost = positions.first()
        repeat(spec.levels) { index ->
            builder.addCylinder(referencePost.x, referencePost.y, sectionStart(spec, index) + 0.18, postRadius * 1.42, 0.55, 14)
        }
    }

    private fun addBase(builder: MeshBuilder, width: Double, depth: Double) {
        builder.addBox(0.0, 0.0, 0.0, width, depth, BASE_HEIGHT_MM)
    }

    private fun totalHeight(spec: CalibrationTowerSpec): Double = BASE_HEIGHT_MM + spec.levels * spec.sectionHeightMm
    private fun sectionStart(spec: CalibrationTowerSpec, index: Int): Double = BASE_HEIGHT_MM + index * spec.sectionHeightMm

    private fun starPoints(outerRadius: Double, innerRadius: Double, pointCount: Int, rotationRadians: Double): List<Point2> =
        (0 until pointCount * 2).map { index ->
            val angle = rotationRadians + PI * index / pointCount
            val radius = if (index % 2 == 0) outerRadius else innerRadius
            Point2(cos(angle) * radius, sin(angle) * radius)
        }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
}
