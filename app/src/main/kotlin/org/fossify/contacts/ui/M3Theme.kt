package org.fossify.contacts.ui

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.fossify.contacts.R

/**
 * M3 配色与深浅模式的应用入口。
 *
 * ── 为什么要有这个类 ────────────────────────────────────────────
 *
 * Android 没法在运行时改一个 @color 的值。想让用户切配色，只能让布局引用
 * ?attr/colorPrimary 这类**属性**，再用不同的 ThemeOverlay 决定属性指向哪个颜色。
 *
 * 所以每个 Activity 都必须在 setContentView **之前**调 [apply]。晚了的话
 * 已经 inflate 出来的 View 会保留旧主题的颜色，表现为"切了配色但有几个控件没变"。
 *
 * ── 切换时为什么要 recreate ──────────────────────────────────
 *
 * 主题是在 Activity 创建时解析的，之后改 theme 对已有 View 无效。
 * [switchPalette] 和 [switchDarkMode] 都会走 recreate()，这是 Android 上
 * 换主题的标准做法，也是系统设置里换深色模式时发生的事。
 */
object M3Theme {

    private const val PREFS = "m3_theme"
    private const val KEY_PALETTE = "palette"
    private const val KEY_DARK_MODE = "dark_mode"

    /** 配色。名字和设计稿里的 palette 参数一致。 */
    enum class Palette(val id: String) {
        DEFAULT("default"),
        TEAL("teal"),
        WARM("warm");

        companion object {
            fun from(id: String?) = entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /** 深浅模式。FOLLOW_SYSTEM 是默认值 —— 用户没表态时不要替他决定。 */
    enum class DarkMode(val id: String, val nightMode: Int) {
        FOLLOW_SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun from(id: String?) = entries.firstOrNull { it.id == id } ?: FOLLOW_SYSTEM
        }
    }

    // ------------------------------------------------------------------ 读写

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun palette(context: Context): Palette =
        Palette.from(prefs(context).getString(KEY_PALETTE, null))

    fun darkMode(context: Context): DarkMode =
        DarkMode.from(prefs(context).getString(KEY_DARK_MODE, null))

    // ------------------------------------------------------------------ 应用

    /**
     * 套主题。**必须在 setContentView 之前调用。**
     *
     * 基础主题永远是 Theme.Contacts.M3；非默认配色再叠一层 overlay。
     * 用 theme.applyStyle(..., force = true) 而不是换基础主题，是因为
     * overlay 只需要覆盖颜色，其余属性（commons 那些）要原样保留。
     */
    fun apply(activity: Activity) {
        activity.setTheme(R.style.Theme_Contacts_M3)
        when (palette(activity)) {
            Palette.DEFAULT -> Unit
            Palette.TEAL -> activity.theme.applyStyle(R.style.ThemeOverlay_Contacts_Teal, true)
            Palette.WARM -> activity.theme.applyStyle(R.style.ThemeOverlay_Contacts_Warm, true)
        }
    }

    /** 进程启动时调一次，把保存的深浅模式还给 AppCompat。放在 Application.onCreate。 */
    fun applyDarkModeGlobally(context: Context) {
        AppCompatDelegate.setDefaultNightMode(darkMode(context).nightMode)
    }

    fun switchPalette(activity: Activity, palette: Palette) {
        if (palette == palette(activity)) return
        prefs(activity).edit().putString(KEY_PALETTE, palette.id).apply()
        activity.recreate()
    }

    fun switchDarkMode(activity: Activity, mode: DarkMode) {
        if (mode == darkMode(activity)) return
        prefs(activity).edit().putString(KEY_DARK_MODE, mode.id).apply()
        // setDefaultNightMode 自己会触发所有 Activity 重建，这里不用再 recreate，
        // 重复调会导致重建两次，肉眼可见地闪一下。
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }
}
