package io.github.z1812.hyperdock.compose.service

import android.content.Context
import android.content.SharedPreferences
import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import org.json.JSONArray
import org.json.JSONObject

/** 导出/导入全部配置。 */
internal object ConfigBackupService {
    private const val SCHEMA_VERSION = 1

    fun exportJson(context: Context): String {
        val settings = JSONObject()
        prefs(context).all.forEach { (key, value) ->
            if (key !in PrefKeys.SYNCED) return@forEach
            settings.put(key, toJsonValue(value))
        }
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        settings.put(PrefKeys.CONFIG_APP_VERSION, appVersion)
        settings.put(PrefKeys.CONFIG_SCHEMA_VERSION, SCHEMA_VERSION)
        return JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("appVersion", appVersion)
            .put("settings", settings)
            .toString(2)
    }

    fun importJson(context: Context, raw: String): Int {
        val root = runCatching { JSONObject(raw) }.getOrElse { throw InvalidConfigException() }
        val settings = root.optJSONObject("settings") ?: throw InvalidConfigException()
        val editor = prefs(context).edit()

        var count = 0
        settings.keys().forEach { key ->
            if (key !in PrefKeys.SYNCED) return@forEach
            val value = settings.opt(key)
            if (value == null || value === JSONObject.NULL) return@forEach
            when {
                value is JSONArray -> {
                    val set = (0 until value.length())
                        .mapNotNull { value.optString(it).takeIf { s -> s.isNotEmpty() } }
                        .toSet()
                    editor.putStringSet(key, set)
                }
                value is Boolean -> editor.putBoolean(key, value)
                value is Float || value is Double -> editor.putFloat(key, (value as Number).toFloat())
                value is Number -> editor.putLong(key, value.toLong())
                value is String -> editor.putString(key, value)
                else -> throw InvalidConfigException()
            }
            count++
        }
        root.optString("appVersion").trim().takeIf(String::isNotEmpty)?.let {
            editor.putString(PrefKeys.CONFIG_APP_VERSION, it)
        }
        editor.putInt(PrefKeys.CONFIG_SCHEMA_VERSION, SCHEMA_VERSION)
        if (!editor.commit()) throw IllegalStateException("SharedPreferences commit failed")
        return count
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PrefsRepository.PREFS_NAME, Context.MODE_PRIVATE)

    private fun toJsonValue(value: Any?): Any = when (value) {
        is Set<*> -> JSONArray(value.filterIsInstance<String>().sorted())
        null -> JSONObject.NULL
        else -> value
    }
}

internal class InvalidConfigException : IllegalArgumentException()
