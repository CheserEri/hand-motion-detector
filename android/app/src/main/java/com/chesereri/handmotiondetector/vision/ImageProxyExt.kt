package com.chesereri.handmotiondetector.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy

/**
 * 将 CameraX 的 RGBA_8888 ImageProxy 转换为正向、可选镜像的 Bitmap。
 * 前置摄像头需要水平镜像，手势方向才符合用户直觉。
 */
fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap? {
    if (format != PixelFormat.RGBA_8888 || planes.size != 1) return null

    val plane = planes[0]
    val pixelStride = plane.pixelStride
    if (pixelStride <= 0) return null
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val paddedWidth = width + rowPadding / pixelStride
    if (paddedWidth <= 0 || height <= 0) return null

    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    plane.buffer.rewind()
    padded.copyPixelsFromBuffer(plane.buffer)
    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
    if (cropped !== padded) padded.recycle()

    val rotation = imageInfo.rotationDegrees
    if (rotation == 0 && !mirror) return cropped

    val matrix = Matrix().apply {
        // 先旋转到正向，再按显示方向镜像；顺序反了会让前置画面上下颠倒。
        if (rotation != 0) postRotate(rotation.toFloat())
        if (mirror) postScale(-1f, 1f)
    }
    val result = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
    if (result !== cropped) cropped.recycle()
    return result
}
