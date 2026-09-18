package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager

/** Reads the user-managed 快速启动 entries without touching shortcut settings. */
internal object SidebarQuickLaunchConfig {
    fun ids(): List<String> = ConfigManager.getStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED, emptySet())
        .asSequence()
        .filter(String::isNotBlank)
        .filter { '|' in it }
        .map { entry ->
            val parts = entry.split('|', limit = 2)
            val id = parts.first()
            val component = parts[1]
            "activity:$component|$id"
        }
        .sorted()
        .toList()
}
