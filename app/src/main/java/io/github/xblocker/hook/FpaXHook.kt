package io.github.xblocker.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Embedded loaders use the legacy API, with no framework service or shared-prefs bridge. */
class FpaXHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    override fun initZygote(param: IXposedHookZygoteInit.StartupParam) {
        EmbeddedModule.apkPath = param.modulePath
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.twitter.android") return
        XHook().handlePackage(param.packageName, param.processName, param.classLoader)
    }
}
