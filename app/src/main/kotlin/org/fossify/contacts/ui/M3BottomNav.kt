package org.fossify.contacts.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR
import org.fossify.contacts.R

/**
 * 底部导航。
 *
 * 不用 BottomNavigationView：设计稿的胶囊是 64×32，而 BottomNavigationView
 * 的 item 内部布局是私有的，改不到那个尺寸。手写一共几十行，还省掉一个 menu xml。
 *
 * 用法：
 *     val nav = M3BottomNav(binding.mainBottomNav)
 *     nav.setItems(M3BottomNav.defaultItems()) { index -> 切页面 }
 *     nav.select(0)
 */
class M3BottomNav(private val container: LinearLayout) {

    data class Item(
        @DrawableRes val icon: Int,
        @StringRes val label: Int,
    )

    private val views = mutableListOf<View>()
    private var selectedIndex = -1

    fun setItems(items: List<Item>, onSelect: (Int) -> Unit) {
        container.removeAllViews()
        views.clear()

        val inflater = LayoutInflater.from(container.context)
        items.forEachIndexed { index, item ->
            val view = inflater.inflate(R.layout.item_m3_nav, container, false)
            view.findViewById<ImageView>(R.id.nav_icon)
                .setImageDrawable(ContextCompat.getDrawable(container.context, item.icon))
            view.findViewById<TextView>(R.id.nav_label).setText(item.label)
            view.contentDescription = container.context.getString(item.label)
            view.setOnClickListener {
                // 重复点当前项不回调，否则会重复触发页面切换动画
                if (index != selectedIndex) {
                    select(index)
                    onSelect(index)
                }
            }
            container.addView(view)
            views.add(view)
        }
    }

    /**
     * 只改外观，不触发回调。
     * 用于同步外部造成的页面变化（返回键、深链接进来的页面）。
     */
    fun select(index: Int) {
        selectedIndex = index
        views.forEachIndexed { i, view ->
            val on = i == index
            val context = view.context

            // 胶囊的显隐由 selected 状态驱动 m3_bg_nav_pill 里的 selector
            view.findViewById<View>(R.id.nav_pill).isSelected = on
            view.isSelected = on

            view.findViewById<ImageView>(R.id.nav_icon).setColorFilter(
                context.themeColor(
                    if (on) MaterialR.attr.colorOnSecondaryContainer
                    else MaterialR.attr.colorOnSurfaceVariant
                )
            )
            view.findViewById<TextView>(R.id.nav_label).setTextColor(
                context.themeColor(
                    if (on) MaterialR.attr.colorOnSurface
                    else MaterialR.attr.colorOnSurfaceVariant
                )
            )
        }
    }

    companion object {
        /** 联系人 / 收藏 / 设置，和设计稿一致。 */
        // 文案来自 commons（contacts_tab / favorites_tab / settings 都在那边定义），
        // 图标是 app 自己的。Kotlin 里的 R 是按包区分的，XML 里不用管是因为
        // 资源合并后同名的会被统一到 app 的 R —— 但 Kotlin 引用必须写对包。
        fun defaultItems() = listOf(
            Item(R.drawable.ic_m3_group, org.fossify.commons.R.string.contacts_tab),
            Item(R.drawable.ic_m3_star, org.fossify.commons.R.string.favorites_tab),
            Item(R.drawable.ic_m3_settings, org.fossify.commons.R.string.settings),
        )
    }
}
