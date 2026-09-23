package io.github.z1812.hyperdock.systemtile

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * 模块 App 侧的系统磁贴目录缓存。
 *
 * [SystemTileCatalogProtocol.ACTION_CATALOG] 是 SystemUI 定向广播过来的，由
 * [SystemTileCatalogReceiver] 落库；Compose 的选择器页面再从这里读。
 *
 * 之所以需要这一层：`RemotePreferences` 只能模块 App → Hook 单向写，
 * Hook 侧 `edit()` 返回 null，所以 SystemUI 探测到的结果没法回写配置，
 * 只能靠自己收广播再落地。
 *
 * 图标存成 `filesDir` 下的 PNG 文件 —— 选择器要把它画出来，而跨进程传
 * Drawable 不可能，缓成文件最省事（同时也是进程重启后的兜底）。
 */
object SystemTileCatalogCache {

    private const val ICON_DIR = "system_tile_catalog_icons"

    /**
     * 模块 App 侧的光栅化边长。
     *
     * 比广播里用的 96 大一档：这条路径只在模块进程内跑一次并落盘，
     * 尺寸换清晰度没有任何传输代价（选择器按 28dp 展示，高密度屏上 144 才不糊）。
     */
    private const val ICON_RASTER_SIZE = 144

    /** 选择器用的目录条目。 */
    data class Item(
        val id: String,
        val label: String,
        val iconFile: String?,
    )

    /** 目录是否已就绪。false 时选择器回退内置的 [SystemTileSpecs] 全表。 */
    fun isReady(context: Context): Boolean = readSpecs(context).isNotEmpty()

    /** 读取缓存；label / 图标文件任一缺失都不会导致整体失败。顺序即 SystemUI 给出的原生顺序。 */
    fun load(context: Context): List<Item> {
        val specs = readSpecs(context)
        if (specs.isEmpty()) return emptyList()
        val prefs = prefs(context)
        val labelBySpec = associate(prefs.getStringSet(SystemTileCatalogProtocol.KEY_LABELS, emptySet()).orEmpty())
        return specs.mapNotNull { spec ->
            val id = SystemTileSpecs.specToId[spec] ?: spec
            val label = labelBySpec[spec].orEmpty()
            val iconPath = iconFile(context, id).takeIf { it.isFile }?.absolutePath
            // 文案取不到就用内置表兜底（动态条目则直接跳过，没有名字没法展示）。
            val name = label.ifBlank { SystemTileSpecs.byId[id]?.let { it.zh } ?: "" }
            if (name.isBlank()) return@mapNotNull null
            Item(id = id, label = name, iconFile = iconPath)
        }
    }

    fun save(context: Context, entries: List<SystemTileCatalogProtocol.Entry>) {
        if (entries.isEmpty()) return
        prefs(context).edit()
            .putString(
                SystemTileCatalogProtocol.KEY_SPEC_LIST,
                SystemTileCatalogProtocol.encodeSpecs(entries.map { it.spec }),
            )
            .putStringSet(
                SystemTileCatalogProtocol.KEY_LABELS,
                entries.filter { it.label.isNotBlank() }.map { "${it.spec}=${it.label}" }.toSet(),
            )
            .putStringSet(
                SystemTileCatalogProtocol.KEY_ICON_REFS,
                entries.filter { it.iconRef.isNotBlank() }.map { "${it.spec}=${it.iconRef}" }.toSet(),
            )
            .apply()
        // 图标落成文件：跨进程传 Drawable 不可能，而选择器要把它画出来。
        // 广播里带的 PNG 只在当次有效，资源名则在本进程解析 —— 模块 App 有完整的
        // 资源访问权，`createPackageContext` 一把就能把矢量图标光栅化。
        val dir = File(context.filesDir, ICON_DIR).apply { mkdirs() }
        // 先清空：目录变了（换机/系统更新）后旧文件不再被引用，留着只是垃圾。
        dir.listFiles()?.forEach { it.delete() }
        entries.forEach { entry ->
            val png = entry.png?.takeIf { it.isNotEmpty() }
                ?: SystemTileCatalogProtocol.resolveIconRef(context, entry.iconRef)
                    ?.let { SystemTileCatalogProtocol.rasterizePng(it, ICON_RASTER_SIZE) }
                ?: return@forEach
            runCatching { iconFile(context, entry.id).writeBytes(png) }
        }
    }

    /** 向 SystemUI 请求重发目录（选择器页面打开时调用，兜住"广播时模块 App 还没装好/没起过"）。 */
    fun requestRefresh(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(SystemTileCatalogProtocol.ACTION_REQUEST_CATALOG)
                    .setPackage(SystemTileCatalogProtocol.SYSTEMUI_PKG),
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SystemTileCatalogProtocol.PREFS_NAME, Context.MODE_PRIVATE)

    private fun readSpecs(context: Context): List<String> = runCatching {
        SystemTileCatalogProtocol.decodeSpecs(
            prefs(context).getString(SystemTileCatalogProtocol.KEY_SPEC_LIST, null),
        )
    }.getOrDefault(emptyList())

    private fun associate(pairs: Set<String>): Map<String, String> =
        pairs.associate { it.substringBefore('=', "") to it.substringAfter('=', "") }

    private fun iconFile(context: Context, id: String): File =
        File(File(context.filesDir, ICON_DIR), id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".png")
}
