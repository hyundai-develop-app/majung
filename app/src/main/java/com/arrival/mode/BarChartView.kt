package com.arrival.mode

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** 시간대별 만차율 막대 그래프. 강조 막대(도착 시각)만 파란색. */
class BarChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    data class Bar(val label: String, val pct: Int, val highlight: Boolean = false)

    var bars: List<Bar> = listOf(
        Bar("13:30", 58), Bar("14:00", 80), Bar("도착", 95, highlight = true), Bar("14:30", 90), Bar("15:00", 82),
    )
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val chartTop = 34 * dp
        val chartBottom = height - 34 * dp
        val slot = width / bars.size.toFloat()
        val barW = min(slot - 16 * dp, 72 * dp)
        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2
            val top = chartBottom - (chartBottom - chartTop) * b.pct / 100f
            barPaint.shader = if (b.highlight) LinearGradient(0f, top, 0f, chartBottom, 0xFF2F6FED.toInt(), 0xFF6B9BFF.toInt(), Shader.TileMode.CLAMP) else null
            barPaint.color = 0xFFE5E7EB.toInt()
            canvas.drawRoundRect(cx - barW / 2, top, cx + barW / 2, chartBottom, 10 * dp, 10 * dp, barPaint)

            textPaint.isFakeBoldText = b.highlight
            textPaint.textSize = (if (b.highlight) 22 else 17) * dp
            textPaint.color = if (b.highlight) 0xFF2F6FED.toInt() else 0xFF9CA3AF.toInt()
            canvas.drawText("${b.pct}%", cx, top - 10 * dp, textPaint)

            textPaint.textSize = 17 * dp
            textPaint.color = if (b.highlight) 0xFF111827.toInt() else 0xFF9CA3AF.toInt()
            canvas.drawText(b.label, cx, height - 6 * dp, textPaint)
        }
    }
}
