package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.shortcutstyle.ShortcutTileStyle
import io.github.z1812.hyperdock.xposed.ConfigManager

/**
 * 快捷方式磁贴的样式自定义读取（Hook 侧）。
 *
 * 每次调用都直接问 [ConfigManager]，不做本地缓存：它背后是 RemotePreferences 的内存
 * 映射，一次读就是一次 map 查找，远比 [SidebarShortcutController.styleTileIcon] 里的
 * 位图归一化便宜；反过来加缓存才危险 —— 用户在设置页改完颜色，侧边栏要立刻变，
 * 缓存失效时机一旦没接对就会"改了没反应"。
 */
internal object ShortcutStyleConfig {

    /** 自定义样式是否启用。关掉时走原本的硬编码配色，行为与改造前一致。 */
    val enabled: Boolean
        get() = ConfigManager.getBoolean(PrefKeys.SHORTCUTS_CUSTOM_STYLE, false)

    /** 磁贴底色。[active] 为真取"开启状态背景"，否则取"关闭状态背景"。 */
    fun background(active: Boolean): Int = if (active) {
        color(PrefKeys.SHORTCUTS_STYLE_BACKGROUND_ON, ShortcutTileStyle.DEFAULT_BACKGROUND_ON)
    } else {
        color(PrefKeys.SHORTCUTS_STYLE_BACKGROUND_OFF, ShortcutTileStyle.DEFAULT_BACKGROUND_OFF)
    }

    /** 磁贴图标的着色。[active] 为真取"开启状态颜色"，否则取"关闭状态颜色"。 */
    fun iconColor(active: Boolean): Int = if (active) {
        color(PrefKeys.SHORTCUTS_STYLE_ICON_ON, ShortcutTileStyle.DEFAULT_ICON_ON)
    } else {
        color(PrefKeys.SHORTCUTS_STYLE_ICON_OFF, ShortcutTileStyle.DEFAULT_ICON_OFF)
    }

    private fun color(key: String, fallback: Int): Int =
        ShortcutTileStyle.unpack(ConfigManager.getLong(key, ShortcutTileStyle.pack(fallback)))
}
