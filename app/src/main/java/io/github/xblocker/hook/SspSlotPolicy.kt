package io.github.xblocker.hook

/** No host mutation; preserve object identity when disabled or when no confirmed ad exists. */
internal object SspSlotPolicy {
    fun enabled(moduleEnabled: Boolean, blockPromoted: Boolean) = moduleEnabled && blockPromoted
    fun <T> remove(items: List<T>, block: Boolean, isAd: (T) -> Boolean): List<T> {
        if (!block) return items
        var copy: ArrayList<T>? = null
        items.forEachIndexed { index, item ->
            if (isAd(item)) {
                if (copy == null) copy = ArrayList<T>(items.size).apply { addAll(items.subList(0, index)) }
            } else copy?.add(item)
        }
        return copy ?: items
    }
}

/** Bounded per-process slot deduplication; policy evaluations never become blocked-ad events. */
internal class SspSlotLedger(private val limit: Int = 1000) {
    private val seen = LinkedHashSet<String>()
    @Synchronized fun record(id: String): Boolean {
        if (id.isBlank() || !seen.add(id)) return false
        if (seen.size > limit) seen.remove(seen.first())
        return true
    }
}
