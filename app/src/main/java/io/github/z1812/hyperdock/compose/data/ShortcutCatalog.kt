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
import io.github.z1812.hyperdock.systemtile.SystemTileCatalogCache
import io.github.z1812.hyperdock.systemtile.SystemTileSpecs
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Carrier
import top.yukonga.miuix.kmp.icon.extended.Location
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
    /** 系统磁贴的真实图标（SystemUI 从磁贴本体取出后缓存成 PNG 的本地路径）。 */
    val systemIconPath: String? = null,
)

/** 快捷方式目录：内置系统开关 + 已安装第三方 QS Tile。 */
object ShortcutCatalog {

    /**
     * 少数条目使用 Miuix 矢量图标；其余为 null，走通用占位图标。
     * 清单本身（id / spec / 文案）统一来自 [SystemTileSpecs]，避免两侧漂移。
     */
    private val SYSTEM_ICONS: Map<String, ImageVector> = mapOf(
        "screenshot" to MiuixIcons.ScreenCapture,
        "mobile_data" to MiuixIcons.Carrier,
        "location" to MiuixIcons.Location,
        "auto_rotate" to MiuixIcons.RotateLeft,
        "dark_mode" to MiuixIcons.Theme,
        "cast" to MiuixIcons.ScreenMirroring,
        "mute" to MiuixIcons.VolumeOff,
    )

    /** 已解析的系统磁贴条目。 */
    private class SystemEntry(
        val id: String,
        val name: String,
        val icon: ImageVector?,
        val iconPath: String?,
    )

    private val HYPER_ISLAND_ENTRIES = listOf(
        HyperIslandEntry("hyperisland_motion_photo", R.string.shortcut_hyperisland_motion_photo),
        HyperIslandEntry("hyperisland_screen_record", R.string.shortcut_hyperisland_screen_record),
    )

    private class HyperIslandEntry(val id: String, @StringRes val labelRes: Int)

    /**
     * 系统磁贴清单。
     *
     * 优先用 SystemUI 探测回来的目录（[SystemTileCatalogCache]）：里面只有**本机确实
     * 支持**的磁贴 —— SystemUI 侧逐个 `createTile` + `isAvailable()` 筛过 —— 且文案取自
     * 磁贴本身（已本地化），图标是从磁贴本体取出的真实图标。
     *
     * 目录未就绪（首次安装、广播尚未到达）时回退内置 [SystemTileSpecs] 全表，
     * 保证页面始终可用。
     */
    private fun systemEntries(context: Context): List<SystemEntry> {
        val catalog = SystemTileCatalogCache.load(context)
        if (catalog.isEmpty()) {
            return SystemTileSpecs.ALL
                .filter { it.inPicker }
                .map { SystemEntry(it.id, context.getString(it.labelRes), SYSTEM_ICONS[it.id], null) }
        }
        return catalog.map { item ->
            SystemEntry(item.id, item.label, SYSTEM_ICONS[item.id], item.iconFile)
        }
    }

    fun all(context: Context): List<ShortcutItem> {
        val systemOwner = context.getString(R.string.shortcut_owner_system)
        val system = systemEntries(context).map { entry ->
            ShortcutItem(
                id = entry.id,
                name = entry.name,
                owner = systemOwner,
                isSystem = true,
                icon = entry.icon,
                systemIconPath = entry.iconPath,
            )
        }
        val hyperIslandOwner = context.getString(R.string.shortcut_owner_hyperisland)
        val hyperIsland = HYPER_ISLAND_ENTRIES.map { entry ->
            ShortcutItem(
                id = entry.id,
                name = context.getString(entry.labelRes),
                owner = hyperIslandOwner,
                isSystem = true,
                drawableRes = R.drawable.ic_focus_ticker_screen_recorder,
            )
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
