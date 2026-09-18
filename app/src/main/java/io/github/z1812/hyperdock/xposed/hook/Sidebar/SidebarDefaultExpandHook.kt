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

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            return
        }

        val classLoader = param.defaultClassLoader
        val openMethod = findSidebarOpenMethod(classLoader)
        if (openMethod == null) {
            logWarn(module, "normal sidebar open method unavailable")
            return
        }
        openMethod.isAccessible = true
        log(module, "hooking normal sidebar ${openMethod.declaringClass.name}.${openMethod.name}")
        hookWrapperPreload(module, openMethod.parameterTypes[0])
        hookPanelReleaseWithDexKit(module, classLoader, openMethod.parameterTypes[0])
        module.hook(openMethod).intercept { chain ->
            val opening = chain.args.getOrNull(1) as? Boolean ?: false
            sidebarOpen.set(opening)
            if (opening && ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) {
                chain.args.firstOrNull()?.let { findRootView(it)?.visibility = View.VISIBLE }
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
                findRootView(chain.thisObject)?.post {
                    if (ConfigManager.getBoolean(PREF_ENABLED, false)) {
                        findPanelLayout(chain.thisObject)?.let { preparePanelHooks(module, it) }
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
            if (!silentAdd.get()) return@intercept chain.proceed()
            val parent = chain.args.getOrNull(0) as? ViewGroup ?: return@intercept chain.proceed()
            val view = chain.args.getOrNull(1) as? View ?: return@intercept chain.proceed()
            val params = chain.args.getOrNull(2) as? ViewGroup.LayoutParams
                ?: return@intercept chain.proceed()
            applySilentAdd(parent, view, params)
            null
        }
    }

    /** 以终态加入全部应用面板：无入场动画、无位移缩放，直接可见。 */
    private fun applySilentAdd(parent: ViewGroup, view: View, params: ViewGroup.LayoutParams) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeCallbacks(null)
        view.animate().cancel()
        parent.addView(view, params)
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.visibility = View.VISIBLE
        runCatching {
            view.javaClass.methods.firstOrNull {
                it.returnType == Void.TYPE && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.invoke(view, false)
        }
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
            val before = runCatching { panelField?.get(target) }.getOrNull()
            runCatching { method.isAccessible = true; method.invoke(target) }
            val after = runCatching { panelField?.get(target) }.getOrNull()
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

    private fun findSidebarOpenMethod(loader: ClassLoader): Method? {
        return SidebarExpandDexDiscovery.find(loader).asSequence().flatMap { type ->
            runCatching { type.declaredMethods.asSequence() }.getOrDefault(emptySequence())
        }.firstOrNull { method ->
            val p = method.parameterTypes
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && p.size == 6 &&
                p[1] == Boolean::class.javaPrimitiveType &&
                p[2] == Float::class.javaPrimitiveType && p[3] == Float::class.javaPrimitiveType &&
                Runnable::class.java.isAssignableFrom(p[4]) && Runnable::class.java.isAssignableFrom(p[5])
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
                        return@intercept chain.proceed()
                    }
                    val panel = panelField.get(chain.thisObject)
                    if (panel == null) return@intercept chain.proceed()
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
