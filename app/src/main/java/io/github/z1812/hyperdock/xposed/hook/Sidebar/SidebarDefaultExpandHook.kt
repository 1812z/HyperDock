package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.util.Log
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

    /** DockWindowType 普通 Dock（DockAssistantView）。 */
    private const val DOCK_TYPE_NORMAL = 4

    /** 功能开关配置键，必须在 Compose 端与 ConfigManager 的 core 组中同时登记。 */
    const val PREF_ENABLED = PrefKeys.SIDEBAR_EXPAND_ALL_APPS

    /** 仅在“静默加入全部应用面板”期间为 true，用于跳过原生入场动画。 */
    private val silentAdd = AtomicBoolean(false)
    private val silentHookInstalled = AtomicBoolean(false)
    private val retainedPanelFields = Collections.synchronizedMap(WeakHashMap<Class<*>, Field>())
    private val retentionHooked = Collections.synchronizedSet(HashSet<Method>())
    private val cleanupMethods = Collections.synchronizedSet(HashSet<Method>())

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
        // The wrapper open callback is too late for a synchronized reveal. Hook
        // TurboLayout's own creation callbacks as the primary expansion point.
        findTurboLayout(classLoader)?.let { turboClass ->
            hookExpandedCreate(module, turboClass)
            hookCreate(module, turboClass)
        } ?: logWarn(module, "TurboLayout unavailable; using wrapper fallback")
        hookPanelReleaseWithDexKit(module, classLoader, openMethod.parameterTypes[0])
        module.hook(openMethod).intercept { chain ->
            val opening = chain.args.getOrNull(1) as? Boolean ?: false
            if (opening && ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) {
                chain.args.firstOrNull()?.let { findRootView(it)?.visibility = View.VISIBLE }
            }
            val result = chain.proceed()
            if (opening && ConfigManager.getBoolean(PREF_ENABLED, false)) {
                val wrapper = chain.args.firstOrNull() ?: return@intercept result
                // Fallback for builds without a discoverable Turbo callback.
                // Run inline so panel creation and sidebar opening share the
                // same main-thread transaction; never enqueue a later post.
                findPanelLayout(wrapper)?.let {
                    preparePanelHooks(module, it)
                    expandAllApps(it)
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
        // Do not retain the old AllApps View here. Its lifecycle owns a
        // ViewModelStore/adapter; restoring it after any guessed cleanup hook
        // leaves a dead panel that immediately collapses on touch.
        installSilentAddHook(module, panelLayout)
    }

    private fun installRetentionHooks(module: XposedModule, panelLayout: Any) {
        panelLayout.javaClass.declaredMethods.filter {
            it.returnType == Void.TYPE && it.parameterCount == 0
        }.forEach { method ->
            if (!retentionHooked.add(method)) return@forEach
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                if (!ConfigManager.getBoolean(PrefKeys.SIDEBAR_PANEL_CACHE, false)) {
                    return@intercept chain.proceed()
                }
                val field = retainedPanelFields[chain.thisObject.javaClass]
                val before = runCatching { field?.get(chain.thisObject) }.getOrNull()
                if (before != null && cleanupMethods.contains(method)) {
                    runCatching { field?.set(chain.thisObject, null) }
                    return@intercept try {
                        chain.proceed()
                    } finally {
                        runCatching { field?.set(chain.thisObject, before) }
                    }
                }
                val result = chain.proceed()
                if (before != null && runCatching { field?.get(chain.thisObject) }.getOrNull() == null) {
                    cleanupMethods.add(method)
                    runCatching { field?.set(chain.thisObject, before) }
                }
                result
            }
        }
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

    private inline fun installHook(module: XposedModule, name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logError(module, "$name hook failed: ${Log.getStackTraceString(t)}")
        }
    }

    /** Hook `TurboLayout.a0(int x6)`：带尺寸参数的侧边栏创建入口。 */
    private fun hookExpandedCreate(module: XposedModule, turboLayout: Class<*>) {
        val method = SidebarExpandMethodDiscovery.sixIntVoid(turboLayout) ?: run {
            logWarn(module, "TurboLayout.a0(int x6) unavailable")
            return
        }
        log(module, "hooking ${turboLayout.name}.${method.name}")
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            preparePanelHooks(module, chain.thisObject)
            runCatching { expandAllAppsIfEnabled(chain.thisObject) }
                .onFailure { logError(module, "expand after a0 failed: ${it.message}") }
            result
        }
    }

    /** Hook `TurboLayout.c0()`：无参数的侧边栏创建入口。 */
    private fun hookCreate(module: XposedModule, turboLayout: Class<*>) {
        val method = SidebarExpandMethodDiscovery.create(turboLayout) ?: run {
            logWarn(module, "TurboLayout.c0() unavailable")
            return
        }
        log(module, "hooking ${turboLayout.name}.${method.name}")
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            preparePanelHooks(module, chain.thisObject)
            runCatching { expandAllAppsIfEnabled(chain.thisObject) }
                .onFailure { logError(module, "expand after c0 failed: ${it.message}") }
            result
        }
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
            chain.args.getOrNull(0) as? ViewGroup ?: return@intercept chain.proceed()
            val view = chain.args.getOrNull(1) as? View ?: return@intercept chain.proceed()
            // Let the native helper finish binding/registration first. Bypassing
            // it and calling addView directly creates an empty shell panel.
            val result = chain.proceed()
            applySilentVisual(view)
            result
        }
    }

    /** 原生加入完成后清除入场动画，保留其绑定和层级初始化。 */
    private fun applySilentVisual(view: View) {
        view.animate().cancel()
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

    /** 仅在开关开启时触发默认展开。 */
    private fun expandAllAppsIfEnabled(turbo: Any?) {
        if (!ConfigManager.getBoolean(PREF_ENABLED, false)) return
        expandAllApps(turbo)
    }

    /**
     * 触发原生“展开全部应用”流程，但跳过其入场动画。
     * 仅在普通 Dock（type == 4）且尚未展开时执行。
     */
    private fun expandAllApps(turbo: Any?) {
        val target = turbo ?: return
        val dockLayout = findDockLayout(target) ?: return
        // The target class was already resolved from the sidebar package and
        // matched TurboLayout's creation signature, so a second heuristic
        // wrapper check can only reject valid builds.
        // Once created, never invoke the native toggle/create methods again.
        // Some releases expose collapse as a public no-arg method, which made
        // the old candidate loop hide the panel on the second open.
        val panelField = findPanelField(target)
        val existingPanel = runCatching {
            panelField?.isAccessible = true
            panelField?.get(target) as? View
        }.getOrNull()
        if (existingPanel != null) {
            restorePanelVisibility(existingPanel)
            return
        }
        val previous = silentAdd.get()
        silentAdd.set(true)
        try {
            invokeExpansionCandidates(target, panelField)
        } finally {
            silentAdd.set(previous)
        }
    }

    private fun findPanelField(target: Any): Field? {
        return target.javaClass.declaredFields.firstOrNull { field ->
            ViewGroup::class.java.isAssignableFrom(field.type) &&
                field.type.declaredMethods.any { method ->
                    method.parameterCount == 0 && method.returnType == target.javaClass
                }
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

    private fun invokeExpansionCandidates(target: Any, panelField: Field?) {
        val methods = listOfNotNull(SidebarExpandMethodDiscovery.expansion(target.javaClass))
        for (method in methods) {
            val before = runCatching { panelField?.get(target) }.getOrNull()
            runCatching { method.isAccessible = true; method.invoke(target) }
            val after = runCatching { panelField?.get(target) }.getOrNull()
            if (after != null && after !== before) return
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

    private fun findSidebarRemoveMethod(loader: ClassLoader): Method? {
        return SidebarExpandDexDiscovery.find(loader).asSequence().flatMap { type ->
            runCatching { type.declaredMethods.asSequence() }.getOrDefault(emptySequence())
        }.firstOrNull { method ->
            val p = method.parameterTypes
            method.returnType == Void.TYPE && p.size == 2 &&
                p[1] == Boolean::class.javaPrimitiveType &&
                p[0].declaredMethods.any { it.parameterCount == 0 && ViewGroup::class.java.isAssignableFrom(it.returnType) }
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
            val panelField = turboClass.declaredFields.firstOrNull { field ->
                ViewGroup::class.java.isAssignableFrom(field.type) &&
                    field.type.declaredMethods.any { it.parameterCount == 0 && it.returnType == wrapperClass }
            } ?: run {
                logWarn(module, "DexKit panel release: AllApps field unavailable")
                return
            }
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
                    if (!cacheEnabled) {
                        return@intercept chain.proceed()
                    }
                    val panel = panelField.get(chain.thisObject)
                    log(module, "panel release intercepted cacheEnabled=true panelFound=${panel != null}")
                    if (panel == null) return@intercept chain.proceed()
                    // Keep the native panel and its ViewModel/adapter alive.
                    // Running the release body removes the View from the hierarchy,
                    // and restoring only the field leaves a dead panel on next open.
                    return@intercept null
                }
            }
            log(module, "hooking ${releases.size} panel release methods by DexKit invoke graph")
        }.onFailure { logWarn(module, "DexKit panel release match failed: ${it.message}") }
    }

    private fun invokeNoArg(target: Any, vararg names: String): Any? {
        val method = SidebarExpandMethodDiscovery.noArg(target.javaClass, *names) ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) }.getOrNull()
    }

    /**
     * Resolve obfuscated SecurityCenter classes from dex metadata.  The package is
     * stable across HyperOS releases, while class/method names are not.
     */
    private fun findTurboLayout(loader: ClassLoader): Class<*>? {
        return SidebarExpandDexDiscovery.find(loader).firstOrNull { type ->
            runCatching {
                ViewGroup::class.java.isAssignableFrom(type) &&
                    SidebarExpandMethodDiscovery.sixIntVoid(type) != null &&
                    SidebarExpandMethodDiscovery.create(type) != null
            }.getOrDefault(false)
        }
    }

    private fun findAppsAddHelper(loader: ClassLoader): Class<*>? {
        return SidebarExpandDexDiscovery.find(loader).firstOrNull { type ->
            runCatching {
                    SidebarExpandMethodDiscovery.appAddHelper(type)
            }.getOrDefault(false)
        }
    }

}
