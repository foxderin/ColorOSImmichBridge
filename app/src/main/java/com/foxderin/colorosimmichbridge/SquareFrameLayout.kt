package com.foxderin.colorosimmichbridge

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** FrameLayout measured as a square (height follows width) for grid cells. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
