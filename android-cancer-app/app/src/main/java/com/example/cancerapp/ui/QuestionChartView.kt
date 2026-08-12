package com.example.cancerapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.min

class QuestionChartView(context: Context) : View(context) {
    data class Point(val label: String, val value: Float)

    private var points: List<Point> = emptyList()
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD8C8BA.toInt(); strokeWidth = dp(1f) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB86642.toInt(); strokeWidth = dp(3f); style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB86642.toInt(); style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF79685C.toInt(); textSize = dp(11f) }

    fun setPoints(value: List<Point>) {
        points = value.takeLast(31)
        contentDescription = if (points.isEmpty()) "No trend data" else points.joinToString { "${it.label}: ${it.value}" }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(190f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) {
            canvas.drawText("Not enough numeric answers to graph yet", dp(12f), height / 2f, text)
            return
        }
        val left = dp(34f); val right = width - dp(12f); val top = dp(16f); val bottom = height - dp(34f)
        repeat(4) { row ->
            val y = top + (bottom - top) * row / 3f
            canvas.drawLine(left, y, right, y, axis)
        }
        val rawMin = points.minOf { it.value }
        val rawMax = points.maxOf { it.value }
        val minValue = min(0f, rawMin)
        val maxValue = max(minValue + 1f, rawMax)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = if (points.size == 1) (left + right) / 2f else left + (right - left) * index / (points.size - 1f)
            val y = bottom - (point.value - minValue) / (maxValue - minValue) * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, dp(4f), dot)
            if (points.size <= 8 || index == 0 || index == points.lastIndex) {
                canvas.save(); canvas.rotate(-35f, x, bottom + dp(15f)); canvas.drawText(point.label, x - dp(8f), bottom + dp(15f), text); canvas.restore()
            }
        }
        canvas.drawPath(path, line)
        canvas.drawText(String.format("%.1f", maxValue), dp(3f), top + dp(4f), text)
        canvas.drawText(String.format("%.1f", minValue), dp(3f), bottom, text)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
}
