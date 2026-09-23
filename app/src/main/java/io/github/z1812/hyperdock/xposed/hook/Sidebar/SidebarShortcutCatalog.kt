package io.github.z1812.hyperdock.xposed.hook.Sidebar

import io.github.z1812.hyperdock.systemtile.SystemTileSpecs

/** Pure data and ordering rules for custom shortcut entries. */
internal object SidebarShortcutCatalog {

    /** HyperIsland 条目的 id 前缀。它们不是系统磁贴，不受磁贴可用性过滤影响。 */
    const val HYPER_ISLAND_PREFIX = "hyperisland_"

    /**
     * 系统磁贴展示顺序。与 Compose 端共用 [SystemTileSpecs]，此处不再单独维护。
     */
    private val systemOrder = SystemTileSpecs.pickerOrder

    private val hyperIslandOrder = listOf("hyperisland_motion_photo", "hyperisland_screen_record")

    /**
     * 系统磁贴文案（zh to en）+ HyperIsland 条目。
     *
     * Hook 代码运行在安全中心进程，读不到模块自身的 R.string，
     * 因此系统磁贴文案必须内联 —— 但与 Compose 端**同源于** [SystemTileSpecs]，
     * 不再是两份手工维护的表。
     *
     * 注意：HyperIsland 条目必须留在本表内。[defaultOrder] 用
     * `it !in systemLabels` 判定"其它"分组，移除会让同一 id 被追加两次。
     */
    val systemLabels: Map<String, Pair<String, String>> =
        SystemTileSpecs.ALL.associate { it.id to (it.zh to it.en) } + mapOf(
            "hyperisland_motion_photo" to ("实况录制" to "Live recording"),
            "hyperisland_screen_record" to ("屏幕录制" to "Screen recording"),
        )

    /**
     * 生成注入顺序：模块侧 [io.github.z1812.hyperdock.PrefKeys.SHORTCUTS_ORDER] 记录过的
     * id 按用户自定义顺序在前；未记录的（旧版本升级、外部写入）按内置规则追加在尾部。
     */
    fun orderIds(added: Set<String>, customOrder: List<String>): List<String> {
        val rank = HashMap<String, Int>(customOrder.size)
        customOrder.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        val recorded = added.filter { it in rank }.sortedBy { rank[it] ?: Int.MAX_VALUE }
        val unrecorded = added.filter { it !in rank }
        if (unrecorded.isEmpty()) return recorded
        return recorded + defaultOrder(unrecorded)
    }

    private fun defaultOrder(added: Collection<String>): List<String> {
        // 动态条目（本机独有、内置表里没有的 spec）在 systemOrder 里查不到，
        // indexOf 返回 -1 会让它们排到最前面；统一折算成"未收录"排在已知项之后。
        val system = added.filter { it in systemLabels && it !in hyperIslandOrder }
            .sortedBy { systemOrder.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        val hyperIsland = added.filter { it in hyperIslandOrder }.sortedBy { hyperIslandOrder.indexOf(it) }
        val activities = added.filter { it.startsWith("activity:") }.sorted()
        val thirdParty = added.filter { '/' in it && !it.startsWith("activity:") }.sorted()
        val others = added.filter { it !in systemLabels && !it.startsWith("activity:") && '/' !in it }.sorted()
        return system + hyperIsland + activities + thirdParty + others
    }
}
