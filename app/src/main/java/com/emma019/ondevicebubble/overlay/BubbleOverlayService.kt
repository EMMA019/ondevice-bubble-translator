package com.emma019.ondevicebubble.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.emma019.ondevicebubble.MainActivity
import com.emma019.ondevicebubble.R
import com.emma019.ondevicebubble.accessibility.TranslateAccessibilityService
import com.emma019.ondevicebubble.translate.LanguageDetector
import com.emma019.ondevicebubble.translate.MlKitTranslationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fast path: Accessibility text → language id → ML Kit translate → bubbles.
 * No screenshot/OCR on the primary path.
 */
class BubbleOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayBubbleManager
    private val detector = LanguageDetector()
    private val engine = MlKitTranslationEngine()
    private var busy = false
    private val targetLang = "ja"

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
            ACTION_START_A11Y, null -> {
                startAsForeground()
                if (!TranslateAccessibilityService.isEnabled()) {
                    toast(getString(R.string.a11y_required))
                }
                showControlPanel()
                toast("Ready: tap 訳 (text grab)")
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
        val a11y = TranslateAccessibilityService.instance
        if (a11y == null) {
            toast(getString(R.string.a11y_required))
            return
        }
        busy = true
        try {
            toast("Grabbing text…")
            val blocks = withContext(Dispatchers.Default) { a11y.snapshotTextBlocks() }
            if (blocks.isEmpty()) {
                toast("No text nodes — enable Accessibility / open a text-heavy screen")
                return
            }
            val limited = blocks
                .filter { it.text.length >= 2 }
                .sortedByDescending { it.text.length }
                .take(40)
            toast("Detecting languages (${limited.size})…")

            val prepared = withContext(Dispatchers.Default) {
                limited.mapNotNull { block ->
                    val lang = runCatching { detector.detect(block.text) }.getOrDefault("und")
                    if (lang == "ja" || lang == "und") {
                        // Skip Japanese; keep und for a best-effort en translate below.
                        if (lang == "ja") return@mapNotNull null
                    }
                    val source = if (lang == "und") "en" else lang
                    if (engine.mapLang(source) == null) return@mapNotNull null
                    block to source
                }
            }
            if (prepared.isEmpty()) {
                toast("Nothing to translate (already JA / unsupported)")
                return
            }

            toast("Translating ${prepared.size}…")
            val translated = withContext(Dispatchers.Default) {
                prepared.map { (block, source) ->
                    val out = runCatching {
                        engine.translate(block.text, source, targetLang)
                    }.getOrElse { err ->
                        Log.e(TAG, "translate failed ($source)", err)
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
            .setContentText(getString(R.string.overlay_notification_text_a11y))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, 0)
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
        runCatching { detector.close() }
        runCatching { engine.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { overlay.dispose() }
        runCatching { detector.close() }
        runCatching { engine.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BubbleOverlay"
        const val ACTION_START_A11Y = "com.emma019.ondevicebubble.action.START_A11Y"
        const val ACTION_STOP = "com.emma019.ondevicebubble.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "bubble_overlay"
        private const val NOTIFICATION_ID = 42

        fun startA11y(context: Context) {
            val intent = Intent(context, BubbleOverlayService::class.java).setAction(ACTION_START_A11Y)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BubbleOverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
