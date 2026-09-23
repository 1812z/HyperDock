package io.github.z1812.hyperdock.systemtile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收 SystemUI 推来的系统磁贴目录，落库到模块自身的 SharedPreferences + files 目录。
 *
 * 这是"SystemUI → 模块 App"这唯一一条新增通道的接收端。之所以必须新增通道：
 * `RemotePreferences` 只能模块 App → Hook 单向写（hook 侧 `edit()` 返回 null），
 * 所以 Hook 探测到的可用磁贴清单没法回写配置。
 *
 * 落库要在后台线程做，所以用 [goAsync] 把进程存活时间延伸到写完为止 ——
 * 40 条图标的光栅化不适合压在广播接收的主线程上。
 */
class SystemTileCatalogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SystemTileCatalogProtocol.ACTION_CATALOG) return
        val entries = SystemTileCatalogProtocol.readEntries(intent)
        if (entries.isEmpty()) return
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                SystemTileCatalogCache.save(appContext, entries)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "save system tile catalog failed", t)
            } finally {
                runCatching { pending.finish() }
            }
        }.start()
    }

    private companion object {
        const val TAG = "HyperDock[SystemTileCatalog]"
    }
}
