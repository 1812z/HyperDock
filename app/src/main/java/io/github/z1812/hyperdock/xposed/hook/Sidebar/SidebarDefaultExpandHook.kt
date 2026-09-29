package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.view.View
import android.view.ViewGroup
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.HashSet
import java.util.concurrent.atomic.AtomicBoolean
import org.luckypray.dexkit.DexKitBridge

/**
 * 安全中心侧边栏（全局 Dock）默认展开全部应用。
 *
 * 原生行为：侧边栏滑出后，底部“全部应用”图标需要再点一次才会展开全部应用面板，
 * 并且该面板有独立入场动画，看起来和侧边栏不是一体的。
 *
 * 本 Hook 在侧边栏每次布局创建完成后，直接用原生展开流程把全部应用面板以
 * “终态”加入 TurboLayout，跳过它自身的入场动画。这样面板会作为侧边栏容器的
 * 子 View 一起参与侧边栏自身的位移/缩放/透明度动画 —— 展开时已经展开全部，
 * 收起时也随整体一起收起。
 */
object SidebarDefaultExpandHook : BaseHook() {
    private const val TAG = "HyperDock[SidebarExpand]"

    /** 功能开关配置键，必须在 Compose 端与 ConfigManager 的 core 组中同时登记。 */
    const val PREF_ENABLED = PrefKeys.SIDEBAR_EXPAND_ALL_APPS

    /** 仅在“静默加入全部应用面板”期间为 true，用于跳过原生入场动画。 */
    private val silentAdd = AtomicBoolean(false)
    /** Native expansion itself calls the same release routine as close. */
    private val autoExpanding = AtomicBoolean(false)
    private val sidebarOpen = AtomicBoolean(false)
    private val silentHookInstalled = AtomicBoolean(false)
    private val retainedPanelFields = Collections.synchronizedMap(WeakHashMap<Class<*>, Field>())

    /**
     * 已初始化面板的进程级缓存：**按深色态分桶**，最多两份（浅色 / 深色）。
     *
     * 分桶而不是只留一份，是因为面板配色只在构建时定（换 Context 的 `uiMode`），
     * 而"该用哪套配色"会随两件事变：系统深色模式切换、游戏/视频工具箱出现消失。
     * 只留一份的话，每次切换都得作废重建 —— 而 `hasBoxPanel()` 还会随侧边栏窗口
     * 重建来回抖动（旧 box 条目被剪掉→false、新 box 挂上→true），实测 8 秒内
     * 重建了 3 次。分桶之后切换只是"换一份"，抖动完全无害。
     *
     * 宿主不就地处理横竖屏切换（TurboLayout 没有 `onConfigurationChanged`），旋转时
     * 整条侧边栏窗口连同 TurboLayout 一起重建；缓存是进程级的，新 TurboLayout 建好
     * 后由 [adoptRetainedPanel] 把对应那份过户回宿主字段，让原生 `W()` 继续复用。
     */
    private val retainedPanels = Collections.synchronizedMap(HashMap<Boolean, View>())

    /** 侧边栏 wrapper 的类型（`openMethod` 的第一个参数），用于把面板重新绑到新窗口。 */
    @Volatile private var wrapperClass: Class<*>? = null

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            return
        }

        val classLoader = param.defaultClassLoader
        val openMethod = SidebarExpandDexDiscovery.findOpenMethod(classLoader)
        if (openMethod == null) {
            logWarn(module, "normal sidebar open method unavailable")
            return
        }
        openMethod.isAccessible = true
        val wrapperType = openMethod.parameterTypes[0]
        wrapperClass = wrapperType
        log(module, "hooking normal sidebar ${openMethod.declaringClass.name}.${openMethod.name}")
        hookWrapperPreload(module, wrapperType)
        hookPanelReleaseWithDexKit(module, classLoader, wrapperType)
        module.hook(openMethod).intercept { chain ->
            val opening = chain.args.getOrNull(1) as? Boolean ?: false
            sidebarOpen.set(opening)
            if (opening && ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) {
                chain.args.firstOrNull()?.let { wrapper ->
                    findRootView(wrapper)?.visibility = View.VISIBLE
                    val turbo = findPanelLayout(wrapper)
                    // 工具箱（深色）出现/消失后，缓存里的面板配色是旧的，按需换一份。
                    syncPanelTheme(module, turbo, wrapper)
                    // 旋转后宿主换了 TurboLayout，面板字段是空的：先把保留下来的
                    // 面板过户过去，原生展开入口 W() 才会复用它而不是重新构建。
                    if (turbo != null) adoptRetainedPanel(module, turbo, wrapper)
                }
            }
            val result = chain.proceed()
            if (opening && ConfigManager.getBoolean(PREF_ENABLED, false)) {
                val wrapper = chain.args.firstOrNull() ?: return@intercept result
                val panel = findPanelLayout(wrapper)
                if (panel != null) {
                    preparePanelHooks(module, panel)
                    expandAllApps(panel)
                } else {
                    findRootView(wrapper)?.post {
                        findPanelLayout(wrapper)?.let {
                            preparePanelHooks(module, it)
                            expandAllApps(it)
                        }
                    }
                }
            }
            result
        }
    }

    private fun hookWrapperPreload(module: XposedModule, wrapperClass: Class<*>) {
        wrapperClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            module.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                val owner = chain.thisObject
                findRootView(owner)?.post {
                    val turbo = findPanelLayout(owner) ?: return@post
                    // 面板预加载独立于「自动展开」：即使不做静默展开，也要装上面板
                    // 加入钩子，否则拿不到面板实例，缓存与过户都无从谈起。
                    val cache = ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)
                    if (ConfigManager.getBoolean(PREF_ENABLED, false) || cache) {
                        preparePanelHooks(module, turbo)
                    }
                    if (cache) {
                        // 系统深色态/工具箱变了的话，缓存里的面板配色是旧的，按需换一份。
                        syncPanelTheme(module, turbo, owner)
                        adoptRetainedPanel(module, turbo, owner)
                    }
                }
                result
            }
        }
    }

    private fun findRootView(wrapper: Any): View? =
        wrapper.javaClass.declaredFields.asSequence().mapNotNull { field ->
            runCatching { field.isAccessible = true; field.get(wrapper) as? View }.getOrNull()
        }.firstOrNull()

    private fun preparePanelHooks(module: XposedModule, panelLayout: Any) {
        // Install the add hook once; the native panel instance itself is kept
        // by the release hook when caching is enabled.
        installSilentAddHook(module, panelLayout)
    }

    private fun installSilentAddHook(module: XposedModule, panelLayout: Any) {
        if (silentHookInstalled.get()) return
        val helper = panelLayout.javaClass.declaredFields.asSequence().map { it.type }.firstOrNull { type ->
            type.declaredMethods.any { method ->
                method.parameterCount == 3 &&
                    ViewGroup::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    View::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                    ViewGroup.LayoutParams::class.java.isAssignableFrom(method.parameterTypes[2])
            }
        } ?: return
        if (silentHookInstalled.compareAndSet(false, true)) hookSilentAdd(module, helper)
    }

    /** 配置关闭时不再执行，避免影响原生交互。 */
    override fun onConfigChanged() {
        if (!ConfigManager.getBoolean(PREF_ENABLED, false)) {
            silentAdd.set(false)
        }
        if (!ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) {
            // 关掉预加载就不要再攥着面板（它们连带引用着 wrapper 与旧窗口）。
            synchronized(retainedPanels) { retainedPanels.clear() }
        }
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }

    /**
     * 拦截全部应用面板的加入动作。静默模式下直接以终态加入，
     * 保留原生 `W()` 计算出的布局参数与层级。
     */
    private fun hookSilentAdd(module: XposedModule, helper: Class<*>) {
        val method = helper.declaredMethods.firstOrNull { candidate ->
            candidate.parameterCount == 3 &&
                ViewGroup::class.java.isAssignableFrom(candidate.parameterTypes[0]) &&
                View::class.java.isAssignableFrom(candidate.parameterTypes[1]) &&
                ViewGroup.LayoutParams::class.java.isAssignableFrom(candidate.parameterTypes[2])
        } ?: run {
            logWarn(module, "all apps add method unavailable")
            return
        }
        log(module, "hooking ${helper.name}.${method.name}")
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val view = chain.args.getOrNull(1) as? View
            // 无论静默与否都要记账：这是「已初始化面板」唯一的构造后入口。
            if (view != null) {
                // 复用成功时宿主加入的就是我们过户过去的那一份；
                // 为 false 说明宿主重新构建了一次（旋转后没兜住就是这里）。
                log(module, "all apps panel add: reused=${retainedPanels.values.any { it === view }}")
                rememberPanel(view)
            }
            if (!silentAdd.get() || view == null) return@intercept chain.proceed()
            // Keep the native add path: it binds the adapter, click listener,
            // ViewModel and TurboLayout state. Only normalize its visual state
            // after the real hierarchy operation has completed.
            val result = chain.proceed()
            applySilentVisual(view)
            result
        }
    }

    /** 原生加入完成后清除入场动画，保留真实面板状态。 */
    private fun applySilentVisual(view: View) {
        fun normalize() {
            runCatching {
                Class.forName("miuix.animation.Folme")
                    .getMethod("clean", View::class.java)
                    .invoke(null, view)
            }
            view.animate().cancel()
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.visibility = View.VISIBLE
        }
        normalize()
        // TurboLayout installs its Folme pre-draw animation after the native
        // add returns. Normalize once more on the next frame; the native View
        // and adapter remain untouched.
        view.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                normalize()
                return true
            }
        })
    }

    /**
     * 触发原生“展开全部应用”流程，但跳过其入场动画。
     * 仅在普通 Dock（type == 4）且尚未展开时执行。
     */
    private fun expandAllApps(turbo: Any?) {
        val target = turbo ?: return
        val dockLayout = findDockLayout(target) ?: return
        if (!isNormalDock(target)) return
        val staggered = ConfigManager.getBoolean(PREF_ENABLED, false) &&
            ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false) &&
            ConfigManager.getBoolean(PrefKeys.SIDEBAR_STAGGERED_EXPAND, false)
        // Native expansion may dispatch an internal close-shaped callback. Keep
        // the lifecycle marked open until the real sidebar open method receives
        // `opening=false`, so cache cannot swallow that callback.
        sidebarOpen.set(true)

        // With panel caching enabled the native All Apps View survives the
        // sidebar close. Do not run the public no-arg candidate loop against an
        // existing panel: on affected builds that loop includes the collapse
        // action, so automatic expand immediately collapses the cached panel.
        if (ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) {
            val panelField = findPanelField(target)
            val cachedPanel = runCatching {
                panelField?.isAccessible = true
                panelField?.get(target) as? View
            }.getOrNull()
            if (cachedPanel != null) {
                if (cachedPanel.parent != null) {
                    restorePanelVisibility(cachedPanel)
                } else {
                    // The cached object survived, but its native container may
                    // have detached it during the close transition. Re-enter
                    // only the real expansion method once so it is reattached;
                    // never iterate all public no-arg methods (that includes
                    // the collapse action on some releases).
                    val expand = findNativePanelExpandMethod(target.javaClass)
                    if (expand != null) {
                        val previousSilent = silentAdd.get()
                        autoExpanding.set(true)
                        silentAdd.set(!staggered)
                        runCatching {
                            expand.isAccessible = true
                            expand.invoke(target)
                        }.also {
                            autoExpanding.set(false)
                            silentAdd.set(previousSilent)
                        }
                    }
                    if (!staggered && ConfigManager.getBoolean(PREF_ENABLED, false)) {
                        restorePanelVisibility(cachedPanel)
                    }
                }
                setDockExpanded(dockLayout)
                return
            }
        }

        val previous = silentAdd.get()
        silentAdd.set(!staggered)
        autoExpanding.set(true)
        try {
            invokeExpansionCandidates(target)
            setDockExpanded(dockLayout)
        } finally {
            autoExpanding.set(false)
            silentAdd.set(previous)
        }
        // 走的是原生重建分支：把新建出来的这份记下来，下次旋转还能过户。
        runCatching {
            findPanelField(target)?.let { field ->
                field.isAccessible = true
                (field.get(target) as? View)?.let { rememberPanel(it) }
            }
        }
    }

    private fun findPanelField(target: Any): Field? {
        // Share the exact field identified by the cache hook. AllApps exposes
        // the sidebar wrapper, not TurboLayout, through its no-arg accessor.
        var type: Class<*>? = target.javaClass
        while (type != null) {
            retainedPanelFields[type]?.let { return it }
            type = type.superclass
        }
        return null
    }

    private fun allFields(type: Class<*>): Sequence<Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.forEach { yield(it) }
            current = current.superclass
        }
    }

    /** 记下这一份已初始化的面板（按它实际的深色态分桶），供后续复用/过户。 */
    private fun rememberPanel(panel: View) {
        if (!ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) return
        // 只认宿主真正的面板类型（由 DexKit 的释放方法反向定位出来的那一个），
        // 免得把 dock 或其它加入动作里的 View 记成面板。
        val type = panelType() ?: return
        if (!type.isInstance(panel)) return
        retainedPanels[SidebarAllAppsDarkHook.isNightContext(panel.context)] = panel
    }

    /** 宿主「全部应用」面板的类型；拿不到就说明释放钩子没装上，缓存无从谈起。 */
    private fun panelType(): Class<*>? =
        synchronized(retainedPanelFields) { retainedPanelFields.values.firstOrNull()?.type }

    private fun detach(panel: View) =
        runCatching { (panel.parent as? ViewGroup)?.removeView(panel) }.isSuccess

    /** 这份面板是不是还挂在某个仍在显示的窗口上（那就不能抢）。 */
    private fun onScreen(panel: View): Boolean {
        val parent = panel.parent
        return parent is View && parent.isAttachedToWindow
    }

    /**
     * 让宿主的面板字段指向「与当前深色态匹配的那一份缓存面板」。
     *
     * 匹配的那份还没有缓存时，把不匹配的那份摘掉并清空字段，让宿主重新构建 ——
     * 构建出来会被 [rememberPanel] 记进对应桶，下次就命中了。
     */
    internal fun syncPanelTheme(module: XposedModule, turbo: Any?, wrapper: Any? = null) {
        if (!ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) return
        val target = turbo ?: return
        val field = findPanelField(target) ?: return
        field.isAccessible = true
        val expected = SidebarAllAppsDarkHook.expectedNightState()
        val wanted = retainedPanels[expected]
        val current = runCatching { field.get(target) as? View }.getOrNull()
        val state = "${if (expected) "dark" else "light"} | ${SidebarAllAppsDarkHook.expectedNightDetail()}"
        if (wanted == null) {
            // 没有这一态的缓存：当前那份配色不对就让它重建（只有真正不匹配才动，
            // 避免和 hasBoxPanel() 的抖动一起把面板反复重建）。
            if (current != null && SidebarAllAppsDarkHook.isNightContext(current.context) != expected) {
                detach(current)
                runCatching { field.set(target, null) }
                log(module, "panel ${if (expected) "dark" else "light"} not cached; rebuilding | $state")
            }
            return
        }
        if (current === wanted && wanted.parent != null) return
        if (onScreen(wanted)) {
            log(module, "cached ${if (expected) "dark" else "light"} panel still on screen; keep current")
            return
        }
        detach(wanted)
        if (current != null && current !== wanted) detach(current)
        if (runCatching { field.set(target, wanted) }.isSuccess) {
            normalizePanelTransform(wanted)
            // 这份缓存面板可能是上一个侧边栏窗口构建的，内部还攥着旧 wrapper。
            // 换到当前 turbo 上时必须重新绑，否则它的回调会打在被丢弃的旧窗口上。
            if (wrapper != null) rebindWrapper(wanted, wrapper)
            log(module, "panel switched to cached ${if (expected) "dark" else "light"} instance | $state")
            rebindInjectedIcons(wanted)
        }
    }

    /**
     * 缓存面板重新上屏后，让注入条目的图标再绑一次。
     *
     * 面板复用时宿主不会重新提交列表，图标只有首轮绑定那一次机会；那一轮若早于
     * 图标资源就绪（跨包取图标 / 系统磁贴目录异步到达）就只剩占位图，必须点一下
     * 或再开一次面板才补得上。`SidebarShortcutController.refreshPanelIcons` 内部
     * 有节流，多次调用无害。用 post 是等面板真正挂到窗口上再重绑。
     */
    private fun rebindInjectedIcons(panel: View) {
        runCatching { SidebarShortcutController.requestIconRebind(panel) }
    }

    /**
     * 把缓存里匹配当前深色态的那份面板过户到 [turbo] 的面板字段上。
     *
     * 旋转后宿主重建侧边栏窗口，新的 TurboLayout 面板字段是 null，原生展开入口
     * `W()` 会重新构建面板（日志里表现为 `AllAppsDark | panel ctor`）。这里先把
     * 缓存的面板写回字段并重新绑定到当前 wrapper，`W()` 就会走「复用已有面板」
     * 的分支而不是重新 inflate。
     *
     * 失败一律静默放弃，回退到原生重建，不影响任何既有行为。
     */
    private fun adoptRetainedPanel(module: XposedModule, turbo: Any, wrapper: Any) {
        val panel = retainedPanels[SidebarAllAppsDarkHook.expectedNightState()] ?: return
        val field = findPanelField(turbo) ?: return
        // 旧窗口还挂在屏幕上的时候不能抢：那份面板还在被使用。
        val parent = panel.parent
        if (parent is View && parent.isAttachedToWindow) {
            log(module, "panel adopt skipped: previous panel still on screen")
            return
        }
        val current = runCatching { field.isAccessible = true; field.get(turbo) }.getOrNull()
        if (current != null) return
        if (parent is ViewGroup) {
            val removed = runCatching { parent.removeView(panel) }.isSuccess
            if (!removed || panel.parent != null) {
                logWarn(module, "panel adopt failed: cannot detach from previous container")
                return
            }
        }
        val rebound = rebindWrapper(panel, wrapper)
        if (!runCatching { field.set(turbo, panel) }.isSuccess) {
            logWarn(module, "panel adopt failed: panel field not writable")
            return
        }
        normalizePanelTransform(panel)
        log(
            module,
            "panel adopted into new turbo panel=${panel.javaClass.name} " +
                "detached=${parent != null} wrapperRebound=$rebound"
        )
        rebindInjectedIcons(panel)
    }

    /**
     * 面板对象自己持有侧边栏 wrapper（发现面板字段时就是靠「存在无参方法返回
     * wrapper」认出来的）。过户到新窗口后必须把它重新指向当前 wrapper，
     * 否则面板的回调会打在已经被丢弃的旧窗口上。
     */
    private fun rebindWrapper(panel: View, wrapper: Any): Boolean {
        val type = wrapperClass ?: return false
        runCatching {
            panel.javaClass.declaredMethods.firstOrNull { method ->
                method.parameterCount == 1 && method.parameterTypes[0] == type
            }?.let { method ->
                method.isAccessible = true
                method.invoke(panel, wrapper)
                return true
            }
        }
        runCatching {
            allFields(panel.javaClass).firstOrNull { it.type == type }?.let { field ->
                field.isAccessible = true
                field.set(panel, wrapper)
                return true
            }
        }
        return false
    }

    /** 清掉上一次收起动画留下的位移/透明度，避免复用时面板是隐形的。visibility 不动。 */
    private fun normalizePanelTransform(panel: View) {
        panel.animate().cancel()
        panel.alpha = 1f
        panel.scaleX = 1f
        panel.scaleY = 1f
        panel.translationX = 0f
        panel.translationY = 0f
    }

    private fun restorePanelVisibility(panel: View) {
        panel.animate().cancel()
        panel.alpha = 1f
        panel.scaleX = 1f
        panel.scaleY = 1f
        panel.translationX = 0f
        panel.translationY = 0f
        panel.visibility = View.VISIBLE
    }

    private fun invokeExpansionCandidates(target: Any) {
        val methods = listOfNotNull(findNativePanelExpandMethod(target.javaClass))
        val panelField = findPanelField(target) ?: return
        panelField.isAccessible = true
        for (method in methods) {
            val before = runCatching { panelField.get(target) }.getOrNull()
            runCatching { method.isAccessible = true; method.invoke(target) }
            val after = runCatching { panelField.get(target) }.getOrNull()
            if (after != null && after !== before) return
        }
    }

    /** TurboLayout's All Apps attach entry is W() on the current host build. */
    private fun findNativePanelExpandMethod(clazz: Class<*>): Method? {
        return runCatching { clazz.getDeclaredMethod("W") }
            .getOrNull()
            ?.takeIf { it.returnType == Void.TYPE && it.parameterCount == 0 }
            ?: SidebarExpandMethodDiscovery.expansion(clazz)
    }

    private fun setDockExpanded(dockLayout: Any) {
        runCatching {
            dockLayout.javaClass.getMethod("setBottomIconSelected", Boolean::class.javaPrimitiveType)
                .invoke(dockLayout, true)
        }
    }

    /** 侧边栏类型是否为普通 Dock（dockType == 4），避免影响游戏/视频模式。 */
    private fun isNormalDock(turbo: Any): Boolean {
        // Normal sidebar owns TurboLayout through a sidebar-wrapper object whose
        // no-arg accessor returns this exact layout type. Game-only layouts do
        // not have that wrapper relationship.
        return turbo.javaClass.declaredFields.any { field ->
            field.type.declaredMethods.any { method ->
                method.parameterCount == 0 && method.returnType == turbo.javaClass
            }
        }
    }

    private fun findDockLayout(target: Any): Any? {
        runCatching {
            target.javaClass.getMethod("getDockLayout").invoke(target)
        }.getOrNull()?.let { return it }
        return target.javaClass.methods.asSequence()
            .filter { it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType) }
            .mapNotNull { method -> runCatching { method.invoke(target) }.getOrNull() }
            .firstOrNull { value ->
                value.javaClass.methods.any {
                    it.returnType == Void.TYPE && it.parameterCount == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
            }
    }

    private fun findPanelLayout(wrapper: Any?): Any? {
        val owner = wrapper ?: return null
        return owner.javaClass.declaredFields.asSequence().mapNotNull { field ->
            runCatching {
                field.isAccessible = true
                field.get(owner)
            }.getOrNull()
        }.firstOrNull { value ->
            value is ViewGroup && value.javaClass.declaredMethods.any { method ->
                method.returnType == Void.TYPE && method.parameterCount == 6 &&
                    method.parameterTypes.all { it == Int::class.javaPrimitiveType }
            }
        }
    }

    /** Find the panel-release method by its invoke graph, never by its R8 name. */
    private fun hookPanelReleaseWithDexKit(module: XposedModule, loader: ClassLoader, wrapperClass: Class<*>) {
        runCatching {
            val turboClass = wrapperClass.declaredFields.map { it.type }.firstOrNull { type ->
                ViewGroup::class.java.isAssignableFrom(type) && type.declaredMethods.any { method ->
                    method.returnType == Void.TYPE && method.parameterCount == 6 &&
                        method.parameterTypes.all { it == Int::class.javaPrimitiveType }
                }
            } ?: run {
                logWarn(module, "DexKit panel release: Turbo layout class unavailable")
                return
            }
            val panelField = allFields(turboClass).firstOrNull { field ->
                ViewGroup::class.java.isAssignableFrom(field.type) &&
                    field.type.declaredMethods.any { it.parameterCount == 0 && it.returnType == wrapperClass }
            } ?: run {
                logWarn(module, "DexKit panel release: AllApps field unavailable")
                return
            }
            panelField.isAccessible = true
            retainedPanelFields[turboClass] = panelField
            val releases = System.loadLibrary("dexkit").let {
                DexKitBridge.create(loader, false).use { bridge ->
                    bridge.getClassData(turboClass)?.findMethod {
                        matcher {
                            returnType("void")
                            paramTypes()
                            invokeMethods {
                                add {
                                    declaredClass(panelField.type.name)
                                    returnType("void")
                                    paramTypes()
                                }
                            }
                        }
                    }?.mapNotNull { data ->
                        runCatching { data.getMethodInstance(loader) }.getOrNull()
                    }?.filter { method ->
                        // The private Turbo cleanup routine is the only safe point to
                        // preserve the panel. Lifecycle callbacks also call panel methods
                        // during normal transitions and must remain untouched.
                        Modifier.isPrivate(method.modifiers) && !method.isSynthetic
                    } ?: emptyList()
                }
            }
            if (releases.isEmpty()) {
                logWarn(module, "DexKit panel release: cleanup method unavailable")
                return
            }
            panelField.isAccessible = true
            releases.forEach { release ->
                release.isAccessible = true
                module.hook(release).intercept { chain ->
                    val cacheEnabled = ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)
                    if (!cacheEnabled || autoExpanding.get() || sidebarOpen.get()) {
                        if (cacheEnabled) {
                            // 面板要被原生销毁了，再留着就是一份死面板。
                            runCatching {
                                val dying = panelField.get(chain.thisObject) as? View
                                if (dying != null) {
                                    synchronized(retainedPanels) {
                                        retainedPanels.entries.removeAll { it.value === dying }
                                    }
                                }
                            }
                            log(
                                module,
                                "panel release runs (autoExpanding=${autoExpanding.get()} " +
                                    "sidebarOpen=${sidebarOpen.get()}); cache dropped"
                            )
                        }
                        return@intercept chain.proceed()
                    }
                    val panel = panelField.get(chain.thisObject)
                    if (panel == null) return@intercept chain.proceed()
                    if (panel is View) rememberPanel(panel)
                    // On the next open, TurboLayout.R() can run before the
                    // sidebar open dispatcher. At that point the cached panel
                    // is already detached (parent=null). Let native R() run so
                    // the panel can be rebuilt and attached; intercepting it
                    // here leaves the field pointing at an orphaned View and
                    // the following automatic expand has nothing to mount.
                    if (panel is View && panel.parent == null) {
                        // R() would clear the cached field. Keep the native
                        // object for d0()/W() to reuse on the next manual or
                        // automatic expand; never fake its attachment with
                        // addView, because TurboLayout also tracks f18876q.
                        return@intercept null
                    }
                    // Keep the native panel and its ViewModel/adapter alive.
                    // Running the release body removes the View from the hierarchy,
                    // and restoring only the field leaves a dead panel on next open.
                    return@intercept null
                }
            }
            log(module, "hooking ${releases.size} panel release methods by DexKit invoke graph")
        }.onFailure { logWarn(module, "DexKit panel release match failed: ${it.message}") }
    }

}
