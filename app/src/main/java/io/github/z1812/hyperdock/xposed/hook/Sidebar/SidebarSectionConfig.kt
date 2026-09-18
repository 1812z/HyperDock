package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import java.util.Locale

/** Visibility/order and user-facing labels for the four sidebar sections. */
internal object SidebarSectionConfig {
    const val ALL_APPS = "all_apps"
    const val NATIVE_QUICK_FUNCTIONS = "native_quick_functions"
    const val SHORTCUTS = "shortcuts"
    const val QUICK_ACTIONS = "quick_actions"

    fun order(): List<String> {
        val valid = setOf(ALL_APPS, NATIVE_QUICK_FUNCTIONS, SHORTCUTS, QUICK_ACTIONS)
        val configured = ConfigManager.getString(PrefKeys.SIDEBAR_SECTION_ORDER, "")
            .split(',').map(String::trim)
        return (configured + valid).filter { it in valid }.distinct()
    }

    fun enabled(id: String, fallbackKey: String?): Boolean {
        if (!ConfigManager.contains(PrefKeys.SIDEBAR_SECTION_CONFIGURED)) {
            return if (id == SHORTCUTS || id == QUICK_ACTIONS) {
                fallbackKey?.let { ConfigManager.getBoolean(it, false) } == true
            } else true
        }
        return id in ConfigManager.getStringSet(PrefKeys.SIDEBAR_SECTION_VISIBILITY, emptySet())
    }

    fun isChinese(): Boolean = Locale.getDefault().language.startsWith("zh")

    fun shortcutsTitle(): String = if (isChinese()) "快捷方式" else "Shortcuts"

    fun quickActionsTitle(): String = if (isChinese()) "快速启动" else "Quick launch"
}
