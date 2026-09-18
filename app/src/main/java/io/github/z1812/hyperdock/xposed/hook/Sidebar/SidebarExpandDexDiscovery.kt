package io.github.z1812.hyperdock.xposed.hook.Sidebar

import org.luckypray.dexkit.DexKitBridge

/** Shared DexKit class cache for the default-expand hook. */
internal object SidebarExpandDexDiscovery {
    @Volatile private var classes: List<Class<*>>? = null

    fun find(loader: ClassLoader): List<Class<*>> {
        classes?.let { return it }
        val result = runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(loader, false).use { bridge ->
                (bridge.findClass { searchPackages("com.miui.dock.sidebar") } +
                    bridge.findClass { searchPackages("xb") }).distinctBy { it.name }
                    .mapNotNull { data -> runCatching { data.getInstance(loader) }.getOrNull() }
            }
        }.getOrElse { emptyList() }
        classes = result
        return result
    }
}
