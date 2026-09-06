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
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.emma019.ondevicebubble.MainActivity
import com.emma019.ondevicebubble.R
import com.emma019.ondevicebubble.translate.EngineRegistry
import com.emma019.ondevicebubble.translate.MlKitTranslationEngine
import com.emma019.ondevicebubble.translate.TranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BubbleOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                startAsForeground()
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection?.stop()
                projection = mpm.getMediaProjection(resultCode, data).also { mp ->
                    mp.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopSelfSafely()
                        }
                    }, null)
                    captor = ScreenCaptor(this, mp)
                }
                engine = EngineRegistry.all(this).firstOrNull { it.id == "mlkit" } ?: MlKitTranslationEngine()
                overlay.showControls(
                    onTranslate = { scope.launch { translateOnce() } },
                    onClear = { overlay.clearTranslations() },
                    onStop = { stopSelfSafely() },
                )
                Toast.makeText(this, R.string.overlay_notification_text, Toast.LENGTH_SHORT).show()
            }
        }
        return START_STICKY
    }

    private suspend fun translateOnce() {
        if (busy) return
        val capture = captor ?: return
        busy = true
        try {
            Toast.makeText(this, R.string.status_translating, Toast.LENGTH_SHORT).show()
            val bitmap = capture.capture()
            val blocks = withContext(Dispatchers.Default) { ocr.recognize(bitmap) }
            bitmap.recycle()
            if (blocks.isEmpty()) {
                Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show()
                return
            }
            withContext(Dispatchers.Default) {
                engine.prepare {}
            }
            val translated = withContext(Dispatchers.Default) {
                blocks.map { block ->
                    val out = runCatching {
                        engine.translate(block.text, "en", "ja")
                    }.getOrElse { block.text }
                    TranslatedBlock(original = block, translated = out)
                }
            }
            overlay.showTranslations(translated)
        } catch (t: Throwable) {
            Toast.makeText(this, t.message ?: t.toString(), Toast.LENGTH_LONG).show()
        } finally {
            busy = false
        }
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
        overlay.dispose()
        ocr.close()
        engine.close()
        projection?.stop()
        projection = null
        captor = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        overlay.dispose()
        ocr.close()
        engine.close()
        projection?.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.emma019.ondevicebubble.action.START_OVERLAY"
        const val ACTION_STOP = "com.emma019.ondevicebubble.action.STOP_OVERLAY"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "bubble_overlay"
        private const val NOTIFICATION_ID = 42

        fun stop(context: Context) {
            context.startService(Intent(context, BubbleOverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
