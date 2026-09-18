package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.luckypray.dexkit.DexKitBridge

/**
 * SystemUI 侧的 QS 点击桥。
 *
 * 安全中心侧边栏只负责展示列表；真正的 CustomTile/QSTile 只能由 SystemUI
 * 的 MiuiQSHostAdapter 创建和点击，因此通过显式包内广播把点击转回 SystemUI。
 */
object SidebarQsBridgeHook {
    const val ACTION_CLICK = "io.github.z1812.hyperdock.action.CLICK_QS_TILE"
    const val ACTION_STATE = "io.github.z1812.hyperdock.action.QS_TILE_STATE"
    const val ACTION_QUERY_STATE = "io.github.z1812.hyperdock.action.QUERY_QS_TILE_STATE"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_SPEC = "spec"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_TOGGLEABLE = "toggleable"

    private const val TAG = "HyperDock[SidebarQsBridge]"
    private const val HOST_CLASS = "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"

    @Volatile
    private var host: Any? = null
    private var systemContext: Context? = null
    @Volatile
    private var receiverInstalled = false

    fun init(module: XposedModule, param: PackageLoadedParam) {
        if (param.packageName != "com.android.systemui") return
        runCatching {
            val loader = param.defaultClassLoader
            systemContext = runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Context
            }.getOrNull()
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                val context = chain.args.getOrNull(0) as? Context
                if (context != null) {
                    systemContext = context
                    installReceiver(module, context)
                }
                result
            }
            val hostClass = findHostClass(loader)
                ?: throw ClassNotFoundException("SystemUI QS host")
            hostClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    host = chain.thisObject
                    result
                }
            }
            // 构造函数可能早于模块注入完成；方法级捕获可覆盖这种时序。
            (hostClass.declaredMethods.asSequence() + hostClass.methods.asSequence()).distinctBy { it.toGenericString() }.filter { method ->
                method.name == "clickTile" || method.name == "getTiles" || method.name == "createTile" || method.name == "addTile" ||
                    (method.returnType == Void.TYPE && method.parameterCount == 1 &&
                        (method.parameterTypes[0] == ComponentName::class.java ||
                            method.parameterTypes[0] == String::class.java))
            }.forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    host = chain.thisObject
                    chain.proceed()
                }
            }
            runCatching {
                // JADX 显示 p055qs，运行时包名仍是 qs。
                val customTileClass = Class.forName("com.android.systemui.qs.external.CustomTile", false, loader)
                customTileClass.methods.filter { it.name == "updateTileState" && it.parameterCount == 2 }
                    .forEach { method ->
                        method.isAccessible = true
                        module.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            val component = chain.thisObject.javaClass.methods.firstOrNull {
                                it.name == "getComponent" && it.parameterCount == 0
                            }?.invoke(chain.thisObject) as? ComponentName
                            val tile = chain.args.firstOrNull()
                            val state = tile?.javaClass?.methods?.firstOrNull {
                                it.name == "getState" && it.parameterCount == 0
                            }?.invoke(tile) as? Int
                            if (component != null && state != null) {
                                module.log(android.util.Log.DEBUG, TAG, "tile state ${component.flattenToShortString()}=$state")
                                if (state != 0) {
                                    val toggleable = isToggleable(chain.thisObject)
                                    sendStateValue(systemContext, component.flattenToString(), null,
                                        toggleable && state == 2, toggleable)
                                }
                            }
                            result
                        }
                    }
            }
        }.onFailure {
            module.log(android.util.Log.ERROR, TAG, "init failed: ${it.message}")
        }
    }

    private fun findHostClass(loader: ClassLoader): Class<*>? {
        runCatching { Class.forName(HOST_CLASS, false, loader) }.getOrNull()?.let { return it }
        return runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(loader, false).use { bridge ->
                bridge.findClass {
                    searchPackages("com.android.systemui.qs.pipeline.domain.adapter")
                }.asSequence().mapNotNull { data ->
                    runCatching { data.getInstance(loader) }.getOrNull()
                }.firstOrNull { type ->
                    runCatching {
                        type.declaredMethods.any { method ->
                            method.parameterCount == 1 &&
                                method.parameterTypes[0] == ComponentName::class.java
                        } && type.declaredMethods.any { method ->
                            method.parameterCount == 1 &&
                                method.parameterTypes[0] == String::class.java
                        }
                    }.getOrDefault(false)
                }
            }
        }.getOrNull()
    }

    private fun installReceiver(module: XposedModule, context: Context) {
        if (receiverInstalled) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_QUERY_STATE) {
                    val component = intent.getStringExtra(EXTRA_COMPONENT)
                    val spec = intent.getStringExtra(EXTRA_SPEC)
                    host?.let { queryState(context, it, component, spec) }
                    return
                }
                if (intent.action != ACTION_CLICK) return
                val componentText = intent.getStringExtra(EXTRA_COMPONENT)
                val spec = intent.getStringExtra(EXTRA_SPEC)
                module.log(android.util.Log.DEBUG, TAG, "click request component=$componentText spec=$spec")
                val currentHost = host
                if (currentHost != null) {
                    click(module, context, currentHost, componentText, spec)
                } else {
                    module.log(android.util.Log.WARN, TAG, "click ignored: QS host unavailable")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_CLICK)
            addAction(ACTION_QUERY_STATE)
        }
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

    private fun click(module: XposedModule, context: Context, hostObject: Any, componentText: String?, spec: String?) {
        runCatching {
            if (!componentText.isNullOrEmpty()) {
                val normalizedComponent = componentText.replace("\\", "")
                val component = ComponentName.unflattenFromString(normalizedComponent)
                    ?: run {
                        module.log(android.util.Log.ERROR, TAG, "invalid tile component=$normalizedComponent")
                        return@runCatching
                    }
                val tileSpec = "custom(${component.flattenToShortString()})"
                val existing = findTile(hostObject, tileSpec)
                if (existing != null) {
                    module.log(android.util.Log.DEBUG, TAG, "click existing tile=$tileSpec")
                    ensureTileListening(existing)
                    val clickTile = hostObject.javaClass.methods.firstOrNull {
                        it.name == "clickTile" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == ComponentName::class.java
                    }
                    if (clickTile != null) clickTile.invoke(hostObject, component)
                    else invokeTileClick(existing)
                } else {
                    module.log(android.util.Log.DEBUG, TAG, "click dynamic tile=$tileSpec")
                    val tile = createTile(hostObject, tileSpec)
                    if (tile == null) {
                        module.log(android.util.Log.WARN, TAG, "createTile returned null: $tileSpec")
                    } else {
                        module.log(android.util.Log.DEBUG, TAG, "created tile=${tile.javaClass.name}")
                        initializeAndClickDynamic(tile)
                    }
                }
                return@runCatching
            }
            if (spec.isNullOrEmpty()) return@runCatching
            val existing = findTile(hostObject, spec)
            if (existing != null) {
                val clickTile = hostObject.javaClass.methods.firstOrNull {
                    it.name == "clickTile" && it.parameterCount == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (clickTile != null) clickTile.invoke(hostObject, spec)
                else invokeTileClick(existing)
                sendState(context, null, spec, existing)
            } else {
                createTile(hostObject, spec)?.let { tile ->
                    invokeTileClick(tile)
                    sendState(context, null, spec, tile)
                }
            }
        }.onFailure { module.log(android.util.Log.ERROR, TAG, "tile click failed: ${it.message}") }
    }

    private fun findTile(hostObject: Any, spec: String): Any? = runCatching {
        val tiles = hostObject.javaClass.methods.firstOrNull {
            it.name == "getTiles" && it.parameterCount == 0
        }?.invoke(hostObject) as? Iterable<*>
        tiles?.firstOrNull { item ->
            item?.javaClass?.methods?.firstOrNull {
                it.name == "getTileSpec" && it.parameterCount == 0
            }?.invoke(item) == spec
        }
    }.getOrNull()

    private fun createTile(hostObject: Any, spec: String): Any? = runCatching {
        hostObject.javaClass.methods.firstOrNull {
            it.name == "createTile" && it.parameterCount == 1 &&
                it.parameterTypes[0] == String::class.java
        }?.invoke(hostObject, spec)
    }.getOrNull()


    private fun invokeTileClick(tile: Any) {
        runCatching {
            tile.javaClass.methods.firstOrNull { it.name == "refreshState" && it.parameterCount == 0 }
                ?.invoke(tile)
        }
        val methods = (tile.javaClass.declaredMethods.asSequence() + tile.javaClass.methods.asSequence())
            .distinctBy { it.toGenericString() }
            .toList()
        val click = methods.firstOrNull { it.name == "click" && it.parameterCount == 1 }
            ?: methods.firstOrNull { it.name == "click" && it.parameterCount == 0 }
        if (click != null) {
            click.isAccessible = true
            if (click.parameterCount == 0) click.invoke(tile) else click.invoke(tile, null)
        } else {
            throw NoSuchMethodException(
                "Tile click method unavailable: ${tile.javaClass.name} methods=" +
                    methods.filter { it.name.contains("click", true) || it.name.contains("refresh", true) }
                        .joinToString { it.toGenericString() },
            )
        }
    }

    private fun initializeAndClickDynamic(tile: Any) {
        val methods = tile.javaClass.methods
        runCatching {
            methods.firstOrNull { it.name == "handleInitialize" && it.parameterCount == 0 }?.invoke(tile)
            methods.firstOrNull {
                it.name == "handleSetListening" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.invoke(tile, true)
            methods.firstOrNull { it.name == "refreshState" && it.parameterCount == 1 }?.invoke(tile, null)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { invokeTileClick(tile) }
        }, 300L)
    }

    private fun ensureTileListening(tile: Any) {
        runCatching {
            tile.javaClass.methods.firstOrNull {
                it.name == "handleSetListening" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }?.invoke(tile, true)
        }
    }

    private fun sendState(context: Context, component: String?, spec: String?, tile: Any) {
        Handler(Looper.getMainLooper()).postDelayed({
            sendStateNow(context, component, spec, tile)
        }, 350L)
    }

    private fun sendStateNow(context: Context, component: String?, spec: String?, tile: Any) {
        val toggleable = isToggleable(tile)
        val active = runCatching {
            tile.javaClass.methods.firstOrNull { it.parameterCount == 0 &&
                (it.name == "isActive" || it.name == "getActive" || it.name == "isEnabled")
            }?.invoke(tile) as? Boolean
        }.getOrNull() ?: runCatching {
            val state = tile.javaClass.methods.firstOrNull { it.name == "getState" && it.parameterCount == 0 }
                ?.invoke(tile) ?: return@runCatching null
            state.javaClass.declaredFields.firstOrNull { it.name == "value" && it.type == Boolean::class.javaPrimitiveType }
                ?.let { field ->
                    field.isAccessible = true
                    return@runCatching field.getBoolean(state)
                }
            val field = state.javaClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                ?: return@runCatching null
            field.isAccessible = true
            (field.getInt(state) == 2)
        }.getOrNull() ?: return
        sendStateValue(context, component, spec, if (toggleable) active else false, toggleable)
    }

    private fun sendStateValue(context: Context?, component: String?, spec: String?, active: Boolean, toggleable: Boolean = true) {
        context ?: return
        context.sendBroadcast(
            Intent(ACTION_STATE)
                .putExtra(EXTRA_COMPONENT, component)
                .putExtra(EXTRA_SPEC, spec)
                .putExtra(EXTRA_ENABLED, active)
                .putExtra(EXTRA_TOGGLEABLE, toggleable),
        )
    }

    private fun isToggleable(tile: Any): Boolean = runCatching {
        val direct = tile.javaClass.methods.firstOrNull {
            it.name == "isToggleableTile" && it.parameterCount == 0
        }?.invoke(tile) as? Boolean
        if (direct != null) return@runCatching direct
        val managerField = tile.javaClass.declaredFields.firstOrNull {
            it.name == "mServiceManager" || it.type.name.endsWith("TileServiceManager")
        }
            ?: return@runCatching false
        managerField.isAccessible = true
        val manager = managerField.get(tile) ?: return@runCatching false
        manager.javaClass.methods.firstOrNull {
            it.name == "isToggleableTile" && it.parameterCount == 0
        }?.invoke(manager) as? Boolean ?: false
    }.getOrDefault(false)

    private fun queryState(context: Context, hostObject: Any, component: String?, spec: String?) {
        val tileSpec = component?.let {
            val normalized = ComponentName.unflattenFromString(it) ?: return
            "custom(${normalized.flattenToShortString()})"
        } ?: spec ?: return
        val tile = findTile(hostObject, tileSpec) ?: createTile(hostObject, tileSpec) ?: return
        ensureTileListening(tile)
        runCatching {
            tile.javaClass.methods.firstOrNull { it.name == "refreshState" && it.parameterCount == 1 }
                ?.invoke(tile, null)
        }
        if (component == null) sendState(context, null, spec, tile)
    }
}
