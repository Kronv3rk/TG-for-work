package com.flowseal.tgwsproxy

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import android.view.View.MeasureSpec

class AdaptiveScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    private val baseLeft = paddingLeft
    private val baseTop = paddingTop
    private val baseRight = paddingRight
    private val baseBottom = paddingBottom
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (left == insetLeft && top == insetTop && right == insetRight && bottom == insetBottom) return
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val maxWidth = (560 * resources.displayMetrics.density).toInt()
        val (left, right) = AdaptiveLayout.horizontalPadding(
            width, maxWidth, baseLeft + insetLeft, baseRight + insetRight
        )
        val top = baseTop + insetTop
        val bottom = baseBottom + insetBottom
        if (left != paddingLeft || right != paddingRight || top != paddingTop || bottom != paddingBottom) {
            setPadding(left, top, right, bottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
