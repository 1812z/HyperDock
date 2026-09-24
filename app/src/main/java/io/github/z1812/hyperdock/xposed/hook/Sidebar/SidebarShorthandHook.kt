package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 横屏下恢复侧边栏的「速记」图标。
 *
 * 宿主把速记条目（`c8.k`）和它下面的分割线（`c8.b`）拼进侧边栏列表的判据是
 * `i8/f.i(Context)`：
 * ```
 * i(ctx) = c && n4.M(ctx) && h()
 *   c   = Settings.Secure "notes_pref_enable_float_mode" == 1（笔记速记开关）
 *   M   = 竖屏恒为 true；横屏时退化成 h0.E()，部分机器上为 false ⇒ 横屏没有速记
 *   h() = 笔记应用版本 >= 358
 * ```
 * 同一个 `i()` 还被 dock adapter（`z7/f`）用来把位置偏移 2（跳过速记和分割线），
 * 所以只改这一个点，列表构造与位置映射会一起改，不会点错应用。
 *
 * 只在「原生判定 false 且当前横屏」时补 true，并且必须先确认速记本来是开着的
 * （优先用竖屏时观察到的原生结果，没观察过就直接读那个 Settings）——
 * 用户主动关掉速记时不能强行塞一个进去。
 */
object SidebarShorthandHook : BaseHook() {
    private const val TAG = "HyperDock[Shorthand]"

    private const val SHORTHAND_UTILS = "i8.f"
    private const val METHOD_AVAILABLE = "i"
    private const val SETTING_NOTES_FLOAT = "notes_pref_enable_float_mode"

    /** 竖屏时原生 `i()` 的返回值；null 表示还没见过竖屏的调用。 */
    @Volatile private var portraitResult: Boolean? = null

    /** 上一次对外补 true 的结果，仅在翻转时打日志（`i()` 调用极其频繁）。 */
    @Volatile private var lastRestored = false

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) return
        val loader = runCatching { param.defaultClassLoader }.getOrNull()
        if (loader == null) {
            logWarn(module, "no class loader; shorthand restore disabled")
            return
        }
        val type = runCatching { Class.forName(SHORTHAND_UTILS, false, loader) }.getOrNull()
        if (type == null) {
            logWarn(module, "$SHORTHAND_UTILS unavailable; shorthand restore disabled")
            return
        }
        val byName = runCatching { type.getDeclaredMethod(METHOD_AVAILABLE, Context::class.java) }
            .getOrNull()?.takeIf { it.returnType == Boolean::class.javaPrimitiveType }
        val method = byName ?: type.declaredMethods.firstOrNull {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterCount == 1 && it.parameterTypes[0] == Context::class.java
        }
        if (method == null) {
            logWarn(module, "$SHORTHAND_UTILS.$METHOD_AVAILABLE(Context) unavailable")
            return
        }
        method.isAccessible = true
        runCatching {
            module.hook(method).intercept { chain ->
                val original = chain.proceed() as? Boolean ?: false
                val context = chain.args.firstOrNull() as? Context
                val landscape = isLandscape(context)
                if (!landscape) portraitResult = original
                if (original) return@intercept true
                if (!landscape) return@intercept false
                // 横屏且原生判定不可用：速记本来开着才补 true。
                val enabled = portraitResult ?: notesEnabled(context)
                if (enabled != lastRestored) {
                    lastRestored = enabled
                    log(module, "shorthand restored in landscape=$enabled (native=false)")
                }
                enabled
            }
        }.onFailure { logWarn(module, "shorthand hook failed: ${it.message}") }
        log(module, "hooks installed on ${type.name}.${method.name}")
    }

    /** 笔记速记开关。宿主 `i8/f.f(Context)` 就是这么读的。 */
    private fun notesEnabled(context: Context?): Boolean {
        val resolver = context?.contentResolver ?: return false
        return runCatching {
            Settings.Secure.getInt(resolver, SETTING_NOTES_FLOAT, -1) == 1
        }.getOrDefault(false)
    }

    private fun isLandscape(context: Context?): Boolean {
        val config = context?.resources?.configuration ?: return false
        return config.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
