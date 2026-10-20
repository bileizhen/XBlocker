package io.github.xblocker.hook

import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/** Only fields demonstrably read by the discovered model's toString are eligible at runtime. */
internal data class MediaModelField(val owner: String, val name: String, val type: String) {
    fun toJson(): JSONObject = JSONObject().put("owner", owner).put("name", name).put("type", type)
}

internal data class MediaModelClass(
    val className: String,
    val kind: String,
    val toStringPrefix: String,
    val fields: List<MediaModelField>,
) {
    fun toJson(): JSONObject = JSONObject().put("class", className).put("kind", kind)
        .put("prefix", toStringPrefix).put("fields", JSONArray(fields.map { it.toJson() }))

    companion object {
        fun fromJson(value: JSONObject): MediaModelClass {
            val fields = value.getJSONArray("fields")
            require(fields.length() in 1..80)
            return MediaModelClass(value.getString("class"), value.getString("kind"), value.getString("prefix"),
                List(fields.length()) { i -> fields.getJSONObject(i).let {
                    MediaModelField(it.getString("owner"), it.getString("name"), it.getString("type"))
                } }).also {
                require(it.className.isNotBlank() && it.fields.size in 1..80)
                require(it.fields.distinct().size == it.fields.size)
                require(it.fields.all { f -> f.owner.isNotBlank() && f.name.isNotBlank() && f.type.isNotBlank() })
            }
        }
    }
}

internal data class MediaModelSchema(val models: List<MediaModelClass>, val variants: List<MediaModelClass>) {
    val imageClass: String? get() = models.singleOrNull { it.kind == "image" }?.className
    val videoClasses: Set<String> get() = models.filter { it.kind == "video" || it.kind == "gif" }.map { it.className }.toSet()
    val modelClasses: Set<String> get() = models.map { it.className }.toSet()
    val variantClasses: Set<String> get() = variants.map { it.className }.toSet()

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("models", JSONArray(models.map { it.toJson() }))
        .put("variants", JSONArray(variants.map { it.toJson() }))

    companion object {
        fun fromJson(value: JSONObject): MediaModelSchema {
            require(value.getInt("version") == 1)
            fun read(key: String): List<MediaModelClass> = value.getJSONArray(key).let { array ->
                require(array.length() in 0..8)
                List(array.length()) { MediaModelClass.fromJson(array.getJSONObject(it)) }
            }
            return MediaModelSchema(read("models"), read("variants")).also { schema ->
                require(schema.models.isNotEmpty() && schema.models.size <= 3)
                require(schema.models.map { it.kind }.distinct().size == schema.models.size)
                require(schema.models.all { it.kind in setOf("gif", "image", "video") })
                require(schema.models.map { it.className }.distinct().size == schema.models.size)
                require(schema.variants.all { it.kind == "variant" })
                require(schema.variants.map { it.className }.distinct().size == schema.variants.size)
                require(schema.models.all { it.toStringPrefix == prefixFor(it.kind) })
                require(schema.variants.all { it.toStringPrefix in variantPrefixes })
            }
        }

        internal val variantPrefixes = setOf("MediaVariant(", "VideoVariant(")
        internal fun prefixFor(kind: String): String = when (kind) {
            "image" -> "MediaContentImage("
            "video" -> "MediaContentVideo("
            "gif" -> "MediaContentGif("
            else -> error("Unsupported media model kind")
        }
    }
}

/**
 * Finds stable Kotlin model descriptions across obfuscation generations. DexKit's usingFields is
 * intentionally treated as an unordered set: R8 can load bitRate before url, despite printing url
 * first. HostMediaSelection subsequently proves field semantics against the selected live object.
 */
internal object MediaModelDiscovery {
    fun discover(bridges: List<DexKitBridge>): MediaModelSchema {
        require(bridges.isNotEmpty())
        fun find(prefix: String, kind: String): List<MediaModelClass> = bridges.flatMap { bridge ->
            bridge.findMethod {
                matcher {
                    name = "toString"
                    paramCount = 0
                    returnType = "java.lang.String"
                    usingStrings(listOf(prefix), StringMatchType.StartsWith)
                }
            }.mapNotNull { candidate -> runCatching { inspect(candidate, prefix, kind) }.getOrNull() }
        }.distinctBy { it.className }

        val models = listOf("image", "video", "gif").mapNotNull { kind ->
            find(MediaModelSchema.prefixFor(kind), kind).singleOrNull()
        }
        val variants = MediaModelSchema.variantPrefixes.flatMap { prefix ->
            val found = find(prefix, "variant")
            found.takeIf { it.size <= 1 }.orEmpty()
        }.distinctBy { it.className }
        val supported = models.filter { it.kind == "image" || variants.isNotEmpty() }
        check(supported.isNotEmpty()) { "Media models missing or ambiguous" }
        return MediaModelSchema(supported, variants)
    }

    private fun inspect(method: MethodData, prefix: String, kind: String): MediaModelClass {
        check(!Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers))
        val strings = method.usingStrings
        val required = when (kind) {
            "image" -> listOf("mediaId=", "imageUrl=")
            "video", "gif" -> listOf("mediaId=", "variants=")
            else -> listOf("url=", "bitRate=", "contentType=")
        }
        check(required.all { label -> strings.any { it.contains(label) } })
        check(method.invokes.any { it.returnTypeName == "java.lang.StringBuilder" })

        val owners = buildSet {
            var current = requireNotNull(method.declaredClass)
            repeat(8) {
                add(current.name)
                current = current.superClass ?: return@buildSet
            }
        }
        val readFields = method.usingFields.filter { it.usingType == FieldUsingType.Read }
            .map { it.field }.filter { !Modifier.isStatic(it.modifiers) && it.declaredClassName in owners }
            .distinctBy { it.descriptor }
        check(readFields.size in 2..80)
        check(readFields.count { it.typeName == "java.lang.String" } >= if (kind == "video") 1 else 2)
        if (kind == "variant") check(readFields.any { it.typeName == "java.lang.Integer" || it.typeName == "int" })
        // Constructor writes corroborate that these are model properties, not a utility's globals.
        check(readFields.all { field -> field.writers.any { it.isConstructor && it.declaredClassName in owners } })
        return MediaModelClass(method.declaredClassName, kind, prefix,
            readFields.map { MediaModelField(it.declaredClassName, it.fieldName, it.typeName) })
    }
}
