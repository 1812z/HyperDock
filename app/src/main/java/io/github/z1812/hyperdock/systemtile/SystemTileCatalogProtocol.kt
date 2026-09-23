package io.github.z1812.hyperdock.systemtile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import io.github.z1812.hyperdock.utils.IconNormalizer
import java.io.ByteArrayOutputStream

/**
 * 系统磁贴目录（catalog）的跨进程协议。
 *
 * ## 为什么需要它
 *
 * 系统磁贴清单过去是写死的（[SystemTileSpecs]）。问题是：
 *   1. 图标靠"猜资源名 + getIdentifier"永远猜不中 —— QS 磁贴图标在 SystemUI 里，
 *      而安全中心的 AssetManager 并没有加载 SystemUI 的 APK，查不到就是 0。
 *   2. 机型差异 —— 写死的条目里有些本机根本不支持，点了没反应。
 *
 * 正解只能是从磁贴本体取：`QSTile.State` 是公有字段结构，带 `label` / `icon` /
 * `state`；`MiuiQSTile.isAvailable()` 判定本机可用性。但只有 SystemUI 进程能创建
 * 磁贴，而安全中心（渲染侧边栏）与模块 App（渲染选择器）都需要这份数据，
 * 于是用定向广播把整份目录推过去。
 *
 * ## 方向
 *
 * ```
 *   SystemUI ──ACTION_CATALOG──> com.miui.securitycenter   （侧边栏，已有同类通道）
 *            └─ACTION_CATALOG──> io.github.z1812.hyperdock  （模块 App 选择器，新增）
 *   两者 ────ACTION_REQUEST_CATALOG──> com.android.systemui （补拉，兜住进程启动顺序）
 * ```
 *
 * 注意 `RemotePreferences` 是**单向**的（模块 App → Hook，hook 侧 `edit()` 返回 null），
 * 所以 hook 探测到的结果无法回写配置，只能走广播。
 */
object SystemTileCatalogProtocol {

    const val MODULE_PKG = "io.github.z1812.hyperdock"
    const val SYSTEMUI_PKG = "com.android.systemui"
    const val SIDEBAR_PKG = "com.miui.securitycenter"

    /** SystemUI → 安全中心 / 模块 App：整份可用磁贴目录。 */
    const val ACTION_CATALOG = "io.github.z1812.hyperdock.action.SYSTEM_TILE_CATALOG"

    /** 安全中心 / 模块 App → SystemUI：请求重发目录。 */
    const val ACTION_REQUEST_CATALOG = "io.github.z1812.hyperdock.action.REQUEST_SYSTEM_TILE_CATALOG"

    /** ArrayList&lt;String&gt;，与 [EXTRA_LABELS]、[EXTRA_ICON_REFS] 同序。 */
    const val EXTRA_SPECS = "specs"

    /** ArrayList&lt;String&gt;，磁贴自带的已本地化文案；缺省为空串。 */
    const val EXTRA_LABELS = "labels"

    /** ArrayList&lt;String&gt;，形如 `com.android.systemui:drawable/ic_qs_nfc_on`；缺省为空串。 */
    const val EXTRA_ICON_REFS = "icon_refs"

    /** Bundle，键为 spec，值为 PNG 字节数组；仅当 [EXTRA_ICON_REFS] 取不到资源名时才有值。 */
    const val EXTRA_ICONS = "icons"

    /** Long，目录构建时刻（`SystemClock.elapsedRealtime()`），用于判断是否需要重拉。 */
    const val EXTRA_BUILT_AT = "built_at"

    /** 安全中心 / 模块 App 各自的本地缓存名（存在各自进程的 data 目录下）。 */
    const val PREFS_NAME = "hyperdock_system_tile_catalog"

    /**
     * 持久化的 spec 列表，逗号连接。
     *
     * 刻意不用 `putStringSet`：`getStringSet` 返回无序集合，会让选择器的条目顺序每次都变。
     * spec 本身不含逗号（`custom(...)` 已在构建期排除），连接/拆分是安全的。
     */
    const val KEY_SPEC_LIST = "spec_list"

    /** label / iconRef 只需按键查找，与顺序无关，仍用 Set。 */
    const val KEY_LABELS = "labels"
    const val KEY_ICON_REFS = "icon_refs"
    const val KEY_BUILT_AT = "built_at"

    fun encodeSpecs(specs: Collection<String>): String = specs.joinToString(",")

    fun decodeSpecs(raw: String?): List<String> = raw.orEmpty().split(',').filter { it.isNotBlank() }

    /** 目录条目。`png` 只在收到广播的当次有效，不落盘。 */
    class Entry(
        val spec: String,
        val label: String,
        val iconRef: String,
        val png: ByteArray?,
    ) {
        /** 目录所用的稳定 id：能对上内置表的用表的 id（保住用户已有配置），否则就用 spec 本身。 */
        val id: String = SystemTileSpecs.specToId[spec] ?: spec

        override fun toString(): String = "Entry(spec=$spec, label=$label, iconRef=$iconRef)"
    }

    /** 从广播 Intent 中解出目录条目；[EXTRA_SPECS] 缺失或为空视为无效。 */
    fun readEntries(intent: Intent): List<Entry> {
        val specs = intent.getStringArrayListExtra(EXTRA_SPECS) ?: return emptyList()
        if (specs.isEmpty()) return emptyList()
        val labels = intent.getStringArrayListExtra(EXTRA_LABELS).orEmpty()
        val refs = intent.getStringArrayListExtra(EXTRA_ICON_REFS).orEmpty()
        val icons: Bundle? = intent.getBundleExtra(EXTRA_ICONS)
        return specs.mapIndexedNotNull { index, spec ->
            if (spec.isNullOrEmpty()) return@mapIndexedNotNull null
            Entry(
                spec = spec,
                label = labels.getOrNull(index).orEmpty(),
                iconRef = refs.getOrNull(index).orEmpty(),
                png = runCatching { icons?.getByteArray(spec) }.getOrNull(),
            )
        }
    }

    /** 把目录条目写进 Intent（发送端用）。 */
    fun writeEntries(builder: Intent, entries: List<Entry>): Intent {
        val specs = ArrayList<String>(entries.size)
        val labels = ArrayList<String>(entries.size)
        val refs = ArrayList<String>(entries.size)
        val icons = Bundle()
        entries.forEach { entry ->
            specs.add(entry.spec)
            labels.add(entry.label)
            refs.add(entry.iconRef)
            entry.png?.let { icons.putByteArray(entry.spec, it) }
        }
        builder.putStringArrayListExtra(EXTRA_SPECS, specs)
        builder.putStringArrayListExtra(EXTRA_LABELS, labels)
        builder.putStringArrayListExtra(EXTRA_ICON_REFS, refs)
        if (!icons.isEmpty) builder.putExtra(EXTRA_ICONS, icons)
        return builder
    }

    // ───────────────────── 图标解析（两侧共用，避免两份实现漂移）─────────────────────

    private const val DEFAULT_RASTER_SIZE = 96

    /**
     * 把 `com.android.systemui:drawable/ic_qs_nfc_on` 这类全名解析成 Drawable。
     *
     * 关键在 `createPackageContext`：`getIdentifier` 会先按包名查 AssetManager 里的
     * PackageGroup，目标 APK 没被加载就直接返回 0，所以不能拿自己进程的
     * `context.resources` 去查别的包。framework（`android`）永远在本地，无需（也无法）
     * 创建包上下文。
     */
    fun resolveIconRef(context: Context, ref: String): Drawable? {
        if (ref.isEmpty() || !ref.contains(':')) return null
        val pkg = ref.substringBefore(':')
        val type = ref.substringAfter(':').substringBefore('/')
        val name = ref.substringAfterLast('/')
        if (type.isEmpty() || name.isEmpty()) return null
        return runCatching {
            val res = if (pkg == "android") {
                context.resources
            } else {
                context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY).resources
            }
            val resId = res.getIdentifier(name, type, pkg)
            if (resId == 0) null else res.getDrawable(resId, null)
        }.getOrNull()
    }

    fun decodePng(context: Context, png: ByteArray?): Drawable? {
        if (png == null || png.isEmpty()) return null
        return runCatching {
            BitmapFactory.decodeByteArray(png, 0, png.size)
                ?.let { BitmapDrawable(context.resources, it) }
        }.getOrNull()
    }

    /**
     * Drawable → PNG 字节。整份目录的图标加起来通常只有几十 KB，远低于 Binder 上限。
     *
     * 走 [IconNormalizer] 而不是直接拉伸：QS 矢量图的画面并不铺满自己的 viewport，
     * 直接拉伸会把这个比例原样带进 PNG，接收侧再按 `Fit` 画出来就偏小。
     *
     * `trimWhitePlate = true`：磁贴图标里有一部分是"白底 + 图形"，白底会把中间的
     * 图形衬得偏小。归一化内部只在墨迹明显小于整幅（≤92%）时才收窄，彩色满幅整图
     * 不受影响。侧边栏注入路径用同一判据，两侧视觉尺寸才对得上。
     */
    fun rasterizePng(drawable: Drawable, size: Int = DEFAULT_RASTER_SIZE): ByteArray? = runCatching {
        val bitmap = IconNormalizer.normalize(drawable, size, trimWhitePlate = true)
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }.getOrNull()
}
