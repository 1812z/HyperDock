package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.z1812.hyperdock.xposed.hook.BaseHook

/**
 * Stable LSPosed entry point for the sidebar custom sections.
 *
 * The implementation is delegated to [SidebarShortcutController]. Keeping this
 * class as a thin adapter prevents lifecycle wiring from being coupled to the
 * obfuscated SecurityCenter model classes.
 */
object SidebarShortcutHook : BaseHook() {
    override fun getTag(): String = "HyperDock[SidebarShortcut]"

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        SidebarShortcutController.onInit(module, param)
    }
}
