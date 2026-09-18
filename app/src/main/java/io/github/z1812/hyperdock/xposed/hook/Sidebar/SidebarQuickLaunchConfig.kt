package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager

/** Reads the user-managed 快速启动 entries without touching shortcut settings. */
internal object SidebarQuickLaunchConfig {
    fun ids(): List<String> = ConfigManager.getStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED, emptySet())
        .asSequence()
        .filter(String::isNotBlank)
        .map { "activity:$it" }
        .sorted()
        .toList()
}
