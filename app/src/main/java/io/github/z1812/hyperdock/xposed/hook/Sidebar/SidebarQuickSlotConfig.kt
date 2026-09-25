package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.quicklaunch.QuickLaunchFormat
import io.github.z1812.hyperdock.xposed.ConfigManager

/**
 * 「速记旁边的图标」的取值格式。
 *
 * 三种来源共用同一个字符串键 [PrefKeys.SIDEBAR_QUICK_SLOT]：
 *
 * | 来源 | 值 | 例 |
 * |---|---|---|
 * | 应用 | `app:<包名>` | `app:com.tencent.mm` |
 * | 内置快捷方式 | 目录 id 或 `pkg/cls` | `hyperisland_screen_record`、`com.foo/.Bar` |
 * | 快速启动 | `activity:<payload>|<entryId>` | 同 [QuickLaunchFormat] |
 */
internal object SidebarQuickSlotConfig {

    /** 应用条目前缀。 */
    const val APP_PREFIX = "app:"

    fun appId(packageName: String): String = APP_PREFIX + packageName

    fun isApp(id: String): Boolean = id.startsWith(APP_PREFIX)

    fun packageOf(id: String): String = id.removePrefix(APP_PREFIX)

    /** 当前配置值；空串表示不留这一格。 */
    fun current(): String = ConfigManager.getString(PrefKeys.SIDEBAR_QUICK_SLOT, "")
}
