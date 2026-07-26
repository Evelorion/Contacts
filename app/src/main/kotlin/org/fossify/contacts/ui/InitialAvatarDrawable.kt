package org.fossify.contacts.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import android.content.Context
import org.fossify.contacts.R
import kotlin.math.abs
import kotlin.math.min

/**
 * 没有头像照片时显示的首字母色块。
 *
 * ── 颜色怎么定的 ──────────────────────────────────────────────
 *
 * 用**联系人的稳定标识**（联系人 id 或姓名）算哈希取模，而不是列表下标。
 * 用下标的话，删掉一个联系人会导致它后面所有人的头像颜色集体变一次 ——
 * 用户会觉得"我的通讯录怎么自己乱了"。
 *
 * 8 个颜色都是 M3 的 40 号色阶（深色模式下换成 80 号），配的文字颜色
 * 分别是白 / 深，对比度都过 WCAG AA。
 *
 * ── 为什么自己画而不用 TextView 套背景 ────────────────────────
 *
 * 列表里每行一个，套 FrameLayout + TextView + 背景是三层 View。
 * 一个 Drawable 直接画在 ImageView 里，滚动时省掉大量测量和布局。
 */
class InitialAvatarDrawable(
    context: Context,
    private val initial: String,
    key: Any,
    private val shape: Shape = Shape.CIRCLE,
) : Drawable() {

    enum class Shape { CIRCLE, SQUIRCLE }

    private val palette = intArrayOf(
        R.color.m3_avatar_0, R.color.m3_avatar_1, R.color.m3_avatar_2, R.color.m3_avatar_3,
        R.color.m3_avatar_4, R.color.m3_avatar_5, R.color.m3_avatar_6, R.color.m3_avatar_7,
    )

    private val backgroundColor = ContextCompat.getColor(
        context,
        palette[abs(stableHash(key)) % palette.size]
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 底色是 40/80 号色阶，白字对比度够；深色模式下的 80 号色阶偏亮，
        // 所以按亮度决定用白字还是深字，而不是写死白色。
        color = if (isLight(backgroundColor)) Color.parseColor("#1D1B20") else Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val size = min(b.width(), b.height()).toFloat()

        when (shape) {
            Shape.CIRCLE -> canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), size / 2f, bgPaint)
            // 设计稿写的是 34% 圆角
            Shape.SQUIRCLE -> canvas.drawRoundRect(rect, size * 0.34f, size * 0.34f, bgPaint)
        }

        if (initial.isEmpty()) return

        // 字号取容器的 39%，这是设计稿里 44dp 头像配 17sp 文字的比例
        textPaint.textSize = size * 0.39f
        // drawText 的 y 是基线不是中心，要减去 (ascent+descent)/2 才是视觉居中
        val metrics = textPaint.fontMetrics
        val baseline = b.exactCenterY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(initial, b.exactCenterX(), baseline, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
    }

    @Deprecated("Drawable 的抽象方法，必须实现", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /**
         * 稳定哈希。
         *
         * 不用 Object.hashCode()：String 的 hashCode 在 JVM 上确实稳定，
         * 但 Long/Int 装箱后的 hashCode 语义不同，混用会让同一个人在
         * "按 id 取色"和"按姓名取色"两条路径下拿到不同颜色。
         * 这里统一转成字符串再算，行为可预期。
         */
        private fun stableHash(key: Any): Int {
            val s = key.toString()
            var h = 0
            for (c in s) h = 31 * h + c.code
            return h
        }

        /** 相对亮度，用 sRGB 的感知权重。>0.6 认为是亮色，该配深字。 */
        private fun isLight(color: Int): Boolean {
            val r = Color.red(color) / 255.0
            val g = Color.green(color) / 255.0
            val b = Color.blue(color) / 255.0
            return (0.299 * r + 0.587 * g + 0.114 * b) > 0.6
        }

        /** 取姓名的首字。中文取第一个汉字，英文取首字母大写。空名回退到 "?"。 */
        fun initialOf(name: String?): String {
            val trimmed = name?.trim().orEmpty()
            if (trimmed.isEmpty()) return "?"
            val first = trimmed.first()
            return if (first.isLetter() || first.code > 0x2E80) {
                first.uppercase()
            } else {
                "#"
            }
        }
    }
}
