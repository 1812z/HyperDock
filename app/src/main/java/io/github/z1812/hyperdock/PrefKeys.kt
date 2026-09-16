package io.github.z1812.hyperdock

/**
 * 配置键的统一来源。Compose 端与 Hook 端必须引用同一常量，避免两侧键名漂移。
 *
 * 只有此处的键才会被 [io.github.z1812.hyperdock.HyperDockApp] 同步到 Hook 进程。
 */
object PrefKeys {
    // 通用
    const val DEBUG_LOG = "debug_log"
    const val LOCALE = "locale"
    const val CHECK_UPDATE_ON_LAUNCH = "check_update_on_launch"
    const val HIDE_DESKTOP_ICON = "hide_desktop_icon"

    // 外观
    const val THEME_MODE = "theme_mode"
    const val MONET_COLOR_ENABLED = "monet_color_enabled"
    const val THEME_SEED_COLOR = "theme_seed_color"
    const val FLOATING_NAVIGATION_BAR = "floating_navigation_bar"
    const val LIQUID_GLASS_NAVIGATION_BAR = "liquid_glass_navigation_bar"
    const val BLUR_BARS = "blur_bars"
    const val PREDICTIVE_BACK_MAX_TRANSLATION = "predictive_back_max_translation"

    // 侧边栏
    const val SIDEBAR_EXPAND_ALL_APPS = "sidebar_expand_all_apps"
    const val SIDEBAR_HIDE_ALL_APPS_BUTTON = "sidebar_hide_all_apps_button"

    const val CONFIG_APP_VERSION = "config_app_version"
    const val CONFIG_SCHEMA_VERSION = "config_schema_version"

    /** 需要放入 core 组、供 Hook 进程最先读取的键。 */
    val CORE = setOf(DEBUG_LOG, SIDEBAR_EXPAND_ALL_APPS, SIDEBAR_HIDE_ALL_APPS_BUTTON)

    /** 需要同步到 Hook 进程、并参与导入导出的业务配置键。 */
    val SYNCED = setOf(
        DEBUG_LOG,
        LOCALE,
        CHECK_UPDATE_ON_LAUNCH,
        THEME_MODE,
        MONET_COLOR_ENABLED,
        THEME_SEED_COLOR,
        FLOATING_NAVIGATION_BAR,
        LIQUID_GLASS_NAVIGATION_BAR,
        BLUR_BARS,
        PREDICTIVE_BACK_MAX_TRANSLATION,
        SIDEBAR_EXPAND_ALL_APPS,
        SIDEBAR_HIDE_ALL_APPS_BUTTON,
    )
}
