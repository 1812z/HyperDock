package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.util.Log
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * 侧边栏关闭 Hook，供其他模块主动关闭侧边栏。
 *
 * 侧边栏窗口由安全中心 `:ui` 进程内的 DockWindowManagerService 管理，
 * 它通过 `ISidebarOverlay` Binder 对外（SystemUI 等）提供开关：
 * - `hideNewDock()`：无参关闭
 * - `hideNewDockWithAnim(int)`：带动画关闭，int 为系统侧动画参数
 *
 * 两个方法名经过 R8 混淆且会随版本漂移，因此用 DexKit 以方法体内稳定的
 * 错误日志字符串定位（两条日志互不为对方的子串，特征唯一）。
 * Binder 实例通过 hook 其构造函数捕获；同时被动 hook 两个关闭方法，
 * 记录系统每次关闭侧边栏时使用的动画参数，主动关闭时优先复用，
 * 保证动画表现与原生一致。
 */
object SidebarCloseHook : BaseHook() {
    private const val TAG = "HyperDock[SidebarClose]"
    private const val HIDE_LOG = "hideNewDock: mDockWindowManager is null!"
    private const val HIDE_ANIM_LOG = "hideNewDockWithAnim: mDockWindowManager is null!"

    /** 无系统 gesture 记录时的默认值；`P0(1)` 与其他值一样都会走动画收起分支。 */
    private const val DEFAULT_GESTURE = 1

    @Volatile private var binder: Any? = null
    @Volatile private var hideMethod: Method? = null
    @Volatile private var hideWithAnimMethod: Method? = null
    @Volatile private var lastAnimParam: Int? = null

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) return

        val loader = param.defaultClassLoader
        val hide = findMethodByString(loader, HIDE_LOG)
        val hideAnim = findMethodByString(loader, HIDE_ANIM_LOG)
        if (hideAnim == null) {
            logWarn(module, "animated sidebar hide method not found; auto close disabled")
            return
        }
        hideMethod = hide
        hideWithAnimMethod = hideAnim
        log(
            module,
            "hide=${hide?.declaringClass?.simpleName}.${hide?.name}" +
                " hideAnim=${hideAnim.declaringClass.simpleName}.${hideAnim.name}",
        )

        // 构造函数捕获 Binder 实例：模块注入早于任何应用代码，服务创建即捕获。
        val binderClass = (hide ?: hideAnim)!!.declaringClass
        runCatching {
            binderClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    binder = chain.thisObject
                    result
                }
            }
        }.onFailure { logWarn(module, "hook binder constructors failed: ${it.message}") }

        // 无参 hide（瞬时移除）仅用于 Binder 实例捕获，绝不主动调用。
        hide?.let { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    binder = chain.thisObject
                    chain.proceed()
                }
            }.onFailure { logWarn(module, "hook hide method failed: ${it.message}") }
        }
        hideAnim?.let { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    binder = chain.thisObject
                    (chain.args.firstOrNull() as? Int)?.let { lastAnimParam = it }
                    chain.proceed()
                }
            }.onFailure { logWarn(module, "hook hide-anim method failed: ${it.message}") }
        }
    }

    /**
     * 关闭侧边栏，走系统自身的动画收起路径（`hideNewDockWithAnim` → `P0(int)`），
     * 与点击窗外/拖拽释放后的收起动画一致；绝不调用 `hideNewDock`（`O0(false)`）——
     * 那条路径是 removeAllViews + setVisibility(GONE) 的瞬时移除。
     *
     * gesture 参数优先复用系统最近一次关闭使用的值（SystemUI 经 Binder 传入），
     * 无记录时默认 1；`P0` 的全部分支（编辑态特殊动画 / `d2(p,false)` 标准收起）
     * 都带动画。Binder 方法内部会 post 到服务 Handler，可任意线程调用。
     *
     * @return 是否成功发起关闭（Binder 未捕获、动画方法不可用或调用失败返回 false）。
     */
    fun closeSidebar(): Boolean {
        val target = binder
        if (target == null) {
            Log.w(TAG, "close failed: sidebar binder not captured")
            return false
        }
        val animMethod = hideWithAnimMethod ?: run {
            Log.w(TAG, "close failed: animated hide method unavailable")
            return false
        }
        val gesture = lastAnimParam ?: DEFAULT_GESTURE
        return runCatching { animMethod.invoke(target, gesture) }
            .onFailure { Log.w(TAG, "hide with anim failed: ${it.message}") }
            .isSuccess
    }

    private fun findMethodByString(loader: ClassLoader, string: String): Method? =
        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(loader, false).use { bridge ->
                bridge.findMethod {
                    matcher { usingStrings(string) }
                }.firstOrNull()?.getMethodInstance(loader)
            }
        }.getOrNull()

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }
}
