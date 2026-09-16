package io.github.z1812.hyperdock.xposed.hook.Sidebar

import android.app.Application
import android.content.ComponentName
import android.util.Log
import android.widget.TextView
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
 * QuickInfo 的 packageName/className/type 决定：第三方开关回落到拉起对应应用，系统开关暂为占位。
 */
object SidebarShortcutHook : BaseHook() {
    private const val TAG = "HyperDock[SidebarShortcut]"

    // 运行时类名（安全中心侧边栏全应用面板；jadx 反混淆后为 C13617b 等，这里用真实名字）。
    private const val ADAPTER_CLASS = "com.miui.dock.allapps.b"
    private const val STATE_FLOW_CLASS = "kp.h0"
    private const val TITLE_HOLDER_CLASS = "o7.o"
    private const val SHORTCUT_CLASS = "com.miui.dock.allapps.Model\$Shortcut"
    private const val TITLE_CLASS = "com.miui.dock.allapps.Model\$Title"
    private const val QUICK_INFO_CLASS = "u7.a"
    private const val EDIT_STATE_CLASS = "com.miui.dock.allapps.h0"

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
    /** 记录本 Hook 创建的标题对象，避免使用无效 textRes 作为识别标记。 */
    private val injectedTitles = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    // ── 反射缓存 ──────────────────────────────────────────────────────────────
    private var adapterMethod: Method? = null
    private var stateFlowSetMethods: List<Method> = emptyList()
    private var titleBindMethod: Method? = null
    private var shortcutCtor: Constructor<*>? = null
    private var titleCtor: Constructor<*>? = null
    private var quickInfoCtor: Constructor<*>? = null
    private var quickInfoStringFields: List<Field> = emptyList()
    private var quickInfoTypeField: Field? = null
    private var quickInfoTypeClass: Class<*>? = null
    private var editStateNone: Any? = null
    private var titleIntField: Field? = null
    private var titleStringField: Field? = null
    private var holderTextViewField: Field? = null

    override fun getTag() = TAG

    override fun onInit(module: XposedModule, param: PackageLoadedParam) {
        val processName = runCatching { Application.getProcessName() }.getOrNull().orEmpty()
        if (!isUiProcess(param.packageName, processName)) {
            log(module, "skip non-UI process: $processName")
            return
        }
        if (!prepare(module, param.defaultClassLoader)) return

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
                        // 先走原生 bind，让默认标题颜色、字号、padding 等样式完整生效，
                        // 再覆盖文案；不能像旧实现一样直接跳过原生 bind。
                        val result = chain.proceed()
                        applyTitleText(chain.thisObject, readTitleResolvedText(title))
                        result
                    } else {
                        chain.proceed()
                    }
                }
                log(module, "hooked title bind ${m.declaringClass.name}.${m.name}")
            } catch (t: Throwable) {
                logError(module, "title bind hook failed: ${t.message}")
            }
        }
    }

    // ── 初始化反射句柄 ─────────────────────────────────────────────────────────

    private fun prepare(module: XposedModule, loader: ClassLoader): Boolean {
        return try {
            val adapterClass = Class.forName(ADAPTER_CLASS, false, loader)
            adapterMethod = adapterClass.declaredMethods.firstOrNull { m ->
                m.returnType == Void.TYPE && m.parameterCount == 1 &&
                List::class.java.isAssignableFrom(m.parameterTypes[0])
            }

            // 不依赖 r8 优化后的字段/方法签名：只按公开语义寻找单参数 setValue，
            // 并在运行时以 List 参数/值做校验。不同版本可能有桥接重载，全部去重 hook。
            stateFlowSetMethods = runCatching {
                val stateClass = Class.forName(STATE_FLOW_CLASS, false, loader)
                (stateClass.methods.asSequence() + stateClass.declaredMethods.asSequence())
                    .filter { m ->
                        m.name == "setValue" && m.parameterCount == 1 &&
                            m.returnType == Void.TYPE
                    }
                    .distinctBy { m -> m.toGenericString() }
                    .onEach { it.isAccessible = true }
                    .toList()
            }.getOrDefault(emptyList())

            val titleClass = Class.forName(TITLE_CLASS, false, loader)
            val titleHolderClass = Class.forName(TITLE_HOLDER_CLASS, false, loader)
            titleBindMethod = titleHolderClass.declaredMethods.firstOrNull { m ->
                m.returnType == Void.TYPE && m.parameterCount == 1 && m.parameterTypes[0] == titleClass
            }

            val shortcutClass = Class.forName(SHORTCUT_CLASS, false, loader)
            val quickInfoClass = Class.forName(QUICK_INFO_CLASS, false, loader)
            val editStateClass = Class.forName(EDIT_STATE_CLASS, false, loader)

            quickInfoCtor = quickInfoClass.getDeclaredConstructor().also { it.isAccessible = true }
            shortcutCtor = shortcutClass.getDeclaredConstructor(
                quickInfoClass, Boolean::class.javaPrimitiveType, editStateClass,
            ).also { it.isAccessible = true }
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

            if (stateFlowSetMethods.isEmpty() && adapterMethod == null) {
                logWarn(module, "StateFlow setValue and adapter o(List) not found")
            }
            if (titleBindMethod == null) logWarn(module, "title bind method not found")
            if (shortcutCtor == null || quickInfoCtor == null || editStateNone == null) {
                logWarn(module, "shortcut model wiring incomplete")
            }
            stateFlowSetMethods.isNotEmpty() || adapterMethod != null
        } catch (t: Throwable) {
            logError(module, "prepare failed: ${Log.getStackTraceString(t)}")
            false
        }
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
                log(module, "injected shortcut: $id")
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
        val (pkg, cls, label) = resolveShortcut(id) ?: return null
        if (!writeQuickInfo(qi, id, label, pkg, cls)) return null
        return runCatching { shortcutCtor.newInstance(qi, false, editStateNone) }.getOrNull()
    }

    private fun resolveShortcut(id: String): Triple<String, String, String>? {
        SYSTEM_LABELS[id]?.let { (zh, en) ->
            return Triple("", "", if (isChinese()) zh else en)
        }
        return if ('/' in id) {
            val pkg = id.substringBeforeLast('/')
            Triple(pkg, "", thirdPartyLabel(id))
        } else {
            null
        }
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
    private fun writeQuickInfo(qi: Any, id: String, label: String, pkg: String, cls: String): Boolean {
        val fields = quickInfoStringFields
        if (fields.size < 8) return false
        return runCatching {
            fields[0].set(qi, ID_PREFIX + id)   // id
            fields[1].set(qi, "")               // icon，留空走默认图标
            fields[2].set(qi, label)            // name
            fields[3].set(qi, label)            // title（展示名）
            fields[4].set(qi, "native")         // action
            fields[5].set(qi, "")               // uri
            fields[6].set(qi, pkg)              // packageName
            fields[7].set(qi, cls)              // className
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

    private fun isChinese(): Boolean = Locale.getDefault().language.startsWith("zh")

    private fun sectionTitle(): String = if (isChinese()) "快捷方式" else "Shortcuts"

    private fun enumByName(type: Class<*>?, name: String): Any? {
        if (type == null || !type.isEnum) return null
        return type.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
    }

    private fun appContext(): android.content.Context? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as android.content.Context
        }.getOrNull()
}
