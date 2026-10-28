package io.github.xblocker.hook

import android.content.Context
import io.github.xblocker.BuildConfig
import java.io.File
import java.util.zip.ZipFile

/** The module embedded in X can differ from the separately installed settings APK. */
internal object EmbeddedModule {
    @Volatile var apkPath: String? = null

    fun paths(context: Context): List<String> = buildList {
        if (BuildConfig.FPA_COMPAT) apkPath?.let(::add)
        runCatching { context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).sourceDir }
            .getOrNull()?.let(::add)
    }.distinct().filter { File(it).isFile && File(it).canRead() }

    fun keywords(context: Context): String = paths(context).firstNotNullOfOrNull { path ->
        runCatching { ZipFile(path).use { zip ->
            zip.getEntry("assets/keywords.txt")?.let { entry ->
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        } }.getOrNull()
    } ?: error("Embedded module keywords unavailable; install and open the matching XBlocker FPA APK")
}
