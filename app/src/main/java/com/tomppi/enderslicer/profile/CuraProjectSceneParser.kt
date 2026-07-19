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
            runCatching {
                parseTransform(transformText).withEmbeddedTargetBounds(objectElement)
            }.onFailure { warnings += "Cura object transform could not be parsed: ${it.message}" }
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

    private fun ModelPlacement.Affine3mf.withEmbeddedTargetBounds(
        objectElement: Element?,
    ): ModelPlacement.Affine3mf {
        val vertices = objectElement?.getElementsByTagNameNS("*", "vertex") ?: return this
        if (vertices.length == 0) return this

        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        repeat(vertices.length) { index ->
            val vertex = vertices.item(index) as? Element ?: return@repeat
            val x = vertex.getAttribute("x").toDoubleOrNull() ?: error("invalid embedded vertex X")
            val y = vertex.getAttribute("y").toDoubleOrNull() ?: error("invalid embedded vertex Y")
            val z = vertex.getAttribute("z").toDoubleOrNull() ?: error("invalid embedded vertex Z")
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "embedded mesh contains a non-finite vertex" }

            val transformedX = linear[0] * x + linear[1] * y + linear[2] * z + translationXmm
            val transformedY = linear[3] * x + linear[4] * y + linear[5] * z + translationYmm
            val transformedZ = linear[6] * x + linear[7] * y + linear[8] * z + translationZmm
            minX = minOf(minX, transformedX)
            maxX = maxOf(maxX, transformedX)
            minY = minOf(minY, transformedY)
            maxY = maxOf(maxY, transformedY)
            minZ = minOf(minZ, transformedZ)
        }

        return copy(
            targetCenterXmm = (minX + maxX) / 2.0,
            targetCenterYmm = (minY + maxY) / 2.0,
            targetBaseZmm = minZ,
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
        runCatching { isXIncludeAware = false }
        runCatching { setExpandEntityReferences(false) }
    }
}
