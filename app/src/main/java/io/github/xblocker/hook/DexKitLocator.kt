package io.github.xblocker.hook

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.github.xblocker.BuildConfig
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

data class DexKitResolution(val method: Method?, val report: DexKitScanReport)

/** One discovery per startup/update, never per response. Caches are private to each process UID. */
object DexKitLocator {
    private const val HOST = "com.twitter.android"
    private const val CACHE = "xblocker_dexkit"
    // Bump whenever matching rules change; the module build additionally invalidates cached answers.
    private const val SCHEMA = 1
    private val dexEntry = Regex("classes(?:[0-9]+)?\\.dex")
    @Volatile private var nativeLoaded = false

    /** App-process API. Performs real APK scanning unless an exact, validated cache is present. */
    @Synchronized fun scan(context: Context, force: Boolean = false): DexKitScanReport = discover(context, force, null).report

    /** Host-process API. Known API stays the fast path and never claims a DexKit scan. */
    @Synchronized fun resolve(context: Context, loader: ClassLoader): DexKitResolution {
        val started = SystemClock.elapsedRealtime()
        val host = runCatching { hostInfo(context) }.getOrNull()
        val fast = runCatching { reflect(DexKitCandidatePolicy.FAST_DESCRIPTOR, loader) }.getOrNull()
        if (fast != null) return DexKitResolution(fast, report(host, "fast", 1,
            "known_entry", DexKitCandidatePolicy.FAST_DESCRIPTOR, started))
        return discover(context, false, loader)
    }

    private fun discover(context: Context, force: Boolean, loader: ClassLoader?): DexKitResolution {
        val started = SystemClock.elapsedRealtime()
        var host: PackageInfo? = null
        var fingerprint = ""
        var previous = ""
        return try {
            host = hostInfo(context)
            val paths = HostApkPaths.resolve(checkNotNull(host.applicationInfo), loader)
            fingerprint = fingerprint(host, paths)
            val prefs = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            val saved = runCatching { JSONObject(prefs.getString("target", "{}") ?: "{}") }.getOrDefault(JSONObject())
            previous = saved.optString("descriptor").takeIf(DexKitCandidatePolicy::validDescriptor).orEmpty()
            if (!force && previous.isNotEmpty() && saved.optString("fingerprint") == fingerprint) {
                val method = if (loader == null) null else runCatching { reflect(previous, loader) }.getOrNull()
                if (loader == null || method != null) return DexKitResolution(method,
                    report(host, "cache", 1, "validated_cache", previous, started, fingerprint, previous, false))
                prefs.edit().remove("target").apply()
            }
            loadNative(context)
            val candidates = mutableListOf<String>()
            for (path in paths) {
                // Language/resource splits contain no DEX. Avoid initializing a native bridge for them.
                val hasDex = ZipFile(path).use { zip -> zip.entries().asSequence().any {
                    dexEntry.matches(it.name)
                } }
                if (!hasDex) continue
                DexKitBridge.create(path).use { bridge ->
                    val methods = bridge.findMethod {
                        searchPackages("okhttp3")
                        matcher {
                            returnType = DexKitCandidatePolicy.RESPONSE
                            paramCount = 0
                            usingStrings(listOf("Canceled"), StringMatchType.Equals)
                        }
                    }
                    for (method in methods) {
                        if (DexKitCandidatePolicy.accepts(method.descriptor, method.modifiers, method.usingStrings,
                                method.invokes.map { it.descriptor })) candidates += method.descriptor
                    }
                }
            }
            // Installing a new host while the scan runs must not publish/cache a mixed snapshot.
            val latestHost = hostInfo(context)
            check(fingerprint == fingerprint(latestHost, HostApkPaths.resolve(checkNotNull(latestHost.applicationInfo), loader))) { "Host changed during scan" }
            val target = DexKitCandidatePolicy.unique(candidates)
            if (target == null) {
                prefs.edit().remove("target").apply()
                DexKitResolution(null, report(host, "failed", 0,
                    if (candidates.isEmpty()) "no_match" else "ambiguous_match:${candidates.size}",
                    "", started, fingerprint, previous))
            } else {
                val method = if (loader == null) null else reflect(target, loader)
                prefs.edit().putString("target", JSONObject().put("fingerprint", fingerprint).put("descriptor", target).toString()).apply()
                DexKitResolution(method, report(host, "dexkit", 1, "unique_match", target, started,
                    fingerprint, previous, previous.takeIf { it.isNotEmpty() }?.let { it != target }))
            }
        } catch (e: Throwable) {
            // A missing ABI/library or parser mismatch must leave the existing Jackson fallback usable.
            if (e is ThreadDeath || e is VirtualMachineError) throw e
            runCatching { context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit().remove("target").apply() }
            DexKitResolution(null, report(host, "failed", 0, "scan_error:${e.javaClass.simpleName}",
                "", started, fingerprint, previous))
        }
    }

    @Suppress("DEPRECATION")
    private fun hostInfo(context: Context) = context.packageManager.getPackageInfo(HOST, 0)

    private fun fingerprint(host: PackageInfo, paths: List<String>): String {
        val material = buildString {
            append("$SCHEMA:${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}:${host.longVersionCode}:${host.lastUpdateTime}")
            paths.forEach { path ->
                val file = File(path)
                check(file.isFile && file.canRead())
                append('\n').append(file.canonicalPath).append(':').append(file.length()).append(':').append(file.lastModified())
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun reflect(descriptor: String, loader: ClassLoader): Method {
        check(DexKitCandidatePolicy.validDescriptor(descriptor))
        val className = descriptor.substring(1, descriptor.indexOf(';')).replace('/', '.')
        val name = descriptor.substringAfter(";->").substringBefore('(')
        val method = loader.loadClass(className).getDeclaredMethod(name)
        check(method.parameterCount == 0 && method.returnType == loader.loadClass(DexKitCandidatePolicy.RESPONSE))
        check(!Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers))
        return method
    }

    @Synchronized internal fun loadNative(context: Context) {
        if (nativeLoaded) return
        if (BuildConfig.FPA_COMPAT && runCatching { System.loadLibrary("dexkit") }.isSuccess) {
            nativeLoaded = true
            return
        }
        if (BuildConfig.FPA_COMPAT) {
            val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            for (path in EmbeddedModule.paths(context)) {
                val loaded = runCatching {
                    val library = ZipFile(path).use { zip -> abis.firstNotNullOfOrNull { abi ->
                        "lib/$abi/libdexkit.so".takeIf { zip.getEntry(it) != null }
                    } } ?: return@runCatching false
                    System.load("$path!/$library")
                    true
                }.getOrDefault(false)
                if (loaded) { nativeLoaded = true; return }
            }
        }
        val module = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0)
        val extracted = File(module.nativeLibraryDir, "libdexkit.so")
        if (extracted.isFile) {
            System.load(extracted.absolutePath)
        } else {
            // AGP's uncompressed native packaging supports direct loading from the installed APK.
            val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
            val library = ZipFile(module.sourceDir).use { zip -> abis.firstNotNullOfOrNull { abi ->
                "lib/$abi/libdexkit.so".takeIf { zip.getEntry(it) != null }
            } } ?: error("DexKit ABI unavailable")
            System.load("${module.sourceDir}!/$library")
        }
        nativeLoaded = true
    }

    private fun report(host: PackageInfo?, mode: String, success: Int, details: String, target: String,
                       started: Long, fingerprint: String = "", previous: String = "", changed: Boolean? = null) =
        DexKitScanReport(host?.versionName.orEmpty(), host?.longVersionCode ?: 0,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, mode, success, details = details,
            targetDescriptor = target, durationMs = SystemClock.elapsedRealtime() - started,
            hostFingerprint = fingerprint, previousTargetDescriptor = previous, cacheChanged = changed)
}
