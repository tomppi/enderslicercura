package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.PrusaSliceSettings
import org.json.JSONObject

/** JSON serialization for [PrusaSliceSettings]; additive and forgiving on read. */
object PrusaSliceSettingsJson {

    fun serialize(settings: PrusaSliceSettings): String =
        JSONObject()
            .put("layerHeightMm", settings.layerHeightMm)
            .put("firstLayerHeightMm", settings.firstLayerHeightMm)
            .put("perimeters", settings.perimeters)
            .put("topSolidLayers", settings.topSolidLayers)
            .put("bottomSolidLayers", settings.bottomSolidLayers)
            .put("thinWalls", settings.thinWalls)
            .put("externalPerimetersFirst", settings.externalPerimetersFirst)
            .put("fillDensityPercent", settings.fillDensityPercent)
            .put("fillPattern", settings.fillPattern)
            .put("skirtLoops", settings.skirtLoops)
            .put("skirtHeightLayers", settings.skirtHeightLayers)
            .put("skirtDistanceMm", settings.skirtDistanceMm)
            .put("brimWidthMm", settings.brimWidthMm)
            .put("overhangs", settings.overhangs)
            .put("firstLayerExtrusionWidthMm", settings.firstLayerExtrusionWidthMm)
            .put("perimeterExtrusionWidthMm", settings.perimeterExtrusionWidthMm)
            .put("externalPerimeterExtrusionWidthMm", settings.externalPerimeterExtrusionWidthMm)
            .put("infillExtrusionWidthMm", settings.infillExtrusionWidthMm)
            .put("solidInfillExtrusionWidthMm", settings.solidInfillExtrusionWidthMm)
            .put("topInfillExtrusionWidthMm", settings.topInfillExtrusionWidthMm)
            .put("extraKeys", JSONObject(settings.extraKeys))
            .put("supportMaterial", settings.supportMaterial)
            .put("supportThresholdAngleDegrees", settings.supportThresholdAngleDegrees)
            .put("supportPattern", settings.supportPattern)
            .put("supportInterface", settings.supportInterface)
            .put("supportInterfaceLayers", settings.supportInterfaceLayers)
            .put("printSpeedMmPerSecond", settings.printSpeedMmPerSecond)
            .put("externalPerimeterSpeedMmPerSecond", settings.externalPerimeterSpeedMmPerSecond)
            .put("infillSpeedMmPerSecond", settings.infillSpeedMmPerSecond)
            .put("firstLayerSpeedMmPerSecond", settings.firstLayerSpeedMmPerSecond)
            .put("travelSpeedMmPerSecond", settings.travelSpeedMmPerSecond)
            .put("nozzleTemperatureC", settings.nozzleTemperatureC)
            .put("firstLayerTemperatureC", settings.firstLayerTemperatureC)
            .put("bedTemperatureC", settings.bedTemperatureC)
            .put("firstLayerBedTemperatureC", settings.firstLayerBedTemperatureC)
            .put("fanSpeedPercent", settings.fanSpeedPercent)
            .put("retractionLengthMm", settings.retractionLengthMm)
            .put("retractionSpeedMmPerSecond", settings.retractionSpeedMmPerSecond)
            .put("retractionMinTravelMm", settings.retractionMinTravelMm)
            .put("retractLiftMm", settings.retractLiftMm)
            .put("useFirmwareRetraction", settings.useFirmwareRetraction)
            .put("extrusionMultiplierPercent", settings.extrusionMultiplierPercent)
            .toString()

    fun deserialize(encoded: String): PrusaSliceSettings? = runCatching {
        val json = JSONObject(encoded)
        val base = PrusaSliceSettings()
        base.copy(
            layerHeightMm = json.optDouble("layerHeightMm", base.layerHeightMm),
            firstLayerHeightMm = json.optDouble("firstLayerHeightMm", base.firstLayerHeightMm),
            perimeters = json.optInt("perimeters", base.perimeters),
            topSolidLayers = json.optInt("topSolidLayers", base.topSolidLayers),
            bottomSolidLayers = json.optInt("bottomSolidLayers", base.bottomSolidLayers),
            thinWalls = json.optBoolean("thinWalls", base.thinWalls),
            externalPerimetersFirst = json.optBoolean("externalPerimetersFirst", base.externalPerimetersFirst),
            fillDensityPercent = json.optDouble("fillDensityPercent", base.fillDensityPercent),
            fillPattern = json.optString("fillPattern", base.fillPattern),
            skirtLoops = json.optInt("skirtLoops", base.skirtLoops),
            skirtHeightLayers = json.optInt("skirtHeightLayers", base.skirtHeightLayers),
            skirtDistanceMm = json.optDouble("skirtDistanceMm", base.skirtDistanceMm),
            brimWidthMm = json.optDouble("brimWidthMm", base.brimWidthMm),
            overhangs = json.optBoolean("overhangs", base.overhangs),
            firstLayerExtrusionWidthMm = optNullable(json, "firstLayerExtrusionWidthMm"),
            perimeterExtrusionWidthMm = optNullable(json, "perimeterExtrusionWidthMm"),
            externalPerimeterExtrusionWidthMm = optNullable(json, "externalPerimeterExtrusionWidthMm"),
            infillExtrusionWidthMm = optNullable(json, "infillExtrusionWidthMm"),
            solidInfillExtrusionWidthMm = optNullable(json, "solidInfillExtrusionWidthMm"),
            topInfillExtrusionWidthMm = optNullable(json, "topInfillExtrusionWidthMm"),
            extraKeys = parseExtraKeys(json.optJSONObject("extraKeys")),
            supportMaterial = json.optBoolean("supportMaterial", base.supportMaterial),
            supportThresholdAngleDegrees = json.optDouble("supportThresholdAngleDegrees", base.supportThresholdAngleDegrees),
            supportPattern = json.optString("supportPattern", base.supportPattern),
            supportInterface = json.optBoolean("supportInterface", base.supportInterface),
            supportInterfaceLayers = json.optInt("supportInterfaceLayers", base.supportInterfaceLayers),
            printSpeedMmPerSecond = json.optDouble("printSpeedMmPerSecond", base.printSpeedMmPerSecond),
            externalPerimeterSpeedMmPerSecond = json.optDouble("externalPerimeterSpeedMmPerSecond", base.externalPerimeterSpeedMmPerSecond),
            infillSpeedMmPerSecond = json.optDouble("infillSpeedMmPerSecond", base.infillSpeedMmPerSecond),
            firstLayerSpeedMmPerSecond = json.optDouble("firstLayerSpeedMmPerSecond", base.firstLayerSpeedMmPerSecond),
            travelSpeedMmPerSecond = json.optDouble("travelSpeedMmPerSecond", base.travelSpeedMmPerSecond),
            nozzleTemperatureC = json.optInt("nozzleTemperatureC", base.nozzleTemperatureC),
            firstLayerTemperatureC = json.optInt("firstLayerTemperatureC", base.firstLayerTemperatureC),
            bedTemperatureC = json.optInt("bedTemperatureC", base.bedTemperatureC),
            firstLayerBedTemperatureC = json.optInt("firstLayerBedTemperatureC", base.firstLayerBedTemperatureC),
            fanSpeedPercent = json.optInt("fanSpeedPercent", base.fanSpeedPercent),
            retractionLengthMm = json.optDouble("retractionLengthMm", base.retractionLengthMm),
            retractionSpeedMmPerSecond = json.optDouble("retractionSpeedMmPerSecond", base.retractionSpeedMmPerSecond),
            retractionMinTravelMm = json.optDouble("retractionMinTravelMm", base.retractionMinTravelMm),
            retractLiftMm = json.optDouble("retractLiftMm", base.retractLiftMm),
            useFirmwareRetraction = json.optBoolean("useFirmwareRetraction", base.useFirmwareRetraction),
            extrusionMultiplierPercent = json.optDouble("extrusionMultiplierPercent", base.extrusionMultiplierPercent),
        )
    }.getOrNull()

    private fun optNullable(json: JSONObject, key: String): Double? =
        if (json.has(key) && !json.isNull(key)) json.optDouble(key, Double.NaN).takeIf { it.isFinite() } else null

    private fun parseExtraKeys(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.optString(key, "")
        }
        return result
    }
}
