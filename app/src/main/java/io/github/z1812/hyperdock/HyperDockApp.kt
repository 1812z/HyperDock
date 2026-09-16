package io.github.z1812.hyperdock

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.z1812.hyperdock.compose.data.PrefsRepository
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.security.MessageDigest

/**
 * 自定义 Application，负责将 Compose 端写入的配置镜像同步到 LSPosed 的
 * RemotePreferences，使 Hook 进程能读到最新配置。
 *
 * RemotePreferences 在 hook 进程打开时会经 Binder 初始化整组数据，单组过大会触发
 * TransactionTooLarge/DeadObject。这里将配置拆为 core + shards，避免任何单个 prefs 组过大。
 */
class HyperDockApp : Application(), XposedServiceHelper.OnServiceListener {

    private val configPrefs: SharedPreferences by lazy {
        getSharedPreferences(PrefsRepository.PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var xposedService: XposedService? = null

    private val syncLock = Any()

    private val configPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        syncKeyToRemote(prefs, key)
    }

    override fun onCreate() {
        super.onCreate()
        AppLocaleController.apply(this, configPrefs.getString(PrefKeys.LOCALE, "").orEmpty())
        XposedServiceHelper.registerListener(this)
        configPrefs.registerOnSharedPreferenceChangeListener(configPrefsListener)
    }

    override fun onTerminate() {
        configPrefs.unregisterOnSharedPreferenceChangeListener(configPrefsListener)
        xposedService = null
        ServiceState.markNotReady()
        super.onTerminate()
    }

    // ── XposedService 回调 ────────────────────────────────────────────────────

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        ServiceState.markReady(service.apiVersion, service.frameworkName, service.frameworkVersion)
        Log.d(TAG, "XposedService bound, syncing sharded prefs")
        syncAllToRemote(service)
        ServiceState.notifyReady()
    }

    override fun onServiceDied(service: XposedService) {
        xposedService = null
        ServiceState.markNotReady()
        Log.d(TAG, "XposedService died")
    }

    // ── 同步实现 ──────────────────────────────────────────────────────────────

    private fun syncKeyToRemote(prefs: SharedPreferences, key: String?) {
        val service = xposedService ?: return
        syncToRemote(service, prefs, key)
    }

    private fun syncAllToRemote(service: XposedService) {
        syncToRemote(service, configPrefs, key = null)
    }

    private fun syncToRemote(service: XposedService, sourcePrefs: SharedPreferences, key: String?) {
        synchronized(syncLock) {
            try {
                if (key == null) {
                    syncAllIfChanged(service, sourcePrefs)
                } else {
                    if (!shouldSyncKey(key)) return
                    invalidateRemoteDigest(service)
                    val remote = service.getRemotePreferences(remotePrefsNameForKey(key))
                    val editor = remote.edit() ?: error("remote editor unavailable")
                    check(writeValue(editor, key, sourcePrefs.all[key])) {
                        "unsupported preference value for $key"
                    }
                    check(editor.commit()) { "remote commit failed for $key" }
                    Log.d(TAG, "synced key=$key to remote prefs")
                }
            } catch (e: Exception) {
                val scope = if (key == null) "all" else key
                Log.w(TAG, "syncToRemote failed (key=$scope): ${e.message}")
            }
        }
    }

    fun requestScope(packages: List<String>) {
        val service = xposedService ?: throw IllegalStateException("XposedService is not ready")
        requestScope(service, packages) {}
    }

    fun requestScope(
        packages: List<String>,
        onResult: (Result<List<String>>) -> Unit,
    ) {
        val service = xposedService
        if (service == null) {
            onResult(Result.failure(IllegalStateException("XposedService is not ready")))
            return
        }
        requestScope(service, packages, onResult)
    }

    private fun requestScope(
        service: XposedService,
        packages: List<String>,
        onResult: (Result<List<String>>) -> Unit,
    ) {
        val currentScope = service.scope.toSet()
        val missingPackages = packages.filterNot { it in currentScope }
        if (missingPackages.isEmpty()) {
            Log.d(TAG, "scope already granted: $packages")
            onResult(Result.success(service.scope))
            return
        }

        service.requestScope(missingPackages, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(scope: List<String>) {
                Log.d(TAG, "scope request approved: $scope")
                onResult(Result.success(scope))
            }

            override fun onScopeRequestFailed(message: String) {
                Log.w(TAG, "scope request failed: $message")
                onResult(Result.failure(IllegalStateException(message)))
            }
        })
    }

    fun getCurrentScope(): List<String> {
        val service = xposedService ?: throw IllegalStateException("XposedService is not ready")
        return service.scope
    }

    fun getFrameworkInfo(): Map<String, Any> {
        val service = xposedService ?: throw IllegalStateException("XposedService is not ready")
        return mapOf(
            "apiVersion" to service.apiVersion,
            "frameworkName" to service.frameworkName,
            "frameworkVersion" to service.frameworkVersion,
            "frameworkVersionCode" to service.frameworkVersionCode,
            "scope" to service.scope,
        )
    }

    private fun syncAllIfChanged(service: XposedService, src: SharedPreferences) {
        val source = src.all.filterKeys(::shouldSyncKey)
        val digest = configDigest(source)
        val meta = service.getRemotePreferences(REMOTE_PREFS_META)
        if (meta.getInt(META_FORMAT_VERSION, 0) == SYNC_FORMAT_VERSION &&
            meta.getString(META_CONFIG_DIGEST, null) == digest
        ) {
            Log.d(TAG, "config unchanged, skipped full sync (${source.size} keys)")
            return
        }

        val grouped = source.entries.groupBy({ remotePrefsNameForKey(it.key) }, { it })
        var changedGroups = 0
        for (prefsName in allRemotePrefsNames()) {
            val entries = grouped[prefsName].orEmpty()
            if (syncGroupDiff(service, prefsName, entries)) changedGroups++
        }
        updateRemoteDigest(service, digest)
        Log.d(TAG, "diff sync done: ${source.size} keys, $changedGroups changed groups")
    }

    private fun syncGroupDiff(
        service: XposedService,
        prefsName: String,
        entries: List<Map.Entry<String, Any?>>,
    ): Boolean {
        val remote = service.getRemotePreferences(prefsName)
        val current = remote.all
        val target = entries.associate { it.key to it.value }
        val removedKeys = current.keys - target.keys
        val changedEntries = target.filter { (key, value) -> current[key] != value }
        if (removedKeys.isEmpty() && changedEntries.isEmpty()) return false

        val editor = remote.edit() ?: error("remote editor unavailable for $prefsName")
        removedKeys.forEach(editor::remove)
        for ((key, value) in changedEntries) {
            check(writeValue(editor, key, value)) { "unsupported preference value for $key" }
        }
        check(editor.commit()) { "remote commit failed for $prefsName" }
        Log.d(
            TAG,
            "diff synced $prefsName: ${changedEntries.size} changed, ${removedKeys.size} removed",
        )
        return true
    }

    private fun updateRemoteDigest(service: XposedService, digest: String) {
        val editor = service.getRemotePreferences(REMOTE_PREFS_META).edit()
            ?: error("remote metadata editor unavailable")
        editor.putInt(META_FORMAT_VERSION, SYNC_FORMAT_VERSION)
        editor.putString(META_CONFIG_DIGEST, digest)
        check(editor.commit()) { "remote metadata commit failed" }
    }

    private fun invalidateRemoteDigest(service: XposedService) {
        val editor = service.getRemotePreferences(REMOTE_PREFS_META).edit()
            ?: error("remote metadata editor unavailable")
        editor.remove(META_CONFIG_DIGEST)
        check(editor.commit()) { "remote metadata invalidation failed" }
    }

    private fun configDigest(values: Map<String, *>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.asSequence()
            .filter { shouldSyncKey(it.key) }
            .sortedBy { it.key }
            .forEach { (key, value) ->
                updateDigestPart(digest, key)
                when (value) {
                    is Boolean -> updateDigestPart(digest, "b:$value")
                    is Int -> updateDigestPart(digest, "i:$value")
                    is Long -> updateDigestPart(digest, "l:$value")
                    is Float -> updateDigestPart(digest, "f:${value.toRawBits()}")
                    is String -> updateDigestPart(digest, "s:$value")
                    is Set<*> -> {
                        updateDigestPart(digest, "set")
                        value.filterIsInstance<String>().sorted().forEach {
                            updateDigestPart(digest, it)
                        }
                    }
                    null -> updateDigestPart(digest, "null")
                    else -> updateDigestPart(digest, "unsupported:${value::class.java.name}:$value")
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigestPart(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }

    private fun allRemotePrefsNames(): List<String> = buildList(SHARD_COUNT + 1) {
        add(REMOTE_PREFS_CORE)
        for (index in 0 until SHARD_COUNT) add("$REMOTE_PREFS_SHARD_PREFIX$index")
    }

    private fun writeValue(editor: SharedPreferences.Editor, key: String, value: Any?): Boolean {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
                if (value.any { it !is String }) return false
                editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
            null -> editor.remove(key)
            else -> return false
        }
        return true
    }

    private fun shouldSyncKey(key: String): Boolean = key in PrefKeys.SYNCED

    private fun remotePrefsNameForKey(key: String): String =
        if (key in PrefKeys.CORE) REMOTE_PREFS_CORE
        else "$REMOTE_PREFS_SHARD_PREFIX${shardForKey(key)}"

    private fun shardForKey(key: String): Int = (key.hashCode() and Int.MAX_VALUE) % SHARD_COUNT

    companion object {
        private const val TAG = "HyperDock[App]"
        const val REMOTE_PREFS_CORE = "HyperDockXposedCore"
        const val REMOTE_PREFS_SHARD_PREFIX = "HyperDockXposedShard"
        private const val REMOTE_PREFS_META = "HyperDockXposedMeta"
        const val SHARD_COUNT = 32
        private const val META_FORMAT_VERSION = "sync_format_version"
        private const val META_CONFIG_DIGEST = "config_digest"
        private const val SYNC_FORMAT_VERSION = 1

        private object ServiceState {
            @Volatile private var serviceReady = false
            @Volatile private var apiVersion: Int = 0
            @Volatile private var frameworkName: String = ""
            @Volatile private var frameworkVersion: String = ""
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            private val serviceReadyLock = Object()

            fun isReady(): Boolean = serviceReady

            fun getApiVersion(): Int = apiVersion

            fun getFrameworkName(): String = frameworkName

            fun getFrameworkVersion(): String = frameworkVersion

            fun markReady(newApiVersion: Int, newFrameworkName: String, newFrameworkVersion: String) {
                apiVersion = newApiVersion
                frameworkName = newFrameworkName
                frameworkVersion = newFrameworkVersion
                serviceReady = true
            }

            fun markNotReady() {
                serviceReady = false
                apiVersion = 0
                frameworkName = ""
                frameworkVersion = ""
            }

            fun notifyReady() {
                synchronized(serviceReadyLock) { serviceReadyLock.notifyAll() }
            }

            fun awaitReady(timeoutMs: Long): Boolean {
                if (isReady()) return true
                synchronized(serviceReadyLock) {
                    if (!isReady()) {
                        try {
                            serviceReadyLock.wait(timeoutMs)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                return isReady()
            }
        }

        fun isReady(): Boolean = ServiceState.isReady()

        fun getApiVersion(): Int = ServiceState.getApiVersion()

        fun getFrameworkName(): String = ServiceState.getFrameworkName()

        fun getFrameworkVersion(): String = ServiceState.getFrameworkVersion()

        fun awaitReady(timeoutMs: Long = 1500): Boolean = ServiceState.awaitReady(timeoutMs)
    }
}
