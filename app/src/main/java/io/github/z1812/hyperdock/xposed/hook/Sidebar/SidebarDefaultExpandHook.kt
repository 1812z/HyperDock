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
import java.util.concurrent.atomic.AtomicBoolean

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

    private const val TURBO_LAYOUT = "com.miui.gamebooster.windowmanager.newbox.TurboLayout"
    private const val APPS_ADD_HELPER = "com.miui.gamebooster.windowmanager.newbox.e"
    private const val DOCK_WINDOW_TYPE = "ia.a"

    /** DockWindowType 普通 Dock（DockAssistantView）。 */
    private const val DOCK_TYPE_NORMAL = 4

    /** 功能开关配置键，必须在 Compose 端与 ConfigManager 的 core 组中同时登记。 */
    const val PREF_ENABLED = PrefKeys.SIDEBAR_EXPAND_ALL_APPS

    /** 仅在“静默加入全部应用面板”期间为 true，用于跳过原生入场动画。 */
    private val silentAdd = AtomicBoolean(false)

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            return
        }

        val classLoader = param.defaultClassLoader
        val turboLayout = runCatching { Class.forName(TURBO_LAYOUT, false, classLoader) }.getOrNull()
        if (turboLayout == null) {
            logWarn(module, "TurboLayout unavailable")
            return
        }

        installHook(module, "TurboLayout.a0") { hookExpandedCreate(module, turboLayout) }
        installHook(module, "TurboLayout.c0") { hookCreate(module, turboLayout) }
        installHook(module, "TurboLayout.R retain all apps") { hookReleaseRetain(module, turboLayout) }

        val appsAddHelper = runCatching { Class.forName(APPS_ADD_HELPER, false, classLoader) }.getOrNull()
        if (appsAddHelper == null) {
            logWarn(module, "all apps add helper unavailable")
            return
        }
        installHook(module, "allApps silent add") { hookSilentAdd(module, appsAddHelper) }
    }

    /** 配置关闭时不再执行，避免影响原生交互。 */
    override fun onConfigChanged() {
        if (!ConfigManager.getBoolean(PREF_ENABLED, false)) {
            silentAdd.set(false)
        }
    }    private fun isUiProcess(packageName: String, processName: String): Boolean {
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
        val method = findSixIntVoid(turboLayout) ?: run {
            logWarn(module, "TurboLayout.a0(int x6) unavailable")
            return
        }
        log(module, "hooking ${turboLayout.name}.${method.name}")
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching { expandAllAppsIfEnabled(chain.thisObject) }
                .onFailure { logError(module, "expand after a0 failed: ${it.message}") }
            result
        }
    }

    /** Hook `TurboLayout.c0()`：无参数的侧边栏创建入口。 */
    private fun hookCreate(module: XposedModule, turboLayout: Class<*>) {
        val method = findNoArgVoid(turboLayout, "c0") ?: run {
            logWarn(module, "TurboLayout.c0() unavailable")
            return
        }
        log(module, "hooking ${turboLayout.name}.${method.name}")
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            runCatching { expandAllAppsIfEnabled(chain.thisObject) }
                .onFailure { logError(module, "expand after c0 failed: ${it.message}") }
            result
        }
    }

    /**
     * JADX 已确认 R() 是每次侧边栏重建时释放 AllApps 的位置：它调用
     * C5799w.m19041P() 后把 f20693p 置空，导致下一次 W() new 一个面板。
     * 临时把字段置空再执行原方法，可让 R() 完成状态复位但跳过释放分支，
     * 随后恢复原面板引用。W() 会重新挂载同一个 View，避免重新解析 300 个应用。
     */
    private fun hookReleaseRetain(module: XposedModule, turboLayout: Class<*>) {
        val method = findNoArgVoid(turboLayout, "R") ?: run {
            logWarn(module, "TurboLayout.R() unavailable")
            return
        }
        val panelField = turboLayout.declaredFields.firstOrNull { field ->
            field.type.name == "com.miui.dock.allapps.w" ||
                field.type.name == "com.miui.dock.allapps.C5799w"
        } ?: run {
            logWarn(module, "TurboLayout all apps field unavailable")
            return
        }
        panelField.isAccessible = true
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            if (!ConfigManager.getBoolean(PREF_ENABLED, false)) return@intercept chain.proceed()
            val panel = runCatching { panelField.get(chain.thisObject) }.getOrNull()
            if (panel == null) return@intercept chain.proceed()
            panelField.set(chain.thisObject, null)
            try {
                chain.proceed()
            } finally {
                // R() 已将 f20694q 复位为 false，W() 随后会复用并重新挂载该 View。
                runCatching { panelField.set(chain.thisObject, panel) }
            }
            null
        }
        log(module, "hooking ${turboLayout.name}.${method.name} to retain AllApps panel")
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
            view.javaClass
                .getMethod("setDismissing", Boolean::class.javaPrimitiveType)
                .invoke(view, false)
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
        val dockLayout = invokeNoArg(target, "getDockLayout") ?: return
        if (!isNormalDock(target)) return
        val open = findNoArgVoid(target.javaClass, "W") ?: return

        val previous = silentAdd.get()
        silentAdd.set(true)
        try {
            open.invoke(target)
        } finally {
            silentAdd.set(previous)
        }
        runCatching {
            dockLayout.javaClass
                .getMethod("setBottomIconSelected", Boolean::class.javaPrimitiveType)
                .invoke(dockLayout, true)
        }
    }

    /** 侧边栏类型是否为普通 Dock（dockType == 4），避免影响游戏/视频模式。 */
    private fun isNormalDock(turbo: Any): Boolean {
        val typeField = turbo.javaClass.declaredFields.firstOrNull { it.type.name == DOCK_WINDOW_TYPE }
            ?: return false
        typeField.isAccessible = true
        val type = typeField.get(turbo) ?: return false

        // DockWindowType 内部以两个 int 字段保存当前类型与上一次类型，当前类型字段名为 "a"。
        val typeClass = type.javaClass
        val typeValueField = typeClass.declaredFields.firstOrNull { field ->
            field.name == "a" && field.type == Int::class.javaPrimitiveType
        } ?: typeClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
        ?: return false
        typeValueField.isAccessible = true
        val typeValue = typeValueField.get(type) as? Int ?: return false
        return typeValue == DOCK_TYPE_NORMAL
    }

    private fun invokeNoArg(target: Any, vararg names: String): Any? {
        val method = findNoArg(target.javaClass, *names) ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun findNoArg(clazz: Class<*>, vararg names: String): Method? {
        names.forEach { name ->
            runCatching { clazz.getDeclaredMethod(name) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun findNoArgVoid(clazz: Class<*>, vararg names: String): Method? =
        findNoArg(clazz, *names)?.takeIf { it.returnType == Void.TYPE }

    private fun findSixIntVoid(clazz: Class<*>): Method? =
        clazz.declaredMethods.firstOrNull { candidate ->
            candidate.returnType == Void.TYPE &&
                candidate.parameterCount == 6 &&
                candidate.parameterTypes.all { it == Int::class.javaPrimitiveType }
        }
}
