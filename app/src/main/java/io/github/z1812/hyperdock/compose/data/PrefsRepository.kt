package io.github.z1812.hyperdock.compose.data

import android.content.Context
import android.content.SharedPreferences
import io.github.z1812.hyperdock.PrefKeys

/**
 * 应用配置读写。
 *
 * 存储文件为 [PREFS_NAME]，键使用无前缀的普通名称（例如 `theme_mode`）。
 * Compose 端与 Hook 端共用同一套键名。
 */
class PrefsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun all(): Map<String, *> = prefs.all

    fun getBoolean(key: String, default: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getString(key: String, default: String = ""): String =
        runCatching { prefs.getString(key, default) ?: default }.getOrDefault(default)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getLong(key: String, default: Long): Long =
        runCatching { prefs.getLong(key, default) }.getOrDefault(default)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getInt(key: String, default: Int): Int =
        runCatching { prefs.getInt(key, default) }.getOrDefault(default)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getStringSet(key: String): Set<String> =
        runCatching {
            prefs.getStringSet(key, emptySet())
                ?.filterNot { it == PrefKeys.EMPTY_SET_MARKER }
                ?.toSet()
                ?: emptySet()
        }
            .getOrDefault(emptySet())

    fun contains(key: String): Boolean = prefs.contains(key)

    fun putStringSet(key: String, value: Set<String>) {
        val stored = if (value.isEmpty()) setOf(PrefKeys.EMPTY_SET_MARKER) else value
        prefs.edit().putStringSet(key, stored).apply()
    }

    fun getDouble(key: String, default: Double): Double =
        runCatching { prefs.getFloat(key, default.toFloat()).toDouble() }.getOrDefault(default)

    fun putDouble(key: String, value: Double) {
        prefs.edit().putFloat(key, value.toFloat()).apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun addChangeListener(listener: (String) -> Unit): () -> Unit {
        val delegate = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            key?.let(listener)
        }
        prefs.registerOnSharedPreferenceChangeListener(delegate)
        return { prefs.unregisterOnSharedPreferenceChangeListener(delegate) }
    }

    companion object {
        /** 配置文件名，Application 与备份服务共用。 */
        const val PREFS_NAME = "HyperDockPreferences"
    }
}
