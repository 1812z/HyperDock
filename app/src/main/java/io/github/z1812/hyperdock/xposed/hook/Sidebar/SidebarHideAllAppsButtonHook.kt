package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 隐藏侧边栏 Dock 底部的「全部应用」按钮。
 *
 * 原生行为：DockLayout（[TURBO_LAYOUT.getDockLayout] 的返回类型）在构造时调用私有方法
 * `s()`，在里面 `new ImageView(...)` 并 `addView(...)` 到自身，作为展开/收起全部应用的入口；
 * 同时应用列表 RecyclerView 在 `t()` 中预留了 48dp + 12dp 的底部留白给该按钮。
 *
 * 本 Hook 直接拦截 `s()`，在按钮被创建之前返回：ImageView 不会实例化，也不会进入视图树，
 * 不存在“先创建再遍历删除”的过程；并顺手把列表底部留白归零，让应用列表铺满面板。
 *
 * 由于按钮不再存在，DockLayout 中三处未判空访问按钮的成员
 * （getBottomIconHeight / getBottomIconWidth / q(int[])）会被同步置为安全值。
 */
object SidebarHideAllAppsButtonHook : BaseHook() {
    private const val TAG = "HyperDock[HideAllAppsBtn]"

    private const val TURBO_LAYOUT = "com.miui.gamebooster.windowmanager.newbox.TurboLayout"

    /** 功能开关配置键，必须在 Compose 端与 ConfigManager 的 core 组中同时登记。 */
    const val PREF_ENABLED = PrefKeys.SIDEBAR_HIDE_ALL_APPS_BUTTON

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

        val dockLayout = resolveDockLayout(turboLayout) ?: run {
            logWarn(module, "DockLayout unavailable")
            return
        }

        val iconField = dockLayout.declaredFields.firstOrNull { it.type == ImageView::class.java }
        if (iconField == null) {
            logWarn(module, "bottom icon field unavailable")
            return
        }
        iconField.isAccessible = true

        val listField = dockLayout.declaredFields.firstOrNull { it.type.name.endsWith("RecyclerView") }

        val createMethod = findNoArgVoid(dockLayout, "s")
        if (createMethod == null) {
            logWarn(module, "bottom icon create method unavailable")
            return
        }

        installHook(module, "DockLayout.s") {
            hookIconCreate(module, createMethod, listField)
        }
        installHook(module, "bottom icon accessors") {
            hookIconAccessors(module, dockLayout, iconField)
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

    /** DockLayout 即 TurboLayout 的 dock 布局类型，避免硬编码混淆后的类名。 */
    private fun resolveDockLayout(turboLayout: Class<*>): Class<*>? {
        val getter = runCatching { turboLayout.getDeclaredMethod("getDockLayout") }.getOrNull()
            ?: return null
        val type = getter.returnType
        return type.takeIf { it != Void.TYPE && !it.isInterface && !it.isPrimitive }
    }

    /**
     * 拦截 `DockLayout.s()`：开启开关时在按钮创建前直接返回，
     * 并回收列表原生为按钮预留的底部留白。
     */
    private fun hookIconCreate(module: XposedModule, createMethod: Method, listField: Field?) {
        log(module, "hooking icon create: ${createMethod.declaringClass.name}.${createMethod.name}")
        createMethod.isAccessible = true
        module.hook(createMethod).intercept { chain ->
            if (!ConfigManager.getBoolean(PREF_ENABLED, false)) return@intercept chain.proceed()
            runCatching { reclaimBottomSpace(chain.thisObject, listField) }
                .onFailure { logError(module, "reclaim bottom space failed: ${it.message}") }
            null
        }
    }

    /** 按钮不再被创建，让依赖它的成员在字段为空时返回安全值。 */
    private fun hookIconAccessors(module: XposedModule, dockLayout: Class<*>, iconField: Field) {
        dockLayout.declaredMethods.forEach { method ->
            val isSizeGetter = method.parameterCount == 0 &&
                method.returnType == Int::class.javaPrimitiveType &&
                (method.name == "getBottomIconHeight" || method.name == "getBottomIconWidth")
            val isLocationGetter = method.returnType == Void.TYPE &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == IntArray::class.java

            if (!isSizeGetter && !isLocationGetter) return@forEach
            log(module, "hooking icon accessor: ${dockLayout.name}.${method.name}")
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                val icon = runCatching { iconField.get(chain.thisObject) }.getOrNull()
                if (icon != null) return@intercept chain.proceed()
                if (isSizeGetter) 0 else null
            }
        }
    }

    /** 应用列表 RecyclerView 原生带有 dp_48 + dp_12 的底部留白，用于容纳按钮。 */
    private fun reclaimBottomSpace(dockLayout: Any?, listField: Field?) {
        val list = runCatching { listField?.get(dockLayout) as? View }.getOrNull() ?: return
        val params = list.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.bottomMargin == 0) return
        params.bottomMargin = 0
        list.layoutParams = params
    }

    private fun findNoArgVoid(clazz: Class<*>, name: String): Method? =
        clazz.declaredMethods.firstOrNull { candidate ->
            candidate.name == name &&
                candidate.parameterCount == 0 &&
                candidate.returnType == Void.TYPE &&
                !Modifier.isStatic(candidate.modifiers)
        }
}
