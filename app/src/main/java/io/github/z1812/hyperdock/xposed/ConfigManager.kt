package io.github.z1812.hyperdock.xposed

import android.content.SharedPreferences
import io.github.z1812.hyperdock.PrefKeys
import io.github.libxposed.api.XposedModule

/**
 * 基于 RemotePreferences 的配置管理器。
 *
 * RemotePreferences 打开时会初始化整组数据，所以配置被拆为一个小 core 组和多个 shard，
 * 避免单次 Binder 事务超过缓冲区。键名与 Compose 端 [io.github.z1812.hyperdock.compose.data.PrefsRepository]
 * 完全一致，无任何前缀。
 */
object ConfigManager {

    private const val TAG = "HyperDock[ConfigManager]"
    private const val PREFS_CORE = "HyperDockXposedCore"
    private const val PREFS_SHARD_PREFIX = "HyperDockXposedShard"
    private const val SHARD_COUNT = 32

    @Volatile private var corePrefs: SharedPreferences? = null
    @Volatile private var initialized = false
    @Volatile private var module: XposedModule? = null

    private val shardPrefs = arrayOfNulls<SharedPreferences>(SHARD_COUNT)
    private val changeListeners = mutableListOf<() -> Unit>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        //module?.log("$TAG: prefs changed: key=$key")
        notifyListeners()
    }

    /**
     * 初始化：直接通过 [XposedModule.getRemotePreferences] 同步获取远程 SharedPreferences。
     * 幂等，多次调用只执行一次。
     */
    @Synchronized
    fun init(module: XposedModule) {
        if (initialized) return
        try {
            val prefs = module.getRemotePreferences(PREFS_CORE)
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            corePrefs = prefs
            this.module = module
            initialized = true
            module.log("$TAG: remote prefs '$PREFS_CORE' loaded")
            notifyListeners()
        } catch (e: UnsupportedOperationException) {
            module.logWarn("$TAG: init failed: embedded framework, remote prefs unavailable")
            initialized = true
        } catch (e: Throwable) {
            module.logError("$TAG: init failed: ${e.message}")
            initialized = true
        }
    }

    /** 注册配置变化回调，Prefs 每次变更后触发（调用方负责只注册一次）。 */
    @Synchronized
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    // ── 类型化读取 ──────────────────────────────────────────────────────────────

    fun getBoolean(key: String, default: Boolean): Boolean =
        try {
            prefsForKey(key)?.getBoolean(key, default) ?: default
        } catch (_: ClassCastException) {
            default
        }

    fun getString(key: String, default: String = ""): String =
        try {
            prefsForKey(key)?.getString(key, default) ?: default
        } catch (_: ClassCastException) {
            default
        }

    fun getInt(key: String, default: Int): Int =
        try {
            prefsForKey(key)?.getInt(key, default) ?: default
        } catch (_: ClassCastException) {
            default
        }

    fun getLong(key: String, default: Long): Long =
        try {
            prefsForKey(key)?.getLong(key, default) ?: default
        } catch (_: ClassCastException) {
            default
        }

    fun getFloat(key: String, default: Float): Float =
        try {
            prefsForKey(key)?.getFloat(key, default) ?: default
        } catch (_: ClassCastException) {
            default
        }

    fun getDouble(key: String, default: Double): Double = getFloat(key, default.toFloat()).toDouble()

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> =
        try {
            prefsForKey(key)?.getStringSet(key, default)
                ?.filterNot { it == PrefKeys.EMPTY_SET_MARKER }
                ?.toSet()
                ?: default
        } catch (_: ClassCastException) {
            default
        }

    fun contains(key: String): Boolean = prefsForKey(key)?.contains(key) ?: false

    fun isDebugLogEnabled(): Boolean = getBoolean(PrefKeys.DEBUG_LOG, false)

    /** 供同进程内其他组件获取 module 引用以写日志。 */
    fun module(): XposedModule? = module

    // ── 内部实现 ────────────────────────────────────────────────────────────────

    private fun prefsForKey(key: String): SharedPreferences? {
        if (key in PrefKeys.CORE) return corePrefs
        val index = shardForKey(key)
        shardPrefs[index]?.let { return it }
        val m = module ?: return null
        return synchronized(this) {
            shardPrefs[index] ?: try {
                m.getRemotePreferences("$PREFS_SHARD_PREFIX$index").also { prefs ->
                    prefs.registerOnSharedPreferenceChangeListener(prefsListener)
                    shardPrefs[index] = prefs
                    //m.log("$TAG: remote prefs '$PREFS_SHARD_PREFIX$index' loaded")
                }
            } catch (e: Throwable) {
                m.logError("$TAG: shard $index load failed: ${e.message}")
                null
            }
        }
    }

    private fun shardForKey(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % SHARD_COUNT

    private fun notifyListeners() {
        val ls = synchronized(this) { changeListeners.toList() }
        ls.forEach { runCatching { it() } }
    }
}
