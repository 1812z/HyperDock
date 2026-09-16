package io.github.z1812.hyperdock.xposed

import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarDefaultExpandHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 模块主入口，继承 XposedModule。
 * 框架在各目标进程加载时回调 [onPackageLoaded]，由此分发到各子 Hook。
 */
class HyperDockModule : XposedModule() {

    private var configManagerInitialized = false

    override fun onPackageLoaded(param: PackageLoadedParam) {
        initializeConfigManager()
        log("onPackageLoaded: pkg=${param.packageName}")

        when (param.packageName) {
            "com.miui.securitycenter" -> {
                SidebarDefaultExpandHook.init(this, param)
            }
        }
    }

    private fun initializeConfigManager() {
        if (!configManagerInitialized) {
            ConfigManager.init(this)
            configManagerInitialized = true
        }
    }
}
