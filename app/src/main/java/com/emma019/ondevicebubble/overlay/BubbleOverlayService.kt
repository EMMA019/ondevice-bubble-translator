package com.emma019.ondevicebubble.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.emma019.ondevicebubble.MainActivity
import com.emma019.ondevicebubble.R
import com.emma019.ondevicebubble.translate.MlKitTranslationEngine
import com.emma019.ondevicebubble.translate.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException

class BubbleOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayBubbleManager
    private var projection: MediaProjection? = null
    private var captor: ScreenCaptor? = null
    private val ocr = ScreenOcr()
    private var engine: TranslationEngine = MlKitTranslationEngine()
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayBubbleManager(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    toast("Missing capture permission data")
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                startAsForeground()
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                runCatching { projection?.stop() }
                try {
                    val mp = mpm.getMediaProjection(resultCode, data)
                    mp.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                Log.w(TAG, "MediaProjection stopped")
                                mainHandler.post {
                                    toast("Screen capture ended — start overlay again")
                                    stopSelfSafely()
                                }
                            }
                        },
                        mainHandler,
                    )
                    projection = mp
                    captor = ScreenCaptor(this, mp)
                    engine = MlKitTranslationEngine()
                    showControlPanel()
                    toast("Ready: tap 訳")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start projection", t)
                    toast("Capture start failed: ${t.message}")
                    stopSelfSafely()
                }
            }
        }
        return START_STICKY
    }

    private fun showControlPanel() {
        overlay.showControls(
            onTranslate = { scope.launch { translateOnce() } },
            onClear = { overlay.clearTranslations() },
            onStop = { stopSelfSafely() },
        )
    }

    private suspend fun translateOnce() {
        if (busy) {
            toast("Already translating…")
            return
        }
        val capture = captor
        if (capture == null) {
            toast("Capture not ready — restart overlay")
            return
        }
        busy = true
        try {
            toast("Capturing…")
            overlay.removeControls()
            delay(150)
            val bitmap = capture.capture()
            toast("OCR… (${bitmap.width}x${bitmap.height})")
            val blocks = try {
                withContext(Dispatchers.Default) { ocr.recognize(bitmap) }
            } catch (t: TimeoutCancellationException) {
                toast("OCR timed out — try again")
                emptyList()
            } finally {
                bitmap.recycle()
            }
            Log.i(TAG, "OCR blocks=${blocks.size}")
            if (blocks.isEmpty()) {
                toast("No text found / OCR empty")
                return
            }
            val limited = blocks
                .sortedByDescending { it.text.length }
                .take(16)
            toast("Translating ${limited.size} blocks…")
            withContext(Dispatchers.Default) { engine.prepare {} }
            val translated = withContext(Dispatchers.Default) {
                limited.map { block ->
                    val out = runCatching {
                        engine.translate(block.text, "en", "ja")
                    }.getOrElse { err ->
                        Log.e(TAG, "translate failed", err)
                        block.text
                    }
                    TranslatedBlock(original = block, translated = out)
                }
            }
            overlay.showTranslations(translated)
            toast("Done: ${translated.size} bubbles")
        } catch (t: Throwable) {
            Log.e(TAG, "translateOnce failed", t)
            toast(t.message ?: t.toString())
        } finally {
            showControlPanel()
            busy = false
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Bubble translator",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun stopSelfSafely() {
        runCatching { overlay.dispose() }
        runCatching { ocr.close() }
        runCatching { engine.close() }
        runCatching { projection?.stop() }
        projection = null
        captor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { overlay.dispose() }
        runCatching { ocr.close() }
        runCatching { engine.close() }
        runCatching { projection?.stop() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BubbleOverlay"
        const val ACTION_START = "com.emma019.ondevicebubble.action.START_OVERLAY"
        const val ACTION_STOP = "com.emma019.ondevicebubble.action.STOP_OVERLAY"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "bubble_overlay"
        private const val NOTIFICATION_ID = 42

        fun stop(context: Context) {
            context.startService(
                Intent(context, BubbleOverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
