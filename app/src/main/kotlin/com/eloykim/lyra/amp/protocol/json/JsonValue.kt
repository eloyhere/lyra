package com.eloykim.lyra.amp.protocol.json

/**
 * Minimal JSON tree used by the AMP port.
 *
 * The tree deliberately keeps object insertion order for construction, while
 * [CanonicalJson] sorts keys before hashing or signing. A separate parser keeps
 * the protocol module free of Android's `org.json` and of serialization plugins.
 */
sealed interface JsonValue {

    data class Obj(val fields: LinkedHashMap<String, JsonValue> = LinkedHashMap()) : JsonValue {

        operator fun get(key: String): JsonValue? = fields[key]

        fun has(key: String): Boolean = fields.containsKey(key)

        fun string(key: String): String? = (fields[key] as? Str)?.value

        fun int(key: String): Int? = (fields[key] as? Num)?.value?.toInt()

        fun long(key: String): Long? = (fields[key] as? Num)?.value?.toLong()

        fun double(key: String): Double? = (fields[key] as? Num)?.value

        fun bool(key: String): Boolean? = (fields[key] as? Bool)?.value

        fun obj(key: String): Obj? = fields[key] as? Obj

        fun arr(key: String): Arr? = fields[key] as? Arr

        fun requireString(key: String): String =
            string(key) ?: throw IllegalArgumentException("缺少字符串字段 $key")

        fun requireInt(key: String): Int =
            int(key) ?: throw IllegalArgumentException("缺少整数字段 $key")

        fun requireObj(key: String): Obj =
            obj(key) ?: throw IllegalArgumentException("缺少对象字段 $key")

        fun requireArr(key: String): Arr =
            arr(key) ?: throw IllegalArgumentException("缺少数组字段 $key")

        fun copyWith(key: String, value: JsonValue): Obj {
            val next = LinkedHashMap(fields)
            next[key] = value
            return Obj(next)
        }

        fun without(key: String): Obj {
            val next = LinkedHashMap(fields)
            next.remove(key)
            return Obj(next)
        }

        companion object {
            fun of(vararg fields: Pair<String, JsonValue?>): Obj =
                Obj(LinkedHashMap<String, JsonValue>().apply {
                    fields.forEach { (key, value) -> if (value != null) put(key, value) }
                })

            fun fromMap(fields: Map<String, JsonValue>): Obj = Obj(LinkedHashMap(fields))
        }
    }

    data class Arr(val items: List<JsonValue> = emptyList()) : JsonValue {

        val size: Int get() = items.size

        fun obj(index: Int): Obj? = items.getOrNull(index) as? Obj

        fun string(index: Int): String? = (items.getOrNull(index) as? Str)?.value

        companion object {
            fun of(vararg items: JsonValue?): Arr = Arr(items.filterNotNull())
            fun ofList(items: List<JsonValue>): Arr = Arr(items)
        }
    }

    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

fun jsonObj(vararg fields: Pair<String, JsonValue?>): JsonValue.Obj = JsonValue.Obj.of(*fields)

fun jsonArr(vararg items: JsonValue?): JsonValue.Arr = JsonValue.Arr.of(*items)

fun jsonStrings(values: List<String>): JsonValue.Arr =
    JsonValue.Arr(values.map { JsonValue.Str(it) })

fun jsonNumber(value: Int): JsonValue.Num = JsonValue.Num(value.toDouble())

fun jsonNumber(value: Long): JsonValue.Num = JsonValue.Num(value.toDouble())

fun jsonNumber(value: Double): JsonValue.Num = JsonValue.Num(value)

fun jsonBool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

fun jsonText(value: String): JsonValue.Str = JsonValue.Str(value)

fun JsonValue.asObjOrNull(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue.asArrOrNull(): JsonValue.Arr? = this as? JsonValue.Arr
