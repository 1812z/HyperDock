package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.HashSet
import java.util.WeakHashMap
import java.util.Locale
import org.luckypray.dexkit.DexKitBridge

/**
 * 快捷方式栏目注入。
 *
 * 在全部应用面板（侧边栏）顶部注入一条“快捷方式”栏目：一个 [Model.Title] 头 + 若干 [Model.Shortcut]。
 * 注入点优先选择全部应用 ViewModel 的 StateFlow setter，
 * 在列表发射前把自定义条目拼到列表最前面。这样 adapter 与 SpanSizeLookup
 * 读取的是同一份数据，不会因 adapter 侧二次注入造成栏目错位。
 *
 * 快捷方式数据来自模块侧白名单（PrefKeys.SHORTCUTS_ADDED），由本 Hook 侧做 id -> 展示名 映射，
 * 并复用原生 QuickInfo / Model.Shortcut 结构。点击动作由原生 ShortcutViewHolder 依据
 * QuickInfo 的 packageName/className/type 决定：第三方条目携带真实 TileService 组件，
 * 由原生点击逻辑继续处理；系统开关保留 SystemUI 归属，避免伪造应用组件。
 */
object SidebarShortcutHook : BaseHook() {
    private const val TAG = "HyperDock[SidebarShortcut]"

    // 下面的类均在 prepare() 中按方法/字段签名从当前 APK dex 自动发现。

    /** 注入标题的哨兵 textRes。原生标题一定使用有效资源 id，-1 不会与之冲突。 */
    private const val TITLE_SENTINEL = -1

    /** 注入快捷方式的 QuickInfo id 前缀，避免与原生快捷方式 id 冲突。 */
    private const val ID_PREFIX = "hyperdock::"

    /** 系统开关的展示顺序，需与 Compose 端 ShortcutCatalog.SYSTEM_ENTRIES 保持一致。 */
    private val SYSTEM_ORDER = listOf(
        "screen_record", "screenshot", "wifi", "bluetooth", "flashlight", "airplane_mode",
        "mobile_data", "location", "auto_rotate", "dnd", "dark_mode", "hotspot", "cast", "mute", "alarm",
    )

    private val SYSTEM_LABELS = mapOf(
        "screen_record" to ("录屏" to "Screen recording"),
        "screenshot" to ("截图" to "Screenshot"),
        "wifi" to ("WLAN" to "Wi-Fi"),
        "bluetooth" to ("蓝牙" to "Bluetooth"),
        "flashlight" to ("手电筒" to "Flashlight"),
        "airplane_mode" to ("飞行模式" to "Airplane mode"),
        "mobile_data" to ("移动数据" to "Mobile data"),
        "location" to ("定位" to "Location"),
        "auto_rotate" to ("自动旋转" to "Auto-rotate"),
        "dnd" to ("勿扰模式" to "Do not disturb"),
        "dark_mode" to ("深色模式" to "Dark mode"),
        "hotspot" to ("个人热点" to "Hotspot"),
        "cast" to ("投屏" to "Cast"),
        "mute" to ("静音" to "Mute"),
        "alarm" to ("闹钟" to "Alarm"),
    )

    private val tileLabelCache = HashMap<String, String>()
    private val tileState = HashMap<String, Boolean>()
    private val toggleableTiles = HashSet<String>()
    private val tileViews = HashMap<String, ImageView>()
    private val queriedStates = HashSet<String>()
    private val originalIconBounds = java.util.WeakHashMap<ImageView, IntArray>()
    @Volatile private var stateReceiverInstalled = false
    /** 记录本 Hook 创建的标题对象，避免使用无效 textRes 作为识别标记。 */
    private val injectedTitles = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    // ── 反射缓存 ──────────────────────────────────────────────────────────────
    private var adapterMethod: Method? = null
    private var stateFlowSetMethods: List<Method> = emptyList()
    private var titleBindMethod: Method? = null
    private var shortcutBindMethod: Method? = null
    private var genericBindMethod: Method? = null
    private var adapterBindMethods: List<Method> = emptyList()
    private var shortcutClickMethod: Method? = null
    private var clickDispatchMethod: Method? = null
    private var shortcutCtor: Constructor<*>? = null
    private var titleCtor: Constructor<*>? = null
    private var quickInfoCtor: Constructor<*>? = null
    private var quickInfoStringFields: List<Field> = emptyList()
    private var quickInfoTypeField: Field? = null
    private var quickInfoTypeClass: Class<*>? = null
    private var shortcutQuickInfoField: Field? = null
    private var editStateNone: Any? = null
    private var titleIntField: Field? = null
    private var titleStringField: Field? = null
    private var holderTextViewField: Field? = null
    private var shortcutIconViewField: Field? = null
    private var shortcutModelField: Field? = null
    private var holderCurrentModelField: Field? = null
    private var discoveredClasses: List<Class<*>>? = null
    private var modelItemType: Class<*>? = null
    private var spanSizeMethods: List<Method> = emptyList()
    @Volatile private var injectedPrefixSize = 0
    @Volatile private var stateFlowSynchronized = false
    @Volatile private var officialItemCount = 0
    @Volatile private var injectedTail: List<Any> = emptyList()
    @Volatile private var activeAdapterModels: List<Any> = emptyList()
    private var modelRootType: Class<*>? = null
    private var titleModelType: Class<*>? = null
    private var shortcutModelType: Class<*>? = null
    private var dividerModel: Any? = null
    private var prepareRetryCount = 0
    private var prepareRetryScheduled = false
    private var lastPrepareFailure: String? = null
    private var preparePermanentlyFailed = false
    private var injectionLogged = false

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            //log(module, "skip non-UI process: $processName")
            return
        }
        installStateReceiver()
        if (preparePermanentlyFailed) return
        if (!prepare(module, param.defaultClassLoader)) {
            schedulePrepareRetry(module, param)
            return
        }
        prepareRetryCount = 0

        // 优先保留 StateFlow；同时保留 Adapter 列表提交入口作为运行时兜底，
        // 因为部分版本的 setter 只用于缓存，展开面板实际走 adapter 提交。
        if (stateFlowSetMethods.isNotEmpty()) {
            log(module, "injection mode=STATE_FLOW (${stateFlowSetMethods.size} setter(s))")
            stateFlowSetMethods.forEach { m ->
                try {
                    module.hook(m).intercept { chain ->
                        val value = chain.args.getOrNull(0)
                        if (value is List<*>) {
                            @Suppress("UNCHECKED_CAST")
                            val list = value as List<Any>
                            // SecurityCenter reuses the same MutableStateFlow implementation
                            // for search/pinned/edit-state lists. Only mutate the flow whose
                            // payload is actually Model (App/Title/Shortcut); touching an
                            // unrelated list makes native titles get sorted as tiles.
                            if (isLikelyAllAppsList(list)) {
                                val injected = inject(module, list)
                                if (injected !== list) stateFlowSynchronized = true
                                return@intercept chain.proceed(arrayOf<Any?>(injected))
                            }
                        } else {
                            // The main content flow emits C5724g0.c.b, not a raw
                            // List. SpanSizeLookup unwraps that object through its
                            // single List accessor, so rebuild the wrapper with
                            // the exact injected list as well.
                            val wrapped = injectWrappedState(module, value)
                            if (wrapped != null) {
                                stateFlowSynchronized = true
                                return@intercept chain.proceed(arrayOf(wrapped))
                            }
                        }
                        chain.proceed()
                    }
                } catch (t: Throwable) {
                    logError(module, "StateFlow hook failed: ${t.message}")
                }
            }
        }
        adapterMethod?.let { m ->
            log(module, if (stateFlowSetMethods.isEmpty()) "injection mode=ADAPTER_FALLBACK" else "adapter submit fallback enabled")
            try {
                module.hook(m).intercept { chain ->
                    val original = chain.args.getOrNull(0) as? List<Any>
                        ?: return@intercept chain.proceed()
                    if (isLikelyAllAppsList(original)) {
                        val injected = inject(module, original)
                        activeAdapterModels = injected
                        chain.proceed(arrayOf<Any?>(injected))
                    } else {
                        chain.proceed()
                    }
                }
            } catch (t: Throwable) {
                logError(module, "adapter hook failed: ${t.message}")
            }
        }
        spanSizeMethods.forEach { method ->
            try {
                module.hook(method).intercept { chain ->
                    val position = chain.args.getOrNull(0) as? Int
                        ?: return@intercept chain.proceed()
                    val displayed = activeAdapterModels
                    if (position in displayed.indices) {
                        val item = displayed[position]
                        val wholeLine = item.javaClass == titleModelType ||
                            dividerModel?.javaClass == item.javaClass
                        return@intercept if (wholeLine) {
                            chain.proceed(arrayOf<Any?>(0)) as? Int ?: 1
                        } else 1
                    }
                    val officialCount = officialItemCount
                    val tail = injectedTail
                    if (position >= officialCount && position - officialCount in tail.indices) {
                        val item = tail[position - officialCount]
                        val wholeLine = item.javaClass == titleModelType ||
                            dividerModel?.javaClass == item.javaClass
                        return@intercept if (wholeLine) {
                            chain.proceed(arrayOf<Any?>(0)) as? Int ?: 1
                        } else 1
                    }
                    if (stateFlowSynchronized) return@intercept chain.proceed()
                    val prefix = injectedPrefixSize
                    val headSpan = chain.proceed(arrayOf<Any?>(0)) as? Int ?: 1
                    when {
                        prefix <= 0 -> chain.proceed()
                        headSpan <= 1 -> chain.proceed()
                        position == 0 -> headSpan
                        position < prefix -> 1
                        else -> chain.proceed(arrayOf<Any?>(position - prefix))
                    }
                }
            } catch (t: Throwable) {
                logError(module, "span lookup hook failed: ${t.message}")
            }
        }
        log(module, "shortcut hooks bind=${adapterBindMethods.size} click=${shortcutClickMethod != null} " +
            "dispatch=${clickDispatchMethod != null} span=${spanSizeMethods.size} divider=${dividerModel != null}")

        titleBindMethod?.let { m ->
            try {
                module.hook(m).intercept { chain ->
                    val title = chain.args.getOrNull(0)
                    if (title != null && isInjectedTitle(title)) {
                        // 原生 bind 会把 textRes=-1 交给 TextView.setText(int)，触发
                        // Resources.NotFoundException。标题样式来自 XML，直接改文案即可。
                        applyTitleText(chain.thisObject, readTitleResolvedText(title))
                        null
                    } else {
                        val result = chain.proceed()
                        applyNativeTitleStyle(chain.thisObject)
                        result
                    }
                }
            } catch (t: Throwable) {
                logError(module, "title bind hook failed: ${t.message}")
            }
        }

        shortcutBindMethod?.let { m ->
            try {
                module.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    val model = chain.args.getOrNull(0)
                    if (model != null && isInjectedShortcut(model)) {
                        bindInjectedIcon(chain.thisObject, model)
                    } else {
                        resetNativeIcon(chain.thisObject)
                    }
                    result
                }
            } catch (t: Throwable) {
                logError(module, "shortcut bind hook failed: ${t.message}")
            }
        }

        genericBindMethod?.let { m ->
            try {
                module.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    val model = chain.args.getOrNull(0)
                    if (model != null && isInjectedShortcut(model)) {
                        bindInjectedIcon(chain.thisObject, model)
                    } else {
                        resetNativeIcon(chain.thisObject)
                    }
                    result
                }
            } catch (t: Throwable) {
                logError(module, "generic shortcut bind hook failed: ${t.message}")
            }
        }

        shortcutClickMethod?.let { m ->
            try {
                module.hook(m).intercept { chain ->
                    val directModel = runCatching { shortcutModelField?.get(chain.thisObject) }.getOrNull()
                    val directQuickInfo = directModel?.let(::quickInfoFromModel)
                    if (directModel != null && directQuickInfo != null &&
                        quickInfoId(directQuickInfo).startsWith(ID_PREFIX)) {
                        val image = runCatching { shortcutIconViewField?.get(chain.thisObject) as? ImageView }.getOrNull()
                        val context = image?.context ?: appContext()
                        if (context != null) {
                            handleInjectedModelClick(directModel, context)
                            bindInjectedIcon(chain.thisObject, directModel)
                        }
                        return@intercept null
                    }
                    val handled = handleInjectedClick(chain.thisObject)
                    if (handled) null else chain.proceed()
                }
            } catch (t: Throwable) {
                logError(module, "shortcut click hook failed: ${t.message}")
            }
        }

        clickDispatchMethod?.let { m ->
            try {
                module.hook(m).intercept { chain ->
                    val handled = runCatching { handleInjectedClick(chain.thisObject) }
                        .onFailure { Log.e(TAG, "click dispatch exception", it) }
                        .getOrDefault(false)
                    if (handled) null else chain.proceed()
                }
            } catch (t: Throwable) {
                logError(module, "generic click dispatch hook failed: ${t.message}")
            }
        }

        adapterBindMethods.forEach { m ->
            try {
                module.hook(m).intercept { chain ->
                    val result = chain.proceed()
                    val holder = chain.args.firstOrNull() ?: return@intercept result
                    val model = findShortcutModel(holder) ?: return@intercept result
                    if (isInjectedShortcut(model)) {
                        installDirectShortcutClick(holder, model)
                    } else {
                        resetNativeIcon(holder)
                    }
                    result
                }
            } catch (t: Throwable) {
                logError(module, "adapter bind hook failed: ${t.message}")
            }
        }
    }

    // ── 初始化反射句柄 ─────────────────────────────────────────────────────────

    private fun prepare(module: XposedModule, loader: ClassLoader): Boolean {
        return try {
            stateFlowSynchronized = false
            injectedPrefixSize = 0
            officialItemCount = 0
            injectedTail = emptyList()
            activeAdapterModels = emptyList()
            val adapterClass = findAdapterClass(loader)
                ?: throw ClassNotFoundException("all-apps adapter by signature")
            val modelClass = findModelRootClass(loader)
                ?: throw ClassNotFoundException("all-apps model root by nested constructors")
            modelRootType = modelClass
            val titleClass = findTitleModelClass(modelClass)
                ?: throw ClassNotFoundException("Model.Title by constructor")
            val shortcutClass = findShortcutModelClass(modelClass)
                ?: throw ClassNotFoundException("Model.Shortcut by constructor")
            titleModelType = titleClass
            shortcutModelType = shortcutClass
            dividerModel = findDividerModel(modelClass)
            val shortcutCtorCandidate = shortcutClass.declaredConstructors.firstOrNull {
                it.parameterCount == 3 &&
                    !it.parameterTypes[0].isPrimitive &&
                    it.parameterTypes[2].isEnum &&
                    looksLikeQuickInfo(it.parameterTypes[0])
            } ?: throw ClassNotFoundException("Model.Shortcut constructor")
            val quickInfoClass = shortcutCtorCandidate.parameterTypes[0]
            val editStateClass = shortcutCtorCandidate.parameterTypes[2]
            shortcutQuickInfoField = shortcutClass.declaredFields.firstOrNull {
                it.type == quickInfoClass
            }?.also { it.isAccessible = true }
            adapterMethod = adapterClass.declaredMethods.firstOrNull { m ->
                m.returnType == Void.TYPE && m.parameterCount == 1 &&
                List::class.java.isAssignableFrom(m.parameterTypes[0])
            }
            adapterBindMethods = adapterClass.declaredMethods.filter { method ->
                !Modifier.isAbstract(method.modifiers) &&
                    method.returnType == Void.TYPE && method.parameterCount >= 2 &&
                    !method.parameterTypes[0].isPrimitive &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
            }.onEach { it.isAccessible = true }
            spanSizeMethods = findSpanSizeMethods(loader)

            // 不依赖 r8 优化后的字段/方法签名：只按公开语义寻找单参数 setValue，
            // 并在运行时以 List 参数/值做校验。不同版本可能有桥接重载，全部去重 hook。
            // StateFlow discovery is opportunistic. A foreign field type must
            // never abort the whole hook; adapter injection remains available.
            stateFlowSetMethods = runCatching {
                findStateFlowSetters(adapterClass, loader)
            }.getOrDefault(emptyList())

            val titleHolderClass = findTitleHolderClass(loader, titleClass)
                ?: throw ClassNotFoundException("title holder by signature")
            titleBindMethod = titleHolderClass.declaredMethods.firstOrNull { m ->
                !Modifier.isAbstract(m.modifiers) &&
                    m.returnType == Void.TYPE && m.parameterCount == 1 && m.parameterTypes[0] == titleClass
            }

            val shortcutHolderClass = findShortcutHolderClass(loader, shortcutClass)
                ?: throw ClassNotFoundException("shortcut holder by signature")
            // onClick/Model$c 逻辑在快捷 holder 的父类中；由实际继承关系取得，
            // 基础点击逻辑从快捷持有者的父类取得。
            val shortcutBaseHolderClass = shortcutHolderClass.superclass
                ?: throw ClassNotFoundException("shortcut holder base")
            modelItemType = findModelItemType(shortcutBaseHolderClass, titleClass, shortcutClass)

            quickInfoCtor = quickInfoClass.declaredConstructors
                .sortedBy { it.parameterCount }
                .firstOrNull()?.also { it.isAccessible = true }
                ?: throw NoSuchMethodException("QuickInfo constructor")
            shortcutCtor = shortcutCtorCandidate.also { it.isAccessible = true }
            titleCtor = titleClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType, String::class.java,
            ).also { it.isAccessible = true }
            editStateNone = enumByName(editStateClass, "NONE")

            // QuickInfo 的 8 个 String 字段被混淆为 a~h，排序后即 id/icon/name/title/action/uri/pkg/cls。
            quickInfoStringFields = generateSequence(quickInfoClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { it.type == String::class.java }
                .sortedBy { it.name }
                .toList()
                .onEach { it.isAccessible = true }
            quickInfoTypeField = generateSequence(quickInfoClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.type.isEnum }
            quickInfoTypeField?.isAccessible = true
            quickInfoTypeClass = quickInfoTypeField?.type

            // Title 是 data class，仅含 int textRes 与 String resolvedText 两个实例字段。
            titleIntField = titleClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
            titleIntField?.isAccessible = true
            titleStringField = titleClass.declaredFields.firstOrNull { it.type == String::class.java }
            titleStringField?.isAccessible = true

            // Title ViewHolder 仅持有一个 TextView。
            holderTextViewField = titleHolderClass.declaredFields
                .firstOrNull { TextView::class.java.isAssignableFrom(it.type) }
            holderTextViewField?.isAccessible = true

            shortcutBindMethod = shortcutHolderClass.declaredMethods.firstOrNull { method ->
                !Modifier.isAbstract(method.modifiers) &&
                    method.returnType == Void.TYPE && method.parameterCount == 1 &&
                    method.parameterTypes[0] == shortcutClass
            }?.also { it.isAccessible = true }
            shortcutClickMethod = findConcreteViewMethod(shortcutHolderClass, preferOnClick = false)
            clickDispatchMethod = findConcreteViewMethod(shortcutBaseHolderClass, preferOnClick = true)
            genericBindMethod = shortcutBaseHolderClass.declaredMethods.firstOrNull { method ->
                !Modifier.isAbstract(method.modifiers) && method.parameterCount == 1 &&
                    modelItemType?.let { method.parameterTypes[0] == it } == true &&
                    method.returnType == Void.TYPE
            }?.also { it.isAccessible = true }
            var holderType: Class<*>? = shortcutHolderClass
            while (holderType != null && holderType != Any::class.java) {
                if (shortcutIconViewField == null) {
                    shortcutIconViewField = holderType.declaredFields.firstOrNull {
                        ImageView::class.java.isAssignableFrom(it.type)
                    }?.also { it.isAccessible = true }
                }
                if (shortcutModelField == null) {
                    shortcutModelField = holderType.declaredFields.firstOrNull {
                        it.type == shortcutClass
                    }?.also { it.isAccessible = true }
                }
                if (holderCurrentModelField == null) {
                    holderCurrentModelField = holderType.declaredFields.firstOrNull {
                        modelItemType?.let { type -> it.type == type } == true
                    }?.also { it.isAccessible = true }
                }
                holderType = holderType.superclass
            }

            if (stateFlowSetMethods.isEmpty() && adapterMethod == null) {
                logWarn(module, "StateFlow setValue and adapter o(List) not found")
            }
            if (titleBindMethod == null) logWarn(module, "title bind method not found")
            if (shortcutBindMethod == null) logWarn(module, "shortcut bind method not found")
            if (shortcutClickMethod == null) logWarn(module, "shortcut click method not found")
            if (clickDispatchMethod == null) logWarn(module, "click dispatch method not found")
            if (shortcutCtor == null || quickInfoCtor == null || editStateNone == null) {
                logWarn(module, "shortcut model wiring incomplete")
            }
            stateFlowSetMethods.isNotEmpty() || adapterMethod != null
        } catch (t: Throwable) {
            lastPrepareFailure = "${t.javaClass.simpleName}: ${t.message}"
            log(module, "prepare deferred: $lastPrepareFailure")
            false
        }
    }

    private fun findModelRootClass(loader: ClassLoader): Class<*>? = discoverClasses(loader)
        .sortedWith(compareBy<Class<*>> {
            // The package is part of the feature contract, while the class name is
            // R8-renamed between releases. Prefer this package without hardcoding
            // Model/Title/Shortcut names.
            if (it.packageName == "com.miui.dock.allapps") 0 else 1
        }.thenByDescending { Modifier.isAbstract(it.modifiers) })
        .firstOrNull { root ->
        runCatching {
            val hasViewType = root.declaredMethods.any { method ->
                method.parameterCount == 0 && method.returnType.isEnum
            }
            root.declaredClasses.any { type ->
                type.declaredConstructors.any { ctor ->
                    ctor.parameterCount == 2 && ctor.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        ctor.parameterTypes[1] == String::class.java
                }
            } && root.declaredClasses.any { type ->
                type.declaredConstructors.any { ctor ->
                    ctor.parameterCount == 3 &&
                        !ctor.parameterTypes[0].isPrimitive &&
                        ctor.parameterTypes[2].isEnum &&
                        looksLikeQuickInfo(ctor.parameterTypes[0])
                }
            } && hasViewType
        }.getOrDefault(false)
    }

    private fun findTitleModelClass(modelClass: Class<*>): Class<*>? =
        runCatching {
            modelClass.declaredClasses.firstOrNull { type ->
                type.superclass == modelClass &&
                type.declaredConstructors.any { ctor ->
                    ctor.parameterCount == 2 &&
                        ctor.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        ctor.parameterTypes[1] == String::class.java
                }
            }
        }.getOrNull()

    /** Model.C5707b is the official section separator (a singleton instance). */
    private fun findDividerModel(modelClass: Class<*>): Any? = runCatching {
        modelClass.declaredClasses.firstOrNull { type ->
            type != titleModelType && type != shortcutModelType &&
                modelClass.isAssignableFrom(type) &&
                type.declaredConstructors.none { it.parameterCount > 0 } &&
                type.declaredFields.any { field ->
                    Modifier.isStatic(field.modifiers) && field.type == type
                }
        }?.let { type ->
            val field = type.declaredFields.first { field ->
                Modifier.isStatic(field.modifiers) && field.type == type
            }.also { it.isAccessible = true }
            field.get(null)
        }
    }.getOrNull()

    private fun findShortcutModelClass(modelClass: Class<*>): Class<*>? =
        runCatching {
            modelClass.declaredClasses.firstOrNull { type ->
                type.superclass != null &&
                    modelClass.isAssignableFrom(type.superclass) &&
                type.declaredConstructors.any { ctor ->
                    ctor.parameterCount == 3 &&
                        !ctor.parameterTypes[0].isPrimitive &&
                        ctor.parameterTypes[2].isEnum &&
                        looksLikeQuickInfo(ctor.parameterTypes[0])
                }
            }
        }.getOrNull()

    /**
     * QuickInfo is not identified by its R8 name. In the current APK JADX shows
     * eight String payload fields (id/icon/name/title/action/uri/package/class)
     * and an enum type field. Requiring that shape prevents an unrelated nested
     * three-argument model from being selected as Model.Shortcut.
     */
    private fun looksLikeQuickInfo(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isEnum || type.isInterface) return false
        val fields = generateSequence(type as Class<*>?) { it.superclass }
            .flatMap { runCatching { it.declaredFields.asSequence() }.getOrDefault(emptySequence()) }
            .toList()
        val strings = fields.count { it.type == String::class.java }
        val enumFields = fields.count { it.type.isEnum }
        val hasJsonLoader = runCatching {
            type.declaredMethods.any { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0].name == "org.json.JSONObject"
            }
        }.getOrDefault(false)
        return strings >= 8 && enumFields >= 1 && hasJsonLoader
    }

    private fun findModelItemType(
        baseHolderClass: Class<*>,
        titleClass: Class<*>,
        shortcutClass: Class<*>,
    ): Class<*>? = baseHolderClass.declaredMethods.firstOrNull { method ->
        method.parameterCount == 1 && method.returnType == Void.TYPE &&
            method.parameterTypes[0].declaringClass?.let { it == titleClass.declaringClass } == true &&
            method.parameterTypes[0] != titleClass && method.parameterTypes[0] != shortcutClass
    }?.parameterTypes?.firstOrNull()

    private fun findConcreteViewMethod(type: Class<*>, preferOnClick: Boolean): Method? {
        val methods = generateSequence(type as Class<*>?) { it.superclass }
            .flatMap { current ->
                runCatching { current.declaredMethods.asSequence() }.getOrDefault(emptySequence())
            }
            .filter { method ->
                !Modifier.isAbstract(method.modifiers) && method.returnType == Void.TYPE &&
                    method.parameterCount == 1 && View::class.java.isAssignableFrom(method.parameterTypes[0])
            }
            .toList()
        return (if (preferOnClick) {
            methods.firstOrNull { it.name == "onClick" } ?: methods.firstOrNull()
        } else {
            methods.firstOrNull { it.name == "onClick" } ?: methods.firstOrNull()
        })?.also { it.isAccessible = true }
    }

    /** Resolve adapter by its list-submit + RecyclerView bind contract. */
    private fun findAdapterClass(loader: ClassLoader): Class<*>? {
        return discoverClasses(loader).sortedBy {
            if (it.packageName == "com.miui.dock.allapps") 0 else 1
        }.firstOrNull { candidate ->
            candidate.declaredMethods.any {
                    it.parameterCount == 1 && it.returnType == Void.TYPE &&
                        List::class.java.isAssignableFrom(it.parameterTypes[0])
                } && candidate.declaredMethods.any {
                    !Modifier.isAbstract(it.modifiers) && it.returnType == Void.TYPE &&
                        it.parameterCount >= 2 && !it.parameterTypes[0].isPrimitive &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType
                }
        }
    }

    /**
     * Find GridLayoutManager.SpanSizeLookup by inheritance and its int(int)
     * contract. The concrete nested class name is R8-dependent.
     */
    private fun findSpanSizeMethods(loader: ClassLoader): List<Method> = discoverClasses(loader)
        .asSequence()
        .filter { it.packageName == "com.miui.dock.allapps" }
        .filter { candidate ->
            generateSequence(candidate.superclass as Class<*>?) { it.superclass }
                .any { it.name.contains("GridLayoutManager") }
        }
        .flatMap { it.declaredMethods.asSequence() }
        .filter { method ->
            !Modifier.isAbstract(method.modifiers) &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        .onEach { it.isAccessible = true }
        .toList()

    /** Find the real holder in the current SecurityCenter dex by the model signature. */
    private fun findShortcutHolderClass(loader: ClassLoader, shortcutClass: Class<*>): Class<*>? {
        return discoverClasses(loader).firstOrNull { candidate ->
            val bindsShortcut = candidate.declaredMethods.any { method ->
                method.parameterCount == 1 && method.returnType == Void.TYPE &&
                    method.parameterTypes[0] == shortcutClass
            }
            val handlesView = candidate.declaredMethods.any { method ->
                method.parameterCount == 1 &&
                    View::class.java.isAssignableFrom(method.parameterTypes[0])
            } || candidate.superclass?.declaredMethods?.any { method ->
                method.parameterCount == 1 &&
                    View::class.java.isAssignableFrom(method.parameterTypes[0])
            } == true
            bindsShortcut && handlesView
        }
    }

    /** Title holder is similarly identified by its single Model.Title bind method. */
    private fun findTitleHolderClass(loader: ClassLoader, titleClass: Class<*>): Class<*>? {
        return discoverClasses(loader).firstOrNull { candidate ->
            candidate.declaredMethods.any { method ->
                method.parameterCount == 1 && method.returnType == Void.TYPE &&
                    method.parameterTypes[0] == titleClass
            }
        }
    }

    /**
     * 优先从 AllApps adapter 持有的状态对象反推出 setter，避免把某一版 R8 类名
     * 当成协议；只按 StateFlow 的行为契约匹配。
     */
    private fun findStateFlowSetters(adapterClass: Class<*>, loader: ClassLoader): List<Method> {
        val candidates = LinkedHashSet<Class<*>>()
        var type: Class<*>? = adapterClass
        while (type != null && type != Any::class.java) {
            runCatching { type.declaredFields.toList() }.getOrDefault(emptyList()).forEach { field ->
                val fieldType = field.type
                if (runCatching {
                        fieldType.methods.any { it.name == "setValue" && it.parameterCount == 1 }
                    }.getOrDefault(false)) {
                    candidates.add(fieldType)
                }
            }
            type = type.superclass
        }
        // 主路径：按 StateFlow 的行为契约扫描当前 APK，不依赖 vp.h0/kp.h0 等 R8 名称。
        discoverClasses(loader)
            .filter { type ->
                runCatching {
                    type.declaredMethods.any { m ->
                        m.name == "setValue" && m.parameterCount == 1 && m.returnType == Void.TYPE
                    } && type.declaredMethods.any { m ->
                        m.name == "getValue" && m.parameterCount == 0
                    }
                }.getOrDefault(false)
            }
            .forEach(candidates::add)
        return candidates.flatMap { stateClass ->
            val methods = runCatching {
                stateClass.methods.asSequence().toList() + stateClass.declaredMethods.asSequence().toList()
            }.getOrDefault(emptyList())
            methods.asSequence()
                .filter { m ->
                    m.name == "setValue" && m.parameterCount == 1 &&
                        m.returnType == Void.TYPE
                }
                .onEach { it.isAccessible = true }
                .toList()
        }.distinctBy { it.declaringClass.name + "#" + it.toGenericString() }
    }

    // ── 注入 ──────────────────────────────────────────────────────────────────

    private fun inject(module: XposedModule, original: List<Any>): List<Any> {
        if (!injectionLogged) {
            log(module, "all-apps list accepted size=${original.size} nativeTitles=" +
                original.count { titleModelType?.let { type -> it.javaClass == type } == true })
            injectionLogged = true
        }
        // The adapter emits an empty initial snapshot before All Apps data is
        // loaded. Do not create a transient shortcut section from that snapshot;
        // the next non-empty emission will be processed normally.
        if (original.isEmpty()) return original
        // 每次 StateFlow 发射都代表面板重新展开/刷新，重新读取 SystemUI 的当前状态。
        queriedStates.clear()
        // setValue 可能被多个观察者/重放路径调用；绝不能叠加第二个自定义栏目。
        if (original.any { isInjectedTitle(it) || isInjectedShortcut(it) }) return original
        // Preserve the configured app whitelist/blacklist. The native list and
        // its state wrapper are both passed through this same injection path.
        val filtered = filterCustomApps(original)
        val quickFunctions = if (ConfigManager.getBoolean(PrefKeys.QUICK_FUNCTIONS_ENABLED, false)) {
            ConfigManager.getStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED, emptySet())
                .filter { it.isNotBlank() }
                .map { "activity:$it" }
                .sorted()
        } else emptyList()
        val added = if (ConfigManager.getBoolean(PrefKeys.SHORTCUTS_ENABLED, false)) {
            ConfigManager.getStringSet(PrefKeys.SHORTCUTS_ADDED, emptySet())
        } else emptySet()
        val ordered = orderIds(added)
        if (quickFunctions.isEmpty() && ordered.isEmpty()) {
            injectedPrefixSize = 0
            officialItemCount = filtered.size
            injectedTail = emptyList()
            return filtered
        }
        injectedPrefixSize = 0

        val result = ArrayList<Any>(filtered.size + quickFunctions.size + ordered.size + 2)
        // Keep a valid resource id as a crash-safe fallback if a title holder
        // variant is not covered by the runtime hook. Covered holders replace
        // the text with resolvedText below.
        val styleRes = filtered.asSequence()
            .mapNotNull { readTitleResource(it) }
            .firstOrNull { it > 0 }
            ?: android.R.string.ok
        // Keep the official dataset byte-for-byte in front. Appending our own
        // section means native title positions, sorting and SpanSizeLookup stay
        // completely untouched.
        result.addAll(filtered)
        if (ordered.isNotEmpty()) {
            dividerModel?.let { result.add(it) }
            buildTitle(styleRes, sectionTitle())?.let {
                result.add(it)
            }
        }
        var created = 0
        ordered.forEach { id ->
            buildShortcut(id)?.let {
                result.add(it)
                created++
            }
        }
        if (quickFunctions.isNotEmpty()) {
            dividerModel?.let { result.add(it) }
            buildTitle(styleRes, quickFunctionTitle())?.let { result.add(it) }
        }
        // 自定义显式 Activity 单独作为“快捷功能”栏目追加，避免和磁贴混排。
        quickFunctions.forEach { id ->
            buildShortcut(id)?.let { result.add(it) }
        }
        officialItemCount = filtered.size
        injectedTail = result.drop(filtered.size)
        if (ordered.isNotEmpty() && created == 0) {
            log(module, "shortcut models unavailable: requested=${ordered.size} quickInfoFields=${quickInfoStringFields.size}")
        }
        return result
    }

    private fun injectWrappedState(module: XposedModule, value: Any?): Any? {
        if (value == null) return null
        val type = value.javaClass
        val list = runCatching {
            type.methods.firstOrNull { it.parameterCount == 0 &&
                List::class.java.isAssignableFrom(it.returnType) }
                ?.also { it.isAccessible = true }
                ?.invoke(value) as? List<Any>
        }.getOrNull() ?: runCatching {
            generateSequence(type as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { List::class.java.isAssignableFrom(it.type) }
                ?.also { it.isAccessible = true }
                ?.get(value) as? List<Any>
        }.getOrNull() ?: return null
        if (!isLikelyAllAppsList(list)) return null
        val injected = inject(module, list)
        if (injected === list) return value
        val ctor = type.declaredConstructors.firstOrNull {
            it.parameterCount == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return null
        return runCatching {
            ctor.isAccessible = true
            ctor.newInstance(injected)
        }.getOrNull()
    }

    /** 安全中心内 StateFlow 是通用容器；必须排除小窗/其它列表。 */
    private fun isLikelyAllAppsList(value: List<*>): Boolean {
        if (value.isEmpty()) return false
        val model = modelRootType
        val title = titleModelType
        val shortcut = shortcutModelType
        var hasModelItem = false
        var hasNativeTitle = false
        value.forEach { item ->
            val type = item?.javaClass ?: return@forEach
            if (model != null && model.isAssignableFrom(type)) {
                hasModelItem = true
            }
            if (title != null && type == title && !isInjectedTitle(item)) {
                hasNativeTitle = true
            }
        }
        // SpanSizeLookup reads the dedicated main-content StateFlow by position.
        // Depending on load timing, its first emission can be the raw app list
        // before native section titles are inserted. The main list is still the
        // large Model list; pinned/search/edit lists are bounded and much smaller.
        // Accept either a native-title list or a sufficiently large Model list so
        // the state used by SpanSizeLookup is updated before the adapter binds.
        return hasModelItem && (hasNativeTitle || value.size >= 32)
    }

    /** 根据设置页的黑/白名单过滤原生 Model.App；其它标题、快捷方式保持不变。 */
    private fun filterCustomApps(original: List<Any>): List<Any> {
        if (!ConfigManager.getBoolean(PrefKeys.ALL_APPS_CUSTOM_ENABLED, false)) return original
        val selected = ConfigManager.getStringSet(PrefKeys.ALL_APPS_CUSTOM_PACKAGES, emptySet())
        val whitelist = ConfigManager.getString(PrefKeys.ALL_APPS_CUSTOM_MODE, "blacklist") == "whitelist"
        val filtered = original.filter { item ->
            val pkg = packageNameOf(item, selected)
            if (pkg.isNullOrEmpty()) {
                true
            } else {
                if (whitelist) pkg in selected else pkg !in selected
            }
        }
        return filtered
    }

    private fun packageNameOf(item: Any, selected: Set<String>): String? {
        val hierarchy = generateSequence(item.javaClass as Class<*>?) { it.superclass }.toList()
        // AllApps 列表同时包含原生快捷功能/标题。快捷功能通常暴露
        // getQuickInfo()，不能把其中的 action、uri 或组件字符串误判为包名。
        if (hierarchy.any { it.name.contains("Shortcut") || it.name.contains("Title") } ||
            hierarchy.flatMap { it.declaredMethods.asSequence() }
                .any { it.name == "getQuickInfo" && it.parameterCount == 0 }
        ) return null
        val methods = hierarchy.asSequence()
            .flatMap { it.declaredMethods.asSequence() }
        val methodValue = methods.firstNotNullOfOrNull { method ->
            if (method.parameterCount != 0 || method.returnType != String::class.java) return@firstNotNullOfOrNull null
            runCatching {
                method.isAccessible = true
                val value = method.invoke(item) as? String
                value?.takeIf { it in selected || (it.contains('.') && it.length >= 6) }
            }.getOrNull()
        }
        if (methodValue != null) return methodValue
        val fields = hierarchy.asSequence()
            .flatMap { it.declaredFields.asSequence() }
        return fields.firstNotNullOfOrNull { field ->
            if (field.type != String::class.java) return@firstNotNullOfOrNull null
            runCatching {
                field.isAccessible = true
                val value = field.get(item) as? String
                value?.takeIf { it in selected || (it.contains('.') && it.length >= 6) }
            }.getOrNull()
        }
    }

    private fun orderIds(added: Set<String>): List<String> {
        val system = added.filter { it in SYSTEM_LABELS }.sortedBy { SYSTEM_ORDER.indexOf(it) }
        val activities = added.filter { it.startsWith("activity:") }.sorted()
        val thirdParty = added.filter { '/' in it && !it.startsWith("activity:") }.sorted()
        val others = added.filter { it !in SYSTEM_LABELS && !it.startsWith("activity:") && '/' !in it }.sorted()
        return system + activities + thirdParty + others
    }

    private fun buildTitle(styleRes: Int, title: String): Any? {
        val ctor = titleCtor ?: return null
        return runCatching {
            ctor.newInstance(styleRes, title).also { injectedTitles.add(it) }
        }.getOrNull()
    }

    private fun newQuickInfo(ctor: Constructor<*>): Any? {
        val args = Array<Any?>(ctor.parameterTypes.size) { index ->
            val type = ctor.parameterTypes[index]
            when {
                type == String::class.java -> ""
                type == Boolean::class.javaPrimitiveType -> false
                type == Byte::class.javaPrimitiveType -> 0.toByte()
                type == Short::class.javaPrimitiveType -> 0.toShort()
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Float::class.javaPrimitiveType -> 0f
                type == Double::class.javaPrimitiveType -> 0.0
                type == Char::class.javaPrimitiveType -> '\u0000'
                type.isEnum -> type.enumConstants?.firstOrNull()
                else -> null
            }
        }
        return runCatching { ctor.newInstance(*args) }.getOrNull()
    }

    private fun buildShortcut(id: String): Any? {
        val qiCtor = quickInfoCtor ?: return null
        val shortcutCtor = shortcutCtor ?: return null
        val qi = newQuickInfo(qiCtor) ?: return null
        val target = resolveShortcut(id) ?: return null
        if (!writeQuickInfo(qi, id, target)) return null
        return runCatching { shortcutCtor.newInstance(qi, false, editStateNone) }.getOrNull()
    }

    private fun resolveShortcut(id: String): ShortcutTarget? {
        SYSTEM_LABELS[id]?.let { (zh, en) ->
            return ShortcutTarget("", "", if (isChinese()) zh else en, null)
        }
        if (id.startsWith("activity:")) {
            val componentId = id.removePrefix("activity:")
            val component = ComponentName.unflattenFromString(normalizeComponentId(componentId)) ?: return null
            val context = appContext() ?: return null
            val activityInfo = runCatching {
                context.packageManager.getActivityInfo(component, 0)
            }.getOrNull() ?: return null
            return ShortcutTarget(
                component.packageName,
                component.className,
                runCatching { activityInfo.loadLabel(context.packageManager).toString() }
                    .getOrDefault(component.className.substringAfterLast('.')),
                activityInfo.applicationInfo?.let { rawApplicationIconUri(context, it) },
            )
        }
        return if ('/' in id) {
            val pkg = id.substringBeforeLast('/')
            val component = ComponentName.unflattenFromString(normalizeComponentId(id)) ?: return null
            val context = appContext() ?: return null
            val serviceInfo = runCatching {
                context.packageManager.getServiceInfo(component, 0)
            }.getOrNull()
            ShortcutTarget(
                pkg,
                "",
                thirdPartyLabel(id),
                serviceInfo?.let { rawIconUri(context, it) },
            )
        } else {
            null
        }
    }

    private data class ShortcutTarget(
        val packageName: String,
        val className: String,
        val label: String,
        val iconUri: String?,
    )

    /** QuickInfo.icon 使用 Android 标准资源 URI，避免依赖 MIUI 私有 res:/ 方言。 */
    private fun rawIconUri(
        context: android.content.Context,
        info: android.content.pm.ServiceInfo,
    ): String? {
        if (info.icon == 0) return null
        return runCatching {
            "android.resource://${info.packageName}/${info.icon}"
        }.getOrNull()
    }

    private fun rawApplicationIconUri(
        context: android.content.Context,
        info: android.content.pm.ApplicationInfo,
    ): String? {
        if (info.icon == 0) return null
        return "android.resource://${info.packageName}/${info.icon}"
    }

    private fun isInjectedShortcut(model: Any): Boolean =
        quickInfoFromModel(model)?.let { quickInfoId(it).startsWith(ID_PREFIX) } == true

    private fun findShortcutModel(holder: Any): Any? {
        runCatching { holderCurrentModelField?.get(holder) }.getOrNull()?.let { model ->
            if (model.javaClass == shortcutCtor?.declaringClass) return model
        }
        runCatching { shortcutModelField?.get(holder) }.getOrNull()?.let { return it }
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { field ->
                field.type == shortcutCtor?.declaringClass
            }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(holder)
                }.getOrNull()
            }
            var semanticShortcut: Any? = null
            type.declaredFields.forEach { field ->
                if (field.type.isPrimitive || field.type == String::class.java) return@forEach
                runCatching {
                    field.isAccessible = true
                    val value = field.get(holder)
                    if (value != null && value.javaClass == shortcutCtor?.declaringClass) {
                        semanticShortcut = value
                    }
                }
            }
            semanticShortcut?.let { return it }
            type = type.superclass
        }
        return null
    }

    private fun quickInfoFromModel(model: Any): Any? = runCatching {
        model.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.returnType == quickInfoClassType()
        }?.invoke(model)
            ?: shortcutQuickInfoField?.takeIf { it.declaringClass.isAssignableFrom(model.javaClass) }
                ?.get(model)
    }.getOrNull()

    private fun quickInfoClassType(): Class<*>? = quickInfoCtor?.declaringClass

    private fun quickInfoId(quickInfo: Any): String =
        runCatching { quickInfoStringFields.firstOrNull()?.get(quickInfo) as? String }.getOrNull().orEmpty()

    private fun quickInfoValue(quickInfo: Any, index: Int): String =
        runCatching { quickInfoStringFields.getOrNull(index)?.get(quickInfo) as? String }.getOrNull().orEmpty()

    /** 原生 w0.e 会再次走 IconCustomizer；注入条目直接绑定原始资源，避免空白图标。 */
    private fun bindInjectedIcon(holder: Any, model: Any) {
        installStateReceiver()
        val imageView = runCatching { shortcutIconViewField?.get(findFieldOwner(holder)) as? ImageView }.getOrNull()
            ?: findImageView(holder)
            ?: return
        val quickInfo = quickInfoFromModel(model) ?: return
        val componentId = quickInfoId(quickInfo).removePrefix(ID_PREFIX)
        val drawable = if (componentId.startsWith("activity:")) {
            loadActivityDrawable(imageView.context, componentId.removePrefix("activity:"))
        } else {
            loadInjectedDrawable(imageView.context, componentId)
        }
            ?: loadSystemDrawable(imageView.context, componentId)
            ?: imageView.context.getDrawable(android.R.drawable.ic_menu_info_details)
        val enabled = tileState[componentId] == true
        tileViews[componentId] = imageView
        styleTileIcon(imageView, drawable, enabled)
        if (!componentId.startsWith("activity:") && queriedStates.add(componentId)) {
            requestTileState(imageView.context, componentId)
        }
    }

    private fun installDirectShortcutClick(holder: Any, model: Any) {
        val itemView = runCatching {
            holder.javaClass.methods.firstOrNull {
                it.name == "getItemView" && it.parameterCount == 0
            }?.invoke(holder) as? View
        }.getOrNull() ?: findItemView(holder) ?: return
        val context = itemView.context
        bindInjectedIcon(holder, model)
        itemView.setOnClickListener {
            handleInjectedModelClick(model, context)
            bindInjectedIcon(holder, model)
        }
    }

    private fun findItemView(holder: Any): View? {
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { View::class.java.isAssignableFrom(it.type) }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun findImageView(holder: Any): ImageView? {
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { ImageView::class.java.isAssignableFrom(it.type) }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(holder) as? ImageView
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun findFieldOwner(instance: Any): Any = instance

    private fun loadInjectedDrawable(context: android.content.Context, id: String): Drawable? {
        val component = ComponentName.unflattenFromString(normalizeComponentId(id)) ?: return null
        return runCatching {
            val info = context.packageManager.getServiceInfo(component, 0)
            val iconRes = if (info.icon != 0) info.icon else info.applicationInfo?.icon ?: 0
            if (iconRes == 0) return@runCatching null
            val packageContext = context.createPackageContext(
                info.packageName,
                android.content.Context.CONTEXT_IGNORE_SECURITY,
            )
            packageContext.resources.getDrawable(iconRes, packageContext.theme)
        }.getOrNull()
    }

    private fun loadActivityDrawable(context: android.content.Context, id: String): Drawable? {
        val component = ComponentName.unflattenFromString(normalizeComponentId(id)) ?: return null
        return runCatching {
            val info = context.packageManager.getActivityInfo(component, 0)
            val iconRes = info.applicationInfo?.icon ?: 0
            if (iconRes == 0) return@runCatching null
            val packageContext = context.createPackageContext(
                info.packageName,
                android.content.Context.CONTEXT_IGNORE_SECURITY,
            )
            packageContext.resources.getDrawable(iconRes, packageContext.theme)
        }.getOrNull()
    }

    private fun loadSystemDrawable(context: android.content.Context, id: String): Drawable? {
        val name = when (id) {
            "wifi" -> "stat_sys_wifi"
            "bluetooth" -> "stat_sys_data_bluetooth"
            "mobile_data" -> "stat_sys_data_connected"
            "airplane_mode" -> "stat_sys_airplane_mode"
            "location" -> "ic_menu_mylocation"
            "flashlight" -> "ic_menu_camera"
            "auto_rotate" -> "stat_sys_rotate"
            "dnd", "mute" -> "ic_lock_silent_mode"
            "hotspot" -> "stat_sys_tether_wifi"
            "cast" -> "ic_menu_slideshow"
            "dark_mode" -> "ic_menu_day"
            "screen_record" -> "ic_menu_camera"
            "screenshot" -> "ic_menu_crop"
            else -> null
        } ?: return null
        val resId = context.resources.getIdentifier(name, "drawable", "android")
        return resId.takeIf { it != 0 }?.let { runCatching { context.getDrawable(it) }.getOrNull() }
    }

    private fun styleTileIcon(imageView: ImageView, source: Drawable?, enabled: Boolean) {
        val density = imageView.resources.displayMetrics.density
        if (!originalIconBounds.containsKey(imageView)) {
            val lp = imageView.layoutParams
            originalIconBounds[imageView] = intArrayOf(lp?.width ?: -2, lp?.height ?: -2)
        }
        val size = (46f * density).toInt()
        val padding = (8f * density).toInt()
        imageView.layoutParams = imageView.layoutParams?.apply {
            width = size
            height = size
        }
        imageView.setPadding(padding, padding, padding, padding)
        imageView.translationX = 2f * density
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.parseColor(if (enabled) "#CCE7E7E9" else "#CC4A4A50"))
        }
        source?.mutate()?.apply {
            setTint(Color.parseColor(if (enabled) "#3982FA" else "#F5F5F7"))
            alpha = if (enabled) 255 else 204
        }
        imageView.setImageDrawable(source)
    }

    private fun resetNativeIcon(holder: Any) {
        val imageView = findImageView(holder) ?: return
        imageView.background = null
        imageView.setPadding(0, 0, 0, 0)
        imageView.translationX = 0f
        originalIconBounds[imageView]?.let { bounds ->
            imageView.layoutParams = imageView.layoutParams?.apply {
                width = bounds[0]
                height = bounds[1]
            }
        }
    }

    private fun normalizeComponentId(id: String): String = id.replace("\\", "")

    private fun installStateReceiver() {
        if (stateReceiverInstalled) return
        val context = appContext() ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != SidebarQsBridgeHook.ACTION_STATE) return
                val id = intent.getStringExtra(SidebarQsBridgeHook.EXTRA_COMPONENT)
                    ?: intent.getStringExtra(SidebarQsBridgeHook.EXTRA_SPEC)
                    ?: return
                val enabled = intent.getBooleanExtra(SidebarQsBridgeHook.EXTRA_ENABLED, false)
                val toggleable = intent.getBooleanExtra(SidebarQsBridgeHook.EXTRA_TOGGLEABLE, false)
                tileState[id] = enabled
                if (toggleable) toggleableTiles.add(id) else toggleableTiles.remove(id)
                tileViews[id]?.let { view ->
                    val drawable = loadInjectedDrawable(view.context, id)
                        ?: loadSystemDrawable(view.context, id)
                    styleTileIcon(view, drawable, enabled)
                }
            }
        }
        runCatching {
            val register = Context::class.java.getMethod(
                "registerReceiver", BroadcastReceiver::class.java, IntentFilter::class.java, Int::class.javaPrimitiveType,
            )
            register.invoke(context, receiver, IntentFilter(SidebarQsBridgeHook.ACTION_STATE), 4)
            stateReceiverInstalled = true
        }.recoverCatching { context.registerReceiver(receiver, IntentFilter(SidebarQsBridgeHook.ACTION_STATE)) }
    }

    private fun requestTileState(context: Context, id: String) {
        val component = id.takeIf { '/' in it }
        val spec = if (component == null) systemTileSpec(id) else null
        if (component == null && spec == null) return
        context.sendBroadcast(
            Intent(SidebarQsBridgeHook.ACTION_QUERY_STATE)
                .setPackage("com.android.systemui")
                .putExtra(SidebarQsBridgeHook.EXTRA_COMPONENT, component)
                .putExtra(SidebarQsBridgeHook.EXTRA_SPEC, spec),
        )
    }

    /** 原生 ViewHolder 会把 TileService 类名当 Activity 类名；这里改为可工作的通用动作。 */
    private fun handleInjectedClick(holder: Any): Boolean {
        val model = findShortcutModel(findFieldOwner(holder))
        if (model == null) {
            Log.e(TAG, "click dispatch holder has no model: ${holder.javaClass.name} current=${holderCurrentModelField?.name} shortcut=${shortcutModelField?.name}")
            return false
        }
        val quickInfo = quickInfoFromModel(model)
        if (quickInfo == null) {
            Log.e(TAG, "click dispatch model has no QuickInfo: ${model.javaClass.name}")
            return false
        }
        val context = runCatching {
            (shortcutIconViewField?.get(holder) as? ImageView)?.context
        }.getOrNull() ?: appContext() ?: return false
        return handleInjectedModelClick(model, context)
    }

    private fun handleInjectedModelClick(model: Any, context: android.content.Context): Boolean {
        val quickInfo = quickInfoFromModel(model) ?: return false
        val id = normalizeComponentId(quickInfoId(quickInfo).removePrefix(ID_PREFIX))
        if (toggleableTiles.contains(id)) {
            val enabled = !(tileState[id] ?: false)
            tileState[id] = enabled
            tileViews[id]?.let { view ->
                val drawable = loadInjectedDrawable(view.context, id)
                    ?: loadSystemDrawable(view.context, id)
                styleTileIcon(view, drawable, enabled)
            }
        }
        if (id.startsWith("activity:")) {
            val component = ComponentName.unflattenFromString(id.removePrefix("activity:"))
                ?: return true
            val intent = Intent().setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(TAG, "launch explicit activity failed: ${component.flattenToShortString()}", it) }
            return true
        }
        if ('/' in id) {
            val component = ComponentName.unflattenFromString(id)
            // 这是 TileService，不是 Activity；绝不再回退到小窗启动应用。
            // clickTile 失败时也消费点击，避免产生错误的“打开应用”行为。
            if (component != null) {
                sendQsBridgeClick(context, component.flattenToString(), null)
            }
            return true
        }
        sendQsBridgeClick(context, null, systemTileSpec(id))
        return true
    }

    private fun sendQsBridgeClick(context: android.content.Context, component: String?, spec: String?) {
        if (component == null && spec == null) return
        context.sendBroadcast(
            Intent(SidebarQsBridgeHook.ACTION_CLICK)
                .setPackage("com.android.systemui")
                .putExtra(SidebarQsBridgeHook.EXTRA_COMPONENT, component)
                .putExtra(SidebarQsBridgeHook.EXTRA_SPEC, spec),
        )
    }

    private fun systemTileSpec(id: String): String? = when (id) {
        "wifi" -> "wifi"
        "bluetooth" -> "bt"
        "flashlight" -> "flashlight"
        "airplane_mode" -> "airplane"
        "mobile_data" -> "cell"
        "location" -> "location"
        "auto_rotate" -> "rotation"
        "dnd" -> "dnd"
        "dark_mode" -> "dark"
        "hotspot" -> "hotspot"
        "cast" -> "cast"
        "mute" -> "sound"
        "screen_record" -> "screenrecord"
        "screenshot" -> "screenshot"
        else -> null
    }

    private fun thirdPartyLabel(componentId: String): String {
        tileLabelCache[componentId]?.let { return it }
        val pkg = componentId.substringBeforeLast('/')
        val component = ComponentName.unflattenFromString(componentId)
        val label = appContext()?.let { ctx ->
            component?.let { cn ->
                runCatching {
                    val pm = ctx.packageManager
                    pm.getServiceInfo(cn, 0).loadLabel(pm).toString()
                }.getOrNull()
            }
        } ?: pkg
        tileLabelCache[componentId] = label
        return label
    }

    /** 按 a~h 顺序写入 QuickInfo 的 8 个 String 字段：id, icon, name, title, action, uri, pkg, cls。 */
    private fun writeQuickInfo(qi: Any, id: String, target: ShortcutTarget): Boolean {
        val fields = quickInfoStringFields
        if (fields.size < 8) return false
        return runCatching {
            fields[0].set(qi, ID_PREFIX + id)   // id
            fields[1].set(qi, target.iconUri.orEmpty()) // icon URI
            fields[2].set(qi, target.label)       // name
            fields[3].set(qi, target.label)       // title（展示名）
            fields[4].set(qi, "android.intent.action.MAIN") // action
            fields[5].set(qi, "")               // uri
            fields[6].set(qi, target.packageName) // packageName
            fields[7].set(qi, target.className)   // className
            quickInfoTypeField?.set(qi, enumByName(quickInfoTypeClass, "NATIVE"))
        }.isSuccess
    }

    // ── 标题头渲染补丁 ────────────────────────────────────────────────────────

    private fun isInjectedTitle(title: Any?): Boolean =
        title != null && injectedTitles.contains(title)

    private fun readTitleResource(title: Any): Int? =
        runCatching {
            if (titleClassMatches(title)) readTitleTextRes(title) else null
        }.getOrNull()

    private fun titleClassMatches(title: Any): Boolean =
        title.javaClass == titleCtor?.declaringClass

    private fun readTitleTextRes(title: Any): Int =
        runCatching { titleIntField?.getInt(title) }.getOrNull() ?: -2

    private fun readTitleResolvedText(title: Any): String =
        runCatching { titleStringField?.get(title) as? String }.getOrNull().orEmpty()

    private fun applyTitleText(holder: Any?, text: String) {
        val field = holderTextViewField ?: return
        val view = runCatching { field.get(holder) as? TextView }.getOrNull() ?: return
        view.text = text
        val dark = (view.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        view.setTextColor(if (dark) Color.WHITE else Color.BLACK)
        view.alpha = 0.8f
    }

    private fun applyNativeTitleStyle(holder: Any?) {
        val field = holderTextViewField ?: return
        val view = runCatching { field.get(holder) as? TextView }.getOrNull() ?: return
        val dark = (view.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        view.setTextColor(if (dark) Color.WHITE else Color.BLACK)
        view.alpha = if (dark) 0.8f else 1f
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private fun isUiProcess(packageName: String, processName: String): Boolean {
        if (processName.isEmpty()) return true
        return processName == packageName || processName == "$packageName:ui"
    }

    private fun schedulePrepareRetry(
        module: XposedModule,
        param: PackageLoadedParam,
    ) {
        if (prepareRetryScheduled) return
        // Missing platform/compiler classes are permanent for this Android
        // process, not a late-loading SecurityCenter class. Do not keep
        // posting retries and flooding logcat in that case.
        if (lastPrepareFailure?.startsWith("NoClassDefFoundError") == true ||
            lastPrepareFailure?.startsWith("LinkageError") == true) {
            preparePermanentlyFailed = true
            logError(module, "prepare stopped: ${lastPrepareFailure ?: "linkage failure"}")
            return
        }
        if (prepareRetryCount >= 12) {
            logError(module, "prepare failed after retries; dynamic classes unavailable: ${lastPrepareFailure ?: "unknown"}")
            return
        }
        prepareRetryScheduled = true
        prepareRetryCount++
        Handler(Looper.getMainLooper()).postDelayed({
            prepareRetryScheduled = false
            onInit(module, param)
        }, 500L)
    }

    private fun isChinese(): Boolean = Locale.getDefault().language.startsWith("zh")

    private fun sectionTitle(): String = if (isChinese()) "快捷方式" else "Shortcuts"

    private fun quickFunctionTitle(): String = if (isChinese()) "快捷功能" else "Quick functions"


    private fun enumByName(type: Class<*>?, name: String): Any? {
        if (type == null || !type.isEnum) return null
        return type.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
    }

    /**
     * R8/JADX 名称都不稳定。不要把 dex 中的每个名字都 Class.forName：系统 APK
     * 里存在仅在 Java 编译器环境才有的引用（例如 javax.lang.model），强行加载会
     * 直接触发 NoClassDefFoundError。DexKit 先读取 dex 元数据，只把可能属于
     * All Apps 模型、适配器、ViewHolder 或 StateFlow 的候选类交给 ClassLoader。
     */
    private fun discoverClasses(loader: ClassLoader): List<Class<*>> {
        discoveredClasses?.let { return it }

        fun query(bridge: DexKitBridge): List<String> {
            // Restrict DexKit's metadata query to the small set of packages
            // used by All Apps and its obfuscated holders. An empty query
            // materializes unrelated classes which may reference javac-only
            // TypeMirror/TypeElement before our safety filter can run.
            val modelData = bridge.findClass {
                searchPackages("com.miui.dock.allapps")
            }
            val matched = modelData.asSequence()
                .map { it.name }
                .toMutableSet()
            // Holder/adapter packages are R8-obfuscated and vary by build.
            // Find their declaring classes from parameter types instead of
            // embedding names such as o7/u7/kp in the module.
            modelData.asSequence()
                .filter { it.name.contains('$') }
                .forEach { model ->
                    runCatching {
                        bridge.findMethod { matcher { paramTypes(model.name) } }
                            .forEach { method -> matched += method.className }
                    }
                }
            listOf("java.util.List", "android.view.View").forEach { parameterType ->
                runCatching {
                    bridge.findMethod { matcher { paramTypes(parameterType) } }
                        .forEach { method -> matched += method.className }
                }
            }
            // Nested Model classes are the useful metadata hit; load their
            // enclosing root as well so declaredClasses/constructors can be inspected.
            matched.toList().forEach { name ->
                var separator = name.lastIndexOf('$')
                while (separator > 0) {
                    matched += name.substring(0, separator)
                    separator = name.lastIndexOf('$', separator - 1)
                }
            }
            return matched.toList()
        }

        // DexKit 2.2.0 exports native code; load it once per process. If the host
        // loader cannot be parsed, use the installed APK as a safe fallback.
        runCatching { System.loadLibrary("dexkit") }
            .getOrElse { throw IllegalStateException("DexKit native library unavailable", it) }

        val names = runCatching {
            DexKitBridge.create(loader, false).use(::query)
        }.getOrElse {
            val source = appContext()?.applicationInfo?.sourceDir
                ?: throw IllegalStateException("DexKit could not access host APK", it)
            DexKitBridge.create(source).use(::query)
        }
        val result = names.mapNotNull { name ->
            runCatching {
                Class.forName(name, false, loader).also { type ->
                    // Some host classes mention javac-only types such as
                    // javax.lang.model.element.TypeElement. Loading the Class
                    // object can succeed, while resolving reflection metadata
                    // still throws NoClassDefFoundError; exclude those classes
                    // before the signature matchers inspect them.
                    type.declaredMethods
                    type.declaredConstructors
                    type.declaredFields
                    type.declaredClasses
                }
            }.getOrNull()
        }
        discoveredClasses = result
        return result
    }

    private fun appContext(): android.content.Context? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as android.content.Context
        }.getOrNull()
}
