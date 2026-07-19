package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.ModelPlacement
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

data class CuraProjectScene(
    val modelName: String?,
    val affine: ModelPlacement.Affine3mf?,
    val dropToBuildPlate: Boolean,
    val objectCount: Int,
    val buildItemCount: Int,
    val componentCount: Int,
    val postProcessingScripts: String?,
    val warnings: List<String>,
)

object CuraProjectSceneParser {
    private const val MODEL_PATH = "3D/3dmodel.model"
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun parse(input: InputStream): CuraProjectScene? {
        val entries = CuraArchive.readTextEntries(input, accept = { path ->
            path == MODEL_PATH || path.endsWith(".global.cfg")
        })
        val modelXml = entries[MODEL_PATH] ?: return null
        val document = secureFactory().newDocumentBuilder()
            .parse(modelXml.byteInputStream(Charsets.UTF_8))
        document.documentElement.normalize()

        val objects = document.getElementsByTagNameNS("*", "object")
        val buildItems = document.getElementsByTagNameNS("*", "item")
        val components = document.getElementsByTagNameNS("*", "component")
        val firstItem = buildItems.item(0) as? Element
        val objectId = firstItem?.getAttribute("objectid")?.takeIf(String::isNotBlank)
        val objectElement = (0 until objects.length)
            .mapNotNull { objects.item(it) as? Element }
            .firstOrNull { it.getAttribute("id") == objectId }
            ?: (objects.item(0) as? Element)

        val metadata = linkedMapOf<String, String>()
        objectElement?.getElementsByTagNameNS("*", "metadata")?.let { nodes ->
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as? Element ?: return@repeat
                val name = element.getAttribute("name")
                if (name.isNotBlank()) metadata[name] = element.textContent.trim()
            }
        }

        val warnings = mutableListOf<String>()
        if (objects.length > 1) warnings += "Cura project contains ${objects.length} objects; EnderSlicer currently applies only the first model transform"
        if (buildItems.length > 1) warnings += "Cura project contains ${buildItems.length} build items; multi-object placement is not implemented"
        if (components.length > 0) warnings += "Cura project uses component objects; component transforms are not implemented"

        val transformText = firstItem?.getAttribute("transform")?.trim().orEmpty()
        val affine = if (transformText.isBlank()) {
            null
        } else {
            runCatching { parseTransform(transformText) }
                .onFailure { warnings += "Cura object transform could not be parsed: ${it.message}" }
                .getOrNull()
        }
        val drop = metadata["cura:drop_to_buildplate"]?.equals("true", ignoreCase = true) == true
        val postProcessing = entries.entries
            .firstOrNull { it.key.endsWith(".global.cfg") }
            ?.value
            ?.let(::parsePostProcessingScripts)
        if (!postProcessing.isNullOrBlank()) {
            warnings += "Cura post-processing scripts are configured but are not executed by EnderSlicer"
        }

        return CuraProjectScene(
            modelName = objectElement?.getAttribute("name")?.takeIf(String::isNotBlank),
            affine = affine,
            dropToBuildPlate = drop,
            objectCount = objects.length,
            buildItemCount = buildItems.length,
            componentCount = components.length,
            postProcessingScripts = postProcessing,
            warnings = warnings,
        )
    }

    private fun parseTransform(raw: String): ModelPlacement.Affine3mf {
        val values = raw.split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .map { token -> token.toDoubleOrNull() ?: error("invalid number '$token'") }
        require(values.size == 12) { "expected 12 values but found ${values.size}" }
        require(values.all(Double::isFinite)) { "transform contains a non-finite value" }

        // 3MF stores a row-vector affine matrix:
        // [x y z 1] * [m00 m01 m02 0; m10 m11 m12 0;
        //              m20 m21 m22 0; m30 m31 m32 1].
        // Convert its linear part to the column-vector convention used by the app.
        return ModelPlacement.Affine3mf(
            linear = listOf(
                values[0], values[3], values[6],
                values[1], values[4], values[7],
                values[2], values[5], values[8],
            ),
            translationXmm = values[9],
            translationYmm = values[10],
            translationZmm = values[11],
        )
    }

    private fun parsePostProcessingScripts(globalCfg: String): String? {
        var inMetadata = false
        globalCfg.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                inMetadata = line.equals("[metadata]", ignoreCase = true)
            } else if (inMetadata && line.substringBefore('=', "").trim() == "post_processing_scripts") {
                return line.substringAfter('=', "").trim().takeIf(String::isNotBlank)
            }
        }
        return null
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
    }
}
