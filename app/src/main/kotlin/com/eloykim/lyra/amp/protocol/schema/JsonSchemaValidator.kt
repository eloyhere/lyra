package com.eloykim.lyra.amp.protocol.schema

import com.eloykim.lyra.amp.protocol.json.JsonParseException
import com.eloykim.lyra.amp.protocol.json.JsonParser
import com.eloykim.lyra.amp.protocol.json.JsonValue
import com.eloykim.lyra.amp.protocol.json.jsonObj
import com.eloykim.lyra.amp.protocol.semantic.CanonicalJson
import java.time.Instant

/**
 * Loads the protocol JSON Schemas shipped as classpath resources.
 *
 * The schemas are copied verbatim from the reference repository. Keeping them in
 * resources means the device validates against the same contract as the Node
 * conformance harness, instead of a hand-maintained approximation.
 */
class SchemaStore(private val reader: (String) -> String?) {

    private val cache = HashMap<String, JsonValue.Obj>()

    fun load(fileName: String): JsonValue.Obj {
        val key = if (fileName.endsWith(".json")) fileName else "$fileName.schema.json"
        synchronized(cache) {
            cache[key]?.let { return it }
            val text = reader(key) ?: throw IllegalArgumentException("找不到 schema: $key")
            val parsed = try {
                JsonParser.parse(text)
            } catch (error: JsonParseException) {
                throw IllegalArgumentException("schema $key 不是合法 JSON", error)
            }
            val objectValue = parsed as? JsonValue.Obj
                ?: throw IllegalArgumentException("schema $key 顶层不是对象")
            cache[key] = objectValue
            return objectValue
        }
    }
}

object AmpSchemas {

    private val store: SchemaStore = SchemaStore { fileName ->
        AmpSchemas::class.java.getResourceAsStream("/amp/schemas/$fileName")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
    }

    fun validate(fileName: String, value: JsonValue): List<String> =
        JsonSchemaValidator(store).validateFile(fileName, value)

    /** Validate a known root object kind, e.g. `frame`, `receipt`, `manifest`. */
    fun validateKind(kind: String, value: JsonValue): List<String> =
        validate("$kind.schema.json", value)
}

/**
 * JSON Schema subset validator covering every keyword used by `docs/schemas/`.
 *
 * Unsupported keywords are ignored rather than guessed at; the external Node
 * harness remains the authority for full schema validation.
 */
class JsonSchemaValidator(private val store: SchemaStore) {

    fun validateFile(fileName: String, value: JsonValue): List<String> {
        val schema = store.load(fileName)
        return validateNode(schema, value, "$", fileName)
    }

    private fun validateNode(schema: JsonValue, instance: JsonValue, path: String, baseFile: String): List<String> {
        if (schema !is JsonValue.Obj) {
            return listOf("$path: schema 节点不是对象")
        }
        val problems = ArrayList<String>()

        schema.string("\$ref")?.let { ref ->
            val (file, resolved) = resolve(ref, baseFile)
            problems += validateNode(resolved, instance, path, file)
            return problems
        }

        schema.arr("oneOf")?.let { branches ->
            val branchResults = branches.items.map { branch ->
                validateNode(branch, instance, path, baseFile)
            }
            val matching = branchResults.count { it.isEmpty() }
            if (matching != 1) {
                problems += "$path: oneOf 需要恰好一个分支匹配，实际 $matching"
                if (matching == 0) {
                    branchResults.forEachIndexed { index, branchProblems ->
                        branchProblems.take(3).forEach { problems += "$path: oneOf[$index] $it" }
                    }
                }
            }
        }

        schema.arr("allOf")?.items?.forEach { branch ->
            problems += validateNode(branch, instance, path, baseFile)
        }

        schema.arr("anyOf")?.let { branches ->
            val branchResults = branches.items.map { branch ->
                validateNode(branch, instance, path, baseFile)
            }
            if (branchResults.none { it.isEmpty() }) {
                problems += "$path: anyOf 没有匹配分支"
            }
        }

        schema["not"]?.let { forbidden ->
            if (validateNode(forbidden, instance, path, baseFile).isEmpty()) {
                problems += "$path: 命中 not 分支"
            }
        }

        schema.string("type")?.let { type ->
            if (!matchesType(type, instance)) {
                problems += "$path: 类型应为 $type，实际 ${typeName(instance)}"
                return problems
            }
        }

        schema["const"]?.let { constant ->
            if (CanonicalJson.encode(constant) != CanonicalJson.encode(instance)) {
                problems += "$path: 不等于 const ${CanonicalJson.encode(constant)}"
            }
        }

        schema.arr("enum")?.let { values ->
            val encoded = CanonicalJson.encode(instance)
            if (values.items.none { CanonicalJson.encode(it) == encoded }) {
                problems += "$path: 不在 enum 中"
            }
        }

        if (instance is JsonValue.Obj) {
            validateObject(schema, instance, path, baseFile, problems)
        }
        if (instance is JsonValue.Arr) {
            validateArray(schema, instance, path, baseFile, problems)
        }
        if (instance is JsonValue.Str) {
            validateString(schema, instance, path, problems)
        }
        if (instance is JsonValue.Num) {
            validateNumber(schema, instance, path, problems)
        }

        return problems
    }

    private fun validateObject(
        schema: JsonValue.Obj,
        instance: JsonValue.Obj,
        path: String,
        baseFile: String,
        problems: MutableList<String>,
    ) {
        schema.arr("required")?.items?.forEach { required ->
            val key = (required as? JsonValue.Str)?.value
            if (key != null && !instance.fields.containsKey(key)) {
                problems += "$path: 缺少必需字段 $key"
            }
        }

        val properties = schema.obj("properties") ?: JsonValue.Obj()
        properties.fields.forEach { (key, propertySchema) ->
            val value = instance.fields[key] ?: return@forEach
            problems += validateNode(propertySchema, value, "$path.$key", baseFile)
        }

        when (val additional = schema["additionalProperties"]) {
            is JsonValue.Bool -> {
                if (!additional.value) {
                    instance.fields.keys.filter { !properties.fields.containsKey(it) }.forEach { key ->
                        problems += "$path: 不允许的字段 $key"
                    }
                }
            }

            is JsonValue.Obj -> {
                instance.fields.forEach { (key, value) ->
                    if (!properties.fields.containsKey(key)) {
                        problems += validateNode(additional, value, "$path.$key", baseFile)
                    }
                }
            }

            else -> Unit
        }
    }

    private fun validateArray(
        schema: JsonValue.Obj,
        instance: JsonValue.Arr,
        path: String,
        baseFile: String,
        problems: MutableList<String>,
    ) {
        schema.obj("items")?.let { itemSchema ->
            instance.items.forEachIndexed { index, item ->
                problems += validateNode(itemSchema, item, "$path[$index]", baseFile)
            }
        }
        schema.int("minItems")?.let { minimum ->
            if (instance.items.size < minimum) problems += "$path: 至少需要 $minimum 项"
        }
        schema.int("maxItems")?.let { maximum ->
            if (instance.items.size > maximum) problems += "$path: 最多允许 $maximum 项"
        }
        if (schema.bool("uniqueItems") == true) {
            val encoded = instance.items.map(CanonicalJson::encode)
            if (encoded.size != encoded.toSet().size) problems += "$path: 数组元素必须唯一"
        }
    }

    private fun validateString(
        schema: JsonValue.Obj,
        instance: JsonValue.Str,
        path: String,
        problems: MutableList<String>,
    ) {
        val codePoints = instance.value.codePointCount(0, instance.value.length)
        schema.int("minLength")?.let { minimum ->
            if (codePoints < minimum) problems += "$path: 长度至少 $minimum"
        }
        schema.int("maxLength")?.let { maximum ->
            if (codePoints > maximum) problems += "$path: 长度最多 $maximum"
        }
        schema.string("pattern")?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
            if (regex == null) {
                problems += "$path: schema pattern 无法编译"
            } else if (!regex.containsMatchIn(instance.value)) {
                problems += "$path: 不匹配 pattern $pattern"
            }
        }
        if (schema.string("format") == "date-time") {
            if (runCatching { Instant.parse(instance.value) }.isFailure) {
                problems += "$path: 不是 date-time"
            }
        }
    }

    private fun validateNumber(
        schema: JsonValue.Obj,
        instance: JsonValue.Num,
        path: String,
        problems: MutableList<String>,
    ) {
        if (!instance.value.isFinite()) {
            problems += "$path: 数字必须有限"
            return
        }
        schema.double("minimum")?.let { minimum ->
            if (instance.value < minimum) problems += "$path: 小于 minimum $minimum"
        }
        schema.double("maximum")?.let { maximum ->
            if (instance.value > maximum) problems += "$path: 大于 maximum $maximum"
        }
        schema.double("exclusiveMinimum")?.let { minimum ->
            if (instance.value <= minimum) problems += "$path: 不大于 exclusiveMinimum $minimum"
        }
        schema.double("exclusiveMaximum")?.let { maximum ->
            if (instance.value >= maximum) problems += "$path: 不小于 exclusiveMaximum $maximum"
        }
    }

    private fun resolve(ref: String, baseFile: String): Pair<String, JsonValue.Obj> {
        val hash = ref.indexOf('#')
        val filePart = if (hash >= 0) ref.substring(0, hash) else ref
        val pointer = if (hash >= 0) ref.substring(hash + 1) else ""
        val file = if (filePart.isEmpty()) baseFile else filePart
        var node: JsonValue = store.load(file)
        if (pointer.isNotEmpty() && pointer != "/") {
            pointer.trimStart('/').split('/').forEach { raw ->
                val key = raw.replace("~1", "/").replace("~0", "~")
                node = when (val current = node) {
                    is JsonValue.Obj -> current.fields[key]
                    is JsonValue.Arr -> key.toIntOrNull()?.let { index -> current.items.getOrNull(index) }
                    else -> null
                } ?: throw IllegalArgumentException("$ref 指向不存在的节点 $key")
            }
        }
        val resolved = node as? JsonValue.Obj
            ?: throw IllegalArgumentException("$ref 不是对象")
        return file to resolved
    }

    private fun matchesType(type: String, value: JsonValue): Boolean = when (type) {
        "object" -> value is JsonValue.Obj
        "array" -> value is JsonValue.Arr
        "string" -> value is JsonValue.Str
        "number" -> value is JsonValue.Num
        "integer" -> value is JsonValue.Num && value.value.isFinite() && value.value == Math.floor(value.value)
        "boolean" -> value is JsonValue.Bool
        "null" -> value is JsonValue.Null
        else -> true
    }

    private fun typeName(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> "object"
        is JsonValue.Arr -> "array"
        is JsonValue.Str -> "string"
        is JsonValue.Num -> if (value.value == Math.floor(value.value)) "integer" else "number"
        is JsonValue.Bool -> "boolean"
        is JsonValue.Null -> "null"
    }
}
