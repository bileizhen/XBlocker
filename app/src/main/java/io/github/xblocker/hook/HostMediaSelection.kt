package io.github.xblocker.hook

import java.net.URI
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** Selection is scoped to the viewer's current model, never the last observed network URL. */
internal object HostMediaSelection {
    data class Media(val url: String, val mediaId: String, val type: String, val tweetId: String)
    data class Variant(val url: String, val bitrate: Int?, val mime: String)

    fun highestMp4(variants: List<Variant>): Variant? = variants.filter {
        it.mime.equals("video/mp4", true) && runCatching {
            val u = URI(it.url)
            u.scheme == "https" && u.host == "video.twimg.com" && u.path.endsWith(".mp4")
        }.getOrDefault(false)
    }.maxByOrNull { it.bitrate ?: 0 }

    fun originalPhoto(raw: String): String {
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host == "pbs.twimg.com" && uri.path.startsWith("/media/"))
        val extension = uri.path.substringAfterLast('.', "").substringBefore(':')
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        val format = query.firstOrNull { it.startsWith("format=") }?.substringAfter('=')
            ?: extension.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif") } ?: "jpg"
        require(format in setOf("jpg", "jpeg", "png", "webp", "gif"))
        val path = if (extension in setOf("jpg", "jpeg", "png", "webp", "gif")) uri.path.substringBeforeLast('.') else uri.path
        return "https://pbs.twimg.com$path?" + (query.filterNot {
            it.substringBefore('=') in setOf("format", "name")
        } + listOf("format=$format", "name=orig")).joinToString("&")
    }

    fun fromModel(model: Any?, schema: MediaModelSchema): Media? = runCatching {
        model ?: return null
        val spec = specFor(model, schema.models) ?: return null
        val values = readValues(model, spec)
        val record = description(model, spec, values) ?: return null
        val id = stringProperty(record, values, "mediaId")?.takeIf { mediaId.matches(it) } ?: return null
        if (spec.kind == "image") {
            val url = stringProperty(record, values, "imageUrl") ?: return null
            return Media(originalPhoto(url), id, "image", id)
        }
        if (spec.kind !in setOf("video", "gif")) return null
        val printedVariants = record["variants"] ?: return null
        val lists = values.filterIsInstance<Iterable<*>>().filter { it.toString() == printedVariants }
        val variants = lists.singleOrNull()?.toList()?.takeIf { it.size in 1..64 } ?: return null
        // A video without MP4 must not fall through to its preview image.
        val best = highestMp4(variants.mapNotNull { readVariant(it, schema) }) ?: return null
        val post = record["sourcePostIdentifier"]?.takeIf { raw ->
            raw.toLongOrNull()?.let { it > 0 } == true && values.count { it?.toString() == raw } == 1
        }
        Media(best.url, id, "video", post ?: id)
    }.getOrNull()

    /**
     * Follow captures/controller fields only, never media collections or a model's preview. Multiple
     * selected candidates are ambiguous and deliberately disable the action instead of saving the
     * first/last media observed. All objects are visited by identity and the graph budget is small.
     */
    fun findInCallback(callback: Any, schema: MediaModelSchema): Any? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val selected = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var exhausted = false
        fun visit(value: Any, depth: Int) {
            if (!seen.add(value)) return
            if (seen.size > 64) { exhausted = true; return }
            if (specFor(value, schema.models) != null) { selected += value; return }
            if (depth >= 4 || value is Iterable<*> || value is Map<*, *> || value.javaClass.isArray) return
            val name = value.javaClass.name
            if (!hostObject(name) && !(value === callback && value is kotlin.Function<*>)) return
            // Unsupported media/model data is a leaf too: do not descend into its preview image.
            if (name.startsWith("com.x.models.") || name.startsWith("com.twitter.model.")) return
            var type: Class<*>? = value.javaClass
            var visitedFields = 0
            val children = mutableListOf<Any>()
            repeat(6) {
                val owner = type ?: return@repeat
                if (owner != value.javaClass && !hostObject(owner.name)) return@repeat
                for (f in owner.declaredFields) {
                    if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                    if (++visitedFields > 128) { exhausted = true; return }
                    val child = runCatching { f.isAccessible = true; f.get(value) }.getOrNull() ?: continue
                    if (child is Iterable<*> || child is Map<*, *> || child.javaClass.isArray) continue
                    children += child
                }
                type = owner.superclass
            }
            val direct = children.filter { specFor(it, schema.models) != null }
            if (direct.isNotEmpty()) {
                direct.forEach { selected += it }
                return
            }
            children.filter { hostObject(it.javaClass.name) }.forEach { visit(it, depth + 1) }
        }
        visit(callback, 0)
        return selected.singleOrNull()?.takeUnless { exhausted }
    }

    private fun hostObject(name: String) = name.startsWith("com.x.") || name.startsWith("com.twitter.")

    private fun readVariant(value: Any?, schema: MediaModelSchema): Variant? = runCatching {
        value ?: return null
        val spec = specFor(value, schema.variants) ?: return null
        val fields = readValues(value, spec)
        val record = description(value, spec, fields) ?: return null
        val url = stringProperty(record, fields, "url") ?: return null
        val mime = stringProperty(record, fields, "contentType") ?: return null
        val rawRate = record["bitRate"] ?: return null
        val rates = fields.filter { it == null || it is Int }.filter { (it?.toString() ?: "null") == rawRate }
        if (rates.size != 1) return null
        val rate: Int? = if (rawRate == "null") null else {
            rawRate.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        }
        Variant(url, rate, mime)
    }.getOrNull()

    private fun specFor(value: Any, models: List<MediaModelClass>): MediaModelClass? {
        // Named legacy subclasses may inherit the proven model's toString/fields.
        val hierarchy = generateSequence(value.javaClass as Class<*>?) { it.superclass }.take(8).map { it.name }.toSet()
        return models.filter { it.className in hierarchy }.singleOrNull()
    }

    private fun readValues(value: Any, spec: MediaModelClass): List<Any?> = spec.fields.map { candidate ->
        val owner = generateSequence(value.javaClass as Class<*>?) { it.superclass }.take(8)
            .single { it.name == candidate.owner }
        owner.getDeclaredField(candidate.name).apply {
            check(!Modifier.isStatic(modifiers) && type.name == candidate.type)
            isAccessible = true
        }.get(value)
    }

    private fun stringProperty(record: Map<String, String>, values: List<Any?>, key: String): String? {
        val expected = record[key] ?: return null
        return values.filterIsInstance<String>().filter { it == expected }.singleOrNull()
    }

    private fun description(value: Any, spec: MediaModelClass, fields: List<Any?>): Map<String, String>? {
        val text = value.toString()
        if (text.length !in (spec.toStringPrefix.length + 2)..65536 || !text.startsWith(spec.toStringPrefix) || !text.endsWith(')')) return null
        val printed = fields.map { it?.toString() ?: "null" }.distinct()
        if (printed.any { it.length > 65536 }) return null
        val result = linkedMapOf<String, String>()
        var start = spec.toStringPrefix.length
        while (start < text.lastIndex && result.size < 80) {
            val equal = text.indexOf('=', start)
            if (equal < start) return null
            val key = text.substring(start, equal)
            if (!propertyName.matches(key) || result.containsKey(key)) return null
            val valueStart = equal + 1
            // Match an entire proven field value before reading the next separator. User-controlled
            // alt text may legitimately contain commas, nested model-like text or unmatched brackets.
            val matches = printed.filter { raw ->
                text.regionMatches(valueStart, raw, 0, raw.length) && (valueStart + raw.length == text.lastIndex ||
                    text.startsWith(", ", valueStart + raw.length))
            }
            val raw = matches.singleOrNull() ?: return null
            result[key] = raw
            val end = valueStart + raw.length
            if (end == text.lastIndex) return result
            start = end + 2
        }
        return null
    }

    private val mediaId = Regex("[A-Za-z0-9_-]{1,128}")
    private val propertyName = Regex("[A-Za-z][A-Za-z0-9_]*")
}
