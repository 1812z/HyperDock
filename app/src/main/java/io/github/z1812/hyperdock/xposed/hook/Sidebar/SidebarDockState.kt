package io.github.z1812.hyperdock.xposed.hook.Sidebar

/**
 * 侧边栏（Dock）列表的共享状态：`SidebarColumnsHook` 负责算列宽/列数，
 * `SidebarDockSlotHook` 负责往列表里注入条目，两边的决策都要用到同一份
 * "当前显示列表 / 分割线类 / 速记类 / 速记旁条目实例"。
 */
internal object SidebarDockState {

    /** 分割线模型类型；判断当前模型，不能根据复用 holder 的 View 可见性判断。 */
    @Volatile var dividerClass: Class<*>? = null

    /** 速记条目（原生 `c8.k`）。 */
    @Volatile var shorthandClass: Class<*>? = null

    /** 「速记旁边的图标」的条目实例；未配置时为 null。 */
    @Volatile var slotItem: Any? = null

    /** 最近一次提交给 adapter 的（已注入的）列表，供 SpanSizeLookup 查询。 */
    @Volatile var displayList: List<Any> = emptyList()

    /** 横屏可能没有速记和分割线，位置必须以本次提交的模型为准。 */
    fun isDividerPosition(position: Int): Boolean {
        val type = dividerClass ?: return false
        return displayList.getOrNull(position)?.javaClass == type
    }

    /**
     * 某个位置应占几列。
     *
     * 两列时：分割线占满整行（它在速记下方，把顶部区域和用户应用分开）；
     * 没配置速记旁图标时，速记也占满整行 —— item 根布局是 match_parent、
     * 图标 `center_horizontal`，所以图标保持 48dp 原大小居中，不会被拉伸。
     */
    fun spanFor(position: Int, columns: Int): Int {
        if (columns <= 1) return 1
        val list = displayList
        if (position < 0 || position >= list.size) return 1
        if (isDividerPosition(position)) return columns
        if (slotItem == null && position == 0 && shorthandClass == list[position].javaClass) {
            return columns
        }
        return 1
    }
}
