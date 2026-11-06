package com.jmk.stereoimageview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/**
 * Draws a bitmap "cover" scaled (fills the whole view, no bars) with an extra zoom and pan on top.
 * Pan is in view pixels, measured from the centred position.
 */
class ZoomPane(context: Context) : View(context) {
    var image: Bitmap? = null
        private set
    var zoom = 1f
    var panX = 0f
    var panY = 0f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun setImage(b: Bitmap?) {
        image = b
        invalidate()
    }

    fun setTransform(zoom: Float, panX: Float, panY: Float) {
        this.zoom = zoom
        this.panX = panX
        this.panY = panY
        invalidate()
    }

    /** View pixels per image pixel. */
    fun currentScale(): Float {
        val b = image ?: return 1f
        if (width == 0 || height == 0) return 1f
        return max(width / b.width.toFloat(), height / b.height.toFloat()) * zoom
    }

    fun pan(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        invalidate()
    }

    /** Zoom by [factor] keeping the point (fx, fy) in view coordinates fixed. */
    fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newZoom / zoom
        val cx = width / 2f + panX
        val cy = height / 2f + panY
        panX = fx + (cx - fx) * f - width / 2f
        panY = fy + (cy - fy) * f - height / 2f
        zoom = newZoom
        invalidate()
    }

    /** Maps image pixels to view pixels. */
    fun viewMatrix(): Matrix? {
        val b = image ?: return null
        if (width == 0 || height == 0) return null
        val s = currentScale()
        return Matrix().apply {
            postTranslate(-b.width / 2f, -b.height / 2f)
            postScale(s, s)
            postTranslate(width / 2f + panX, height / 2f + panY)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = image ?: return
        val m = viewMatrix() ?: return
        canvas.drawBitmap(b, m, paint)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 10f
    }
}
