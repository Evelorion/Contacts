package org.fossify.contacts.ui

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

/**
 * 从当前主题里取一个颜色属性的实际值。
 *
 * 布局里能直接写 ?attr/colorPrimary，代码里不行 —— 代码拿到的是属性 id，
 * 得经过主题解析才能变成真正的颜色。所有在代码里设颜色的地方都要走这里，
 * 直接用 ContextCompat.getColor(R.color.m3_primary) 会绕开 ThemeOverlay，
 * 切配色时那些颜色不会变。
 *
 * 注意 context 必须是 **Activity**，不能是 applicationContext ——
 * applicationContext 上没有叠加过 ThemeOverlay。
 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) {
        androidx.core.content.ContextCompat.getColor(this, value.resourceId)
    } else {
        value.data
    }
}
