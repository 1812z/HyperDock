package io.github.z1812.hyperdock.compose.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.DrawableRes
import io.github.z1812.hyperdock.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.Location
import top.yukonga.miuix.kmp.icon.extended.Recording
import top.yukonga.miuix.kmp.icon.extended.RotateLeft
import top.yukonga.miuix.kmp.icon.extended.ScreenCapture
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.VolumeOff

/** 快捷方式展示条目。 */
data class ShortcutItem(
    /** 稳定标识：系统开关为 tile spec，第三方为 ComponentName 扁平字符串。 */
    val id: String,
    val name: String,
    /** 分组名，系统开关归入“系统应用”，第三方归入其应用名。 */
    val owner: String,
    val isSystem: Boolean,
    /** 系统开关图标，为空时使用通用占位图标。 */
    val icon: ImageVector? = null,
    @DrawableRes val drawableRes: Int? = null,
    /** 第三方开关所属应用包名，用于加载应用图标。 */
    val packageName: String? = null,
    /** 第三方 QS Tile 组件名，用于加载该快捷方式自身图标。 */
    val tileComponent: ComponentName? = null,
)

/** 快捷方式目录：内置系统开关 + 已安装第三方 QS Tile。 */
object ShortcutCatalog {

    private data class SystemEntry(
        val id: String,
        @StringRes val labelRes: Int,
        val icon: ImageVector?,
    )

    private val SYSTEM_ENTRIES = listOf(
        SystemEntry("screen_record", R.string.shortcut_screen_record, MiuixIcons.Recording),
        SystemEntry("screenshot", R.string.shortcut_screenshot, MiuixIcons.ScreenCapture),
        SystemEntry("wifi", R.string.shortcut_wifi, null),
        SystemEntry("bluetooth", R.string.shortcut_bluetooth, null),
        SystemEntry("flashlight", R.string.shortcut_flashlight, null),
        SystemEntry("airplane_mode", R.string.shortcut_airplane_mode, null),
        SystemEntry("mobile_data", R.string.shortcut_mobile_data, MiuixIcons.Carrier),
        SystemEntry("location", R.string.shortcut_location, MiuixIcons.Location),
        SystemEntry("auto_rotate", R.string.shortcut_auto_rotate, MiuixIcons.RotateLeft),
        SystemEntry("dnd", R.string.shortcut_dnd, null),
        SystemEntry("dark_mode", R.string.shortcut_dark_mode, MiuixIcons.Theme),
        SystemEntry("hotspot", R.string.shortcut_hotspot, null),
        SystemEntry("cast", R.string.shortcut_cast, MiuixIcons.ScreenMirroring),
        SystemEntry("mute", R.string.shortcut_mute, MiuixIcons.VolumeOff),
        SystemEntry("alarm", R.string.shortcut_alarm, MiuixIcons.Alarm),
    )

    private val HYPER_ISLAND_ENTRIES = listOf(
        SystemEntry("hyperisland_motion_photo", R.string.shortcut_hyperisland_motion_photo, null),
        SystemEntry("hyperisland_screen_record", R.string.shortcut_hyperisland_screen_record, null),
    )

    fun all(context: Context): List<ShortcutItem> {
        val systemOwner = context.getString(R.string.shortcut_owner_system)
        val system = SYSTEM_ENTRIES.map { entry ->
            ShortcutItem(
                id = entry.id,
                name = context.getString(entry.labelRes),
                owner = systemOwner,
                isSystem = true,
                icon = entry.icon,
            )
        }
        val hyperIslandOwner = context.getString(R.string.shortcut_owner_hyperisland)
        val hyperIsland = HYPER_ISLAND_ENTRIES.map { entry ->
            ShortcutItem(entry.id, context.getString(entry.labelRes), hyperIslandOwner, true, drawableRes = R.drawable.ic_focus_ticker_screen_recorder)
        }
        return system + hyperIsland + thirdPartyTiles(context).sortedBy { it.name.lowercase() }
    }

    private fun thirdPartyTiles(context: Context): List<ShortcutItem> {
        val pm = context.packageManager
        val services = runCatching {
            pm.queryIntentServices(Intent(TileService.ACTION_QS_TILE), PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        return services.mapNotNull { resolve ->
            val info = resolve.serviceInfo ?: return@mapNotNull null
            if (info.packageName == context.packageName) return@mapNotNull null
            val component = ComponentName(info.packageName, info.name)
            val name = runCatching { info.loadLabel(pm).toString() }
                .getOrDefault(info.name.substringAfterLast('.'))
            val owner = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(info.packageName, 0)).toString()
            }.getOrDefault(info.packageName)
            ShortcutItem(
                id = component.flattenToString(),
                name = name,
                owner = owner,
                isSystem = false,
                packageName = info.packageName,
                tileComponent = component,
            )
        }
    }

}
