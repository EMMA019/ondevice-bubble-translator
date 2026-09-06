package com.emma019.ondevicebubble.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScreenCaptor(
    private val context: Context,
    private val mediaProjection: MediaProjection,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    suspend fun capture(): Bitmap = withContext(Dispatchers.IO) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val thread = HandlerThread("bubble-capture").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val held = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bmp = imageToBitmap(image, width, height)
                if (held.compareAndSet(null, bmp)) {
                    latch.countDown()
                } else {
                    bmp.recycle()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "image convert failed", t)
            } finally {
                image.close()
            }
        }, handler)

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
                handler,
            )
            val ok = latch.await(3, TimeUnit.SECONDS)
            if (!ok) error("Screen capture timed out (no frame). Re-start overlay and choose Entire screen.")
            held.get() ?: error("Screen capture produced no bitmap")
        } finally {
            runCatching { virtualDisplay?.release() }
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
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
        val cropped = Bitmap.createBitmap(temp, 0, 0, width, height)
        if (cropped !== temp) temp.recycle()
        return cropped
    }

    companion object {
        private const val TAG = "ScreenCaptor"
    }
}
