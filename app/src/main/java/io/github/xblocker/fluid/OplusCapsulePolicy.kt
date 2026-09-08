package io.github.xblocker.fluid

/** Match the native status channel, including the module-owned provider fallback. */
internal object OplusCapsulePolicy {
    fun suppressAutomaticExpansion(packageName: String?, id: Int, channelId: String?): Boolean =
        (packageName == "com.twitter.android" || packageName == "io.github.bileizhen.xblocker") &&
            id == 42 && channelId == "fluid_status_promoted"
}
