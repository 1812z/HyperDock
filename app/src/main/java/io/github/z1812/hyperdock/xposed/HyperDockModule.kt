package io.github.z1812.hyperdock.xposed

import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarCloseHook
import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarColumnsHook
import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarDockSlotHook
import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarDefaultExpandHook
import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarShortcutHook
import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarShorthandHook
import io.github.z1812.hyperdock.xposed.hook.Sidebar.SidebarQsBridgeHook
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

        // 嵌套的 createPackageContext 会再触发一次 onPackageLoaded，此时
        // LoadedApk 刚构造、ClassLoader 还没建好，getDefaultClassLoader() 为 null。
        // 真正那次加载已经装好 Hook，这里直接跳过，否则每个 Hook 都会抛 NPE。
        if (runCatching { param.defaultClassLoader }.getOrNull() == null) {
            log("skip package load without class loader: ${param.packageName}")
            return
        }

        when (param.packageName) {
            "com.miui.securitycenter" -> {
                SidebarDefaultExpandHook.init(this, param)
                SidebarShortcutHook.init(this, param)
                SidebarCloseHook.init(this, param)
                SidebarColumnsHook.init(this, param)
                SidebarDockSlotHook.init(this, param)
                SidebarShorthandHook.init(this, param)
            }
            "com.android.systemui" -> {
                SidebarQsBridgeHook.init(this, param)
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
