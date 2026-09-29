package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.util.Collections
import java.util.WeakHashMap
import java.lang.reflect.Modifier

/**
 * 侧边栏深色时，把「全部应用」面板一起切到深色。
 *
 * ### 原生行为
 * 全部应用面板是 `com.miui.dock.allapps` 里的一个自定义 View（当前版本 `w`，
 * extends LinearLayout）。它的构造函数里就把整块外观定好了：
 * `V()` → `com.miui.dock.allapps.f0.c(this, getContext())`（面板背景 + 背景模糊）
 * 与 `f0.a(getContext(), 搜索栏控件…)`（搜索栏背景、图标、文字颜色），
 * 面板内容则由 `LayoutInflater.from(getContext()).inflate(...)` 展开。
 *
 * 这套配色**只看系统全局深色**：`f0.d()` 里是
 * `DeviceUtil.isDarkMode(context)`（`context.resources.configuration.uiMode`），
 * 其余颜色/背景都是按当前配置解析的 day-night 资源。
 *
 * 而侧边栏的 dock 条会在挂上「box 面板」（游戏工具箱 / 视频工具箱）时变深色 ——
 * 这两个工具箱自身就是深色的，但面板不知道这件事，于是出现
 * 「侧边栏深色 + 全部应用面板浅色」的割裂。
 *
 * ### 改法
 * 不去逐个改颜色（背景、模糊、图标、文字、列表项文字各走一条路，改不全反而更难看），
 * 而是**让面板整个在深色配置下构建**：把面板构造函数拿到的 Context 换成一个
 * `uiMode = UI_MODE_NIGHT_YES` 的上下文。这样
 * 布局展开、`setBackgroundResource`、`DeviceUtil.isDarkMode` 全部走夜间分支，
 * 结果与「系统进入深色模式」逐像素一致。
 *
 * 只在「面板被创建时」生效：面板是随开随建的（宿主关闭全部应用面板时会把 View 丢掉），
 * 因此「先打开工具箱 → 再打开全部应用」这条主路径一定能拿到夜间上下文。
 * 已经建好的面板不做二次染色 —— 那需要重新 inflate，风险与收益不成比例。
 */
object SidebarAllAppsDarkHook : BaseHook() {
    private const val TAG = "HyperDock[AllAppsDark]"

    private const val ALL_APPS_PACKAGE = "com.miui.dock.allapps"

    /** 已挂过钩的面板类，避免同一个类重复 hook 构造函数。 */
    private val hookedTypes = Collections.synchronizedSet(HashSet<Class<*>>())

    /** 原始 Context -> 夜间 Context。弱引用缓存，避免每次开面板都新建。 */
    private val nightContexts = Collections.synchronizedMap(WeakHashMap<Context, Context>())

    /**
     * 由本 Hook 生成的夜间上下文。
     *
     * 必须能认出它们：面板构造是**链式**的（`w(Context, p)` 里再调
     * `w(Context, AttributeSet, int, p)`），外层构造换掉 Context 后，内层构造拿到的
     * 就是我们造的夜间 Context。若把它当成"宿主 Context"记下来，
     * `isNight(host)` 会永久为真 —— 实测表现为「工具箱关了回到桌面，面板还是深色」，
     * 日志里 `systemNight=true`（真实系统其实是浅色）。
     */
    private val syntheticContexts = Collections.newSetFromMap(WeakHashMap<Context, Boolean>())

    /**
     * 最近一次构建面板时拿到的**宿主** Context（弱引用）。
     *
     * 它的 `uiMode` 就是系统当前的深色态 —— 面板自己的 Context 可能是我们换过的
     * 夜间 Context，不能拿来问"系统现在是不是深色"。
     */
    @Volatile private var hostContextRef: java.lang.ref.WeakReference<Context>? = null

    /**
     * 现在面板**应该**用哪套配色。
     *
     * 面板配色只在构建时定（换 Context 的 `uiMode`），缓存复用后改不了，所以缓存
     * 必须按这个值分桶：系统深色一套、系统浅色一套（`SidebarDefaultExpandHook`
     * 按 key 取面板，主题切换只是换一份，不需要作废重建）。
     */
    internal fun expectedNightState(): Boolean {
        val host = hostContextRef?.get() ?: return false
        return systemNight(host) ||
            (ConfigManager.getBoolean(PrefKeys.SIDEBAR_ALL_APPS_DARK_SYNC, false) &&
                SidebarDarkState.hasBoxPanel())
    }

    /**
     * 系统当前的深色态。
     *
     * 只认 [hostContextRef] 里那份**宿主自己的** Context：本 Hook 造的夜间上下文
     * 一旦被当成宿主上下文，`isNight` 就会永久为真（链式构造的内层构造就是这么
     * 把状态污染掉的），所以这里再兜一层。
     */
    private fun systemNight(host: Context?): Boolean {
        if (host == null || isSyntheticNight(host)) return false
        return isNight(host)
    }

    /**
     * 诊断串：把「系统深色 / 开关 / box 面板可见性」分开打。
     *
     * `SidebarDarkState.lastDetail` 是关键 —— 只有它能回答"深色态到底是哪条信号
     * 撑着的"，否则「工具箱关了但面板还是深色」在日志里完全看不出原因。
     */
    internal fun expectedNightDetail(): String {
        val host = hostContextRef?.get()
        val system = systemNight(host)
        val pref = ConfigManager.getBoolean(PrefKeys.SIDEBAR_ALL_APPS_DARK_SYNC, false)
        val box = SidebarDarkState.hasBoxPanel()
        // host 若是 synthetic，说明宿主上下文被我们自己的夜间上下文顶掉了 —— 打了
        // synthetic 就表示代码里已经拦住，真出现了就是判定链还有别的入口。
        val hostTag = when {
            host == null -> "null"
            isSyntheticNight(host) -> "synthetic!"
            else -> "host"
        }
        return "systemNight=$system($hostTag) pref=$pref box=$box(${SidebarDarkState.lastDetail}) " +
            "expected=${system || (pref && box)}"
    }

    /** 取面板/上下文自己的深色态。缓存分桶与"这份面板是哪套配色"都用它判定。 */
    internal fun isNightContext(context: Context?): Boolean = isNight(context)

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) return
        val loader = runCatching { param.defaultClassLoader }.getOrNull()
        if (loader == null) {
            logWarn(module, "no class loader; all-apps dark sync disabled")
            return
        }

        val panel = findPanelClass(module, loader)
        if (panel != null) {
            hookPanelConstructors(module, panel)
        } else {
            logWarn(module, "all-apps panel class not found by signature; falling back to runtime discovery")
        }
        // 兜底：签名匹配不上时，从「面板主题方法被调用」这一刻反推面板类。
        // 面板首次构建会错过，但之后的构建都能拿到夜间上下文。
        hookPanelThemeMethod(module, loader)
        log(module, "all-apps dark sync hooks installed panel=${panel?.name}")
    }

    // ── 发现 ──────────────────────────────────────────────────────────────────

    private fun findPanelClass(module: XposedModule, loader: ClassLoader): Class<*>? {
        val classes = runCatching { SidebarExpandDexDiscovery.findInPackage(loader, ALL_APPS_PACKAGE) }
            .getOrDefault(emptyList())
        log(module, "allapps classes=${classes.size}")
        return classes.firstOrNull(::looksLikePanel)
    }

    /**
     * 面板类的识别不靠类名（R8 每版都变），靠构造函数签名：
     * 一个 View 子类，且构造函数第一个参数是 Context、并带一个
     * `com.miui.dock.*` 的侧边栏包装对象（`w(Context, sidebarWrapper)`）。
     *
     * 运行期从主题方法反推出来的类也必须过这一关：万一 `void(ViewGroup, Context)`
     * 匹配到了包里另一个方法，也不能把夜间上下文塞给无关类。
     */
    private fun looksLikePanel(type: Class<*>): Boolean =
        View::class.java.isAssignableFrom(type) &&
            type.declaredConstructors.any { ctor ->
                val params = ctor.parameterTypes
                params.isNotEmpty() &&
                    params[0] == Context::class.java &&
                    params.any { it.name.startsWith("com.miui.dock.") }
            }

    /**
     * 面板主题方法：`f0.c(ViewGroup panel, Context)`。
     * 只用来在运行期确认面板类并留一条诊断日志，不改任何参数。
     */
    private fun hookPanelThemeMethod(module: XposedModule, loader: ClassLoader) {
        val method = runCatching { SidebarExpandDexDiscovery.findInPackage(loader, ALL_APPS_PACKAGE) }
            .getOrDefault(emptyList())
            .asSequence()
            .flatMap { runCatching { it.declaredMethods.asSequence() }.getOrDefault(emptySequence()) }
            .firstOrNull { candidate ->
                !Modifier.isAbstract(candidate.modifiers) &&
                    candidate.returnType == Void.TYPE &&
                    candidate.parameterCount == 2 &&
                    ViewGroup::class.java.isAssignableFrom(candidate.parameterTypes[0]) &&
                    candidate.parameterTypes[1] == Context::class.java
            }
        if (method == null) {
            logWarn(module, "all-apps theme method unavailable; runtime discovery disabled")
            return
        }
        runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    val panel = chain.args.firstOrNull() as? View ?: return@runCatching
                    log(module, "panel themed ${darkStateDetail()} night=${isNight(panel.context)}")
                    if (looksLikePanel(panel.javaClass)) {
                        hookPanelConstructors(module, panel.javaClass)
                    }
                }.onFailure { logWarn(module, "panel theme probe failed: ${it.message}") }
                result
            }
        }.onFailure { logWarn(module, "panel theme method hook failed: ${it.message}") }
    }

    // ── 上下文替换 ────────────────────────────────────────────────────────────

    private fun hookPanelConstructors(module: XposedModule, type: Class<*>) {
        if (!hookedTypes.add(type)) return
        type.declaredConstructors.forEach { ctor ->
            val index = ctor.parameterTypes.indexOfFirst { it == Context::class.java }
            if (index < 0) return@forEach
            runCatching {
                ctor.isAccessible = true
                module.hook(ctor).intercept { chain ->
                    // 无论最后要不要染深色都留一条日志：这是判断「构造函数 hook 到底
                    // 有没有被触发」的唯一证据（面板每次打开都会新建）。
                    val enabled = ConfigManager.getBoolean(PrefKeys.SIDEBAR_ALL_APPS_DARK_SYNC, false)
                    val box = SidebarDarkState.hasBoxPanel()
                    // 宿主原始 Context 留一份：它是唯一能问出"系统现在是不是深色"的
                    // 上下文（面板自己的 Context 可能是我们换过的夜间 Context）。
                    // 关键：构造是链式的，内层构造拿到的是**我们自己造的**夜间 Context，
                    // 必须把它排除掉，否则 isNight(host) 会永久为真。
                    runCatching { chain.args.getOrNull(index) as? Context }
                        .getOrNull()
                        ?.takeUnless { isSyntheticNight(it) }
                        ?.let { hostContextRef = java.lang.ref.WeakReference(it) }
                    log(
                        module,
                        "panel ctor (${ctor.parameterTypes.joinToString { it.simpleName }}) " +
                            "pref=$enabled box=$box | ${expectedNightDetail()}"
                    )
                    if (!enabled || !box) return@intercept chain.proceed()
                    val original = chain.args.getOrNull(index) as? Context
                    val night = original?.let { nightContext(it) }
                    if (night == null) {
                        logWarn(module, "night context unavailable; panel keeps system theme")
                        return@intercept chain.proceed()
                    }
                    // libxposed 的 args 是 MutableList，而 proceed 收 Array。两条路都铺上，
                    // 免得某条不生效时又要再测一轮：就地改 args（供 proceed() 无参时用），
                    // 同时把改好的 Array 交给 proceed()。两者指向同一个 night，不会重复构造。
                    val swapped = chain.args.toTypedArray().also { it[index] = night }
                    runCatching { chain.args[index] = night }
                        .onFailure { logWarn(module, "panel context in-place override failed: ${it.message}") }
                    val result = runCatching { chain.proceed(swapped) }
                        .getOrElse { chain.proceed() }
                    val self = runCatching { chain.thisObject as? View }.getOrNull()
                    log(
                        module,
                        "panel built applied=${self?.context === night} " +
                            "night=${isNight(self?.context)} ctx=${self?.context?.javaClass?.simpleName}"
                    )
                    result
                }
            }.onFailure { logWarn(module, "panel constructor hook failed: ${it.message}") }
        }
        log(module, "panel constructors hooked on ${type.name}")
    }

    /**
     * 诊断串：把「开关值」和「box 面板」分开打。
     * `contains=false` 说明这个键从没被写过（开关没开过，或配置没同步到本进程），
     * 与「开关开了但值为 false」是两回事，务必区分。
     */
    private fun darkStateDetail(): String {
        val key = PrefKeys.SIDEBAR_ALL_APPS_DARK_SYNC
        val enabled = ConfigManager.getBoolean(key, false)
        val known = runCatching { ConfigManager.contains(key) }.getOrDefault(false)
        return "pref=$enabled(contains=$known) ${expectedNightDetail()}"
    }

    /**
     * 把 [source] 复制成一份「夜间」上下文。
     *
     * `createConfigurationContext` 丢主题，所以原本是 ContextThemeWrapper 时
     * 用它的 themeResId 重新包一层，避免 `?attr/...` 解析到默认主题上。
     */
    private fun nightContext(source: Context): Context? {
        nightContexts[source]?.let { return it }
        return runCatching {
            val config = Configuration(source.resources.configuration)
            config.uiMode =
                (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            val base = source.createConfigurationContext(config)
            val themeRes = runCatching {
                (source.javaClass.getMethod("getThemeResId").invoke(source) as? Number)?.toInt()
            }.getOrNull() ?: 0
            val result = if (themeRes != 0) ContextThemeWrapper(base, themeRes) else base
            nightContexts[source] = result
            // 记下来：链式构造的内层会把它当成 Context 传进来，不能误认成宿主 Context。
            synchronized(syntheticContexts) { syntheticContexts.add(result) }
            result
        }.onFailure { Log.w(TAG, "night context failed: ${it.message}") }.getOrNull()
    }

    /** [context] 是不是本 Hook 造出来的夜间上下文。 */
    private fun isSyntheticNight(context: Context): Boolean =
        synchronized(syntheticContexts) { syntheticContexts.contains(context) }

    private fun isNight(context: Context?): Boolean = runCatching {
        val uiMode = context?.resources?.configuration?.uiMode ?: return false
        (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }.getOrDefault(false)

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
