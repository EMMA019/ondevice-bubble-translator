package com.emma019.ondevicebubble.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ScreenCaptor(
    private val context: Context,
    private val mediaProjection: MediaProjection,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    suspend fun capture(): Bitmap = withContext(Dispatchers.Default) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "bubble-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null,
            )

            var bitmap: Bitmap? = null
            repeat(25) {
                delay(40)
                val image = reader.acquireLatestImage() ?: return@repeat
                image.use { img ->
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val temp = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888,
                    )
                    temp.copyPixelsFromBuffer(buffer)
                    bitmap = Bitmap.createBitmap(temp, 0, 0, width, height)
                    if (temp !== bitmap) temp.recycle()
                }
                if (bitmap != null) return@repeat
            }
            bitmap ?: error("Failed to capture screen frame")
        } finally {
            virtualDisplay?.release()
            reader.close()
        }
    }
}
