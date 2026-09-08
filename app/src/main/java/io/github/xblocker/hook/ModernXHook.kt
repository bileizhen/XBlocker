package io.github.xblocker.hook

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * API 101 entry enables framework service delivery to the module app. API 101 still
 * permits the legacy hook calls used by XHook; do not raise targetApiVersion to 102
 * until those calls (including XSharedPreferences) have been migrated.
 * Older frameworks can continue using assets/xposed_init.
 */
class ModernXHook : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log(Log.INFO, "XBlocker", "API 101 entry loaded in $processName")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        XHook().handlePackage(param.packageName, processName, param.classLoader)
    }
}
