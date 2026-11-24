package io.github.xblocker.hook

/**
 * The profile page most recently composed. The media viewer's action sheet does not capture the
 * tweet author, so a download opened from a profile within a short window is attributed to that
 * profile; anything older or unrelated falls back to Download/X.
 */
internal object ProfileFocus {
    @Volatile var id: String? = null
    @Volatile var handle: String? = null
    @Volatile var name: String? = null
    @Volatile var avatar: String? = null

    @Volatile var at: Long = 0

    const val FRESH_MILLIS = 120_000L

    fun remember(id: String, handle: String, name: String?, avatar: String?) {
        this.id = id
        this.handle = handle
        this.name = name
        this.avatar = avatar
        at = System.currentTimeMillis()
    }

    fun fresh(): Boolean = id != null && System.currentTimeMillis() - at in 0..FRESH_MILLIS
}