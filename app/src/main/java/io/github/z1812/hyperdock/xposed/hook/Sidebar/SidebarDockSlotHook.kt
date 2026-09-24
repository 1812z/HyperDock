package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 两列模式下「速记」旁边的那一格，以及分割线的加长。
 *
 * 宿主（安全中心）的侧边栏列表是 `z7.f` 这个 RecyclerView.Adapter：
 * - 列表元素是接口 `c8.i`（`e(holder)` 绑定、`c(holder)` 解绑）；
 * - 提交列表走 `P(List, boolean)`（内部 DiffUtil）；
 * - 条目有类型：`c8.k` = 速记，`c8.b` = 分割线（绑定时隐藏图标、显示 24dp 的线）；
 * - 原生顺序是 `[速记][分割线][应用…]`。
 *
 * 本 Hook 做两件事：
 * 1. 在提交列表时把一个自造条目插到速记后面。条目用 `Proxy` 直接实现 `c8.i`
 *    —— 宿主对未知类型只做 `instanceof` 判断（getItemViewType / DiffUtil /
 *    点击分发都验证过不会强转），所以 Proxy 是安全的，图标和点击全部由本 Hook
 *    自己接管，不必去猜 `e8.c` 那一套模型的构造方式。
 * 2. 绑定时把分割线加宽到「面板宽 - 左右留白」，配合 `SidebarColumnsHook` 的
 *    SpanSizeLookup（分割线占满整行）把顶部区域和用户应用分开。
 */
object SidebarDockSlotHook : BaseHook() {
    private const val TAG = "HyperDock[DockSlot]"

    private const val TURBO_LAYOUT_CLASS = "com.miui.gamebooster.windowmanager.newbox.TurboLayout"
    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    private const val VIEW_HOLDER_CLASS = "androidx.recyclerview.widget.RecyclerView\$c0"
    private const val ADAPTER_CLASS = "androidx.recyclerview.widget.RecyclerView\$h"
    private const val LINEAR_LAYOUT_MANAGER_CLASS = "androidx.recyclerview.widget.LinearLayoutManager"

    private const val ID_DIVIDER = "divider"
    private const val ID_ICON = "iv_icon"
    private const val ID_PLACEHOLDER = "iv_placeholder"
    private const val DIMEN_DIVIDER_WIDTH = "dock_divider_width"
    private const val DIMEN_ITEM_PADDING = "dock_item_padding"

    private const val MAX_PARENT_DEPTH = 16

    @Volatile private var turboClass: Class<*>? = null
    @Volatile private var holderClass: Class<*>? = null
    /** RecyclerView → 它的 adapter。setAdapter 时先记下，等确认是侧边栏再接管。 */
    private val adapterByView = Collections.synchronizedMap(WeakHashMap<View, Any>())
    @Volatile private var adapterInstance: Any? = null
    @Volatile private var preparedAdapterClass: Class<*>? = null
    @Volatile private var submitMethod: Method? = null
    @Volatile private var bindMethod: Method? = null
    @Volatile private var clickMethod: Method? = null
    @Volatile private var itemInterface: Class<*>? = null
    @Volatile private var slotItem: Any? = null
    @Volatile private var nativeList: List<Any> = emptyList()

    /** 资源 id 在宿主里按包名解析，解析一次即可。 */
    @Volatile private var dividerResId = 0
    @Volatile private var iconResId = 0
    @Volatile private var placeholderResId = 0
    @Volatile private var dividerWidthPx = 0
    @Volatile private var paddingPx = 0
    @Volatile private var resResolved = false

    /** 槽位图标缓存。每次打开侧边栏都重新走 PackageManager 取图标会有几十毫秒延迟，
     *  表现为图标"从无到突然出现"；缓存后除首次外都是即时生效。 */
    @Volatile private var cachedIconId: String? = null
    @Volatile private var cachedIcon: Drawable? = null

    /** change 动画是否已经关掉（每个列表只需要一次）。 */
    @Volatile private var changeAnimationMuted = false

    /** 判定过"不是侧边栏列表"的 adapter 类，避免每次都打一条 warn。 */
    private val rejectedClasses = Collections.newSetFromMap(WeakHashMap<String, Boolean>())

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) return
        val loader = runCatching { param.defaultClassLoader }.getOrNull()
        if (loader == null) {
            logWarn(module, "no class loader; dock slot disabled")
            return
        }
        turboClass = runCatching { Class.forName(TURBO_LAYOUT_CLASS, false, loader) }.getOrNull()
        if (turboClass == null) {
            logWarn(module, "TurboLayout unavailable; dock slot disabled")
            return
        }
        val rvClass = runCatching { Class.forName(RECYCLER_VIEW_CLASS, false, loader) }.getOrNull()
        val adapterClass = runCatching { Class.forName(ADAPTER_CLASS, false, loader) }.getOrNull()
        holderClass = runCatching { Class.forName(VIEW_HOLDER_CLASS, false, loader) }.getOrNull()
        if (rvClass == null || adapterClass == null) {
            logWarn(module, "RecyclerView unavailable; dock slot disabled")
            return
        }
        val setAdapter = runCatching { rvClass.getMethod("setAdapter", adapterClass) }.getOrNull()
        val getAdapter = runCatching { rvClass.getMethod("getAdapter") }.getOrNull()
        if (setAdapter == null) {
            logWarn(module, "setAdapter unavailable; dock slot disabled")
            return
        }
        setAdapter.isAccessible = true
        getAdapter?.isAccessible = true
        runCatching {
            module.hook(setAdapter).intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View
                val adapter = chain.args.firstOrNull()
                if (view != null && adapter != null) {
                    adapterByView[view] = adapter
                    // 侧边栏列表在 DockLayout 构造期就 setAdapter，此时还没挂到
                    // TurboLayout 下面，父链是空的 —— 必须等 attach 之后再判定。
                    claim(module, view, adapter)
                    view.post { claim(module, view, adapter) }
                }
                result
            }
        }.onFailure { logWarn(module, "setAdapter hook failed: ${it.message}") }

        // 兜底挂载点 1：setLayoutManager。视图此刻已经 attach（两列 Hook 就是靠它生效的）。
        val lmClass = resolveLayoutManagerClass(loader)
        val setLm = lmClass?.let { runCatching { rvClass.getMethod("setLayoutManager", it) }.getOrNull() }
            ?: lmClass?.let { target ->
                rvClass.methods.firstOrNull {
                    it.returnType == Void.TYPE && it.parameterCount == 1 && it.parameterTypes[0] == target
                }
            }
        if (setLm != null) {
            setLm.isAccessible = true
            runCatching {
                module.hook(setLm).intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (view != null) {
                        val adapter = adapterByView[view] ?: runCatching { getAdapter?.invoke(view) }.getOrNull()
                        if (adapter != null) claim(module, view, adapter)
                    }
                    result
                }
            }.onFailure { logWarn(module, "setLayoutManager hook failed: ${it.message}") }
        } else {
            logWarn(module, "setLayoutManager unavailable; relying on setAdapter only")
        }

        // 兜底挂载点 2：attach 之后父链必然可用。
        val onAttach = rvClass.declaredMethods.firstOrNull {
            it.name == "onAttachedToWindow" && it.parameterCount == 0
        }
        if (onAttach != null) {
            onAttach.isAccessible = true
            runCatching {
                module.hook(onAttach).intercept { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as? View
                    if (view != null) {
                        val adapter = adapterByView[view] ?: runCatching { getAdapter?.invoke(view) }.getOrNull()
                        if (adapter != null) claim(module, view, adapter)
                    }
                    result
                }
            }.onFailure { logWarn(module, "onAttachedToWindow hook failed: ${it.message}") }
        }
        log(module, "dock slot hooks installed")
    }

    /**
     * 关掉列表的"内容变化"交叉淡入动画。
     *
     * 自造条目在宿主的 DiffUtil 里通常被判为"内容有变化"（它对未知查询一律返回默认值），
     * 于是每次提交都会派发 change 事件，DefaultItemAnimator 拿旧 holder 和新 holder
     * 交叉淡入淡出 —— 视觉上就是图标"从无到突然冒出来"。把 changeDuration 置 0 关掉它，
     * 移动 / 新增 / 移除动画保留，不影响原生那些动效。
     */
    private fun muteChangeAnimation(module: XposedModule, view: View) {
        if (changeAnimationMuted) return
        val animator = runCatching {
            val getter = view.javaClass.getMethod("getItemAnimator")
            getter.isAccessible = true
            getter.invoke(view)
        }.getOrNull()
        if (animator == null) return
        val setter = runCatching {
            animator.javaClass.getMethod("setChangeDuration", Long::class.javaPrimitiveType)
        }.getOrNull()
        if (setter != null) {
            val ok = runCatching {
                setter.isAccessible = true
                setter.invoke(animator, 0L)
            }.isSuccess
            if (ok) {
                changeAnimationMuted = true
                log(module, "item change animation muted on dock list")
                return
            }
        }
        // 兜底：直接把动画器摘掉。宿主 R8 可能把 setChangeDuration 改名了。
        val animatorSetter = view.javaClass.methods.firstOrNull {
            it.name == "setItemAnimator" && it.parameterCount == 1
        }
        val removed = animatorSetter?.let {
            runCatching {
                it.isAccessible = true
                it.invoke(view, null)
            }.isSuccess
        } == true
        if (removed) {
            changeAnimationMuted = true
            log(module, "item animator removed on dock list")
        } else {
            logWarn(module, "cannot mute item animation on dock list")
        }
    }

    /**
     * 确认这个 RecyclerView 是侧边栏列表后接管它的 adapter。
     *
     * **必须先 `prepareAdapter` 成功再写 `adapterInstance`**：宿主侧边栏窗口里还有别的
     * 列表（`n9.e`、全部应用的 `com.miui.dock.allapps.b` 等），它们都没有
     * `submitList(List, boolean)`，一旦先把引用抢走，正在工作的那个 adapter 就会因为
     * 提交拦截器里的身份校验（`thisObject !== adapterInstance`）被整体跳过 ——
     * 表现为槽位图标时有时无、而且 `displayList` 不再更新导致分割线下方的应用错位。
     */
    private fun claim(module: XposedModule, view: View, adapter: Any) {
        if (adapterInstance === adapter) return
        if (!isDockRecycler(view)) return
        if (!prepareAdapter(module, adapter)) return
        adapterInstance = adapter
        adapterByView[view] = adapter
        // 提前把图标读出来 warm 住，避免第一次绑定时 PackageManager 取图造成延迟。
        preloadIcon(view.context)
        muteChangeAnimation(module, view)
        view.post { muteChangeAnimation(module, view) }
        log(module, "dock list claimed: ${adapter.javaClass.name}")
    }

    /** `RecyclerView$LayoutManager` 被 R8 改名了，按 LinearLayoutManager 的父类反查。 */
    private fun resolveLayoutManagerClass(loader: ClassLoader): Class<*>? {
        runCatching { Class.forName("androidx.recyclerview.widget.RecyclerView\$LayoutManager", false, loader) }
            .getOrNull()?.let { return it }
        val linear = runCatching { Class.forName(LINEAR_LAYOUT_MANAGER_CLASS, false, loader) }.getOrNull()
        return linear?.superclass?.takeIf {
            it.name != RECYCLER_VIEW_CLASS && it.name != "java.lang.Object"
        }
    }

    override fun onConfigChanged() {
        // 开关/选中的图标变了：用最近一次原生列表重新提交一次，
        // 触发 DiffUtil 重算（自造条目永远"不相同"，必然重新绑定）。
        val adapter = adapterInstance ?: return
        val method = submitMethod ?: return
        val list = nativeList
        if (list.isEmpty()) return
        runCatching { method.isAccessible = true; method.invoke(adapter, list, true) }
    }

    // ── adapter ───────────────────────────────────────────────────────────────

    /** @return 是否成功接管（找到提交入口并装上钩子）。 */
    private fun prepareAdapter(module: XposedModule, adapter: Any): Boolean {
        val type = adapter.javaClass
        if (preparedAdapterClass == type) return true
        val submit = allMethods(type).firstOrNull { method ->
            !method.isSynthetic && !Modifier.isPrivate(method.modifiers) &&
                method.parameterCount == 2 &&
                List::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (submit == null) {
            // 侧边栏窗口里还有 n9.e / com.miui.dock.allapps.b 这类没有两参提交方法的列表，
            // 它们只是被 isDockRecycler 误判进来的；每个类只提示一次，别刷屏。
            if (rejectedClasses.add(type.name)) {
                logWarn(module, "not the dock list: ${type.name} (no submitList(List, boolean))")
            }
            return false
        }
        val holderType = holderClass
        if (holderType == null) {
            logWarn(module, "RecyclerView\$c0 unavailable; bind hook disabled")
        }
        // onBindViewHolder 可能声明在父类（ListAdapter 之类），要沿继承链找。
        val bind = holderType?.let { holder ->
            allMethods(type).firstOrNull { method ->
                !Modifier.isAbstract(method.modifiers) && !method.isSynthetic &&
                    method.returnType == Void.TYPE && method.parameterCount == 2 &&
                    holder.isAssignableFrom(method.parameterTypes[0]) &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
            }
        }
        submitMethod = submit
        bindMethod = bind
        preparedAdapterClass = type
        log(module, "dock adapter=${type.name} submit=${submit.name} bind=${bind?.name}")

        submit.isAccessible = true
        runCatching {
            module.hook(submit).intercept { chain ->
                // 同类的其它 adapter 实例（非侧边栏）必须放行。
                if (chain.thisObject !== adapterInstance) return@intercept chain.proceed()
                val raw = chain.args.getOrNull(0) as? List<Any>
                    ?: return@intercept chain.proceed()
                val flag = chain.args.getOrNull(1) ?: false
                // 宿主有可能把它上次拿到的列表再传回来（里面带着我们注入过的条目），
                // 不先剔除就会一次比一次多，表现为「分割线上方出现两个设定图标」。
                val list = strip(raw)
                nativeList = list
                learn(list, module)
                // 宿主会在「自己那份列表」和「我们上次交回去的列表」之间反复提交，
                // 内容其实一模一样。若每次都 new 一个 ArrayList，实例不同，
                // ListAdapter 的"同一实例直接返回"短路就失效，于是每秒都要跑一遍
                // DiffUtil 并重绑槽位（表现为图标忽有忽无）。内容相同就复用旧实例。
                val id = SidebarQuickSlotConfig.current()
                if (sameList(lastStripped, list) && lastSlotId == id &&
                    lastTwoColumns == twoColumnsEnabled() && lastInjected.isNotEmpty()
                ) {
                    SidebarDockState.displayList = lastInjected
                    log(module, "submit unchanged (raw=${raw.size}); reuse previous instance")
                    return@intercept chain.proceed(arrayOf<Any?>(lastInjected, flag))
                }
                lastStripped = list
                lastSlotId = id
                lastTwoColumns = twoColumnsEnabled()
                val injected = inject(module, list)
                lastInjected = injected
                SidebarDockState.displayList = injected
                log(
                    module,
                    "submit raw=${raw.size} stripped=${raw.size - list.size} out=${injected.size} " +
                        "slot=${injected.indexOfFirst { it === slotItem }} id=${SidebarQuickSlotConfig.current()}",
                )
                runCatching { chain.proceed(arrayOf<Any?>(injected, flag)) }
                    .getOrElse { chain.proceed() }
            }
        }.onFailure { logWarn(module, "dock submitList hook failed: ${it.message}") }

        bind?.let { method ->
            method.isAccessible = true
            runCatching {
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (chain.thisObject !== adapterInstance) return@intercept result
                    val holder = chain.args.getOrNull(0) ?: return@intercept result
                    val position = chain.args.getOrNull(1) as? Int ?: return@intercept result
                    afterBind(module, holder, position)
                    result
                }
            }.onFailure { logWarn(module, "dock bind hook failed: ${it.message}") }
        }

        // 诊断用：找出宿主可能存在的其它列表提交入口。**只读不改**，
        // 确认之后再决定是否接管，避免误伤别的方法。
        allMethods(type).filter { candidate ->
            candidate !== submit && candidate !== bind &&
                !candidate.isSynthetic && !Modifier.isPrivate(candidate.modifiers) &&
                !Modifier.isAbstract(candidate.modifiers) &&
                candidate.parameterCount in 1..2 &&
                candidate.parameterTypes.any { List::class.java.isAssignableFrom(it) }
        }.forEach { candidate ->
            candidate.isAccessible = true
            runCatching {
                module.hook(candidate).intercept { chain ->
                    if (chain.thisObject === adapterInstance) {
                        val index = candidate.parameterTypes.indexOfFirst { List::class.java.isAssignableFrom(it) }
                        val passed = chain.args.getOrNull(index) as? List<Any>
                        if (passed != null && isDockList(passed) && passed.size != SidebarDockState.displayList.size) {
                            logWarn(
                                module,
                                "unhooked submit path ${candidate.name}(${candidate.parameterTypes.joinToString()}) " +
                                    "size=${passed.size} known=${SidebarDockState.displayList.size}",
                            )
                        }
                    }
                    chain.proceed()
                }
            }
        }
        return true
    }

    /** [passed] 看起来像不像是侧边栏的条目列表（用于诊断其它提交入口）。 */
    private fun isDockList(passed: List<Any>): Boolean {
        if (passed.isEmpty()) return false
        val iface = itemInterface ?: return false
        if (!iface.isInstance(passed.first())) return false
        val known = nativeList
        if (known.isEmpty()) return true
        val overlap = known.count { item -> passed.any { it === item } }
        return overlap >= minOf(2, known.size)
    }

    private fun allInterfaces(type: Class<*>?): Sequence<Class<*>> = sequence {
        var current = type
        while (current != null && current.name != "java.lang.Object") {
            current.interfaces.forEach { yield(it) }
            current = current.superclass
        }
    }

    private fun allMethods(type: Class<*>): Sequence<Method> = sequence {
        var current: Class<*>? = type
        while (current != null && current.name != "java.lang.Object") {
            current.declaredMethods.forEach { yield(it) }
            current = current.superclass
        }
    }

    /** 从原生列表里认出条目接口、速记类、分割线类。 */
    private fun learn(list: List<Any>, module: XposedModule) {
        if (itemInterface == null && list.isNotEmpty()) {
            val holder = holderClass
            // 取「被最多条目实现」的接口：只看 list[0] 会撞上速记/分割线各自的专属接口。
            val counts = LinkedHashMap<Class<*>, Int>()
            list.forEach { item ->
                allInterfaces(item.javaClass).forEach { iface ->
                    if (iface.methods.any { method ->
                            method.parameterCount == 1 &&
                                holder?.isAssignableFrom(method.parameterTypes[0]) == true
                        }
                    ) {
                        counts[iface] = (counts[iface] ?: 0) + 1
                    }
                }
            }
            itemInterface = counts.entries.maxByOrNull { it.value }?.key
            itemInterface?.let { iface ->
                log(module, "item interface=${iface.name} (${counts[iface]}/${list.size})")
                adapterInstance?.let { installClickHook(module, it.javaClass, iface) }
            } ?: logWarn(module, "item interface not found; slot click may not dispatch")
        }
        // 原生顺序 [速记][分割线][应用…]：三个类互不相同即可确认。
        val slotType = slotItem?.javaClass
        if (SidebarDockState.dividerClass == null && list.size >= 3) {
            val a = list[0].javaClass
            val b = list[1].javaClass
            val c = list[2].javaClass
            // 自造条目不能被认成速记/分割线，否则 SpanSizeLookup 会算错占格。
            if (a != b && b != c && a != slotType && b != slotType && c != slotType) {
                SidebarDockState.shorthandClass = a
                SidebarDockState.dividerClass = b
                log(module, "shorthand=${a.name} divider=${b.name}")
            }
        }
    }

    /** 把上次注入的条目从宿主传回来的列表里剔除，保证每次最多只有一个槽位。 */
    private fun strip(list: List<Any>): List<Any> {
        val existing = slotItem ?: return list
        if (list.none { it === existing }) return list
        return ArrayList<Any>(list.filter { it !== existing })
    }

    /** 逐位比较引用：内容一致说明这次提交不会改变行结构，可以复用上次的列表实例。 */
    private fun sameList(a: List<Any>, b: List<Any>): Boolean {
        if (a.size != b.size) return false
        for (index in a.indices) {
            if (a[index] !== b[index]) return false
        }
        return true
    }

    /** 上一次剥离/注入的结果，用于复用实例（见提交拦截器里的说明）。 */
    @Volatile private var lastStripped: List<Any> = emptyList()
    @Volatile private var lastInjected: List<Any> = emptyList()
    @Volatile private var lastSlotId: String = ""
    @Volatile private var lastTwoColumns = false

    private fun inject(module: XposedModule, list: List<Any>): List<Any> {
        val id = SidebarQuickSlotConfig.current()
        if (id.isBlank() || !twoColumnsEnabled()) {
            SidebarDockState.slotItem = null
            return list
        }
        val item = slotItem ?: createSlotItem(module) ?: return list
        SidebarDockState.slotItem = item
        val shorthand = SidebarDockState.shorthandClass
        val index = if (shorthand != null) {
            (list.indexOfFirst { it.javaClass == shorthand } + 1).takeIf { it > 0 } ?: 0
        } else {
            0
        }
        val out = ArrayList<Any>(list.size + 1)
        out.addAll(list)
        out.add(index.coerceIn(0, out.size), item)
        log(module, "slot injected at $index id=$id size=${out.size}")
        return out
    }

    private fun twoColumnsEnabled(): Boolean {
        if (!ConfigManager.getBoolean(PrefKeys.SIDEBAR_TWO_COLUMNS, false)) return false
        if (ConfigManager.getBoolean(PrefKeys.SIDEBAR_EXPAND_ALL_APPS, false)) return false
        return true
    }

    // ── 自造条目 ───────────────────────────────────────────────────────────────

    /**
     * 用动态代理实现宿主的条目接口。宿主对未知条目类型只做 `instanceof`
     * （getItemViewType 返回普通类型、DiffUtil 判定为"不同"、点击分发由本 Hook
     * 接管），因此不需要构造 `e8.c` 那套真实模型。
     */
    private fun createSlotItem(module: XposedModule): Any? {
        val iface = itemInterface ?: return null
        val loader = iface.classLoader ?: return null
        val ref = arrayOfNulls<Any>(1)
        val handler = InvocationHandler { _, method, args ->
            when {
                method.name == "e" && method.parameterCount == 1 -> {
                    bindSlot(module, args?.firstOrNull())
                    null
                }
                method.name == "c" && method.parameterCount == 1 -> null
                method.name == "equals" -> ref[0] === args?.firstOrNull()
                method.name == "hashCode" -> System.identityHashCode(ref[0])
                method.name == "toString" -> "hyperdock::quickslot"
                else -> defaultValue(method.returnType)
            }
        }
        val proxy = runCatching { Proxy.newProxyInstance(loader, arrayOf<Class<*>>(iface), handler) }
            .onFailure { logWarn(module, "slot proxy failed: ${it.message}") }
            .getOrNull() ?: return null
        ref[0] = proxy
        slotItem = proxy
        log(module, "slot item created via ${iface.name}")
        return proxy
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }

    private fun bindSlot(module: XposedModule, holder: Any?) {
        val itemView = findItemView(holder) ?: return
        val context = itemView.context ?: return
        resolveResources(context)
        val id = SidebarQuickSlotConfig.current()
        applySlotAppearance(itemView, context, id)
        // 宿主在 bind 之后还可能改写这些 view（自己的图标/可见性逻辑），并给图标装点击
        // 监听，因此延后一帧再整体覆盖一次，保证显示内容和点击都归我们。
        itemView.post {
            applySlotAppearance(itemView, context, id)
            val listener = View.OnClickListener { launch(context, id, module) }
            itemView.setOnClickListener(listener)
            itemView.findViewById<View>(iconResId)?.setOnClickListener(listener)
        }
        val position = SidebarDockState.displayList.indexOfFirst { it === slotItem }
        log(module, "slot bound pos=$position id=$id icon=${itemView.findViewById<ImageView>(iconResId)?.drawable != null}")
    }

    /** 把这一行改成"一个图标"的样式：隐藏分割线和占位图，显示并设置图标。 */
    private fun applySlotAppearance(itemView: View, context: Context, id: String) {
        itemView.visibility = View.VISIBLE
        itemView.findViewById<View>(dividerResId)?.visibility = View.GONE
        itemView.findViewById<View>(placeholderResId)?.visibility = View.GONE
        val icon = itemView.findViewById<View>(iconResId) as? ImageView ?: return
        icon.visibility = View.VISIBLE
        icon.setImageDrawable(slotIcon(context, id))
    }

    /** 取槽位图标（带缓存）。首次加载可能要走 PackageManager / 资源，之后即时返回。 */
    private fun slotIcon(context: Context, id: String): Drawable? {
        if (id.isBlank()) return null
        val cached = cachedIcon
        if (cached != null && cachedIconId == id) return cached
        val loaded = runCatching { SidebarShortcutController.slotIcon(context, id) }.getOrNull()
        if (loaded != null) {
            cachedIconId = id
            cachedIcon = loaded
        }
        return loaded
    }

    /** 预热图标：接管 adapter 时先异步加载一次，等真正绑定就已经在缓存里了。 */
    private fun preloadIcon(context: Context?) {
        val ctx = context ?: return
        val id = SidebarQuickSlotConfig.current()
        if (id.isBlank() || (cachedIcon != null && cachedIconId == id)) return
        Thread {
            runCatching { slotIcon(ctx.applicationContext, id) }
        }.start()
    }

    private fun launch(context: Context, id: String, module: XposedModule) {
        if (id.isBlank()) return
        val handled = runCatching { SidebarShortcutController.launchById(context, id) }.getOrDefault(false)
        log(module, "slot clicked id=$id handled=$handled")
    }

    // ── 分割线加长 ────────────────────────────────────────────────────────────

    /**
     * 绑定完成后的收尾：给分割线那一行加宽。
     *
     * 位置由 [SidebarDockState.isDividerPosition] 决定，**不能**按"分割线 View 可见"
     * 来判断属于哪一类 —— ViewHolder 是复用的，回收之后再绑到应用行时那条线可能
     * 还是 VISIBLE（宿主只在绑定分割线条目时 show，从不主动 hide），照可见性判断
     * 就会把应用行当成分割线，最后变成"分割线下面第一个应用独占一行居中"。
     */
    private fun afterBind(module: XposedModule, holder: Any, position: Int) {
        if (!SidebarDockState.isDividerPosition(position)) return
        val itemView = findItemView(holder) ?: return
        val context = itemView.context ?: return
        resolveResources(context)
        val divider = itemView.findViewById<View>(dividerResId) ?: return
        divider.visibility = View.VISIBLE
        applyDividerWidth(divider, context)
        log(module, "divider bound at $position width=${divider.layoutParams?.width}")
    }

    private fun applyDividerWidth(divider: View, context: Context) {
        val params = divider.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val twoColumns = SidebarColumnsHook.currentColumns() > 1
        val wantedWidth = if (twoColumns) ViewGroup.LayoutParams.MATCH_PARENT else dividerWidthPx
        val wantedMargin = if (twoColumns) paddingPx else 0
        if (params.width == wantedWidth && params.leftMargin == wantedMargin && params.rightMargin == wantedMargin) {
            return
        }
        params.width = wantedWidth
        params.leftMargin = wantedMargin
        params.rightMargin = wantedMargin
        params.marginStart = wantedMargin
        params.marginEnd = wantedMargin
        runCatching { divider.layoutParams = params }
    }

    private fun resubmit() {
        val adapter = adapterInstance ?: return
        val method = submitMethod ?: return
        val list = nativeList
        if (list.isEmpty()) return
        runCatching { method.isAccessible = true; method.invoke(adapter, list, true) }
    }

    // ── 点击分发（兜底：宿主监听器没被我们覆盖时） ───────────────────────────

    private fun installClickHook(module: XposedModule, type: Class<*>, iface: Class<*>) {
        if (clickMethod?.declaringClass == type) return
        val method = allMethods(type).firstOrNull { candidate ->
            !Modifier.isAbstract(candidate.modifiers) && !candidate.isSynthetic &&
                candidate.parameterCount == 3 &&
                candidate.parameterTypes[0] == iface &&
                View::class.java.isAssignableFrom(candidate.parameterTypes[2])
        } ?: return
        clickMethod = method
        method.isAccessible = true
        runCatching {
            module.hook(method).intercept { chain ->
                if (chain.args.getOrNull(0) === slotItem) {
                    val context = (chain.args.getOrNull(2) as? View)?.context
                    if (context != null) launch(context, SidebarQuickSlotConfig.current(), module)
                    return@intercept null
                }
                chain.proceed()
            }
        }.onFailure { logWarn(module, "dock click hook failed: ${it.message}") }
        log(module, "dock click dispatch hooked: ${method.name}")
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /**
     * 该 RecyclerView 是否侧边栏列表。
     *
     * 主判据是父链上有 TurboLayout；备用判据是祖先里有 id 名带 `sidebar` 的 View
     * （`sidebar_panel_container` 等），避免宿主哪天换了容器类名就整个失效。
     */
    private fun isDockRecycler(view: View): Boolean {
        val turbo = turboClass
        var parent = view.parent
        var depth = 0
        while (parent is View && depth++ < MAX_PARENT_DEPTH) {
            if (turbo?.isInstance(parent) == true) return true
            // 只在 TurboLayout 类找不到的情况下才退而求其次：id 名带 sidebar 的祖先。
            // 侧边栏窗口里还有 n9.e、全部应用列表等，它们同样满足这个宽松条件，
            // TurboLayout 可用时不能再靠它，否则会把这些列表一起认成侧边栏。
            if (turbo == null) {
                val name = runCatching { parent.resources?.getResourceEntryName(parent.id) }.getOrNull()
                if (name != null && name.contains("sidebar")) return true
            }
            parent = parent.parent
        }
        return false
    }

    /**
     * 取 ViewHolder 的 itemView。
     *
     * 必须**按名字**取：宿主 holder（`z7/f$f`）自己就声明了图标、占位图、
     * 分割线三个 View 字段，按类型猜会先撞上分割线。
     */
    private fun findItemView(holder: Any?): View? {
        val target = holder ?: return null
        val field = generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { runCatching { it.declaredFields.asSequence() }.getOrDefault(emptySequence()) }
            .firstOrNull { candidate -> candidate.name == "itemView" && View::class.java.isAssignableFrom(candidate.type) }
            ?: return null
        return runCatching {
            field.isAccessible = true
            field.get(target) as? View
        }.getOrNull()
    }

    private fun resolveResources(context: Context) {
        if (resResolved) return
        val packageName = context.packageName
        val resources = context.resources ?: return
        fun id(name: String): Int =
            runCatching { resources.getIdentifier(name, "id", packageName) }.getOrDefault(0)
        fun dimen(name: String): Int {
            val resId = runCatching { resources.getIdentifier(name, "dimen", packageName) }.getOrDefault(0)
            if (resId == 0) return 0
            return runCatching { resources.getDimensionPixelSize(resId) }.getOrDefault(0)
        }
        dividerResId = id(ID_DIVIDER)
        iconResId = id(ID_ICON)
        placeholderResId = id(ID_PLACEHOLDER)
        dividerWidthPx = dimen(DIMEN_DIVIDER_WIDTH)
        paddingPx = dimen(DIMEN_ITEM_PADDING)
        resResolved = dividerResId != 0 && iconResId != 0
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
