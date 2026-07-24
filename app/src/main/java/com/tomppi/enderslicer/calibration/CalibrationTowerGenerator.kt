package com.tomppi.enderslicer.calibration

import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PlannedLayerEvent
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import java.util.Locale
import kotlin.math.sqrt

enum class CalibrationTestType(
    val displayName: String,
    val unit: String,
    val defaultStart: Double,
    val defaultStep: Double,
    val minimum: Double,
    val maximum: Double,
    val eventType: LayerEventType,
) {
    TEMPERATURE("Temperature tower", "°C", 230.0, -5.0, 150.0, 500.0, LayerEventType.NOZZLE_TEMPERATURE),
    FLOW("Flow tower", "%", 90.0, 2.5, 10.0, 300.0, LayerEventType.FLOW_FACTOR),
    SPEED("Speed-factor tower", "%", 60.0, 10.0, 10.0, 999.0, LayerEventType.SPEED_FACTOR),
    FAN("Fan tower", "%", 0.0, 15.0, 0.0, 100.0, LayerEventType.FAN_SPEED),
    RETRACTION("Firmware-retraction tower", "mm", 0.5, 0.25, 0.0, 100.0, LayerEventType.RETRACTION),
}

data class CalibrationTowerSpec(
    val type: CalibrationTestType = CalibrationTestType.TEMPERATURE,
    val startValue: Double = type.defaultStart,
    val stepValue: Double = type.defaultStep,
    val levels: Int = 8,
    val sectionHeightMm: Double = 8.0,
    val towerWidthMm: Double = 20.0,
)

data class CalibrationTowerResult(
    val mesh: StlMesh,
    val plannedEvents: List<PlannedLayerEvent>,
    val description: String,
    val requiresFirmwareRetraction: Boolean,
    val levelValues: List<Double>,
)

object CalibrationTowerGenerator {
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

        val baseHeight = 1.2
        val builder = MeshBuilder()
        builder.addBox(
            centerX = 0.0,
            centerY = 0.0,
            minZ = 0.0,
            width = spec.towerWidthMm + 5.0,
            depth = spec.towerWidthMm + 5.0,
            height = baseHeight,
        )

        values.indices.forEach { index ->
            val sectionStart = baseHeight + index * spec.sectionHeightMm
            val inset = if (index % 2 == 0) 0.0 else 1.2
            val sectionWidth = spec.towerWidthMm - inset
            builder.addBox(
                centerX = 0.0,
                centerY = 0.0,
                minZ = sectionStart,
                width = sectionWidth,
                depth = sectionWidth,
                height = spec.sectionHeightMm,
            )

            // A short front tab makes every section boundary visible and gives
            // the user an easy bottom-to-top level count without requiring text.
            val markerWidth = 3.0 + (index % 5) * 2.0
            builder.addBox(
                centerX = -sectionWidth / 2.0 + markerWidth / 2.0,
                centerY = sectionWidth / 2.0 + 1.1,
                minZ = sectionStart + 0.8,
                width = markerWidth,
                depth = 2.2,
                height = 1.4,
            )
        }

        val events = values.mapIndexed { index, value ->
            val sectionStart = (baseHeight + index * spec.sectionHeightMm).toFloat()
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
        val fileName = "${spec.type.name.lowercase(Locale.US)}-tower-$first-to-$last.stl"
        return CalibrationTowerResult(
            mesh = builder.finish(fileName),
            plannedEvents = events,
            description = "${spec.type.displayName}: ${spec.levels} levels, $first to $last ${spec.type.unit}",
            requiresFirmwareRetraction = spec.type == CalibrationTestType.RETRACTION,
            levelValues = values,
        )
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    private class MeshBuilder {
        private var data = FloatArray(18 * 12 * 16)
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

            face(x0, y0, z0, x1, y1, z0, 0f, 0f, -1f)
            face(x0, y0, z1, x0, y1, z1, 0f, 0f, 1f)
            face(x0, y0, z0, x0, y0, z1, 0f, -1f, 0f)
            face(x1, y1, z0, x1, y1, z1, 0f, 1f, 0f)
            face(x0, y1, z0, x0, y1, z1, -1f, 0f, 0f)
            face(x1, y0, z0, x1, y0, z1, 1f, 0f, 0f)
        }

        private fun face(
            ax: Float,
            ay: Float,
            az: Float,
            bx: Float,
            by: Float,
            bz: Float,
            nx: Float,
            ny: Float,
            nz: Float,
        ) {
            when {
                nx != 0f -> {
                    triangle(ax, ay, az, ax, by, az, ax, by, bz, nx, ny, nz)
                    triangle(ax, ay, az, ax, by, bz, ax, ay, bz, nx, ny, nz)
                }
                ny != 0f -> {
                    triangle(ax, ay, az, bx, ay, bz, bx, ay, az, nx, ny, nz)
                    triangle(ax, ay, az, ax, ay, bz, bx, ay, bz, nx, ny, nz)
                }
                nz < 0f -> {
                    triangle(ax, ay, az, bx, by, az, bx, ay, az, nx, ny, nz)
                    triangle(ax, ay, az, ax, by, az, bx, by, az, nx, ny, nz)
                }
                else -> {
                    triangle(ax, ay, az, bx, ay, az, bx, by, az, nx, ny, nz)
                    triangle(ax, ay, az, bx, by, az, ax, by, az, nx, ny, nz)
                }
            }
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
            if (length > 1e-12f) {
                nx /= length
                ny /= length
                nz /= length
            }
            if (nx * requestedNx + ny * requestedNy + nz * requestedNz < 0f) {
                triangle(ax, ay, az, cx, cy, cz, bx, by, bz, requestedNx, requestedNy, requestedNz)
                return
            }
            putVertex(ax, ay, az, requestedNx, requestedNy, requestedNz)
            putVertex(bx, by, bz, requestedNx, requestedNy, requestedNz)
            putVertex(cx, cy, cz, requestedNx, requestedNy, requestedNz)
            triangleCount++
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
