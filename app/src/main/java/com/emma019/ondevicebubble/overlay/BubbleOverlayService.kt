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
 * Always-on path: Accessibility screen-change → debounce → language id →
 * translate to Japanese → bubbles. Manual 訳 still works. Auto is ON by default.
 */
class BubbleOverlayService : Service(), TranslateAccessibilityService.ScreenChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayBubbleManager
    private val detector = LanguageDetector()
    private val engine = MlKitTranslationEngine()
    private var busy = false
    private var autoEnabled = true
    private var lastFingerprint: String? = null
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
                TranslateAccessibilityService.screenChangeListener = this
                showControlPanel()
                toast(getString(R.string.overlay_auto_ready))
                // First paint for current screen.
                scope.launch { translateOnce(silent = true) }
            }
        }
        return START_STICKY
    }

    override fun onScreenMaybeChanged() {
        if (!autoEnabled) return
        scope.launch { translateOnce(silent = true) }
    }

    private fun showControlPanel() {
        overlay.showControls(
            autoEnabled = autoEnabled,
            onToggleAuto = {
                autoEnabled = !autoEnabled
                overlay.updateAutoLabel(autoEnabled)
                toast(
                    if (autoEnabled) getString(R.string.overlay_auto_on)
                    else getString(R.string.overlay_auto_off),
                )
                if (autoEnabled) {
                    scope.launch { translateOnce(silent = true) }
                }
            },
            onTranslate = { scope.launch { translateOnce(silent = false) } },
            onClear = {
                overlay.clearTranslations()
                lastFingerprint = null
            },
            onStop = { stopSelfSafely() },
        )
    }

    private suspend fun translateOnce(silent: Boolean) {
        if (busy) return
        val a11y = TranslateAccessibilityService.instance
        if (a11y == null) {
            if (!silent) toast(getString(R.string.a11y_required))
            return
        }
        busy = true
        try {
            if (!silent) toast("Grabbing text…")
            val blocks = withContext(Dispatchers.Default) { a11y.snapshotTextBlocks() }
            val fingerprint = blocks.joinToString("\n") { it.text }.hashCode().toString()
            if (fingerprint == lastFingerprint) return
            if (blocks.isEmpty()) {
                if (!silent) toast("No text nodes")
                overlay.clearTranslations()
                lastFingerprint = fingerprint
                return
            }
            val limited = blocks
                .filter { it.text.length >= 2 }
                .sortedByDescending { it.text.length }
                .take(40)

            val prepared = withContext(Dispatchers.Default) {
                limited.mapNotNull { block ->
                    val lang = runCatching { detector.detect(block.text) }.getOrDefault("und")
                    if (lang == "ja") return@mapNotNull null
                    val source = if (lang == "und") "en" else lang
                    if (engine.mapLang(source) == null) return@mapNotNull null
                    block to source
                }
            }
            if (prepared.isEmpty()) {
                overlay.clearTranslations()
                lastFingerprint = fingerprint
                if (!silent) toast("Nothing to translate")
                return
            }

            if (!silent) toast("Translating ${prepared.size}…")
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
            lastFingerprint = fingerprint
            if (!silent) toast("Done: ${translated.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "translateOnce failed", t)
            if (!silent) toast(t.message ?: t.toString())
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
            .setContentText(getString(R.string.overlay_notification_text_auto))
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
        TranslateAccessibilityService.screenChangeListener = null
        runCatching { overlay.dispose() }
        runCatching { detector.close() }
        runCatching { engine.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        TranslateAccessibilityService.screenChangeListener = null
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
