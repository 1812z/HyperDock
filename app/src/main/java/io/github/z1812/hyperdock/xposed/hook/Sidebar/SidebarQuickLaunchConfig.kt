package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.z1812.hyperdock.PrefKeys
import io.github.z1812.hyperdock.quicklaunch.QuickLaunchFormat
import io.github.z1812.hyperdock.xposed.ConfigManager

/** Reads the user-managed 快速启动 entries without touching shortcut settings. */
internal object SidebarQuickLaunchConfig {
    /**
     * 生成注入顺序：模块侧 [PrefKeys.QUICK_FUNCTIONS_ORDER] 记录过的 entryId
     * 按用户自定义顺序在前；未记录的（旧版本升级、外部写入）按字典序追加在尾部。
     */
    fun ids(): List<String> {
        val entries = rawEntries()
        if (entries.isEmpty()) return entries
        val order = ConfigManager.getString(PrefKeys.QUICK_FUNCTIONS_ORDER, "")
            .split(',')
            .filter(String::isNotBlank)
        if (order.isEmpty()) return entries.sorted()
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        val recorded = entries.filter { it.substringAfterLast('|') in rank }
            .sortedBy { rank[it.substringAfterLast('|')] ?: Int.MAX_VALUE }
        val unrecorded = entries.filter { it.substringAfterLast('|') !in rank }.sorted()
        return recorded + unrecorded
    }

    /**
     * `QUICK_FUNCTIONS_ADDED` 的每个条目形如 `entryId|payload`，映射为
     * `activity:<payload>|<entryId>`。
     *
     * payload **原样透传**：活动是组件名，URL 是 `url:...`（见 [QuickLaunchFormat]）。
     * 启动方式由 hook 侧按 payload 前缀分流，所以这里不需要区分，URL 条目也就自动
     * 跟着走完了标签、图标、排序、删除的全套链路。
     */
    private fun rawEntries(): List<String> = ConfigManager.getStringSet(PrefKeys.QUICK_FUNCTIONS_ADDED, emptySet())
        .asSequence()
        .filter(String::isNotBlank)
        .mapNotNull { entry ->
            val entryId = QuickLaunchFormat.storageEntryId(entry) ?: return@mapNotNull null
            val payload = QuickLaunchFormat.storagePayload(entry) ?: return@mapNotNull null
            QuickLaunchFormat.shortcutId(entryId, payload)
        }
        .toList()
}
