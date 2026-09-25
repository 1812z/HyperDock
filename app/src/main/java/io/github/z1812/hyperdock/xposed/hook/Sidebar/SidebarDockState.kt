package io.github.z1812.hyperdock.xposed.hook.Sidebar

/**
 * 侧边栏（Dock）列表的共享状态：`SidebarColumnsHook` 负责算列宽/列数，
 * `SidebarDockSlotHook` 负责往列表里注入条目，两边的决策都要用到同一份
 * "当前显示列表 / 分割线类 / 速记类 / 速记旁条目实例"。
 */
internal object SidebarDockState {

    /**
     * 分割线条目（原生 `c8.b`：绑定时只显示一条 24dp 的线）。
     *
     * **只当作"列表里有没有分割线"的门闩** —— 不要拿它去比对每个 position 的类：
     * ViewHolder 是复用的，分割线那行的 holder 回收给应用行之后，那条线可能还留着
     * VISIBLE（宿主只在绑定分割线条目时 show，从不主动 hide），按类判断会把某个应用
     * 误认成分割线，于是"分割线下面第一个应用独占一行居中"。
     * 真正用来决定占格的是固定位置，见 [isDividerPosition]。
     */
    @Volatile var dividerClass: Class<*>? = null

    /** 速记条目（原生 `c8.k`）。 */
    @Volatile var shorthandClass: Class<*>? = null

    /** 「速记旁边的图标」的条目实例；未配置时为 null。 */
    @Volatile var slotItem: Any? = null

    /** 最近一次提交给 adapter 的（已注入的）列表，供 SpanSizeLookup 查询。 */
    @Volatile var displayList: List<Any> = emptyList()

    /**
     * 顶部区域占了几格：速记 1 格（未配槽位时独占一行）+ 配了槽位则再 1 格。
     * 分割线紧跟其后，所以它的下标恒等于这个数量。
     */
    private fun dividerIndex(): Int = if (slotItem != null) 2 else 1

    /**
     * [position] 是不是分割线那一行。
     *
     * 原生列表顺序恒为 `[速记][分割线][应用…]`，我们把槽位插在速记之后，
     * 因此分割线的位置是确定的：没槽位时 index 1，有槽位时 index 2。
     */
    fun isDividerPosition(position: Int): Boolean {
        if (dividerClass == null) return false
        return position == dividerIndex()
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
