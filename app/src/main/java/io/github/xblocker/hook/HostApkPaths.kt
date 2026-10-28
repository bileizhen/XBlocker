package io.github.xblocker.hook

import android.content.pm.ApplicationInfo
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexFile
import io.github.xblocker.BuildConfig
import java.io.File
import java.util.zip.ZipFile

/** FPA may load X's original APK from private storage behind a small installed wrapper. */
internal object HostApkPaths {
    fun resolve(info: ApplicationInfo, loader: ClassLoader?): List<String> {
        val installed = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        if (!BuildConfig.FPA_COMPAT || loader == null) return installed.distinct().sorted()
        val loaded = runCatching {
            // Inspect the supplied host loader only. Parents can contain unrelated module DEX.
            val pathList = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply { isAccessible = true }.get(loader)
            val elements = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }.get(pathList) as Array<*>
            elements.mapNotNull { element -> runCatching {
                val value = requireNotNull(element)
                val dex = value.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }.get(value) as? DexFile
                dex?.name
            }.getOrNull() }.filter { path ->
                path != EmbeddedModule.apkPath && File(path).isFile && runCatching {
                    ZipFile(path).use { it.getEntry("AndroidManifest.xml") != null && it.getEntry("classes.dex") != null }
                }.getOrDefault(false)
            }
        }.getOrDefault(emptyList())
        return (installed + loaded).distinct().sorted()
    }
}
