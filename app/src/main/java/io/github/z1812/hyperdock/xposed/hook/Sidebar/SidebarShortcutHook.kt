package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import dalvik.system.DexFile
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.xposed.ConfigManager
import io.github.z1812.hyperdock.xposed.hook.BaseHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.Locale

/**
 * 快捷方式栏目注入。
 *
 * 在全部应用面板（侧边栏）顶部注入一条“快捷方式”栏目：一个 [Model.Title] 头 + 若干 [Model.Shortcut]。
 * 注入点优先选择全部应用 ViewModel 的 StateFlow setter（kp.h0.setValue），
 * 在列表发射前把自定义条目拼到列表最前面。这样 adapter 与 SpanSizeLookup
 * 读取的是同一份数据，不会因 adapter 侧二次注入造成栏目错位。
 *
 * 快捷方式数据来自模块侧白名单（PrefKeys.SHORTCUTS_ADDED），由本 Hook 侧做 id -> 展示名 映射，
 * 并复用原生 QuickInfo(u7.a) / Model.Shortcut 结构。点击动作由原生 ShortcutViewHolder 依据
 * QuickInfo 的 packageName/className/type 决定：第三方条目携带真实 TileService 组件，
 * 由原生点击逻辑继续处理；系统开关保留 SystemUI 归属，避免伪造应用组件。
 */
object SidebarShortcutHook : BaseHook() {
    private const val TAG = "HyperDock[SidebarShortcut]"

    // 下面的类均在 prepare() 中按方法/字段签名从当前 APK dex 自动发现。
    private const val ADAPTER_CLASS = "com.miui.dock.allapps.b"
    private const val TITLE_HOLDER_CLASS = "o7.o"
    private const val QUICK_INFO_CLASS = "u7.a"
    private const val EDIT_STATE_CLASS = "com.miui.dock.allapps.h0"
    private const val MODEL_ITEM_CLASS = "com.miui.dock.allapps.Model\$c"
    private const val STATE_FLOW_CLASS = "kp.h0"
    private const val SHORTCUT_CLASS = "com.miui.dock.allapps.Model\$Shortcut"
    private const val TITLE_CLASS = "com.miui.dock.allapps.Model\$Title"

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
    private var prepareRetryCount = 0
    private var prepareRetryScheduled = false

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            //log(module, "skip non-UI process: $processName")
            return
        }
        if (!prepare(module, param.defaultClassLoader)) {
            schedulePrepareRetry(module, param)
            return
        }
        prepareRetryCount = 0

        // StateFlow 是主注入点。只有目标版本没有该入口时才退回 adapter，避免两处同时注入。
        if (stateFlowSetMethods.isNotEmpty()) {
            stateFlowSetMethods.forEach { m ->
                try {
                    module.hook(m).intercept { chain ->
                        val value = chain.args.getOrNull(0)
                        if (value is List<*>) {
                            @Suppress("UNCHECKED_CAST")
                            return@intercept chain.proceed(
                                arrayOf<Any?>(inject(module, value as List<Any>))
                            )
                        }
                        chain.proceed()
                    }
                    log(module, "hooked StateFlow ${m.declaringClass.name}.${m.name}")
                } catch (t: Throwable) {
                    logError(module, "StateFlow hook failed: ${t.message}")
                }
            }
        } else adapterMethod?.let { m ->
            try {
                module.hook(m).intercept { chain ->
                    val original = chain.args.getOrNull(0) as? List<Any>
                        ?: return@intercept chain.proceed()
                    chain.proceed(arrayOf<Any?>(inject(module, original)))
                }
                log(module, "hooked adapter ${m.declaringClass.name}.${m.name}")
            } catch (t: Throwable) {
                logError(module, "adapter hook failed: ${t.message}")
            }
        }

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
                        chain.proceed()
                    }
                }
                log(module, "hooked title bind ${m.declaringClass.name}.${m.name}")
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
                    }
                    result
                }
                log(module, "hooked shortcut bind ${m.declaringClass.name}.${m.name}")
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
                    }
                    result
                }
                log(module, "hooked generic shortcut bind ${m.declaringClass.name}.${m.name}")
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
                log(module, "hooked shortcut click ${m.declaringClass.name}.${m.name}")
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
                log(module, "hooked generic shortcut click dispatch ${m.declaringClass.name}.${m.name}")
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
                    }
                    result
                }
                log(module, "hooked adapter bind ${m.declaringClass.name}.${m.name}")
            } catch (t: Throwable) {
                logError(module, "adapter bind hook failed: ${t.message}")
            }
        }
    }

    // ── 初始化反射句柄 ─────────────────────────────────────────────────────────

    private fun prepare(module: XposedModule, loader: ClassLoader): Boolean {
        return try {
            val adapterClass = Class.forName(ADAPTER_CLASS, false, loader)
            val titleClass = Class.forName(TITLE_CLASS, false, loader)
            val shortcutClass = Class.forName(SHORTCUT_CLASS, false, loader)
            val shortcutCtorCandidate = shortcutClass.declaredConstructors.firstOrNull {
                it.parameterCount == 3 && it.parameterTypes[0] != null
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
                method.name == "onBindViewHolder" && method.parameterCount >= 2 &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
            }.onEach { it.isAccessible = true }

            // 不依赖 r8 优化后的字段/方法签名：只按公开语义寻找单参数 setValue，
            // 并在运行时以 List 参数/值做校验。不同版本可能有桥接重载，全部去重 hook。
            stateFlowSetMethods = findStateFlowSetters(adapterClass, loader)

            val titleHolderClass = Class.forName(TITLE_HOLDER_CLASS, false, loader)
            titleBindMethod = titleHolderClass.declaredMethods.firstOrNull { m ->
                m.returnType == Void.TYPE && m.parameterCount == 1 && m.parameterTypes[0] == titleClass
            }

            val shortcutHolderClass = Class.forName("o7.n", false, loader)
            val shortcutBaseHolderClass = Class.forName("o7.m", false, loader)
            modelItemType = Class.forName(MODEL_ITEM_CLASS, false, loader)

            quickInfoCtor = quickInfoClass.getDeclaredConstructor().also { it.isAccessible = true }
            shortcutCtor = shortcutCtorCandidate.also { it.isAccessible = true }
            titleCtor = titleClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType, String::class.java,
            ).also { it.isAccessible = true }
            editStateNone = enumByName(editStateClass, "NONE")

            // QuickInfo 的 8 个 String 字段被混淆为 a~h，排序后即 id/icon/name/title/action/uri/pkg/cls。
            quickInfoStringFields = quickInfoClass.declaredFields
                .filter { it.type == String::class.java }
                .sortedBy { it.name }
                .onEach { it.isAccessible = true }
            quickInfoTypeField = quickInfoClass.declaredFields.firstOrNull { it.type.isEnum }
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
                method.returnType == Void.TYPE && method.parameterCount == 1 &&
                    method.parameterTypes[0] == shortcutClass
            }?.also { it.isAccessible = true }
            shortcutClickMethod = shortcutHolderClass.declaredMethods.firstOrNull { method ->
                method.returnType == Void.TYPE && method.parameterCount == 1 &&
                    View::class.java.isAssignableFrom(method.parameterTypes[0])
            }?.also { it.isAccessible = true }
            clickDispatchMethod = shortcutBaseHolderClass.declaredMethods.firstOrNull { method ->
                method.name == "onClick" && method.parameterCount == 1 &&
                    View::class.java.isAssignableFrom(method.parameterTypes[0])
            }?.also { it.isAccessible = true }
            genericBindMethod = shortcutBaseHolderClass.declaredMethods.firstOrNull { method ->
                method.name == "l" && method.parameterCount == 1 &&
                    modelItemType?.let { method.parameterTypes[0] == it } == true
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
            log(module, "holder fields icon=${shortcutIconViewField?.name} shortcut=${shortcutModelField?.name} model=${holderCurrentModelField?.name}")
            if (titleBindMethod == null) logWarn(module, "title bind method not found")
            if (shortcutBindMethod == null) logWarn(module, "shortcut bind method not found")
            if (shortcutClickMethod == null) logWarn(module, "shortcut click method not found")
            if (clickDispatchMethod == null) logWarn(module, "click dispatch method not found")
            if (shortcutCtor == null || quickInfoCtor == null || editStateNone == null) {
                logWarn(module, "shortcut model wiring incomplete")
            }
            stateFlowSetMethods.isNotEmpty() || adapterMethod != null
        } catch (t: Throwable) {
            log(module, "prepare deferred: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * 优先从 AllApps adapter 持有的状态对象反推出 setter，避免把某一版 R8 类名
     * 当成协议。保留 kp.h0 仅作为旧版本没有可见 adapter 字段时的兼容兜底。
     */
    private fun findStateFlowSetters(adapterClass: Class<*>, loader: ClassLoader): List<Method> {
        val candidates = LinkedHashSet<Class<*>>()
        var type: Class<*>? = adapterClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { field ->
                val fieldType = field.type
                if (fieldType.methods.any { it.name == "setValue" && it.parameterCount == 1 }) {
                    candidates.add(fieldType)
                }
            }
            type = type.superclass
        }
        runCatching { candidates.add(Class.forName(STATE_FLOW_CLASS, false, loader)) }
        return candidates.flatMap { stateClass ->
            (stateClass.methods.asSequence() + stateClass.declaredMethods.asSequence())
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
        // setValue 可能被多个观察者/重放路径调用；绝不能叠加第二个自定义栏目。
        if (original.any { isInjectedTitle(it) }) return original
        if (!ConfigManager.getBoolean(PrefKeys.SHORTCUTS_ENABLED, false)) {
            log(module, "shortcuts disabled, skip inject")
            return original
        }
        val added = ConfigManager.getStringSet(PrefKeys.SHORTCUTS_ADDED, emptySet())
        if (added.isEmpty()) {
            log(module, "no shortcut added, skip inject")
            return original
        }

        val ordered = orderIds(added)
        val result = ArrayList<Any>(original.size + ordered.size + 1)
        val styleRes = original.asSequence()
            .mapNotNull { readTitleResource(it) }
            .firstOrNull { it > 0 }
            ?: TITLE_SENTINEL
        buildTitle(styleRes)?.let { result.add(it) }
        ordered.forEach { id ->
            buildShortcut(id)?.let {
                result.add(it)
                //log(module, "injected shortcut: $id")
            } ?: logWarn(module, "skip shortcut (unsupported): $id")
        }
        result.addAll(original)
        log(module, "injected ${ordered.size} shortcut(s) at top, total=${result.size}")
        return result
    }

    private fun orderIds(added: Set<String>): List<String> {
        val system = added.filter { it in SYSTEM_LABELS }.sortedBy { SYSTEM_ORDER.indexOf(it) }
        val thirdParty = added.filter { '/' in it }.sorted()
        val others = added.filter { it !in SYSTEM_LABELS && '/' !in it }.sorted()
        return system + thirdParty + others
    }

    private fun buildTitle(styleRes: Int): Any? {
        val ctor = titleCtor ?: return null
        return runCatching {
            ctor.newInstance(styleRes, sectionTitle()).also { injectedTitles.add(it) }
        }.getOrNull()
    }

    private fun buildShortcut(id: String): Any? {
        val qiCtor = quickInfoCtor ?: return null
        val shortcutCtor = shortcutCtor ?: return null
        val qi = runCatching { qiCtor.newInstance() }.getOrNull() ?: return null
        val target = resolveShortcut(id) ?: return null
        if (!writeQuickInfo(qi, id, target)) return null
        return runCatching { shortcutCtor.newInstance(qi, false, editStateNone) }.getOrNull()
    }

    private fun resolveShortcut(id: String): ShortcutTarget? {
        SYSTEM_LABELS[id]?.let { (zh, en) ->
            return ShortcutTarget("", "", if (isChinese()) zh else en, null)
        }
        return if ('/' in id) {
            val pkg = id.substringBeforeLast('/')
            val component = ComponentName.unflattenFromString(id) ?: return null
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

    private fun isInjectedShortcut(model: Any): Boolean =
        quickInfoFromModel(model)?.let { quickInfoId(it).startsWith(ID_PREFIX) } == true

    private fun findShortcutModel(holder: Any): Any? {
        runCatching { holderCurrentModelField?.get(holder) }.getOrNull()?.let { model ->
            if (model.javaClass.name == SHORTCUT_CLASS) return model
        }
        runCatching { shortcutModelField?.get(holder) }.getOrNull()?.let { return it }
        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { field ->
                field.type.name == SHORTCUT_CLASS || field.type.name.endsWith("Model\$Shortcut")
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
                    if (value != null && value.javaClass.name.endsWith("Model\$Shortcut")) {
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
        val imageView = runCatching { shortcutIconViewField?.get(findFieldOwner(holder)) as? ImageView }.getOrNull()
            ?: findImageView(holder)
            ?: return
        val quickInfo = quickInfoFromModel(model) ?: return
        val componentId = quickInfoId(quickInfo).removePrefix(ID_PREFIX)
        val drawable = loadInjectedDrawable(imageView.context, componentId)
            ?: loadSystemDrawable(imageView.context, componentId)
            ?: imageView.context.getDrawable(android.R.drawable.ic_menu_info_details)
        val enabled = tileState[componentId] == true
        styleTileIcon(imageView, drawable, enabled)
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
        val component = ComponentName.unflattenFromString(id) ?: return null
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
        val size = (40f * density).toInt()
        val padding = (8f * density).toInt()
        imageView.layoutParams = imageView.layoutParams?.apply {
            width = size
            height = size
        }
        imageView.setPadding(padding, padding, padding, padding)
        imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.parseColor(if (enabled) "#E7E7E9" else "#2C2C30"))
        }
        source?.mutate()?.apply {
            setTint(Color.parseColor(if (enabled) "#3982FA" else "#F5F5F7"))
            alpha = 255
        }
        imageView.setImageDrawable(source)
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
        val id = quickInfoId(quickInfo).removePrefix(ID_PREFIX)
        tileState[id] = !(tileState[id] ?: false)
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
            fields[4].set(qi, "native")         // action
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
        title.javaClass.name == TITLE_CLASS

    private fun readTitleTextRes(title: Any): Int =
        runCatching { titleIntField?.getInt(title) }.getOrNull() ?: -2

    private fun readTitleResolvedText(title: Any): String =
        runCatching { titleStringField?.get(title) as? String }.getOrNull().orEmpty()

    private fun applyTitleText(holder: Any?, text: String) {
        val field = holderTextViewField ?: return
        val view = runCatching { field.get(holder) as? TextView }.getOrNull() ?: return
        view.text = text
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
        if (prepareRetryCount >= 12) {
            logError(module, "prepare failed after retries; dynamic classes unavailable")
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

    private fun enumByName(type: Class<*>?, name: String): Any? {
        if (type == null || !type.isEnum) return null
        return type.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
    }

    /**
     * R8/JADX 名称都不稳定；只枚举当前 APK 的 dex，并由字段/方法签名做语义匹配。
     * 结果缓存一次，避免每次 StateFlow 发射重复扫描 dex。
     */
    private fun discoverClasses(loader: ClassLoader): List<Class<*>> {
        discoveredClasses?.let { return it }
        val result = ArrayList<Class<*>>()
        val seen = HashSet<String>()
        fun scanDex(dex: DexFile) {
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                if (!seen.add(name)) continue
                if (!name.startsWith("com.miui.dock.allapps.") &&
                    !name.startsWith("o7.") && !name.startsWith("p381o7.") &&
                    !name.startsWith("u7.") && !name.startsWith("p517u7.")) continue
                runCatching { Class.forName(name, false, loader) }.getOrNull()?.let(result::add)
            }
        }
        runCatching {
            // 优先读取当前 ClassLoader 的实际 dexElements；这覆盖 split/dynamic dex。
            var type: Class<*>? = loader.javaClass
            while (type != null) {
                val pathListField = type.declaredFields.firstOrNull { it.name == "pathList" }
                if (pathListField != null) {
                    pathListField.isAccessible = true
                    val pathList = pathListField.get(loader)
                    val elementsField = pathList.javaClass.declaredFields.firstOrNull { it.name == "dexElements" }
                    elementsField?.let {
                        it.isAccessible = true
                        val elements = it.get(pathList) as? Array<*>
                        elements?.forEach { element ->
                            val dexField = element?.javaClass?.declaredFields?.firstOrNull { f -> f.name == "dexFile" }
                            dexField?.let { f ->
                                f.isAccessible = true
                                (f.get(element) as? DexFile)?.let(::scanDex)
                            }
                        }
                    }
                    break
                }
                type = type.superclass
            }
            val info = appContext()?.applicationInfo ?: return@runCatching
            val paths = buildList {
                add(info.sourceDir)
                info.splitSourceDirs?.let { addAll(it) }
            }
            paths.forEach { source ->
                val dex = DexFile(source)
                try {
                    scanDex(dex)
                } finally {
                    runCatching { dex.close() }
                }
            }
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
