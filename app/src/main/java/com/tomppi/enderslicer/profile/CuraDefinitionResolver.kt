package com.tomppi.enderslicer.profile

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

internal object CuraDefinitionResolver {
    data class Result(
        val globalValues: Map<String, String>,
        val extruderValues: Map<String, String>,
        val modelValues: Map<String, String>,
        val expressionCount: Int,
        val passes: Int,
    )

    private data class SettingDefinition(
        val defaultValue: Any?,
        val expression: String?,
        val settablePerMesh: Boolean,
    )

    private data class DefinitionDocument(
        val parentName: String?,
        val settings: Map<String, SettingDefinition>,
    )

    fun resolve(
        definitionFiles: Map<String, String>,
        machineDefinitionFileName: String,
        extruderDefinitionFileName: String,
        globalOverrides: Map<String, String>,
        extruderOverrides: Map<String, String>,
    ): Result {
        require(definitionFiles.isNotEmpty()) { "No Cura definitions were available for expression resolution" }
        val documents = definitionFiles.mapValues { (name, content) ->
            runCatching { parseDocument(content) }
                .getOrElse { throw IllegalArgumentException("Unable to parse Cura definition $name: ${it.message}", it) }
        }
        val stackCache = mutableMapOf<String, Map<String, SettingDefinition>>()
        val machineDefinitions = resolveDefinitionStack(
            fileName = normalizedDefinitionName(machineDefinitionFileName),
            documents = documents,
            cache = stackCache,
            visiting = linkedSetOf(),
        )
        val extruderOnlyDefinitions = resolveDefinitionStack(
            fileName = normalizedDefinitionName(extruderDefinitionFileName),
            documents = documents,
            cache = stackCache,
            visiting = linkedSetOf(),
        )
        val combinedExtruderDefinitions = linkedMapOf<String, SettingDefinition>().apply {
            putAll(machineDefinitions)
            putAll(extruderOnlyDefinitions)
        }

        val globalValues = defaults(machineDefinitions)
        val extruderValues = defaults(combinedExtruderDefinitions)
        val globalExpressions = expressions(machineDefinitions)
        val extruderExpressions = expressions(combinedExtruderDefinitions)
        val lockedGlobal = mutableSetOf<String>()
        val lockedExtruder = mutableSetOf<String>()

        applyOverrides(globalOverrides, globalValues, globalExpressions, lockedGlobal)

        // Cura's extruder stack inherits the selected global machine/quality
        // stack before applying extruder-specific containers. Re-apply those
        // global values here so per-extruder formulas do not evaluate against
        // unrelated definition defaults. This is especially important for tree
        // support: support_infill_rate depends on the globally selected
        // support_enable/support_structure values.
        applyOverrides(globalOverrides, extruderValues, extruderExpressions, lockedExtruder)
        applyOverrides(extruderOverrides, extruderValues, extruderExpressions, lockedExtruder)

        var passes = 0
        var changed: Boolean
        do {
            changed = false
            passes++
            changed = evaluateScope(
                expressions = globalExpressions,
                locked = lockedGlobal,
                localValues = globalValues,
                globalValues = globalValues,
                extruderValues = extruderValues,
            ) || changed
            changed = evaluateScope(
                expressions = extruderExpressions,
                locked = lockedExtruder,
                localValues = extruderValues,
                globalValues = globalValues,
                extruderValues = extruderValues,
            ) || changed
            check(passes < MAX_PASSES || !changed) {
                "Cura definition expressions did not converge after $MAX_PASSES passes"
            }
        } while (changed)

        val unresolved = linkedMapOf<String, String>()
        collectUnresolved(
            scope = "global",
            expressions = globalExpressions,
            locked = lockedGlobal,
            localValues = globalValues,
            globalValues = globalValues,
            extruderValues = extruderValues,
            output = unresolved,
        )
        collectUnresolved(
            scope = "extruder",
            expressions = extruderExpressions,
            locked = lockedExtruder,
            localValues = extruderValues,
            globalValues = globalValues,
            extruderValues = extruderValues,
            output = unresolved,
        )
        check(unresolved.isEmpty()) {
            unresolved.entries.take(MAX_REPORTED_UNRESOLVED).joinToString(
                prefix = "Unable to resolve Cura definition expressions: ",
                separator = "; ",
            ) { (key, reason) -> "$key ($reason)" }
        }

        val formattedGlobal = globalValues.mapValues { formatValue(it.value) }
        val formattedExtruder = extruderValues.mapValues { formatValue(it.value) }

        // CuraEngine evaluates mesh-sensitive settings from the model's own
        // settings stack. The resolved JSON transport does not infer this scope
        // from the definitions, so explicitly copy every setting marked
        // settable_per_mesh into the model section.
        val formattedModel = linkedMapOf<String, String>().apply {
            combinedExtruderDefinitions.forEach { (key, definition) ->
                if (!definition.settablePerMesh) return@forEach
                val value = extruderValues[key] ?: globalValues[key] ?: return@forEach
                put(key, formatValue(value))
            }
        }

        return Result(
            globalValues = formattedGlobal,
            extruderValues = formattedExtruder,
            modelValues = formattedModel,
            expressionCount = globalExpressions.size + extruderExpressions.size,
            passes = passes,
        )
    }

    private fun resolveDefinitionStack(
        fileName: String,
        documents: Map<String, DefinitionDocument>,
        cache: MutableMap<String, Map<String, SettingDefinition>>,
        visiting: MutableSet<String>,
    ): Map<String, SettingDefinition> {
        cache[fileName]?.let { return it }
        check(visiting.add(fileName)) { "Cyclic Cura definition inheritance involving $fileName" }
        val document = documents[fileName]
            ?: error("Missing Cura definition required by the selected profile: $fileName")
        val result = linkedMapOf<String, SettingDefinition>()
        document.parentName?.let { parent ->
            result.putAll(
                resolveDefinitionStack(
                    fileName = normalizedDefinitionName(parent),
                    documents = documents,
                    cache = cache,
                    visiting = visiting,
                ),
            )
        }
        result.putAll(document.settings)
        visiting.remove(fileName)
        cache[fileName] = result
        return result
    }

    private fun parseDocument(content: String): DefinitionDocument {
        val root = JSONObject(content)
        val settings = linkedMapOf<String, SettingDefinition>()
        root.optJSONObject("settings")?.let { collectSettings(it, settings) }
        return DefinitionDocument(
            parentName = root.optString("inherits").trim().ifEmpty { null },
            settings = settings,
        )
    }

    private fun collectSettings(
        objectValue: JSONObject,
        output: MutableMap<String, SettingDefinition>,
    ) {
        val keys = objectValue.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val setting = objectValue.optJSONObject(key) ?: continue
            val defaultValue = if (setting.has("default_value") && !setting.isNull("default_value")) {
                fromJson(setting.get("default_value"))
            } else {
                null
            }
            val expression = setting.optString("value")
                .trim()
                .takeIf { it.startsWith("=") }
                ?.removePrefix("=")
                ?.trim()
            if (defaultValue != null || expression != null) {
                output[key] = SettingDefinition(
                    defaultValue = defaultValue,
                    expression = expression,
                    settablePerMesh = booleanValue(setting.opt("settable_per_mesh")) ?: false,
                )
            }
            setting.optJSONObject("children")?.let { collectSettings(it, output) }
        }
    }

    private fun booleanValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        is Number -> value.toInt() != 0
        else -> null
    }

    private fun fromJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONArray -> (0 until value.length()).map { index -> fromJson(value.get(index)) }
        is JSONObject -> linkedMapOf<String, Any?>().apply {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, fromJson(value.get(key)))
            }
        }
        is Number, is Boolean -> value
        is String -> normalize(value)
        else -> value.toString()
    }

    private fun defaults(definitions: Map<String, SettingDefinition>): LinkedHashMap<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            definitions.forEach { (key, definition) ->
                if (definition.defaultValue != null) put(key, normalize(definition.defaultValue))
            }
        }
    }

    private fun expressions(definitions: Map<String, SettingDefinition>): LinkedHashMap<String, CuraExpression> {
        return linkedMapOf<String, CuraExpression>().apply {
            definitions.forEach { (key, definition) ->
                definition.expression?.let { expression ->
                    put(key, CuraValueExpressionParser.parse(expression))
                }
            }
        }
    }

    private fun applyOverrides(
        overrides: Map<String, String>,
        values: MutableMap<String, Any?>,
        expressions: MutableMap<String, CuraExpression>,
        locked: MutableSet<String>,
    ) {
        overrides.forEach { (key, rawValue) ->
            val value = rawValue.trim()
            if (value.startsWith("=")) {
                expressions[key] = CuraValueExpressionParser.parse(value.removePrefix("=").trim())
                locked.remove(key)
            } else {
                values[key] = normalize(rawValue)
                expressions.remove(key)
                locked += key
            }
        }
    }

    private fun evaluateScope(
        expressions: Map<String, CuraExpression>,
        locked: Set<String>,
        localValues: MutableMap<String, Any?>,
        globalValues: Map<String, Any?>,
        extruderValues: Map<String, Any?>,
    ): Boolean {
        var changed = false
        val context = CuraEvaluationContext(localValues, globalValues, extruderValues)
        expressions.forEach { (key, expression) ->
            if (key in locked) return@forEach
            val evaluated = runCatching { expression.eval(context) }.getOrNull() ?: return@forEach
            if (!equivalent(localValues[key], evaluated)) {
                localValues[key] = evaluated
                changed = true
            }
        }
        return changed
    }

    private fun collectUnresolved(
        scope: String,
        expressions: Map<String, CuraExpression>,
        locked: Set<String>,
        localValues: Map<String, Any?>,
        globalValues: Map<String, Any?>,
        extruderValues: Map<String, Any?>,
        output: MutableMap<String, String>,
    ) {
        val context = CuraEvaluationContext(localValues, globalValues, extruderValues)
        expressions.forEach { (key, expression) ->
            if (key in locked) return@forEach
            runCatching { expression.eval(context) }
                .onFailure { error -> output["$scope.$key"] = error.message ?: error::class.java.simpleName }
        }
    }

    private fun normalize(value: Any?): Any? {
        if (value !is String) return value
        val trimmed = value.trim()
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false
        trimmed.toDoubleOrNull()?.let { return it }
        return value
    }

    private fun equivalent(left: Any?, right: Any?): Boolean {
        if (left is Number && right is Number) return abs(left.toDouble() - right.toDouble()) < 1e-8
        if (left is Collection<*> && right is Collection<*>) {
            return left.size == right.size && left.zip(right).all { (a, b) -> equivalent(a, b) }
        }
        if (left is Map<*, *> && right is Map<*, *>) {
            return left.keys == right.keys && left.keys.all { key -> equivalent(left[key], right[key]) }
        }
        return left == right
    }

    private fun formatValue(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> value.toString().lowercase()
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float, is Double -> formatNumber((value as Number).toDouble())
        is Number -> value.toString()
        is String -> value
        is Collection<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            when (item) {
                is String -> JSONObject.quote(item)
                else -> formatValue(item)
            }
        }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, item) ->
            "${JSONObject.quote(key.toString())}:${if (item is String) JSONObject.quote(item) else formatValue(item)}"
        }
        else -> value.toString()
    }

    private fun formatNumber(value: Double): String {
        if (value.isFinite() && abs(value - value.toLong()) < 1e-9) return value.toLong().toString()
        return value.toString()
    }

    private fun normalizedDefinitionName(rawName: String): String {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\')
        return if (name.endsWith(".def.json")) name else "$name.def.json"
    }

    private const val MAX_PASSES = 64
    private const val MAX_REPORTED_UNRESOLVED = 12
}
