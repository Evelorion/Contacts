package org.fossify.contacts.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import org.fossify.commons.helpers.BaseConfig
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
     * 套主题。**必须在 super.onCreate() 之后、setContentView() 之前调用。**
     *
     * ── 顺序为什么是这个 ────────────────────────────────────────
     *
     * commons 的 BaseSimpleActivity.onCreate() 里有一句
     *     setTheme(getThemeId(...))
     * 它会拿用户在「自定义颜色」里存的偏好现生成一个主题，然后**整个替换掉**
     * 当前主题。所以在 super.onCreate() 之前 setTheme 是白费的 ——
     * 第一版就是这么写的，结果全 App 是 commons 默认的蓝色，
     * M3 的 token 一个都没生效。
     *
     * 放在 super.onCreate() 之后就轮到我们最后说话。
     */
    fun apply(activity: Activity) {
        activity.setTheme(R.style.Theme_Contacts_M3)
        when (palette(activity)) {
            Palette.DEFAULT -> Unit
            Palette.TEAL -> activity.theme.applyStyle(R.style.ThemeOverlay_Contacts_Teal, true)
            Palette.WARM -> activity.theme.applyStyle(R.style.ThemeOverlay_Contacts_Warm, true)
        }
    }

    /**
     * 把 M3 的颜色写进 commons 的颜色偏好里。
     *
     * ── 为什么必须做这一步 ──────────────────────────────────────
     *
     * 光换主题不够。commons 里大量代码在**运行时**给 View 重新着色：
     *     updateTextColors(holder)          遍历子 View 设 textColor
     *     getProperTextColor()              读 baseConfig.textColor
     *     getProperPrimaryColor()           读 baseConfig.primaryColor
     *     getProperBackgroundColor()        读 baseConfig.backgroundColor
     * 这些值来自 SharedPreferences，和主题属性完全无关。不同步的话，
     * 快速滚动条、对话框、空列表提示、拖拽手柄这些会保持 commons 的蓝，
     * 和 M3 的紫拼在一起。
     *
     * 与其去每个调用点删掉这些调用（有几十处，而且 commons 内部还有更多
     * 碰不到的），不如**把源头的值改成 M3 的**——这样 commons 的着色逻辑
     * 原样跑，产出的却是 M3 的颜色。
     *
     * 在 Application.onCreate 里调一次，切换配色/深浅时再调。
     */
    fun syncCommonsColors(context: Context) {
        val res = context.resources
        val dark = when (darkMode(context)) {
            DarkMode.DARK -> true
            DarkMode.LIGHT -> false
            DarkMode.FOLLOW_SYSTEM ->
                (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }

        // 这里必须直接查颜色资源而不是走主题属性 —— Application 上没有
        // 叠加过 ThemeOverlay，themeColor() 在这里拿不到正确的值。
        fun color(default: Int, teal: Int, warm: Int, tealDark: Int, warmDark: Int, defaultDark: Int): Int {
            val id = when (palette(context)) {
                Palette.DEFAULT -> if (dark) defaultDark else default
                Palette.TEAL -> if (dark) tealDark else teal
                Palette.WARM -> if (dark) warmDark else warm
            }
            return ContextCompat.getColor(context, id)
        }

        val primary = color(
            R.color.m3_primary, R.color.m3_teal_primary, R.color.m3_warm_primary,
            R.color.m3_teal_dark_primary, R.color.m3_warm_dark_primary, R.color.m3_primary
        )
        val surface = color(
            R.color.m3_surface, R.color.m3_teal_surface, R.color.m3_warm_surface,
            R.color.m3_teal_dark_surface, R.color.m3_warm_dark_surface, R.color.m3_surface
        )
        val onSurface = ContextCompat.getColor(context, R.color.m3_on_surface)

        BaseConfig.newInstance(context).apply {
            textColor = onSurface
            backgroundColor = surface
            primaryColor = primary
            accentColor = primary
        }
    }

    /**
     * 把保存的深浅模式还给 AppCompat。在 Application.onCreate 里调一次。
     *
     * 必须早于任何 Activity 创建 —— 晚了的话第一个页面会先按系统默认渲染
     * 一帧再翻过来，肉眼能看到闪一下。
     */
    fun applyDarkModeGlobally(context: Context) {
        AppCompatDelegate.setDefaultNightMode(darkMode(context).nightMode)
    }

    fun switchPalette(activity: Activity, palette: Palette) {
        if (palette == palette(activity)) return
        prefs(activity).edit().putString(KEY_PALETTE, palette.id).apply()
        syncCommonsColors(activity)
        activity.recreate()
    }

    fun switchDarkMode(activity: Activity, mode: DarkMode) {
        if (mode == darkMode(activity)) return
        prefs(activity).edit().putString(KEY_DARK_MODE, mode.id).apply()
        syncCommonsColors(activity)
        // setDefaultNightMode 自己会触发所有 Activity 重建，这里不用再 recreate，
        // 重复调会导致重建两次，肉眼可见地闪一下。
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }
}
