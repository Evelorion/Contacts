package org.fossify.contacts.ui

import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.R as MaterialR
import org.fossify.contacts.R

/**
 * 顶部的筛选 chip（全部 / 常用 / 群组）。
 *
 * 和底部导航是**两个维度**：chip 换的是当前列表筛什么，导航换的是在哪个页面。
 * 设计稿里 1a 规范型默认不显示 chip（靠底部导航切换），
 * 但保留这个组件，设置里打开「显示筛选栏」时用。
 */
class M3FilterChips(private val container: LinearLayout) {

    private val chips = mutableListOf<TextView>()
    private var selectedIndex = 0

    fun setItems(@StringRes labels: List<Int>, onSelect: (Int) -> Unit) {
        container.removeAllViews()
        chips.clear()

        val inflater = LayoutInflater.from(container.context)
        val gap = container.resources.getDimensionPixelSize(R.dimen.m3_chip_gap)

        labels.forEachIndexed { index, labelRes ->
            val chip = inflater.inflate(R.layout.item_m3_chip, container, false) as TextView
            chip.setText(labelRes)
            (chip.layoutParams as LinearLayout.LayoutParams).marginEnd =
                if (index == labels.lastIndex) 0 else gap
            chip.setOnClickListener {
                if (index != selectedIndex) {
                    select(index)
                    onSelect(index)
                }
            }
            container.addView(chip)
            chips.add(chip)
        }
        select(selectedIndex)
    }

    fun select(index: Int) {
        selectedIndex = index
        chips.forEachIndexed { i, chip ->
            val on = i == index
            chip.isSelected = on
            chip.setTextColor(
                chip.context.themeColor(
                    if (on) MaterialR.attr.colorOnSecondaryContainer
                    else MaterialR.attr.colorOnSurfaceVariant
                )
            )
        }
    }
}
