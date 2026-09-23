package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.z1812.hyperdock.systemtile.SystemTileCatalogProtocol
import io.github.z1812.hyperdock.systemtile.SystemTileSpecs
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field

/**
 * SystemUI 侧的 QS 点击桥。
 *
 * 安全中心侧边栏只负责展示列表；真正的 CustomTile/QSTile 只能由 SystemUI
 * 的 MiuiQSHostAdapter 创建和点击，因此通过显式包内广播把点击转回 SystemUI。
 */
object SidebarQsBridgeHook {
    const val ACTION_CLICK = "io.github.z1812.hyperdock.action.CLICK_QS_TILE"
    const val ACTION_STATE = "io.github.z1812.hyperdock.action.QS_TILE_STATE"
    const val ACTION_QUERY_STATE = "io.github.z1812.hyperdock.action.QUERY_QS_TILE_STATE"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_SPEC = "spec"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_TOGGLEABLE = "toggleable"

    /** 侧边栏宿主包名。状态回包必须显式定向到它，否则在 API 33+ 上会被静默丢弃。 */
    private const val SIDEBAR_PKG = "com.miui.securitycenter"

    /** QSTile.State.state 的取值：2 == ACTIVE。 */
    private const val STATE_ACTIVE = 2

    /** 目录广播的两个接收方：侧边栏宿主 + 模块 App 选择器。 */
    private val CATALOG_TARGETS = arrayOf(
        SystemTileCatalogProtocol.SIDEBAR_PKG,
        SystemTileCatalogProtocol.MODULE_PKG,
    )

    /** `state.label` / `state.icon` 由 handleUpdateState 异步填入，create 完必须等一拍再读。 */
    private const val CATALOG_SETTLE_MS = 700L

    /** PNG 兜底光栅化边长。单色矢量图标压出来约 1~2KB，40 条合计远低于 Binder 1MB 上限。 */
    private const val ICON_RASTER_SIZE = 96

    /** 目录构建最大尝试次数（宿主尚未就绪时会探到空结果）。 */
    private const val MAX_CATALOG_ATTEMPTS = 3

    private const val TAG = "HyperDock[SidebarQsBridge]"
    private const val HOST_CLASS = "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"

    @Volatile
    private var host: Any? = null
    private var systemContext: Context? = null
    @Volatile
    private var receiverInstalled = false

    fun init(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != "com.android.systemui") return
        runCatching {
            val loader = param.defaultClassLoader
            systemContext = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                val context = chain.args.getOrNull(0) as? Context
                if (context != null) {
                    systemContext = context
                    installReceiver(module, context)
                }
                result
            }
            val hostClass = findHostClass(loader)
                ?: throw ClassNotFoundException("SystemUI QS host")
            hostClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    host = chain.thisObject
                    scheduleCatalogBuild(module, chain.thisObject)
                    result
                }
            }
            // 构造函数可能早于模块注入完成；方法级捕获可覆盖这种时序。
            (hostClass.declaredMethods.asSequence() + hostClass.methods.asSequence()).distinctBy { it.toGenericString() }.filter { method ->
                method.name == "clickTile" || method.name == "getTiles" || method.name == "createTile" || method.name == "addTile" ||
                    (method.returnType == Void.TYPE && method.parameterCount == 1 &&
                        (method.parameterTypes[0] == ComponentName::class.java ||
                            method.parameterTypes[0] == String::class.java))
            }.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    host = chain.thisObject
                    // 构造函数 Hook 可能早于模块注入完成；这里补拉（内部自带缓存与次数上限）。
                    requestCatalog(module, chain.thisObject)
                    chain.proceed()
                }
            }
            runCatching {
                // JADX 显示 p055qs，运行时包名仍是 qs。
                val customTileClass = Class.forName("com.android.systemui.qs.external.CustomTile", false, loader)
                customTileClass.methods.filter { it.name == "updateTileState" && it.parameterCount == 2 }
                    .forEach { method ->
                        method.isAccessible = true
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            val component = chain.thisObject.javaClass.methods.firstOrNull {
                                it.name == "getComponent" && it.parameterCount == 0
                            }?.invoke(chain.thisObject) as? ComponentName
                            val tile = chain.args.firstOrNull()
                            val state = tile?.javaClass?.methods?.firstOrNull {
                                it.name == "getState" && it.parameterCount == 0
                            }?.invoke(tile) as? Int
                            if (component != null && state != null) {
                                module.log(android.util.Log.DEBUG, TAG, "tile state ${component.flattenToShortString()}=$state")
                                if (state != 0) {
                                    val toggleable = isToggleable(chain.thisObject)
                                    sendStateValue(systemContext, component.flattenToString(), null,
                                        toggleable && state == 2, toggleable)
                                }
                            }
                            result
                        }
                    }
            }
        }.onFailure {
            module.log(android.util.Log.ERROR, TAG, "init failed: ${it.message}")
        }
    }

    private fun findHostClass(loader: ClassLoader): Class<*>? {
        runCatching { Class.forName(HOST_CLASS, false, loader) }.getOrNull()?.let { return it }
        return runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(loader, false).use { bridge ->
                bridge.findClass {
                    searchPackages("com.android.systemui.qs.pipeline.domain.adapter")
                }.asSequence().mapNotNull { data ->
                    runCatching { data.getInstance(loader) }.getOrNull()
                }.firstOrNull { type ->
                    runCatching {
                        type.declaredMethods.any { method ->
                            method.parameterCount == 1 &&
                                method.parameterTypes[0] == ComponentName::class.java
                        } && type.declaredMethods.any { method ->
                            method.parameterCount == 1 &&
                                method.parameterTypes[0] == String::class.java
                        }
                    }.getOrDefault(false)
                }
            }
        }.getOrNull()
    }

    // ───────────────────── 系统磁贴目录（catalog）─────────────────────

    @Volatile
    private var initialBuildScheduled = false

    @Volatile
    private var catalogBuilding = false

    /**
     * 已尝试构建次数。宿主方法会被高频调用，必须设上限，否则一次失败会反复重建。
     *
     * 可能被 hook 拦截器在任意线程读到/自增，竞态后果仅是"多试一次"，不致命。
     */
    @Volatile
    private var catalogAttempts = 0

    /** 构建结果缓存。整份目录在设备生命周期内基本不变，重拉时直接复用。 */
    @Volatile
    private var catalog: List<SystemTileCatalogProtocol.Entry>? = null

    private fun scheduleCatalogBuild(module: XposedModule, hostObject: Any) {
        if (initialBuildScheduled) return
        initialBuildScheduled = true
        // 构造期 SystemUI 尚未就绪，延后一点再探测。
        Handler(Looper.getMainLooper()).postDelayed({ requestCatalog(module, hostObject) }, 3000L)
    }

    /**
     * 构建（或复用）目录，并广播给安全中心与模块 App。
     *
     * 磁贴**必须在主线程创建**：QSTileImpl 自带 Handler，跨线程 `createTile` 会拿到
     * 未初始化的实例，`state.label` / `state.icon` 全是空。
     *
     * 重复调用是安全的：已有缓存就直接重发；构建中或已用尽重试次数就忽略。
     */
    private fun requestCatalog(module: XposedModule, hostObject: Any) {
        catalog?.let {
            broadcastCatalog(it)
            return
        }
        if (catalogBuilding) return
        if (catalogAttempts >= MAX_CATALOG_ATTEMPTS) {
            module.log(android.util.Log.WARN, TAG, "catalog build gave up after $catalogAttempts attempts")
            return
        }
        catalogAttempts++
        catalogBuilding = true
        Handler(Looper.getMainLooper()).post {
            runCatching { buildCatalog(module, hostObject) }
                .onFailure {
                    catalogBuilding = false
                    module.log(android.util.Log.WARN, TAG, "catalog build failed: ${it.message}")
                }
        }
    }

    /**
     * 候选集 = 设备出厂全集 ∪ 默认集 ∪ 控制中心当前集 ∪ 模块内置表。
     *
     * `getStockTiles()` 读的是**设备资源** `miui_quick_settings_tiles_stock`
     * （平板 `_pad`、国际版另有分支），因此它就是"本机型到底有哪些"的权威答案；
     * 再叠加内置表兜住设备资源可能漏掉的项（例如插件磁贴）。
     *
     * 第三方 `custom(...)` 由侧边栏按 ComponentName 单独处理，此处排除。
     */
    private fun collectCandidates(hostObject: Any): List<String> {
        val out = LinkedHashSet<String>()
        runCatching {
            (invokeNoArg(hostObject, "getStockTiles") as? String)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.let(out::addAll)
        }
        runCatching {
            (invokeNoArg(hostObject, "getDefaultTileSpecs") as? Collection<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?.let(out::addAll)
        }
        runCatching {
            (invokeNoArg(hostObject, "getTiles") as? Collection<*>)
                ?.mapNotNull { tile -> (invokeNoArg(tile!!, "getTileSpec") as? String) }
                ?.let(out::addAll)
        }
        SystemTileSpecs.ALL.forEach { out.add(it.spec) }
        return out.filter { !it.startsWith("custom(") }
    }

    private fun buildCatalog(module: XposedModule, hostObject: Any) {
        val candidates = collectCandidates(hostObject)
        // spec → 磁贴实例 + 是否为本函数临时创建（临时实例读完必须 destroy）。
        val primed = ArrayList<Triple<String, Any, Boolean>>(candidates.size)
        for (spec in candidates) {
            val existing = runCatching { findTile(hostObject, spec) }.getOrNull()
            val owned = existing == null
            val tile = existing ?: runCatching { createTile(hostObject, spec) }.getOrNull() ?: continue
            primed.add(Triple(spec, tile, owned))
            // 已在控制中心里的实例是活的，绝不能再 handleInitialize；只有新建的才需要。
            primeTile(tile, initialize = owned)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val entries = ArrayList<SystemTileCatalogProtocol.Entry>(primed.size)
            for ((spec, tile, owned) in primed) {
                val entry = runCatching { readEntry(spec, tile) }.getOrNull()
                if (entry != null) entries.add(entry)
                if (owned) runCatching { invokeNoArg(tile, "destroy") }
            }
            module.log(
                android.util.Log.INFO, TAG,
                "catalog built ${entries.size}/${candidates.size}: " +
                    entries.joinToString(", ") { "${it.spec}(${it.label})" },
            )
            catalogBuilding = false
            if (entries.isEmpty()) {
                // 一条都没探到，通常是宿主尚未就绪而非设备不支持；不落缓存，留给后续触发重试。
                module.log(android.util.Log.WARN, TAG, "catalog empty, will retry on next host call")
                return@postDelayed
            }
            catalog = entries
            broadcastCatalog(entries)
        }, CATALOG_SETTLE_MS)
    }

    /**
     * 让磁贴开始上报状态，`handleUpdateState` 才会把 label/icon 填进 state。
     *
     * `handleInitialize` 只对**新建**实例调用：控制中心里已有的磁贴是活的，
     * 重复初始化会破坏它的监听状态。
     */
    private fun primeTile(tile: Any, initialize: Boolean) {
        runCatching {
            val methods = tile.javaClass.methods
            if (initialize) {
                methods.firstOrNull { it.name == "handleInitialize" && it.parameterCount == 0 }?.invoke(tile)
            }
            methods.firstOrNull {
                it.name == "handleSetListening" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.invoke(tile, true)
            methods.firstOrNull { it.name == "refreshState" && it.parameterCount == 1 }?.invoke(tile, null)
        }
    }

    /**
     * 读取单条目录项。
     *
     * `createTile` 成功 **不等于** 设备支持（jadx 确证 `createTileSync` 只做
     * `tileFactory.createTile`，不判可用性），所以必须再问 `isAvailable()`。
     * label 与 icon 都取不到时，说明这个 spec 在本机名存实亡，直接丢弃。
     */
    private fun readEntry(spec: String, tile: Any): SystemTileCatalogProtocol.Entry? {
        val available = runCatching { invokeNoArg(tile, "isAvailable") as? Boolean }.getOrNull() ?: true
        if (!available) return null
        val state = invokeNoArg(tile, "getState") ?: return null
        val label = runCatching { fieldOf(state, "label") as? CharSequence }.getOrNull()?.toString().orEmpty()
        val icon = runCatching { fieldOf(state, "icon") }.getOrNull()
        val ref = icon?.let { iconRefOf(it) }.orEmpty()
        // 有资源名就不必再传位图：资源名只有几十字节，且渲染出来是矢量的。
        val png = if (ref.isEmpty()) icon?.let { iconPngOf(it) } else null
        if (label.isEmpty() && ref.isEmpty() && png == null) return null
        return SystemTileCatalogProtocol.Entry(spec, label, ref, png)
    }

    /**
     * 从 `QSTile.Icon` 取资源名。
     *
     * jadx：`QSTileImpl$ResourceIcon` 有**公有** `int mResId`，
     * `QSTileImpl$DrawableIconWithRes` 有**公有** `int mId`。
     * `Resources.getResourceName` 返回 `com.android.systemui:drawable/ic_qs_nfc_on` 这种
     * 带包名的全名，接收侧据此就能在正确的包内解析——这正是过去"猜名字 + 只搜
     * framework"永远拿不到图标的原因。
     */
    private fun iconRefOf(icon: Any): String {
        val context = systemContext ?: return ""
        val resId = intFieldOf(icon, "mResId") ?: intFieldOf(icon, "mId") ?: return ""
        if (resId == 0) return ""
        return runCatching { context.resources.getResourceName(resId) }.getOrNull().orEmpty()
    }

    /** 兜底：`QSTile.Icon.getDrawable(Context)` 光栅化成 PNG（覆盖没有资源 id 的 DrawableIcon）。 */
    private fun iconPngOf(icon: Any): ByteArray? {
        val context = systemContext ?: return null
        val drawable = runCatching {
            icon.javaClass.methods.firstOrNull { it.name == "getDrawable" && it.parameterCount == 1 }
                ?.apply { isAccessible = true }
                ?.invoke(icon, context) as? Drawable
        }.getOrNull() ?: return null
        return SystemTileCatalogProtocol.rasterizePng(drawable, ICON_RASTER_SIZE)
    }

    private fun broadcastCatalog(entries: List<SystemTileCatalogProtocol.Entry>) {
        val context = systemContext ?: return
        if (entries.isEmpty()) {
            // 空目录不下发：接收侧会把"空"理解成权威的"本机什么都没有"，
            // 反而把侧边栏清空。保留接收侧的内置回退行为更安全。
            return
        }
        for (target in CATALOG_TARGETS) {
            runCatching {
                val intent = SystemTileCatalogProtocol.writeEntries(
                    Intent(SystemTileCatalogProtocol.ACTION_CATALOG).setPackage(target),
                    entries,
                ).putExtra(SystemTileCatalogProtocol.EXTRA_BUILT_AT, SystemClock.elapsedRealtime())
                context.sendBroadcast(intent)
            }
        }
    }

    private fun installReceiver(module: XposedModule, context: Context) {
        if (receiverInstalled) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == SystemTileCatalogProtocol.ACTION_REQUEST_CATALOG) {
                    // 补拉：安全中心 / 模块 App 的进程可能晚于 SystemUI 启动，
                    // 那次广播它们没听到，这里按需重发。
                    val currentHost = host
                    if (currentHost != null) {
                        module.log(android.util.Log.DEBUG, TAG, "catalog re-requested")
                        requestCatalog(module, currentHost)
                    } else {
                        module.log(android.util.Log.WARN, TAG, "catalog request ignored: QS host unavailable")
                    }
                    return
                }
                if (intent.action == ACTION_QUERY_STATE) {
                    val component = intent.getStringExtra(EXTRA_COMPONENT)
                    val spec = intent.getStringExtra(EXTRA_SPEC)
                    host?.let { queryState(context, it, component, spec) }
                    return
                }
                if (intent.action != ACTION_CLICK) return
                val componentText = intent.getStringExtra(EXTRA_COMPONENT)
                val spec = intent.getStringExtra(EXTRA_SPEC)
                module.log(android.util.Log.DEBUG, TAG, "click request component=$componentText spec=$spec")
                val currentHost = host
                if (currentHost != null) {
                    click(module, context, currentHost, componentText, spec)
                } else {
                    module.log(android.util.Log.WARN, TAG, "click ignored: QS host unavailable")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_CLICK)
            addAction(ACTION_QUERY_STATE)
            addAction(SystemTileCatalogProtocol.ACTION_REQUEST_CATALOG)
        }
        runCatching {
            val register = Context::class.java.getMethod(
                "registerReceiver", BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType,
            )
            // 见 SidebarShortcutController.installStateReceiver 的说明：
            // API 33+ 里 RECEIVER_EXPORTED == 2，原先把 4 当成 EXPORTED 是错的。
            register.invoke(context, receiver, filter, Context.RECEIVER_EXPORTED)
        }.recoverCatching {
            context.registerReceiver(receiver, filter)
        }.onSuccess {
            receiverInstalled = true
        }.onFailure {
            module.log(android.util.Log.ERROR, TAG, "receiver install failed: ${it.message}")
        }
    }

    private fun click(module: XposedModule, context: Context, hostObject: Any, componentText: String?, spec: String?) {
        runCatching {
            if (!componentText.isNullOrEmpty()) {
                val normalizedComponent = componentText.replace("\\", "")
                val component = ComponentName.unflattenFromString(normalizedComponent)
                    ?: run {
                        module.log(android.util.Log.ERROR, TAG, "invalid tile component=$normalizedComponent")
                        return@runCatching
                    }
                val tileSpec = "custom(${component.flattenToShortString()})"
                val existing = findTile(hostObject, tileSpec)
                if (existing != null) {
                    module.log(android.util.Log.DEBUG, TAG, "click existing tile=$tileSpec")
                    ensureTileListening(existing)
                    val clickTile = hostObject.javaClass.methods.firstOrNull {
                        it.name == "clickTile" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == ComponentName::class.java
                    }
                    if (clickTile != null) clickTile.invoke(hostObject, component)
                    else invokeTileClick(existing)
                } else {
                    module.log(android.util.Log.DEBUG, TAG, "click dynamic tile=$tileSpec")
                    val tile = createTile(hostObject, tileSpec)
                    if (tile == null) {
                        module.log(android.util.Log.WARN, TAG, "createTile returned null: $tileSpec")
                    } else {
                        module.log(android.util.Log.DEBUG, TAG, "created tile=${tile.javaClass.name}")
                        initializeAndClickDynamic(tile)
                    }
                }
                return@runCatching
            }
            if (spec.isNullOrEmpty()) return@runCatching
            val existing = findTile(hostObject, spec)
            if (existing != null) {
                val clickTile = hostObject.javaClass.methods.firstOrNull {
                    it.name == "clickTile" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (clickTile != null) clickTile.invoke(hostObject, spec)
                else invokeTileClick(existing)
                sendState(context, null, spec, existing)
            } else {
                createTile(hostObject, spec)?.let { tile ->
                    invokeTileClick(tile)
                    sendState(context, null, spec, tile)
                }
            }
        }.onFailure { module.log(android.util.Log.ERROR, TAG, "tile click failed: ${it.message}") }
    }

    private fun findTile(hostObject: Any, spec: String): Any? = runCatching {
        val tiles = hostObject.javaClass.methods.firstOrNull {
            it.name == "getTiles" && it.parameterCount == 0
        }?.invoke(hostObject) as? Iterable<*>
        tiles?.firstOrNull { item ->
            item?.javaClass?.methods?.firstOrNull {
                it.name == "getTileSpec" && it.parameterCount == 0
            }?.invoke(item) == spec
        }
    }.getOrNull()

    private fun createTile(hostObject: Any, spec: String): Any? = runCatching {
        hostObject.javaClass.methods.firstOrNull {
            it.name == "createTile" && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }?.invoke(hostObject, spec)
    }.getOrNull()


    private fun invokeTileClick(tile: Any) {
        runCatching {
            tile.javaClass.methods.firstOrNull { it.name == "refreshState" && it.parameterCount == 0 }
                ?.invoke(tile)
        }
        val methods = (tile.javaClass.declaredMethods.asSequence() + tile.javaClass.methods.asSequence())
            .distinctBy { it.toGenericString() }
            .toList()
        val click = methods.firstOrNull { it.name == "click" && it.parameterCount == 1 }
            ?: methods.firstOrNull { it.name == "click" && it.parameterCount == 0 }
        if (click != null) {
            click.isAccessible = true
            if (click.parameterCount == 0) click.invoke(tile) else click.invoke(tile, null)
        } else {
            throw NoSuchMethodException(
                "Tile click method unavailable: ${tile.javaClass.name} methods=" +
                    methods.filter { it.name.contains("click", true) || it.name.contains("refresh", true) }
                        .joinToString { it.toGenericString() },
            )
        }
    }

    private fun initializeAndClickDynamic(tile: Any) {
        val methods = tile.javaClass.methods
        runCatching {
            methods.firstOrNull { it.name == "handleInitialize" && it.parameterCount == 0 }?.invoke(tile)
            methods.firstOrNull {
                it.name == "handleSetListening" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.invoke(tile, true)
            methods.firstOrNull { it.name == "refreshState" && it.parameterCount == 1 }?.invoke(tile, null)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { invokeTileClick(tile) }
        }, 300L)
    }

    private fun ensureTileListening(tile: Any) {
        runCatching {
            tile.javaClass.methods.firstOrNull {
                it.name == "handleSetListening" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.invoke(tile, true)
        }
    }

    private fun sendState(context: Context, component: String?, spec: String?, tile: Any) {
        Handler(Looper.getMainLooper()).postDelayed({
            sendStateNow(context, component, spec, tile)
        }, 350L)
    }

    /**
     * 磁贴状态快照。
     *
     * 旧实现按 `state.javaClass.declaredFields` 顺序取"第一个 int 字段"再与 2 比较 ——
     * 字段顺序无保证，取错就整体 return（连广播都不发）。
     *
     * jadx 对目标 SystemUI 的核对结论（com.android.systemui.plugins.qs.QSTile）：
     *   `State.state`   —— **公有 int 字段，字段名就是 state**；0=UNAVAILABLE 1=INACTIVE 2=ACTIVE
     *   `AdapterState.value` —— **公有 boolean 字段**，即磁贴的真实开关值；
     *                            BooleanState / RestrictState / TrafficState 均继承 AdapterState
     *
     * 由此还顺带解决了"可切换"的判定：`isToggleableTile()` **只存在于**
     * `com.android.systemui.qs.external.TileServiceManager`（第三方 TileService 专用，
     * 读 meta-data "android.service.quicksettings.TOGGLEABLE_TILE"），
     * 原生磁贴上既没有该方法也没有 `mServiceManager` 字段 —— 旧逻辑对 wifi/蓝牙这类
     * 原生磁贴恒返回 false，于是 EXTRA_ENABLED 恒 false、侧边栏 toggleableTiles 为空、
     * 状态永远显示为灰。
     * 正确判据：state 里**有没有 `value` 字段**。
     *
     * label 同理取自 `State.label`（公有字段，SystemUI 已按当前语言本地化）。
     * 旧实现读的是 `getTileLabel()` —— 该方法是 `QSTileImpl` 上的，MIUI 的插件接口
     * `MiuiQSTile` 并不暴露它，反射失败被 runCatching 吞掉，label 恒为 null。
     */
    private class TileSnapshot(val enabled: Boolean, val toggleable: Boolean, val label: String?)

    private fun readSnapshot(tile: Any): TileSnapshot? {
        val state = invokeNoArg(tile, "getState") ?: return null
        val stateInt = intFieldOf(state, "state") ?: 0
        val hasValue = findField(state.javaClass, "value", Boolean::class.javaPrimitiveType) != null
        val enabled = if (hasValue) booleanFieldOf(state, "value") ?: false else stateInt == STATE_ACTIVE
        val label = runCatching { fieldOf(state, "label") as? CharSequence }.getOrNull()?.toString()
        return TileSnapshot(enabled, hasValue, label)
    }

    private fun sendStateNow(context: Context, component: String?, spec: String?, tile: Any) {
        val snapshot = readSnapshot(tile) ?: return
        sendStateValue(context, component, spec, snapshot.enabled, snapshot.toggleable)
    }

    private fun sendStateValue(context: Context?, component: String?, spec: String?, active: Boolean, toggleable: Boolean = true) {
        context ?: return
        context.sendBroadcast(
            Intent(ACTION_STATE)
                // 显式定向。旧实现是隐式广播，在 API 33+ 上可达性极差。
                .setPackage(SIDEBAR_PKG)
                .putExtra(EXTRA_COMPONENT, component)
                .putExtra(EXTRA_SPEC, spec)
                .putExtra(EXTRA_ENABLED, active)
                .putExtra(EXTRA_TOGGLEABLE, toggleable),
        )
    }

    private fun isToggleable(tile: Any): Boolean = readSnapshot(tile)?.toggleable ?: false

    // ───────────────────── 反射小工具（按名字查找，不做顺序猜测） ─────────────────────

    private fun invokeNoArg(receiver: Any, name: String): Any? = runCatching {
        val method = receiver.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: receiver.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: return@runCatching null
        method.isAccessible = true
        method.invoke(receiver)
    }.getOrNull()

    private val fieldCache = HashMap<String, Field?>()

    /** 沿 类 → 父类 → 接口 广度优先查找字段并缓存（QSTile$AdapterState 是父类，不能只看 declaredFields）。 */
    private fun findField(start: Class<*>, name: String, type: Class<*>?): Field? {
        // 缓存键必须带上期望类型：同名字段可能被不同调用方以不同期望类型查询，
        // 只按 name 缓存会把"不限类型"解析出的字段错误地交给要求精确类型的调用方。
        val key = start.name + '#' + name + '#' + (type?.name ?: "*")
        if (fieldCache.containsKey(key)) return fieldCache[key]
        var result: Field? = null
        val queue = ArrayDeque<Class<*>>()
        val seen = HashSet<Class<*>>()
        queue.addLast(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            val field = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null && (type == null || field.type == type)) {
                field.isAccessible = true
                result = field
                break
            }
            current.superclass?.let { queue.addLast(it) }
            current.interfaces?.forEach { queue.addLast(it) }
        }
        fieldCache[key] = result
        return result
    }

    private fun intFieldOf(holder: Any, name: String): Int? =
        runCatching { findField(holder.javaClass, name, Int::class.javaPrimitiveType)?.getInt(holder) }.getOrNull()

    private fun booleanFieldOf(holder: Any, name: String): Boolean? =
        runCatching { findField(holder.javaClass, name, Boolean::class.javaPrimitiveType)?.getBoolean(holder) }.getOrNull()

    /** 不限定类型的字段读取（label / icon 这类引用类型用它）。 */
    private fun fieldOf(holder: Any, name: String): Any? =
        runCatching { findField(holder.javaClass, name, null)?.get(holder) }.getOrNull()

    private fun queryState(context: Context, hostObject: Any, component: String?, spec: String?) {
        val tileSpec = component?.let {
            val normalized = ComponentName.unflattenFromString(it) ?: return
            "custom(${normalized.flattenToShortString()})"
        } ?: spec ?: return
        val tile = findTile(hostObject, tileSpec) ?: createTile(hostObject, tileSpec) ?: return
        ensureTileListening(tile)
        runCatching {
            tile.javaClass.methods.firstOrNull { it.name == "refreshState" && it.parameterCount == 1 }
                ?.invoke(tile, null)
        }
        if (component == null) sendState(context, null, spec, tile)
    }
}
