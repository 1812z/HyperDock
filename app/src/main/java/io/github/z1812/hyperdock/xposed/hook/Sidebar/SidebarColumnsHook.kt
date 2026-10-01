package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import io.github.z1812.hyperdock.xposed.LogUtil as Log
import android.view.View
import android.view.ViewGroup
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt
import org.luckypray.dexkit.DexKitBridge

/**
 * 侧边栏两列。
 *
 * 原生侧边栏是一条竖直图标条：`TurboLayout` 下的 DockLayout 里放一个
 * `FadingEdgeRecyclerView`，LayoutManager 是 `LinearLayoutManager`，宽度固定为
 * `dock_panel_width`（72dp，= 1 个 48dp 图标 + 左右各 12dp 留白）。
 *
 * 本 Hook 做两件事：
 * 1. 把该 RecyclerView 的 LayoutManager 换成 `GridLayoutManager(context, 列数)`；
 * 2. 同步把侧边栏面板加宽到「列数 × dock_item_edge + (列数 + 1) × dock_item_padding」
 *    —— 两列时约 132dp，单列时正好等于原生 72dp。TurboLayout 里所有与该宽度
 *    同宽的兄弟 View（占位背景 abstract dock）一起加宽，否则图标会溢出背景；
 *    高度改为 WRAP_CONTENT，让面板随行数收缩（6 个图标从 6 行变 3 行）。
 *
 * 与「自动展开面板」互斥，且**只在竖屏**让位：全部应用面板展开时列数强制回到 1，
 * 面板关闭后恢复；横屏横向空间充足，保持两列不动。
 * 判据是 DockLayout 的 `setBottomIconSelected(boolean)` —— 原生只在展开/收起
 * 全部应用面板时用它切换底部图标的选中态（模块自身的自动展开也走这个方法）。
 *
 * 宿主是重度混淆 + R8 裁剪的：
 * - `GridLayoutManager` 类与 `(Context, int)` 构造还在，但 `getSpanCount()` /
 *   `setSpanCount()` 已被裁掉，反射不能按名字硬取，必须全部失败容忍，
 *   列数由本 Hook 自己记账（[appliedColumns]）。
 * - 主挂载点是 `RecyclerView.setLayoutManager`：按「父链上有 TurboLayout」
 *   判定是否侧边栏列表，不依赖任何宿主字段名。
 * - `TurboLayout` 与 `RecyclerView` 名字稳定（前者被布局 XML 引用、后者是三方库）。
 */
object SidebarColumnsHook : BaseHook() {
    private const val TAG = "HyperDock[SidebarColumns]"

    const val PREF_ENABLED = PrefKeys.SIDEBAR_TWO_COLUMNS

    /** 两列模式下的列数；1 表示保持原生。 */
    private const val COLUMN_COUNT = 2

    private const val TURBO_LAYOUT_CLASS = "com.miui.gamebooster.windowmanager.newbox.TurboLayout"
    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    private const val LAYOUT_MANAGER_CLASS = "androidx.recyclerview.widget.RecyclerView\$LayoutManager"
    private const val GRID_LAYOUT_MANAGER_CLASS = "androidx.recyclerview.widget.GridLayoutManager"
    private const val LINEAR_LAYOUT_MANAGER_CLASS = "androidx.recyclerview.widget.LinearLayoutManager"

    /** 兜底 dp 值：与原生 dock_item_edge / dock_item_padding 一致。 */
    private const val FALLBACK_ITEM_EDGE_DP = 48f
    private const val FALLBACK_ITEM_PADDING_DP = 12f

    /** 侧边栏收起/展开动画最长约 300ms，补一次收敛避开原生延后的写回。 */
    private const val CONVERGE_DELAY_MS = 400L

    /** 向上找 TurboLayout 的最大层数，防止异常层级下无限遍历。 */
    private const val MAX_PARENT_DEPTH = 16

    @Volatile private var turboClass: Class<*>? = null
    @Volatile private var gridClass: Class<*>? = null
    @Volatile private var linearClass: Class<*>? = null
    @Volatile private var gridCtor: Constructor<*>? = null
    @Volatile private var linearCtor: Constructor<*>? = null
    @Volatile private var setLayoutManagerMethod: Method? = null
    @Volatile private var getLayoutManagerMethod: Method? = null
    @Volatile private var turboWidthField: Field? = null
    @Volatile private var turboWidthFieldSearched = false

    /**
     * 每个 dock 实例自己的原生单列宽度；拿不到实例值时等待布局后重试。
     *
     * 只记进程级的一份是不够的：横竖屏切换后宿主重建 dock，原生宽度可能不同，
     * 认旧值会让「旋转后第一次呼出」时算出来的 base 与实际布局对不上，
     * 宽度改不动（下面的兄弟节点遍历会整片跳过）而列数照切两列 —— 挤成一团。
     */
    private val nativeDockWidths = Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** 我们写给每个 View 的宽度，用来把"自己改过的"和"原生的"区分开。 */
    private val appliedWidths = Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** 每个侧边栏实例在原生状态下的高度，用于切回单列时还原。 */
    private val nativeHeights = Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** 侧边栏列表（RecyclerView）在原生状态下的高度，同上。 */
    private val nativeListHeights = Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** 每个侧边栏列表当前生效的列数（宿主侧 getSpanCount 已被裁剪，只能自己记账）。 */
    private val appliedColumns = Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** 已知的 DockLayout 实例，配置变化时用来重放。 */
    private val appliedDocks = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 自己调用 setLayoutManager 期间置位，避免被自己的 hook 再拦截一次。 */
    private val selfSetting = ThreadLocal<Boolean>()

    /** 全部应用面板是否处于展开态。 */
    @Volatile private var allAppsPanelOpen = false

    /** 仅用于当前 dock 的工具箱避让；弱引用避免面板的 parent 反向保活窗口。 */
    private val boxPanels = Collections.synchronizedMap(WeakHashMap<View, WeakReference<View>>())

    /** 当前是否横屏；横屏横向空间充足，不需要为 box 面板让位。 */
    @Volatile private var landscape = false

    /** SpanSizeLookup（`GridLayoutManager$c`）的默认实现，以及它的 `getSpanSize` 钩子状态。 */
    @Volatile private var spanLookupInstalled = false
    private val spanLookupColumns = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) return

        val loader = runCatching { param.defaultClassLoader }.getOrNull()
        if (loader == null) {
            logWarn(module, "no class loader; two-column sidebar disabled")
            return
        }
        val turbo = runCatching { Class.forName(TURBO_LAYOUT_CLASS, false, loader) }.getOrNull()
        if (turbo == null) {
            logWarn(module, "TurboLayout unavailable; two-column sidebar disabled")
            return
        }
        turboClass = turbo

        gridClass = runCatching { Class.forName(GRID_LAYOUT_MANAGER_CLASS, false, loader) }.getOrNull()
        linearClass = runCatching { Class.forName(LINEAR_LAYOUT_MANAGER_CLASS, false, loader) }.getOrNull()
        gridCtor = runCatching { gridClass?.getConstructor(Context::class.java, Int::class.javaPrimitiveType) }
            .getOrNull()?.also { it.isAccessible = true }
        linearCtor = runCatching { linearClass?.getConstructor(Context::class.java) }
            .getOrNull()?.also { it.isAccessible = true }
        if (gridCtor == null) {
            logWarn(module, "GridLayoutManager constructor unavailable; two-column sidebar disabled")
            return
        }
        log(module, "grid=${gridClass?.name} ctor=${gridCtor != null} linear=${linearClass != null}")

        hookRecyclerView(module, loader)
        hookBoxPanel(module, turbo)
        hookRotation(module, turbo)
        findDockLayoutClass(turbo)?.let { dock ->
            log(module, "dock=${dock.name}")
            hookDockConstructors(module, dock)
            hookBottomIconSelected(module, dock)
        } ?: logWarn(module, "dock layout class unavailable; re-apply hooks limited")

        // 侧边栏每次开合都重新落一次，避免原生在别处重建布局参数后丢失修改。
        SidebarExpandDexDiscovery.findOpenMethod(loader)?.let { open ->
            open.isAccessible = true
            runCatching {
            module.hook(open).intercept { chain ->
                val result = chain.proceed()
                runCatching { findDockLayout(chain.args.firstOrNull(), turbo) }
                    .getOrNull()?.let { apply(module, it, converge = true) }
                result
            }
            }.onFailure { logWarn(module, "sidebar open hook failed: ${it.message}") }
        }
        log(module, "two-column sidebar hooks installed")
    }

    override fun onConfigChanged() {
        val module = ConfigManager.module() ?: return
        appliedDocks.toList().forEach { apply(module, it, converge = false) }
    }

    // ── 安装 ──────────────────────────────────────────────────────────────────

    /**
     * 主挂载点：`RecyclerView.setLayoutManager`。宿主在 DockLayout 里给侧边栏列表
     * 设置 `LinearLayoutManager` 时会经过这里，直接替换成 Grid 即可，
     * 不依赖任何宿主字段名。判定条件只是「父链上有 TurboLayout」。
     */
    private fun hookRecyclerView(module: XposedModule, loader: ClassLoader) {
        val rvClass = runCatching { Class.forName(RECYCLER_VIEW_CLASS, false, loader) }.getOrNull()
        if (rvClass == null) {
            logWarn(module, "RecyclerView unavailable; two-column sidebar disabled")
            return
        }
        val lmClass = resolveLayoutManagerClass(loader)
        if (lmClass == null) {
            logWarn(module, "LayoutManager base class unavailable; two-column sidebar disabled")
            return
        }
        // 方法名在 androidx 上是稳定的，但签名里的参数类型可能被 R8 改名，
        // 因此按「唯一一个 void(LayoutManager)」兜底，不依赖名字。
        val setLm = runCatching { rvClass.getMethod("setLayoutManager", lmClass) }.getOrNull()
            ?: rvClass.methods.firstOrNull { candidate ->
                candidate.returnType == Void.TYPE && candidate.parameterCount == 1 &&
                    candidate.parameterTypes[0] == lmClass
            }
        if (setLm == null) {
            logWarn(module, "setLayoutManager unavailable; two-column sidebar disabled")
            return
        }
        setLayoutManagerMethod = setLm
        getLayoutManagerMethod = runCatching { rvClass.getMethod("getLayoutManager") }.getOrNull()
            ?: rvClass.methods.firstOrNull { candidate ->
                candidate.parameterCount == 0 && candidate.returnType == lmClass
            }
        getLayoutManagerMethod?.isAccessible = true
        log(module, "rv=${rvClass.name} lm=${lmClass.name} set=${setLm.name}")
        setLm.isAccessible = true
        runCatching {
            module.hook(setLm).intercept { chain ->
                val self = chain.thisObject as? View
                if (selfSetting.get() == true || self == null || !isDockRecycler(self)) {
                    return@intercept chain.proceed()
                }
                // 横竖屏直接决定要不要让位，进决策前必须先刷新（这里是主挂载点，
                // 旋转后第一次 setLayoutManager 不能拿上一次的旧值）。
                refreshOrientation(self)
                val dock = dockOf(self)
                val wanted = desiredColumns(dock ?: self)
                // 拿不到 dock、或宽度还落不下去时不能上两列：列表会被塞进
                // 单列宽的面板里（旋转后第一次呼出最常见的就是这个）。
                // 这里放原生走完，apply() 的收敛会把宽度落稳后再切两列。
                val widthReady = dock != null && canWiden(dock) && applyPanelSize(module, dock, self, wanted)
                if (wanted > 1 && widthReady && dock != null) {
                    val grid = buildGrid(self.context, wanted)
                    if (grid != null) {
                        installSpanLookup(module, grid, wanted)
                        val result = runCatching { chain.proceed(arrayOf<Any?>(grid)) }
                            .getOrElse {
                                appliedColumns.remove(self)
                                val nativeResult = chain.proceed()
                                apply(module, dock, converge = true)
                                return@intercept nativeResult
                            }
                        appliedColumns[self] = wanted
                        appliedDocks.add(dock)
                        // 原生可能延后写回布局参数，再补一次完整 apply 收敛宽度与列数。
                        dock.postDelayed({ apply(module, dock, converge = false) }, CONVERGE_DELAY_MS)
                        log(module, "grid applied columns=$wanted on ${self.javaClass.simpleName}")
                        return@intercept result
                    }
                    logWarn(module, "grid manager build failed; keeping native layout")
                } else if (wanted > 1) {
                    log(module, "grid deferred columns=$wanted dock=${dock != null}")
                }
                val columns = if (wanted > 1) 1 else wanted
                // 宿主重新装回 LinearLayoutManager 后，旧的「已是两列」记账失效。
                appliedColumns.remove(self)
                val result = chain.proceed()
                if (dock != null) {
                    appliedDocks.add(dock)
                    apply(module, dock, converge = true)
                } else if (columns <= 1) {
                    applyColumnCount(module, self, columns)
                }
                result
            }
        }.onFailure { logWarn(module, "setLayoutManager hook failed: ${it.message}") }
    }

    /**
     * 视频工具箱 / 游戏工具箱出现时回退单列。
     *
     * TurboLayout 里 dock 布局和「box 面板」是横向排列的：box 用 WRAP_CONTENT 宽 +
     * `addRule(RIGHT_OF, dockLayout)` 贴在 dock 右边，自身宽度写死
     * （视频工具箱 `vtb_pannel_width_new` = 289dp、游戏工具箱 `gb_gamebox_width_*`），
     * 不会跟着 dock 收缩。两列把 dock 从 72dp 加宽到 132dp 后，box 直接被挤出屏幕，
     * 表现为「视频工具箱显示不完全」。与其逐个面板改宽度，不如在 box 出现时退回单列，
     * 让 TurboLayout 回到原生布局。
     *
     * 两个挂载点：
     * - DexKit 查找调用 `getTargetBox()` 的无参加载入口，通过 `getBoxView()` 取面板；
     * - 按 `(View, FrameLayout.LayoutParams)` 唯一签名定位游戏工具箱添加入口。
     */
    private fun hookBoxPanel(module: XposedModule, turbo: Class<*>) {
        val boxGetter = runCatching { turbo.getMethod("getBoxView") }.getOrNull()
        val loadBox = runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(turbo.classLoader, false).use { bridge ->
                bridge.getClassData(turbo)?.findMethod {
                    matcher {
                        returnType("void")
                        paramTypes()
                        invokeMethods {
                            add {
                                declaredClass(turbo.name)
                                name("getTargetBox")
                                returnType("android.view.View")
                                paramTypes()
                            }
                        }
                    }
                }?.mapNotNull { runCatching { it.getMethodInstance(turbo.classLoader) }.getOrNull() }
                    ?.singleOrNull { !it.isSynthetic }
            }
        }.getOrNull()
        val frameParams = runCatching {
            Class.forName("android.widget.FrameLayout\$LayoutParams", false, turbo.classLoader)
        }.getOrNull()
        val addBox = frameParams?.let {
            turbo.declaredMethods.singleOrNull { method ->
                method.returnType == Void.TYPE && !method.isSynthetic &&
                    method.parameterTypes.contentEquals(arrayOf(View::class.java, it))
            }
        }
        if (loadBox == null && addBox == null) {
            logWarn(module, "box panel entry points unavailable; toolbox fallback disabled")
            return
        }
        listOfNotNull(loadBox, addBox).forEach { method ->
            method.isAccessible = true
            runCatching {
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val self = chain.thisObject as? View ?: return@runCatching
                        refreshOrientation(self)
                        val box = if (method === addBox) {
                            chain.args.firstOrNull() as? View
                        } else {
                            boxGetter?.invoke(self) as? View
                        } ?: return@runCatching
                        val isNew = boxPanels.put(self, WeakReference(box))?.get() !== box
                        if (isNew) {
                            log(
                                module,
                                "box panel ${box.javaClass.simpleName} via ${method.name}(); fallback=" +
                                    if (landscape) "none(landscape)" else "single column"
                            )
                        }
                        dockLayoutOf(self, turbo)?.let { apply(module, it, converge = true) }
                    }.onFailure { logWarn(module, "box panel apply failed: ${it.message}") }
                    result
                }
            }.onFailure { logWarn(module, "box panel hook failed(${method.name}): ${it.message}") }
        }
        log(module, "box panel hooks installed load=${loadBox != null} add=${addBox != null}")
    }

    private fun hasBoxPanel(dock: View): Boolean {
        val turbo = turboOf(dock) ?: return false
        val box = boxPanels[turbo]?.get() ?: return false
        var current: View? = box
        while (current != null) {
            if (!current.isAttachedToWindow || current.visibility != View.VISIBLE ||
                current.windowVisibility != View.VISIBLE || current.alpha <= 0.01f
            ) return false
            if (current === turbo) return box.isShown
            current = current.parent as? View
        }
        return false
    }

    /**
     * 旋转后重新决策列数。
     *
     * 「竖屏开面板 → 转到横屏」这条路径上，宿主不一定会重新 `setLayoutManager`，
     * 那样面板会一直停在单列。这里补一个刷新点：宿主没重写
     * `onConfigurationChanged` 就整段跳过，靠重新布局兜底。
     */
    private fun hookRotation(module: XposedModule, turbo: Class<*>) {
        val method = runCatching { turbo.getDeclaredMethod("onConfigurationChanged", Configuration::class.java) }
            .getOrNull()
            ?: runCatching { turbo.getMethod("onConfigurationChanged", Configuration::class.java) }.getOrNull()
        if (method == null) {
            log(module, "no onConfigurationChanged on TurboLayout; rotation relies on relayout")
            return
        }
        runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val self = chain.thisObject as? View ?: return@runCatching
                    refreshOrientation(self)
                    dockLayoutOf(self, turbo)?.let { apply(module, it, converge = true) }
                    log(module, "rotation applied landscape=$landscape panel=$allAppsPanelOpen")
                }.onFailure { logWarn(module, "rotation apply failed: ${it.message}") }
                result
            }
        }.onFailure { logWarn(module, "onConfigurationChanged hook failed: ${it.message}") }
        log(module, "rotation hook installed")
    }

    private fun dockLayoutOf(turboView: View, turbo: Class<*>): View? =
        runCatching { turbo.getMethod("getDockLayout").invoke(turboView) }.getOrNull() as? View

    private fun hookDockConstructors(module: XposedModule, dock: Class<*>) {
        dock.declaredConstructors.forEach { constructor ->
            runCatching {
                constructor.isAccessible = true
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    val self = chain.thisObject as? View ?: return@intercept result
                    appliedDocks.add(self)
                    // View.post 在未 attach 时会排进 run queue，等到 addView 后执行，
                    // 那时 parent（TurboLayout）已经可用。
                    self.post { apply(module, self, converge = false) }
                    result
                }
            }.onFailure { logWarn(module, "dock constructor hook failed: ${it.message}") }
        }
    }

    /**
     * 底部「全部应用」图标的选中态回调，是原生唯一的面板开合信号：
     * true = 面板展开，false = 收起。
     */
    private fun hookBottomIconSelected(module: XposedModule, dock: Class<*>) {
        val method = findBottomIconSelectionMethod(dock, module)
        if (method == null) {
            logWarn(module, "bottom icon selection callback unavailable")
            return
        }
        method.isAccessible = true
        log(module, "hooking ${method.declaringClass.name}.${method.name}")
        runCatching {
            module.hook(method).intercept { chain ->
                val selected = chain.args.firstOrNull() as? Boolean ?: return@intercept chain.proceed()
                allAppsPanelOpen = selected
                val self = chain.thisObject as? View
                val result = chain.proceed()
                if (self != null) {
                    appliedDocks.add(self)
                    // 原生会在同一条流程里（例如 J() -> K()）再次写回高度，
                    // 因此除了立即应用，再补一次延迟应用把状态收敛回来。
                    apply(module, self, converge = true)
                    self.postDelayed({ apply(module, self, converge = false) }, CONVERGE_DELAY_MS)
                }
                log(module, "all-apps panel open=$selected")
                result
            }
        }.onFailure { logWarn(module, "bottom icon selection hook failed: ${it.message}") }
    }

    private fun findBottomIconSelectionMethod(dock: Class<*>): Method? {
        // 名字稳定：`SidebarDefaultExpandHook` 也用它同步展开态。
        runCatching {
            dock.getMethod("setBottomIconSelected", Boolean::class.javaPrimitiveType)
        }.getOrNull()?.let { return it }
        // 兜底：宿主自有层次里唯一的 void(boolean)。
        // android.view.* 自带一堆同签名方法，因此遍历到框架类就停。
        val candidates = generateSequence(dock as Class<*>?) { it.superclass }
            .takeWhile { !it.name.startsWith("android.") }
            .flatMap { runCatching { it.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
            .filter { candidate ->
                !Modifier.isStatic(candidate.modifiers) &&
                    candidate.returnType == Void.TYPE &&
                    candidate.parameterCount == 1 &&
                    candidate.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            .toList()
        if (candidates.size != 1) return null
        return candidates.single()
    }

    private fun findBottomIconSelectionMethod(dock: Class<*>, module: XposedModule): Method? =
        findBottomIconSelectionMethod(dock).also {
            if (it == null) {
                val names = runCatching { dock.declaredMethods.map { m -> "${m.name}(${m.parameterTypes.joinToString()})" } }
                    .getOrDefault(emptyList())
                logWarn(module, "no unique void(boolean) on ${dock.name}; candidates=${names.take(12)}")
            }
        }

    // ── 应用 ──────────────────────────────────────────────────────────────────

    private fun apply(module: XposedModule, dock: Any?, converge: Boolean) {
        val view = dock as? View ?: return
        refreshOrientation(view)
        val recycler = findRecyclerView(view) ?: return
        // 先落宽度再定列数：宽度没跟上就退回原生单列。反过来的话，旋转后第一次
        // 呼出会拿到「单列宽度 + 两列图标」（dock 还没挂上 / 原生宽度变了，
        // 宽度那一步会被整片跳过，而列数照切）。
        //
        // 注意宽度**无条件落一次**，不能只在 wanted > 1 时落：竖屏展开全部应用
        // 面板时 wanted 直接就是 1，只在两列分支落宽度的话 dock 会一直停在
        // 394px，表现为「图标回到单列、面板还是两列那么宽」。
        val wanted = desiredColumns(view)
        var columns = wanted
        var sizeOk = applyPanelSize(module, view, recycler, columns)
        if (wanted > 1 && !sizeOk) {
            columns = 1
            sizeOk = applyPanelSize(module, view, recycler, 1)
            log(module, "two columns deferred: panel width not applied")
        }
        if (!sizeOk) logWarn(module, "panel width not applied for columns=$columns")
        applyColumnCount(module, recycler, columns)
        if (converge) {
            view.postDelayed({ apply(module, view, converge = false) }, CONVERGE_DELAY_MS)
        }
    }

    /** 列表实际已应用的列数，宽度尚未就绪时不能把目标两列当作已生效。 */
    internal fun currentColumns(recycler: View): Int = appliedColumns[recycler] ?: 1

    /**
     * 目标列数。两个开关在设置页互斥，但导入备份配置可能绕过 UI，
     * 因此这里再兜一层：自动展开面板开着就不认两列。
     *
     * 两处让位都只发生在竖屏：
     * - 全部应用面板展开（[allAppsPanelOpen]）：竖屏时 dock 加宽到 132dp 会挤压面板，
     *   横屏横向空间充足，保持两列（用户明确要求）；
     * - box 面板（游戏/视频工具箱）：自身宽度写死，横屏同样放得下。
     */
    private fun desiredColumns(dock: View): Int {
        if (!landscape && allAppsPanelOpen) return 1
        if (!landscape && hasBoxPanel(dock)) return 1
        if (!ConfigManager.getBoolean(PREF_ENABLED, false)) return 1
        if (ConfigManager.getBoolean(PrefKeys.SIDEBAR_EXPAND_ALL_APPS, false)) return 1
        return COLUMN_COUNT
    }

    /**
     * 给刚装上的 GridLayoutManager 挂一个 SpanSizeLookup：
     * 两列时让分割线（以及未配置速记旁图标时的速记）占满整行。
     *
     * 宿主把 `SpanSizeLookup` 重命名成 `GridLayoutManager$c`、`getSpanSize` 改成
     * `f(int)`、`setSpanSizeLookup` 改成 `R(GridLayoutManager$c)`，所以全部按签名
     * 反查；默认实现 `GridLayoutManager$a` 可以直接 new 出来，再 hook 它的
     * `getSpanSize`，按实例身份分流（其它 GridLayoutManager 不受影响）。
     */
    private fun installSpanLookup(module: XposedModule, grid: Any, columns: Int) {
        val gridType = gridClass ?: return
        val base = gridType.declaredClasses.firstOrNull { type ->
            Modifier.isAbstract(type.modifiers) && type.declaredMethods.any { method ->
                Modifier.isAbstract(method.modifiers) && method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterCount == 1 && method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        }
        val defaultType = gridType.declaredClasses.firstOrNull { type ->
            type.superclass == base && !Modifier.isAbstract(type.modifiers) &&
                runCatching { type.getConstructor() }.isSuccess
        }
        if (base == null || defaultType == null) {
            logWarn(module, "span lookup classes unavailable; divider stays in one cell")
            return
        }
        val getSpanSize = defaultType.declaredMethods.singleOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                method.returnType == Int::class.javaPrimitiveType && method.parameterCount == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        val setter = gridType.methods.firstOrNull { method ->
            method.returnType == Void.TYPE && method.parameterCount == 1 && method.parameterTypes[0] == base
        }
        if (getSpanSize == null || setter == null) {
            logWarn(module, "span lookup wiring unavailable; divider stays in one cell")
            return
        }
        val instance = runCatching { defaultType.getConstructor().newInstance() }.getOrNull() ?: return
        if (!spanLookupInstalled) {
            getSpanSize.isAccessible = true
            runCatching {
                module.hook(getSpanSize).intercept { chain ->
                    val columnsNow = spanLookupColumns[chain.thisObject] ?: 1
                    if (columnsNow <= 1) {
                        return@intercept chain.proceed()
                    }
                    val position = chain.args.firstOrNull() as? Int ?: return@intercept chain.proceed()
                    SidebarDockState.spanFor(position, columnsNow)
                }
            }.onFailure { logWarn(module, "span lookup hook failed: ${it.message}") }
            spanLookupInstalled = true
            log(module, "span lookup hooked ${defaultType.name}.${getSpanSize.name}")
        }
        spanLookupColumns[instance] = columns
        runCatching {
            setter.isAccessible = true
            setter.invoke(grid, instance)
        }.onFailure { logWarn(module, "setSpanSizeLookup failed: ${it.message}") }
    }

    private fun applyColumnCount(module: XposedModule, recycler: View, columns: Int) {
        val tracked = appliedColumns[recycler]
        if (tracked == columns) return
        // 从没动过又要回到单列：保持原生 LinearLayoutManager，不做无谓替换。
        if (tracked == null && columns == 1) return
        val next = if (columns > 1) {
            buildGrid(recycler.context, columns)
        } else {
            buildLinear(recycler.context) ?: buildGrid(recycler.context, 1)
        }
        if (next == null) {
            logWarn(module, "layout manager build failed for columns=$columns")
            return
        }
        val method = setLayoutManagerMethod
        if (method == null) {
            logWarn(module, "setLayoutManager unresolved; cannot change columns")
            return
        }
        val ok = runCatching {
            selfSetting.set(true)
            method.isAccessible = true
            method.invoke(recycler, next)
        }.onFailure { logWarn(module, "setLayoutManager failed: ${it.message}") }.isSuccess
        selfSetting.set(false)
        if (ok) {
            appliedColumns[recycler] = columns
            if (gridClass?.isInstance(next) == true) installSpanLookup(module, next, columns)
            val current = runCatching { getLayoutManagerMethod?.invoke(recycler)?.javaClass?.name }.getOrNull()
            log(module, "columns=$columns applied via re-apply; lm=$current")
        }
    }

    private fun refreshOrientation(view: View) {
        val config = runCatching { view.resources?.configuration }.getOrNull() ?: return
        landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * 面板尺寸：dock 及其同宽兄弟 View 一起加宽/还原，高度同步切换。
     *
     * @return 宽度是否已经按 [columns] 落到 dock 上。调用方要据此决定列数 ——
     *         宽度没跟上却切了列数，就是两列图标挤在单列宽的面板里。
     */
    private fun applyPanelSize(module: XposedModule, dock: View, recycler: View?, columns: Int): Boolean {
        val context = dock.context ?: return false
        val wide = targetWidth(context, COLUMN_COUNT)
        val base = nativeWidthOf(dock, wide)
        val dockParams = dock.layoutParams
        if (base <= 0 || wide <= 0 || dockParams == null) return false
        val target = if (columns > 1) wide else base

        // 只记录原生高度：此刻若已是 WRAP_CONTENT 说明两列正在生效，不能记成原生值。
        if (dockParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            nativeHeights[dock] ?: run { nativeHeights[dock] = dockParams.height }
        }
        val wantedHeight = if (columns > 1) {
            ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            nativeHeights[dock]?.takeIf { it > 0 }
        }
        applyListHeight(module, recycler, columns)

        // dock 自己**无条件**改：宿主旋转后第一次呼出时它可能还没挂到 TurboLayout 上
        // （`dock.parent == null`），只遍历兄弟节点会把宽度整片跳过，
        // 而列数已经切到两列 —— 这就是「单列宽度 + 两列图标」。
        var applied = dockParams.width == target
        if (!applied) {
            dockParams.width = target
            applied = runCatching { dock.layoutParams = dockParams }.isSuccess
            if (!applied) logWarn(module, "panel width apply failed (target=$target)")
        }
        if (wantedHeight != null && dockParams.height != wantedHeight) {
            dockParams.height = wantedHeight
            runCatching { dock.layoutParams = dockParams }
                .onFailure { logWarn(module, "panel height apply failed: ${it.message}") }
        }
        appliedWidths[dock] = target

        val container = dock.parent as? ViewGroup
        var siblings = 0
        if (container != null) {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index) ?: continue
                if (child === dock) continue
                val params = child.layoutParams ?: continue
                val current = params.width
                // 只动「宽度跟 dock 原生宽度一致」或「我们之前已经动过」的兄弟节点，
                // 免得把 box 面板之类写死宽度的东西一起改掉。
                if (current != base && current != wide && current != appliedWidths[child]) continue
                if (current == target) continue
                params.width = target
                appliedWidths[child] = target
                runCatching { child.layoutParams = params }
                    .onSuccess { siblings++ }
                    .onFailure { logWarn(module, "panel size apply failed: ${it.message}") }
            }
            applyTurboWidthField(container, base, target)
        }
        log(
            module,
            "panel size columns=$columns base=${base}px target=${target}px " +
                "dockWidth=${dock.layoutParams?.width} siblings=$siblings container=${container != null}",
        )
        return applied
    }

    /**
     * 列表高度：原生按「图标数 × 60 + 72」写死成 px，两列后行数减半，
     * 不改成自适应就会在面板底部留出一片空白。
     */
    private fun applyListHeight(module: XposedModule, recycler: View?, columns: Int) {
        val view = recycler ?: return
        val params = view.layoutParams ?: return
        if (columns > 1) {
            if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                nativeListHeights[view] ?: run { nativeListHeights[view] = params.height }
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                runCatching { view.layoutParams = params }
                    .onFailure { logWarn(module, "list height apply failed: ${it.message}") }
            }
            return
        }
        val native = nativeListHeights[view]?.takeIf { it > 0 } ?: return
        if (params.height == native) return
        params.height = native
        runCatching { view.layoutParams = params }
            .onFailure { logWarn(module, "list height restore failed: ${it.message}") }
    }

    /**
     * TurboLayout 用同一个 int 字段（`dock_panel_width`）推导侧边栏宽度，它同时
     * 决定 `getDockContainerWidth()` 等一系列位置计算 —— 收起动画的位移量就是按
     * 它算的。只改布局参数会让收起时只位移 72dp，加宽后的面板退不干净。
     */
    private fun applyTurboWidthField(container: ViewGroup, base: Int, target: Int) {
        // 回退单列时也要写回：`h` 只在这里被改过，留在加宽值上会让
        // getDockContainerWidth()/getContainerWidth() 继续按 132dp 排布 box 面板。
        val field = turboWidthField ?: if (turboWidthFieldSearched) null else {
            findWidthField(container.javaClass, container, base).also {
                turboWidthField = it
                turboWidthFieldSearched = true
            }
        } ?: return
        runCatching {
            field.isAccessible = true
            field.set(container, target)
        }
    }

    // ── 视图定位 ──────────────────────────────────────────────────────────────

    /** 该 RecyclerView 是否侧边栏列表：父链上能摸到 TurboLayout。 */
    private fun isDockRecycler(view: View): Boolean {
        val turbo = turboClass ?: return false
        var parent = view.parent
        var depth = 0
        while (parent is View && depth++ < MAX_PARENT_DEPTH) {
            if (turbo.isInstance(parent)) return true
            parent = parent.parent
        }
        return false
    }

    /** 侧边栏列表的宿主容器：TurboLayout 的直接子 View。 */
    private fun dockOf(recycler: View): View? {
        val turbo = turboClass ?: return recycler.parent as? View
        var current: View = recycler
        var depth = 0
        while (depth++ < MAX_PARENT_DEPTH) {
            val parent = current.parent as? View ?: return null
            if (turbo.isInstance(parent)) return current
            current = parent
        }
        return null
    }

    /** 从任意子 View 往上找到 TurboLayout 实例。 */
    private fun turboOf(view: View): View? {
        val turbo = turboClass ?: return null
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < MAX_PARENT_DEPTH) {
            if (turbo.isInstance(current)) return current
            current = current.parent as? View
        }
        return null
    }

    /** 在 DockLayout 的子树里找 RecyclerView（宿主字段名不可靠，直接遍历）。 */
    private fun findRecyclerView(dock: View): View? {
        if (isRecyclerView(dock.javaClass)) return dock
        if (dock !is ViewGroup) return null
        for (index in 0 until dock.childCount) {
            val child = dock.getChildAt(index) ?: continue
            if (isRecyclerView(child.javaClass)) return child
        }
        for (index in 0 until dock.childCount) {
            val child = dock.getChildAt(index) ?: continue
            findRecyclerView(child)?.let { return it }
        }
        return null
    }

    private fun findDockLayoutClass(turboClass: Class<*>): Class<*>? {
        runCatching { turboClass.getMethod("getDockLayout") }.getOrNull()?.returnType?.let { return it }
        // 兜底：TurboLayout 字段里唯一的 ViewGroup 子类，且其子树里有 RecyclerView。
        return allFields(turboClass).map { it.type }.firstOrNull { type ->
            !type.isPrimitive &&
                isAssignableTo(type, "android.view.ViewGroup") &&
                allFields(type).any { isRecyclerView(it.type) }
        }
    }

    private fun findDockLayout(wrapper: Any?, turboClass: Class<*>): Any? {
        val owner = wrapper ?: return null
        val turbo = owner.javaClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(owner)?.takeIf { turboClass.isInstance(it) }
            }.getOrNull()
        } ?: return null
        return runCatching { turboClass.getMethod("getDockLayout").invoke(turbo) }.getOrNull()
    }

    // ── 反射助手 ──────────────────────────────────────────────────────────────

    /**
     * `RecyclerView$LayoutManager` 在安全服务里被 R8 重命名成 `RecyclerView$n` 之类，
     * 但 `LinearLayoutManager` 的直接父类永远是它，按父类反查即可，不猜名字。
     */
    private fun resolveLayoutManagerClass(loader: ClassLoader): Class<*>? {
        runCatching { Class.forName(LAYOUT_MANAGER_CLASS, false, loader) }.getOrNull()?.let { return it }
        val byLinear = linearClass?.superclass?.takeIf { it.name != RECYCLER_VIEW_CLASS && it.name != "java.lang.Object" }
        if (byLinear != null) return byLinear
        return gridClass?.superclass?.superclass?.takeIf { it.name != RECYCLER_VIEW_CLASS && it.name != "java.lang.Object" }
    }

    private fun buildGrid(context: Context, columns: Int): Any? =
        runCatching { gridCtor?.newInstance(context, columns) }
            .onFailure { Log.w(TAG, "GridLayoutManager(${columns}) ctor failed: ${it.message}") }
            .getOrNull()

    private fun buildLinear(context: Context): Any? =
        runCatching { linearCtor?.newInstance(context) }
            .onFailure { Log.w(TAG, "LinearLayoutManager ctor failed: ${it.message}") }
            .getOrNull()

    /**
     * 只在容器类自身声明的字段里找宽度为 [value] 的 int 字段。
     * 不往框架父类里找：`View` 有大量 int 字段，撞值会把无关字段改坏。
     */
    private fun findWidthField(type: Class<*>, instance: Any, value: Int): Field? =
        type.declaredFields.filter { field ->
            field.type == Int::class.javaPrimitiveType
        }.let { candidates ->
            candidates.singleOrNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.getInt(instance) == value
                }.getOrDefault(false)
            }
        }

    private fun allFields(type: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { yield(it) }
            current = current.superclass
        }
    }

    private fun isRecyclerView(type: Class<*>): Boolean = isAssignableTo(type, RECYCLER_VIEW_CLASS)

    private fun isAssignableTo(type: Class<*>, ancestor: String): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.name == ancestor) return true
            current = current.superclass
        }
        return false
    }

    // ── 尺寸计算 ──────────────────────────────────────────────────────────────

    /** 记录并返回原生单列宽度（px）。只记录一次，且只在看到真实像素值时记录。 */
    private fun nativeWidthOf(dock: View, wide: Int): Int {
        nativeDockWidths[dock]?.takeIf { it > 0 }?.let { return it }
        val width = dock.layoutParams?.width ?: 0
        // 我们自己写进去的宽度（以及两列的目标宽度）都不是原生值，不能记。
        if (width > 0 && width != wide && width != appliedWidths[dock]) {
            nativeDockWidths[dock] = width
            return width
        }
        // 实例尚未提供原生宽度时等待下一次布局，不能借用旋转前其它 dock 的值。
        return 0
    }

    /**
     * 宽度现在能不能落下去。
     *
     * 列数切换**必须**先过这一关：拿不到原生宽度（dock 还没 inflate 完、布局参数
     * 尚不可用）时就把两列塞进单列宽的面板，就是「旋转后第一次呼出挤成一团」。
     */
    private fun canWiden(dock: View): Boolean {
        val wide = dock.context?.let { targetWidth(it, COLUMN_COUNT) } ?: 0
        if (wide <= 0) return false
        if (dock.layoutParams == null) return false
        return nativeWidthOf(dock, wide) > 0
    }

    /**
     * 目标面板宽度：`列数 × dock_item_edge + (列数 + 1) × dock_item_padding`。
     * 单列时正好等于原生 `dock_panel_width`（48 + 12 × 2 = 72dp）。
     * 资源按名字读取，避免依赖可能漂移的资源 id。
     */
    private fun targetWidth(context: Context, columns: Int): Int {
        val metrics = context.resources?.displayMetrics ?: return 0
        val density = metrics.density.takeIf { it > 0f } ?: return 0
        val edge = dimension(context, "dock_item_edge", FALLBACK_ITEM_EDGE_DP)
        val padding = dimension(context, "dock_item_padding", FALLBACK_ITEM_PADDING_DP)
        return ((columns * edge + (columns + 1) * padding) * density).roundToInt()
    }

    private fun dimension(context: Context, name: String, fallbackDp: Float): Float {
        val resources = context.resources ?: return fallbackDp
        val id = runCatching { resources.getIdentifier(name, "dimen", context.packageName) }.getOrDefault(0)
        if (id == 0) return fallbackDp
        return runCatching { resources.getDimension(id) / resources.displayMetrics.density }.getOrDefault(fallbackDp)
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
