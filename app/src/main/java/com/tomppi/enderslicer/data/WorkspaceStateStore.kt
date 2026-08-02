package com.tomppi.enderslicer.data

import android.content.Context
import com.tomppi.enderslicer.calibration.CalibrationTestType
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.PlannedLayerEvent
import com.tomppi.enderslicer.model.ModelPlacement
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Atomic, bounded persistence for the active model workspace. */
class WorkspaceStateStore(private val filesDirectory: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    data class Snapshot(
        val modelPath: String,
        val modelDisplayName: String,
        val placement: ModelPlacement,
        val plannedEvents: List<PlannedLayerEvent>,
        val calibrationDescription: String?,
        val calibrationType: CalibrationTestType?,
        val calibrationFirstValue: Double?,
        val configurationFingerprint: String,
    )

    private val stateDirectory = File(filesDirectory, "persistent-state").apply { mkdirs() }
    private val workspaceFile = File(stateDirectory, "current-workspace.json")

    fun save(snapshot: Snapshot) {
        validate(snapshot)
        val temporary = File(stateDirectory, "current-workspace.next")
        val backup = File(stateDirectory, "current-workspace.previous")
        temporary.delete()
        backup.delete()

        val bytes = encode(snapshot).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WORKSPACE_BYTES) { "Workspace descriptor exceeds its safety limit" }
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        check(temporary.isFile && temporary.length() == bytes.size.toLong()) {
            "Unable to stage the workspace descriptor"
        }

        try {
            if (workspaceFile.exists()) {
                check(
                    workspaceFile.renameTo(backup) ||
                        workspaceFile.copyTo(backup, overwrite = true).let { workspaceFile.delete(); true },
                ) { "Unable to preserve the previous workspace descriptor" }
            }
            try {
                check(
                    temporary.renameTo(workspaceFile) ||
                        temporary.copyTo(workspaceFile, overwrite = true).let { temporary.delete(); true },
                ) { "Unable to commit the workspace descriptor" }
            } catch (error: Throwable) {
                workspaceFile.delete()
                if (backup.exists()) {
                    backup.renameTo(workspaceFile) ||
                        backup.copyTo(workspaceFile, overwrite = true).let { backup.delete(); true }
                }
                throw error
            }
            backup.delete()
        } finally {
            temporary.delete()
            if (backup.exists() && workspaceFile.exists()) backup.delete()
        }
    }

    fun load(): Snapshot? {
        if (!workspaceFile.isFile || workspaceFile.length() == 0L) return null
        require(workspaceFile.length() <= MAX_WORKSPACE_BYTES) {
            "Saved workspace descriptor exceeds its safety limit"
        }
        val snapshot = decode(JSONObject(workspaceFile.readText()))
        validate(snapshot)
        return snapshot
    }

    fun clear() {
        workspaceFile.delete()
        File(stateDirectory, "current-workspace.next").delete()
        File(stateDirectory, "current-workspace.previous").delete()
    }

    private fun validate(snapshot: Snapshot) {
        require(snapshot.modelDisplayName.isNotBlank() && snapshot.modelDisplayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Workspace model name is invalid"
        }
        require(snapshot.configurationFingerprint.matches(FINGERPRINT_PATTERN)) {
            "Workspace configuration fingerprint is invalid"
        }
        val model = File(snapshot.modelPath).canonicalFile
        val modelDirectory = File(filesDirectory, "models").canonicalFile
        require(model.path.startsWith(modelDirectory.path + File.separator)) {
            "Workspace model is outside the private model directory"
        }
        require(model.isFile && model.length() > 0L) { "Workspace model is unavailable" }
        require(snapshot.plannedEvents.size <= MAX_PLANNED_EVENTS) {
            "Workspace contains too many calibration events"
        }
        snapshot.plannedEvents.forEach { event ->
            require(event.targetZMm.isFinite() && event.targetZMm >= 0f) {
                "Workspace event has an invalid target height"
            }
            require(event.value?.isFinite() != false && event.secondaryValue?.isFinite() != false) {
                "Workspace event has a non-finite value"
            }
            require(event.label.length <= MAX_EVENT_LABEL_LENGTH) { "Workspace event label is too long" }
        }
        require(snapshot.calibrationFirstValue?.isFinite() != false) {
            "Workspace calibration value is non-finite"
        }
        require((snapshot.calibrationType == null) == (snapshot.calibrationFirstValue == null)) {
            "Workspace calibration identity is incomplete"
        }
    }

    private fun encode(snapshot: Snapshot): JSONObject {
        val placement = snapshot.placement
        val events = JSONArray()
        snapshot.plannedEvents.forEach { event ->
            events.put(
                JSONObject()
                    .put("targetZMm", event.targetZMm.toDouble())
                    .put("type", event.type.name)
                    .put("value", event.value)
                    .put("secondaryValue", event.secondaryValue)
                    .put("label", event.label),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("modelPath", snapshot.modelPath)
            .put("modelDisplayName", snapshot.modelDisplayName)
            .put(
                "placement",
                JSONObject()
                    .put("linear", JSONArray(placement.linear))
                    .put("centerXmm", placement.centerXmm)
                    .put("centerYmm", placement.centerYmm)
                    .put("baseZmm", placement.baseZmm)
                    .put("source", placement.source),
            )
            .put("plannedEvents", events)
            .put("calibrationDescription", snapshot.calibrationDescription)
            .put("calibrationType", snapshot.calibrationType?.name)
            .put("calibrationFirstValue", snapshot.calibrationFirstValue)
            .put("configurationFingerprint", snapshot.configurationFingerprint)
    }

    private fun decode(root: JSONObject): Snapshot {
        require(root.getInt("version") == VERSION) { "Unsupported workspace descriptor version" }
        val placementJson = root.getJSONObject("placement")
        val linearJson = placementJson.getJSONArray("linear")
        require(linearJson.length() == 9) { "Workspace placement matrix must contain nine values" }
        val eventsJson = root.optJSONArray("plannedEvents") ?: JSONArray()
        require(eventsJson.length() <= MAX_PLANNED_EVENTS) { "Workspace contains too many events" }
        val events = List(eventsJson.length()) { index ->
            val event = eventsJson.getJSONObject(index)
            PlannedLayerEvent(
                targetZMm = event.getDouble("targetZMm").toFloat(),
                type = LayerEventType.valueOf(event.getString("type")),
                value = event.optNullableDouble("value"),
                secondaryValue = event.optNullableDouble("secondaryValue"),
                label = event.optString("label", ""),
            )
        }
        return Snapshot(
            modelPath = root.getString("modelPath"),
            modelDisplayName = root.getString("modelDisplayName"),
            placement = ModelPlacement(
                linear = List(9) { index -> linearJson.getDouble(index) },
                centerXmm = placementJson.getDouble("centerXmm"),
                centerYmm = placementJson.getDouble("centerYmm"),
                baseZmm = placementJson.getDouble("baseZmm"),
                source = placementJson.optString("source", "Restored workspace"),
            ),
            plannedEvents = events,
            calibrationDescription = root.optNullableString("calibrationDescription"),
            calibrationType = root.optNullableString("calibrationType")?.let(CalibrationTestType::valueOf),
            calibrationFirstValue = root.optNullableDouble("calibrationFirstValue"),
            configurationFingerprint = root.getString("configurationFingerprint"),
        )
    }

    companion object {
        fun fingerprint(vararg parts: Any?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                digest.update((part?.toString() ?: "<null>").toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        private fun JSONObject.optNullableString(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

        private fun JSONObject.optNullableDouble(key: String): Double? =
            if (isNull(key) || !has(key)) null else getDouble(key)

        private const val VERSION = 1
        private const val MAX_WORKSPACE_BYTES = 512 * 1024
        private const val MAX_DISPLAY_NAME_LENGTH = 512
        private const val MAX_EVENT_LABEL_LENGTH = 512
        private const val MAX_PLANNED_EVENTS = 256
        private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
    }
}
