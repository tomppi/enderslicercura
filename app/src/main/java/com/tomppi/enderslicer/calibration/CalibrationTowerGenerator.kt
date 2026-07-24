package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PlannedLayerEvent
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class CalibrationModelFeature {
    BRIDGES,
    OVERHANGS,
    FINE_DETAILS,
    THIN_WALLS,
    TOP_SURFACES,
    DIMENSIONAL_RIBS,
    SHARP_CORNERS,
    DIRECTION_CHANGES,
    TALL_WALLS,
    CANTILEVERS,
    SEPARATED_POSTS,
    TRAVEL_GAPS,
    SMALL_ISLANDS,
}

enum class CalibrationTestType(
    val displayName: String,
    val unit: String,
    val defaultStart: Double,
    val defaultStep: Double,
    val minimum: Double,
    val maximum: Double,
    val eventType: LayerEventType,
    val defaultLevels: Int,
    val designDescription: String,
    val modelFeatures: Set<CalibrationModelFeature>,
) {
    TEMPERATURE(
        "Temperature tower",
        "°C",
        230.0,
        -5.0,
        150.0,
        500.0,
        LayerEventType.NOZZLE_TEMPERATURE,
        8,
        "Bridges, a sloped overhang and a thin fin repeat in every temperature section.",
        setOf(
            CalibrationModelFeature.BRIDGES,
            CalibrationModelFeature.OVERHANGS,
            CalibrationModelFeature.FINE_DETAILS,
        ),
    ),
    FLOW(
        "Flow tower",
        "%",
        90.0,
        2.5,
        10.0,
        300.0,
        LayerEventType.FLOW_FACTOR,
        8,
        "Thin perimeter walls, fresh top surfaces and measurement ribs expose over- and under-extrusion.",
        setOf(
            CalibrationModelFeature.THIN_WALLS,
            CalibrationModelFeature.TOP_SURFACES,
            CalibrationModelFeature.DIMENSIONAL_RIBS,
        ),
    ),
    SPEED(
        "Speed-factor tower",
        "%",
        60.0,
        10.0,
        10.0,
        999.0,
        LayerEventType.SPEED_FACTOR,
        8,
        "A sharp multi-point star forces repeated acceleration, cornering and direction changes.",
        setOf(
            CalibrationModelFeature.SHARP_CORNERS,
            CalibrationModelFeature.DIRECTION_CHANGES,
            CalibrationModelFeature.TALL_WALLS,
        ),
    ),
    FAN(
        "Fan tower",
        "%",
        0.0,
        20.0,
        0.0,
        100.0,
        LayerEventType.FAN_SPEED,
        6,
        "Long bridges and three progressively longer unsupported shelves repeat at every fan level.",
        setOf(
            CalibrationModelFeature.BRIDGES,
            CalibrationModelFeature.CANTILEVERS,
            CalibrationModelFeature.OVERHANGS,
        ),
    ),
    RETRACTION(
        "Firmware-retraction tower",
        "mm",
        0.5,
        0.25,
        0.0,
        100.0,
        LayerEventType.RETRACTION,
        8,
        "Eight isolated posts create repeated travel moves and small islands for clear stringing comparison.",
        setOf(
            CalibrationModelFeature.SEPARATED_POSTS,
            CalibrationModelFeature.TRAVEL_GAPS,
            CalibrationModelFeature.SMALL_ISLANDS,
        ),
    ),
}

data class CalibrationTowerSpec(
    val type: CalibrationTestType = CalibrationTestType.TEMPERATURE,
    val startValue: Double = type.defaultStart,
    val stepValue: Double = type.defaultStep,
    val levels: Int = type.defaultLevels,
    val sectionHeightMm: Double = 8.0,
    val towerWidthMm: Double = 20.0,
)

data class CalibrationTowerResult(
    val mesh: StlMesh,
    val plannedEvents: List<PlannedLayerEvent>,
    val description: String,
    val requiresFirmwareRetraction: Boolean,
    val levelValues: List<Double>,
    val modelFeatures: Set<CalibrationModelFeature>,
)

object CalibrationTowerGenerator {
    private const val BASE_HEIGHT_MM = 1.2
    private const val SOLID_OVERLAP_MM = 0.25

    fun generate(
        spec: CalibrationTowerSpec,
        retractionSpeedMmPerSecond: Double,
    ): CalibrationTowerResult {
        require(spec.levels in 2..20) { "Calibration tower levels must be between 2 and 20" }
        require(spec.sectionHeightMm in 3.0..30.0) { "Section height must be between 3 and 30 mm" }
        require(spec.towerWidthMm in 12.0..45.0) { "Tower width must be between 12 and 45 mm" }
        val values = List(spec.levels) { index -> spec.startValue + spec.stepValue * index }
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
            CalibrationTestType.SPEED -> buildSpeedModel(builder, spec)
            CalibrationTestType.FAN -> buildFanModel(builder, spec)
            CalibrationTestType.RETRACTION -> buildRetractionModel(builder, spec)
        }

        val events = values.mapIndexed { index, value ->
            val sectionStart = (BASE_HEIGHT_MM + index * spec.sectionHeightMm).toFloat()
            PlannedLayerEvent(
                targetZMm = sectionStart,
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
        val fileName = "${spec.type.name.lowercase(Locale.US)}-calibration-$first-to-$last.stl"
        return CalibrationTowerResult(
            mesh = builder.finish(fileName),
            plannedEvents = events,
            description = "${spec.type.displayName}: ${spec.levels} levels, $first to $last ${spec.type.unit}. ${spec.type.designDescription}",
            requiresFirmwareRetraction = spec.type == CalibrationTestType.RETRACTION,
            levelValues = values,
            modelFeatures = spec.type.modelFeatures,
        )
    }

    private fun buildTemperatureModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        val core = max(5.0, spec.towerWidthMm * 0.25)
        val baseExtent = max(30.0, spec.towerWidthMm + 16.0)
        addBase(builder, baseExtent, baseExtent)
        builder.addBox(
            centerX = 0.0,
            centerY = 0.0,
            minZ = BASE_HEIGHT_MM - SOLID_OVERLAP_MM,
            width = core,
            depth = core,
            height = totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM,
        )

        repeat(spec.levels) { index ->
            val z = sectionStart(spec, index)
            val sectionHeight = spec.sectionHeightMm
            val postSize = max(1.8, spec.towerWidthMm * 0.10)
            val span = max(8.0, spec.towerWidthMm * 0.58)
            val bridgeY = core / 2.0 + 4.0
            val bridgeRise = min(sectionHeight * 0.50, 4.2)
            val floorDepth = bridgeY + postSize / 2.0 - core / 2.0 + SOLID_OVERLAP_MM
            builder.addBox(
                centerX = 0.0,
                centerY = core / 2.0 + floorDepth / 2.0 - SOLID_OVERLAP_MM,
                minZ = z + 0.18,
                width = span + postSize * 1.4,
                depth = floorDepth,
                height = 0.6,
            )
            listOf(-span / 2.0, span / 2.0).forEach { x ->
                builder.addBox(
                    centerX = x,
                    centerY = bridgeY,
                    minZ = z + 0.18,
                    width = postSize,
                    depth = postSize,
                    height = bridgeRise,
                )
            }
            builder.addBox(
                centerX = 0.0,
                centerY = bridgeY,
                minZ = z + 0.18 + bridgeRise,
                width = span + postSize,
                depth = postSize,
                height = 0.7,
            )

            val rampLength = max(5.0, spec.towerWidthMm * 0.42)
            builder.addWedgeX(
                centerX = -core / 2.0 - rampLength / 2.0 + SOLID_OVERLAP_MM,
                centerY = -core * 0.12,
                minZ = z + sectionHeight * 0.18,
                length = rampLength,
                depth = max(3.0, core * 0.70),
                heightAtNegativeX = 0.65,
                heightAtPositiveX = min(3.2, sectionHeight * 0.42),
            )

            val finLength = max(4.0, spec.towerWidthMm * 0.30)
            builder.addBox(
                centerX = core / 2.0 + finLength / 2.0 - SOLID_OVERLAP_MM,
                centerY = -core * 0.30,
                minZ = z + sectionHeight * 0.14,
                width = finLength,
                depth = 0.65,
                height = min(5.0, sectionHeight * 0.68),
            )
        }
    }

    private fun buildFlowModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        val outer = spec.towerWidthMm
        val wall = (outer * 0.045).coerceIn(0.72, 1.15)
        val baseExtent = outer + 6.0
        addBase(builder, baseExtent, baseExtent)
        builder.addBox(
            centerX = 0.0,
            centerY = 0.0,
            minZ = BASE_HEIGHT_MM - SOLID_OVERLAP_MM,
            width = 4.0,
            depth = 4.0,
            height = totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM,
        )

        repeat(spec.levels) { index ->
            val z = sectionStart(spec, index)
            val sectionHeight = spec.sectionHeightMm
            val wallHeight = max(2.1, sectionHeight - 0.35)
            builder.addBox(
                centerX = 0.0,
                centerY = 0.0,
                minZ = z - SOLID_OVERLAP_MM,
                width = outer,
                depth = outer,
                height = 0.65,
            )
            builder.addBox(0.0, -outer / 2.0 + wall / 2.0, z, outer, wall, wallHeight)
            builder.addBox(0.0, outer / 2.0 - wall / 2.0, z, outer, wall, wallHeight)
            builder.addBox(-outer / 2.0 + wall / 2.0, 0.0, z, wall, outer, wallHeight)
            builder.addBox(outer / 2.0 - wall / 2.0, 0.0, z, wall, outer, wallHeight)

            val couponDepth = max(3.0, outer * 0.22)
            builder.addBox(
                centerX = 0.0,
                centerY = outer / 2.0 + couponDepth / 2.0 - SOLID_OVERLAP_MM,
                minZ = z + sectionHeight - 0.85,
                width = outer * 0.72,
                depth = couponDepth,
                height = 0.65,
            )
            val ribHeight = min(3.2, sectionHeight * 0.45)
            builder.addBox(
                centerX = -outer * 0.24,
                centerY = -outer / 2.0 - 1.0 + SOLID_OVERLAP_MM,
                minZ = z + 0.65,
                width = 0.8,
                depth = 2.0,
                height = ribHeight,
            )
            builder.addBox(
                centerX = outer * 0.24,
                centerY = -outer / 2.0 - 1.0 + SOLID_OVERLAP_MM,
                minZ = z + 0.65,
                width = 1.2,
                depth = 2.0,
                height = ribHeight,
            )
        }
    }

    private fun buildSpeedModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val outer = spec.towerWidthMm / 2.0
        val inner = spec.towerWidthMm * 0.27
        val baseExtent = spec.towerWidthMm + 5.0
        addBase(builder, baseExtent, baseExtent)

        repeat(spec.levels) { index ->
            val scale = if (index % 2 == 0) 1.0 else 0.94
            val points = starPoints(
                outerRadius = outer * scale,
                innerRadius = inner * scale,
                pointCount = 8,
                rotationRadians = PI / 8.0,
            )
            builder.addPolygonPrism(
                points = points,
                minZ = sectionStart(spec, index) - SOLID_OVERLAP_MM,
                height = spec.sectionHeightMm + SOLID_OVERLAP_MM * 2.0,
            )
        }
    }

    private fun buildFanModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        val core = max(5.0, spec.towerWidthMm * 0.24)
        val baseExtent = max(30.0, spec.towerWidthMm + 18.0)
        addBase(builder, baseExtent, baseExtent)
        builder.addBox(
            centerX = 0.0,
            centerY = 0.0,
            minZ = BASE_HEIGHT_MM - SOLID_OVERLAP_MM,
            width = core,
            depth = core,
            height = totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM,
        )

        repeat(spec.levels) { index ->
            val z = sectionStart(spec, index)
            val sectionHeight = spec.sectionHeightMm
            val plateThickness = 0.6
            val shelfLengths = listOf(0.22, 0.35, 0.50).map { max(4.0, spec.towerWidthMm * it) }
            shelfLengths.forEachIndexed { shelfIndex, length ->
                val shelfZ = z + sectionHeight * (0.22 + shelfIndex * 0.22)
                builder.addBox(
                    centerX = 0.0,
                    centerY = -core / 2.0 - length / 2.0 + SOLID_OVERLAP_MM,
                    minZ = shelfZ,
                    width = max(4.0, core * 0.78),
                    depth = length,
                    height = plateThickness,
                )
            }

            val span = max(9.0, spec.towerWidthMm * 0.68)
            val post = max(1.8, spec.towerWidthMm * 0.09)
            val bridgeY = core / 2.0 + 4.5
            val bridgeRise = min(sectionHeight * 0.58, 4.8)
            val connectorDepth = bridgeY + post / 2.0 - core / 2.0 + SOLID_OVERLAP_MM
            builder.addBox(
                centerX = 0.0,
                centerY = core / 2.0 + connectorDepth / 2.0 - SOLID_OVERLAP_MM,
                minZ = z + 0.15,
                width = span + post * 1.5,
                depth = connectorDepth,
                height = 0.55,
            )
            listOf(-span / 2.0, span / 2.0).forEach { x ->
                builder.addBox(x, bridgeY, z + 0.15, post, post, bridgeRise)
            }
            builder.addBox(
                centerX = 0.0,
                centerY = bridgeY,
                minZ = z + 0.15 + bridgeRise,
                width = span + post,
                depth = post,
                height = plateThickness,
            )

            val rampLength = max(5.0, spec.towerWidthMm * 0.38)
            builder.addWedgeX(
                centerX = core / 2.0 + rampLength / 2.0 - SOLID_OVERLAP_MM,
                centerY = 0.0,
                minZ = z + sectionHeight * 0.12,
                length = rampLength,
                depth = max(3.0, core * 0.70),
                heightAtNegativeX = min(3.0, sectionHeight * 0.38),
                heightAtPositiveX = 0.65,
            )
        }
    }

    private fun buildRetractionModel(builder: MeshBuilder, spec: CalibrationTowerSpec) {
        val totalHeight = totalHeight(spec)
        val baseExtent = spec.towerWidthMm + 7.0
        addBase(builder, baseExtent, baseExtent)
        val radius = max(4.8, spec.towerWidthMm * 0.37)
        val postRadius = (spec.towerWidthMm * 0.055).coerceIn(0.9, 1.35)
        val postHeight = totalHeight - BASE_HEIGHT_MM + SOLID_OVERLAP_MM
        val positions = (0 until 8).map { index ->
            val angle = 2.0 * PI * index / 8.0 + PI / 8.0
            Point2(cos(angle) * radius, sin(angle) * radius)
        }
        positions.forEachIndexed { index, point ->
            if (index % 2 == 0) {
                builder.addCylinder(
                    centerX = point.x,
                    centerY = point.y,
                    minZ = BASE_HEIGHT_MM - SOLID_OVERLAP_MM,
                    radius = postRadius,
                    height = postHeight,
                    segments = 14,
                )
            } else {
                builder.addBox(
                    centerX = point.x,
                    centerY = point.y,
                    minZ = BASE_HEIGHT_MM - SOLID_OVERLAP_MM,
                    width = postRadius * 1.85,
                    depth = postRadius * 1.85,
                    height = postHeight,
                )
            }
        }
        val referencePost = positions.first()
        repeat(spec.levels) { index ->
            builder.addCylinder(
                centerX = referencePost.x,
                centerY = referencePost.y,
                minZ = sectionStart(spec, index) + 0.18,
                radius = postRadius * 1.42,
                height = 0.55,
                segments = 14,
            )
        }
    }

    private fun addBase(builder: MeshBuilder, width: Double, depth: Double) {
        builder.addBox(
            centerX = 0.0,
            centerY = 0.0,
            minZ = 0.0,
            width = width,
            depth = depth,
            height = BASE_HEIGHT_MM,
        )
    }

    private fun totalHeight(spec: CalibrationTowerSpec): Double =
        BASE_HEIGHT_MM + spec.levels * spec.sectionHeightMm

    private fun sectionStart(spec: CalibrationTowerSpec, index: Int): Double =
        BASE_HEIGHT_MM + index * spec.sectionHeightMm

    private fun starPoints(
        outerRadius: Double,
        innerRadius: Double,
        pointCount: Int,
        rotationRadians: Double,
    ): List<Point2> = (0 until pointCount * 2).map { index ->
        val angle = rotationRadians + PI * index / pointCount
        val radius = if (index % 2 == 0) outerRadius else innerRadius
        Point2(cos(angle) * radius, sin(angle) * radius)
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private data class Point2(val x: Double, val y: Double)

    private class MeshBuilder {
        private var data = FloatArray(18 * 12 * 32)
        private var size = 0
        private var triangleCount = 0
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY

        fun addBox(
            centerX: Double,
            centerY: Double,
            minZ: Double,
            width: Double,
            depth: Double,
            height: Double,
        ) {
            require(width > 0.0 && depth > 0.0 && height > 0.0)
            val x0 = (centerX - width / 2.0).toFloat()
            val x1 = (centerX + width / 2.0).toFloat()
            val y0 = (centerY - depth / 2.0).toFloat()
            val y1 = (centerY + depth / 2.0).toFloat()
            val z0 = minZ.toFloat()
            val z1 = (minZ + height).toFloat()

            quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0f, 0f, -1f)
            quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f)
            quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f)
            quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f)
            quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f)
            quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1f, 0f, 0f)
        }

        fun addCylinder(
            centerX: Double,
            centerY: Double,
            minZ: Double,
            radius: Double,
            height: Double,
            segments: Int,
        ) {
            require(radius > 0.0 && height > 0.0 && segments >= 6)
            val z0 = minZ.toFloat()
            val z1 = (minZ + height).toFloat()
            val cx = centerX.toFloat()
            val cy = centerY.toFloat()
            repeat(segments) { index ->
                val angle0 = 2.0 * PI * index / segments
                val angle1 = 2.0 * PI * (index + 1) / segments
                val x0 = (centerX + cos(angle0) * radius).toFloat()
                val y0 = (centerY + sin(angle0) * radius).toFloat()
                val x1 = (centerX + cos(angle1) * radius).toFloat()
                val y1 = (centerY + sin(angle1) * radius).toFloat()
                triangle(cx, cy, z0, x1, y1, z0, x0, y0, z0, 0f, 0f, -1f)
                triangle(cx, cy, z1, x0, y0, z1, x1, y1, z1, 0f, 0f, 1f)
                val mid = (angle0 + angle1) / 2.0
                quad(
                    x0, y0, z0,
                    x1, y1, z0,
                    x1, y1, z1,
                    x0, y0, z1,
                    cos(mid).toFloat(), sin(mid).toFloat(), 0f,
                )
            }
        }

        fun addPolygonPrism(points: List<Point2>, minZ: Double, height: Double) {
            require(points.size >= 3 && height > 0.0)
            val centerX = points.sumOf { it.x } / points.size
            val centerY = points.sumOf { it.y } / points.size
            val z0 = minZ.toFloat()
            val z1 = (minZ + height).toFloat()
            repeat(points.size) { index ->
                val current = points[index]
                val next = points[(index + 1) % points.size]
                val x0 = current.x.toFloat()
                val y0 = current.y.toFloat()
                val x1 = next.x.toFloat()
                val y1 = next.y.toFloat()
                triangle(centerX.toFloat(), centerY.toFloat(), z0, x1, y1, z0, x0, y0, z0, 0f, 0f, -1f)
                triangle(centerX.toFloat(), centerY.toFloat(), z1, x0, y0, z1, x1, y1, z1, 0f, 0f, 1f)
                val dx = next.x - current.x
                val dy = next.y - current.y
                val normalLength = sqrt(dx * dx + dy * dy)
                val nx = (dy / normalLength).toFloat()
                val ny = (-dx / normalLength).toFloat()
                quad(x0, y0, z0, x1, y1, z0, x1, y1, z1, x0, y0, z1, nx, ny, 0f)
            }
        }

        fun addWedgeX(
            centerX: Double,
            centerY: Double,
            minZ: Double,
            length: Double,
            depth: Double,
            heightAtNegativeX: Double,
            heightAtPositiveX: Double,
        ) {
            require(length > 0.0 && depth > 0.0 && heightAtNegativeX > 0.0 && heightAtPositiveX > 0.0)
            val x0 = (centerX - length / 2.0).toFloat()
            val x1 = (centerX + length / 2.0).toFloat()
            val y0 = (centerY - depth / 2.0).toFloat()
            val y1 = (centerY + depth / 2.0).toFloat()
            val z0 = minZ.toFloat()
            val zLeft = (minZ + heightAtNegativeX).toFloat()
            val zRight = (minZ + heightAtPositiveX).toFloat()

            quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0f, 0f, -1f)
            quadAuto(x0, y0, zLeft, x1, y0, zRight, x1, y1, zRight, x0, y1, zLeft)
            quad(x0, y0, z0, x1, y0, z0, x1, y0, zRight, x0, y0, zLeft, 0f, -1f, 0f)
            quad(x0, y1, z0, x0, y1, zLeft, x1, y1, zRight, x1, y1, z0, 0f, 1f, 0f)
            quad(x0, y0, z0, x0, y0, zLeft, x0, y1, zLeft, x0, y1, z0, -1f, 0f, 0f)
            quad(x1, y0, z0, x1, y1, z0, x1, y1, zRight, x1, y0, zRight, 1f, 0f, 0f)
        }

        private fun quad(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            dx: Float, dy: Float, dz: Float,
            nx: Float, ny: Float, nz: Float,
        ) {
            triangle(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz)
            triangle(ax, ay, az, cx, cy, cz, dx, dy, dz, nx, ny, nz)
        }

        private fun quadAuto(
            ax: Float, ay: Float, az: Float,
            bx: Float, by: Float, bz: Float,
            cx: Float, cy: Float, cz: Float,
            dx: Float, dy: Float, dz: Float,
        ) {
            val normal = normal(ax, ay, az, bx, by, bz, cx, cy, cz)
            quad(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, normal[0], normal[1], normal[2])
        }

        private fun triangle(
            ax: Float,
            ay: Float,
            az: Float,
            bx: Float,
            by: Float,
            bz: Float,
            cx: Float,
            cy: Float,
            cz: Float,
            requestedNx: Float,
            requestedNy: Float,
            requestedNz: Float,
        ) {
            val actual = normal(ax, ay, az, bx, by, bz, cx, cy, cz)
            if (actual[0] * requestedNx + actual[1] * requestedNy + actual[2] * requestedNz < 0f) {
                triangle(ax, ay, az, cx, cy, cz, bx, by, bz, requestedNx, requestedNy, requestedNz)
                return
            }
            val requestedLength = sqrt(
                requestedNx * requestedNx + requestedNy * requestedNy + requestedNz * requestedNz,
            )
            val nx = requestedNx / requestedLength
            val ny = requestedNy / requestedLength
            val nz = requestedNz / requestedLength
            putVertex(ax, ay, az, nx, ny, nz)
            putVertex(bx, by, bz, nx, ny, nz)
            putVertex(cx, cy, cz, nx, ny, nz)
            triangleCount++
        }

        private fun normal(
            ax: Float,
            ay: Float,
            az: Float,
            bx: Float,
            by: Float,
            bz: Float,
            cx: Float,
            cy: Float,
            cz: Float,
        ): FloatArray {
            val abx = bx - ax
            val aby = by - ay
            val abz = bz - az
            val acx = cx - ax
            val acy = cy - ay
            val acz = cz - az
            var nx = aby * acz - abz * acy
            var ny = abz * acx - abx * acz
            var nz = abx * acy - aby * acx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            require(length > 1e-12f) { "Calibration geometry produced a degenerate triangle" }
            nx /= length
            ny /= length
            nz /= length
            return floatArrayOf(nx, ny, nz)
        }

        private fun putVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            ensure(size + 6)
            data[size++] = x
            data[size++] = y
            data[size++] = z
            data[size++] = nx
            data[size++] = ny
            data[size++] = nz
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
        }

        private fun ensure(required: Int) {
            if (required <= data.size) return
            var capacity = data.size
            while (capacity < required) capacity *= 2
            data = data.copyOf(capacity)
        }

        fun finish(displayName: String): StlMesh {
            require(triangleCount > 0)
            return StlMesh(
                displayName = displayName,
                interleavedVertices = data.copyOf(size),
                triangleCount = triangleCount,
                bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
            )
        }
    }
}
