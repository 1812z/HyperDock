package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * SystemUI 侧的 QS 点击桥。
 *
 * 安全中心侧边栏只负责展示列表；真正的 CustomTile/QSTile 只能由 SystemUI
 * 的 MiuiQSHostAdapter 创建和点击，因此通过显式包内广播把点击转回 SystemUI。
 */
object SidebarQsBridgeHook {
    const val ACTION_CLICK = "io.github.z1812.hyperdock.action.CLICK_QS_TILE"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_SPEC = "spec"

    private const val TAG = "HyperDock[SidebarQsBridge]"
    private const val HOST_CLASS = "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"

    @Volatile
    private var host: Any? = null
    @Volatile
    private var receiverInstalled = false

    fun init(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != "com.android.systemui") return
        runCatching {
            val loader = param.defaultClassLoader
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                val context = chain.args.getOrNull(0) as? Context
                if (context != null) installReceiver(module, context)
                result
            }
            val hostClass = Class.forName(HOST_CLASS, false, loader)
            hostClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    host = chain.thisObject
                    result
                }
            }
        }.onFailure {
            module.log(android.util.Log.ERROR, TAG, "init failed: ${it.message}")
        }
    }

    private fun installReceiver(module: XposedModule, context: Context) {
        if (receiverInstalled) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_CLICK) return
                val componentText = intent.getStringExtra(EXTRA_COMPONENT)
                val spec = intent.getStringExtra(EXTRA_SPEC)
                host?.let { click(it, componentText, spec) }
            }
        }
        val filter = IntentFilter(ACTION_CLICK)
        runCatching {
            val register = Context::class.java.getMethod(
                "registerReceiver", BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType,
            )
            register.invoke(context, receiver, filter, 4 /* RECEIVER_EXPORTED */)
        }.recoverCatching {
            context.registerReceiver(receiver, filter)
        }.onSuccess {
            receiverInstalled = true
        }.onFailure {
            module.log(android.util.Log.ERROR, TAG, "receiver install failed: ${it.message}")
        }
    }

    private fun click(hostObject: Any, componentText: String?, spec: String?) {
        runCatching {
            if (!componentText.isNullOrEmpty()) {
                val component = ComponentName.unflattenFromString(componentText) ?: return@runCatching
                val clickTile = hostObject.javaClass.methods.firstOrNull {
                    it.name == "clickTile" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == ComponentName::class.java
                }
                if (clickTile != null) {
                    clickTile.invoke(hostObject, component)
                    return@runCatching
                }
                val createTile = hostObject.javaClass.methods.firstOrNull {
                    it.name == "createTile" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                val tile = createTile?.invoke(hostObject, "custom($componentText)")
                tile?.javaClass?.methods?.firstOrNull {
                    it.name == "click" && it.parameterCount == 1
                }?.invoke(tile, null)
                return@runCatching
            }
            if (spec.isNullOrEmpty()) return@runCatching
            val tiles = hostObject.javaClass.methods.firstOrNull {
                it.name == "getTiles" && it.parameterCount == 0
            }?.invoke(hostObject) as? Iterable<*>
            val tile = tiles?.firstOrNull { item ->
                item?.javaClass?.methods?.firstOrNull {
                    it.name == "getTileSpec" && it.parameterCount == 0
                }?.invoke(item) == spec
            }
            tile?.javaClass?.methods?.firstOrNull {
                it.name == "click" && it.parameterCount == 1
            }?.invoke(tile, null)
        }
    }
}
