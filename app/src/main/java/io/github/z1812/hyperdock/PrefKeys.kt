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
    const val SIDEBAR_PANEL_CACHE = "sidebar_panel_cache"
    const val SIDEBAR_STAGGERED_EXPAND = "sidebar_staggered_expand"
    /** 侧边栏两列。与 [SIDEBAR_EXPAND_ALL_APPS] 互斥，见 SidebarBehaviorPage。 */
    const val SIDEBAR_TWO_COLUMNS = "sidebar_two_columns"
    /**
     * 两列模式下「速记」旁边那一格放什么。值为条目 id：
     * `app:<包名>` / 快速启动 id（`activity:...`） / 内置快捷方式 id（目录 id 或 `pkg/cls`）。
     * 空串 = 不留这一格（此时速记跨两列居中）。
     */
    const val SIDEBAR_QUICK_SLOT = "sidebar_quick_slot"
    /**
     * 点击模块注入的条目（速记旁那一格 / 全部应用面板里的快捷方式 / 快速启动）
     * 之后是否自动收起侧边栏。取值见 [AUTO_CLOSE_MODES]：
     * `off` / `shortcuts` / `quick_launch` / `all`。默认 `all`（对齐原生：
     * 原生点应用必然收起侧边栏）。开关类磁贴无论取哪个值都保持展开。
     */
    const val SIDEBAR_AUTO_CLOSE_MODE = "sidebar_auto_close_mode"

    /** [SIDEBAR_AUTO_CLOSE_MODE] 的合法取值，顺序与设置页下拉项一致。 */
    val AUTO_CLOSE_MODES = listOf(AUTO_CLOSE_OFF, AUTO_CLOSE_SHORTCUTS, AUTO_CLOSE_QUICK_LAUNCH, AUTO_CLOSE_ALL)
    const val AUTO_CLOSE_OFF = "off"
    const val AUTO_CLOSE_SHORTCUTS = "shortcuts"
    const val AUTO_CLOSE_QUICK_LAUNCH = "quick_launch"
    const val AUTO_CLOSE_ALL = "all"
    const val ALL_APPS_CUSTOM_ENABLED = "all_apps_custom_enabled"
    const val ALL_APPS_CUSTOM_MODE = "all_apps_custom_mode"
    const val ALL_APPS_CUSTOM_PACKAGES = "all_apps_custom_packages"

    // 快捷方式
    const val SHORTCUTS_ENABLED = "shortcuts_enabled"
    const val SHORTCUTS_ADDED = "shortcuts_added"
    const val SHORTCUTS_ORDER = "shortcuts_order"
    const val SHORTCUTS_CUSTOM_LABELS = "shortcuts_custom_labels"
    const val SHORTCUTS_CUSTOM_ICON_URIS = "shortcuts_custom_icon_uris"
    const val SHORTCUTS_COLOR_ICONS = "shortcuts_color_icons"

    // 快捷方式 · 样式自定义。开关打开后，下面四个颜色覆盖磁贴默认配色，
    // 均以 ARGB（无符号 32 位）存为 Long，见 ShortcutTileStyle。
    const val SHORTCUTS_CUSTOM_STYLE = "shortcuts_custom_style"
    const val SHORTCUTS_STYLE_BACKGROUND_ON = "shortcuts_style_background_on"
    const val SHORTCUTS_STYLE_BACKGROUND_OFF = "shortcuts_style_background_off"
    const val SHORTCUTS_STYLE_ICON_ON = "shortcuts_style_icon_on"
    const val SHORTCUTS_STYLE_ICON_OFF = "shortcuts_style_icon_off"

    // 自定义快捷功能（显式 Activity）
    const val QUICK_FUNCTIONS_ENABLED = "quick_functions_enabled"
    const val QUICK_FUNCTIONS_ADDED = "quick_functions_added"
    const val QUICK_FUNCTIONS_ORDER = "quick_functions_order"
    const val QUICK_FUNCTIONS_ICON_URIS = "quick_functions_icon_uris"
    const val QUICK_FUNCTIONS_LABELS = "quick_functions_labels"

    // 侧边栏“全部应用”面板的栏目显示与顺序。顺序以逗号分隔保存，避免
    // SharedPreferences StringSet 丢失用户排序；可见栏目保存为 StringSet。
    const val SIDEBAR_SECTION_VISIBILITY = "sidebar_section_visibility"
    const val SIDEBAR_SECTION_ORDER = "sidebar_section_order"
    const val SIDEBAR_SECTION_CONFIGURED = "sidebar_section_configured"

    const val CONFIG_APP_VERSION = "config_app_version"
    const val CONFIG_SCHEMA_VERSION = "config_schema_version"

    /**
     * StringSet 空集合占位元素。框架 RemotePreferences 对空 Set 的远程写入
     * 会丢失（值不更新/缓存不刷新/通知不派发），因此空集合一律以 {本占位符}
     * 形式落盘与同步，读取端统一过滤。业务值不会出现此字符串。
     */
    const val EMPTY_SET_MARKER = "__hyperdock_empty_set__"

    /** 需要放入 core 组、供 Hook 进程最先读取的键。 */
    val CORE = setOf(
        DEBUG_LOG,
        SIDEBAR_EXPAND_ALL_APPS,
        SIDEBAR_PANEL_CACHE,
        SIDEBAR_STAGGERED_EXPAND,
        SIDEBAR_TWO_COLUMNS,
        SIDEBAR_QUICK_SLOT,
        SIDEBAR_AUTO_CLOSE_MODE,
    )

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
        SIDEBAR_PANEL_CACHE,
        SIDEBAR_STAGGERED_EXPAND,
        SIDEBAR_TWO_COLUMNS,
        SIDEBAR_QUICK_SLOT,
        SIDEBAR_AUTO_CLOSE_MODE,
        ALL_APPS_CUSTOM_ENABLED,
        ALL_APPS_CUSTOM_MODE,
        ALL_APPS_CUSTOM_PACKAGES,
        SHORTCUTS_ENABLED,
        SHORTCUTS_ADDED,
        SHORTCUTS_ORDER,
        SHORTCUTS_CUSTOM_LABELS,
        SHORTCUTS_CUSTOM_ICON_URIS,
        SHORTCUTS_COLOR_ICONS,
        SHORTCUTS_CUSTOM_STYLE,
        SHORTCUTS_STYLE_BACKGROUND_ON,
        SHORTCUTS_STYLE_BACKGROUND_OFF,
        SHORTCUTS_STYLE_ICON_ON,
        SHORTCUTS_STYLE_ICON_OFF,
        QUICK_FUNCTIONS_ENABLED,
        QUICK_FUNCTIONS_ADDED,
        QUICK_FUNCTIONS_ORDER,
        QUICK_FUNCTIONS_ICON_URIS,
        QUICK_FUNCTIONS_LABELS,
        SIDEBAR_SECTION_VISIBILITY,
        SIDEBAR_SECTION_ORDER,
        SIDEBAR_SECTION_CONFIGURED,
    )
}
