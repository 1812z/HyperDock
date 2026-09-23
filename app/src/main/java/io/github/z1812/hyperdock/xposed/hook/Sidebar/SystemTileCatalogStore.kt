package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.SystemClock
import io.github.z1812.hyperdock.systemtile.SystemTileCatalogProtocol
import io.github.z1812.hyperdock.systemtile.SystemTileSpecs

/**
 * 安全中心侧的系统磁贴目录缓存。
 *
 * ## 解决的旧问题
 *
 * 图标过去靠"猜资源名 + `context.resources.getIdentifier(name, "drawable", pkg)`"。
 * 这条路永远拿不到图标，有两层原因：
 *   1. 只搜了 `"android"`（framework），而 QS 磁贴图标绝大多数在 SystemUI 里；
 *   2. 更根本的是 `AssetManager2::GetResourceId` 会先按包名查 PackageGroup，
 *      而安全中心的 AssetManager **没有加载 SystemUI 的 APK**，查不到就返回 0 ——
 *      所以就算把 defPackage 换成 `com.android.systemui` 也仍是 0。
 *      必须 `createPackageContext(pkg, CONTEXT_IGNORE_SECURITY).resources`。
 *
 * 现在改为直接消费 SystemUI 推来的目录：真图标（资源名或 PNG）、真文案、
 * 以及"本机确实支持"这个事实（不支持的条目不进目录，也就不会出现在侧边栏）。
 *
 * ## 缓存策略
 *
 * - 内存：spec → 条目（含 PNG 字节）。
 * - 磁盘：只落 spec / label / iconRef。PNG 体积大，改为每次重拉；
 *   但 spec 集合必须持久化，否则进程重启后到广播到达前会退化成"不过滤"。
 */
internal object SystemTileCatalogStore {
    private const val TAG = "HyperDock[SystemTileCatalog]"

    /** spec → 条目。null 表示从未收到过目录（此时不启用可用性过滤）。 */
    @Volatile
    private var bySpec: Map<String, SystemTileCatalogProtocol.Entry>? = null

    /** id → 条目。id 优先复用内置表的目录 id，动态条目则直接用 spec。 */
    @Volatile
    private var byId: Map<String, SystemTileCatalogProtocol.Entry> = emptyMap()

    /** 目录就绪时侧边栏可见的系统磁贴标签；未就绪保持 null，由调用方回退内置表。 */
    @Volatile
    private var mergedLabels: Map<String, Pair<String, String>>? = null

    private val iconCache = HashMap<String, Drawable>()

    /** 目录是否已就绪。未就绪时所有过滤/图标查询都退化成旧行为。 */
    val ready: Boolean get() = bySpec != null

    fun entry(id: String): SystemTileCatalogProtocol.Entry? = byId[id]

    /** 本机是否支持该目录 id。目录未就绪视为"未知"，按可用处理以保持旧行为。 */
    fun isAvailable(id: String): Boolean {
        val map = bySpec ?: return true
        return byId.containsKey(id) && map.isNotEmpty()
    }

    /** 目录 id → spec。动态条目（不在内置表里的 spec）其 id 就是 spec 本身。 */
    fun specOf(id: String): String? =
        SystemTileSpecs.specOf(id) ?: id.takeIf { byId.containsKey(it) }

    /** 文案：优先用 SystemUI 回传的（已按当前语言本地化），回退内置表。 */
    fun labelFor(id: String, chinese: Boolean): String? =
        byId[id]?.label?.takeIf { it.isNotBlank() } ?: SystemTileSpecs.labelFor(id, chinese)

    fun labelsOrNull(): Map<String, Pair<String, String>>? = mergedLabels

    /** spec 集合，用于判断是否需要重拉（两侧一致时不必重复广播）。 */
    fun specsSnapshot(): Set<String> = bySpec?.keys.orEmpty()

    // ───────────────────── 接收与缓存 ─────────────────────

    fun onCatalog(context: Context, entries: List<SystemTileCatalogProtocol.Entry>) {
        if (entries.isEmpty()) return
        apply(entries)
        persist(context, entries)
        iconCache.clear()
    }

    /**
     * 进程重启后先从这里恢复 spec 集合，保证过滤立刻生效；
     * 图标与文案会随下一次 `ACTION_CATALOG` 补齐。
     */
    fun loadFromDisk(context: Context) {
        if (bySpec != null) return
        val prefs = prefs(context) ?: return
        val specs = SystemTileCatalogProtocol.decodeSpecs(
            prefs.getString(SystemTileCatalogProtocol.KEY_SPEC_LIST, null),
        )
        if (specs.isEmpty()) return
        val labels = prefs.getStringSet(SystemTileCatalogProtocol.KEY_LABELS, emptySet()).orEmpty()
        val refs = prefs.getStringSet(SystemTileCatalogProtocol.KEY_ICON_REFS, emptySet()).orEmpty()
        val labelBySpec = labels.associate { it.substringBefore('=', "") to it.substringAfter('=', "") }
        val refBySpec = refs.associate { it.substringBefore('=', "") to it.substringAfter('=', "") }
        apply(
            specs.map { spec ->
                SystemTileCatalogProtocol.Entry(
                    spec = spec,
                    label = labelBySpec[spec].orEmpty(),
                    iconRef = refBySpec[spec].orEmpty(),
                    png = null,
                )
            },
        )
    }

    private fun apply(entries: List<SystemTileCatalogProtocol.Entry>) {
        bySpec = LinkedHashMap<String, SystemTileCatalogProtocol.Entry>(entries.size).apply {
            entries.forEach { put(it.spec, it) }
        }
        byId = entries.associateBy { it.id }
        mergedLabels = buildMergedLabels()
    }

    /**
     * 合并标签表：只保留本机可用的系统磁贴，再补上内置表里没有的动态条目。
     * HyperIsland 不是系统磁贴，必须原样保留。
     */
    private fun buildMergedLabels(): Map<String, Pair<String, String>> {
        val available = byId
        val base = SidebarShortcutCatalog.systemLabels.filterKeys { id ->
            id.startsWith(SidebarShortcutCatalog.HYPER_ISLAND_PREFIX) || available.containsKey(id)
        }
        val extra = available.mapNotNull { (id, entry) ->
            val label = entry.label.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (base.containsKey(id)) return@mapNotNull null
            id to (label to label)
        }
        return base + extra
    }

    private fun persist(context: Context, entries: List<SystemTileCatalogProtocol.Entry>) {
        runCatching {
            prefs(context)?.edit()
                ?.putString(
                    SystemTileCatalogProtocol.KEY_SPEC_LIST,
                    SystemTileCatalogProtocol.encodeSpecs(entries.map { it.spec }),
                )
                ?.putStringSet(
                    SystemTileCatalogProtocol.KEY_LABELS,
                    entries.filter { it.label.isNotBlank() }.map { "${it.spec}=${it.label}" }.toSet(),
                )
                ?.putStringSet(
                    SystemTileCatalogProtocol.KEY_ICON_REFS,
                    entries.filter { it.iconRef.isNotBlank() }.map { "${it.spec}=${it.iconRef}" }.toSet(),
                )
                ?.putLong(SystemTileCatalogProtocol.KEY_BUILT_AT, SystemClock.elapsedRealtime())
                ?.apply()
        }.onFailure { android.util.Log.w(TAG, "persist catalog failed: ${it.message}") }
    }

    private fun prefs(context: Context): android.content.SharedPreferences? = runCatching {
        context.getSharedPreferences(SystemTileCatalogProtocol.PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    // ───────────────────── 图标解析 ─────────────────────

    /**
     * 解析目录条目的图标。
     *
     * 优先按资源名解析（矢量、清晰、传输开销仅几十字节），失败再回退广播里带的
     * PNG（覆盖没有资源 id 的 `DrawableIcon`）。
     */
    fun iconFor(context: Context, id: String): Drawable? {
        iconCache[id]?.let { return it }
        val entry = byId[id] ?: return null
        val drawable = SystemTileCatalogProtocol.resolveIconRef(context, entry.iconRef)
            ?: SystemTileCatalogProtocol.decodePng(context, entry.png)
            ?: return null
        iconCache[id] = drawable
        return drawable
    }

    // ───────────────────── 补拉 ─────────────────────

    /** 向 SystemUI 请求重发目录（进程启动顺序不同，可能错过那次广播）。 */
    fun requestCatalog(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(SystemTileCatalogProtocol.ACTION_REQUEST_CATALOG)
                    .setPackage(SystemTileCatalogProtocol.SYSTEMUI_PKG),
            )
        }
    }
}
