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
import com.emma019.ondevicebubble.translate.HybridTranslator
import com.emma019.ondevicebubble.translate.LanguageDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Auto → ML Kit.
 * Text source: Accessibility nodes, with Accessibility screenshot+OCR fallback
 * when the tree is thin (Chrome articles, etc.).
 * Secondary mode — primary UX is the WebView reader.
 */
class BubbleOverlayService : Service(), TranslateAccessibilityService.ScreenChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayBubbleManager
    private val detector = LanguageDetector()
    private lateinit var hybrid: HybridTranslator
    private val ocr = ScreenOcr()
    private var busy = false
    private var autoEnabled = true
    private var lastFingerprint: String? = null
    private var lastBlocks: List<TranslatedBlock> = emptyList()
    private val targetLang = "ja"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayBubbleManager(this)
        hybrid = HybridTranslator(this)
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
                val llmHint = if (hybrid.hasLocalModel()) {
                    getString(R.string.overlay_hybrid_llm_ready)
                } else {
                    getString(R.string.overlay_hybrid_llm_missing)
                }
                toast("${getString(R.string.overlay_auto_ready)}\n$llmHint")
                scope.launch { translateOnce(silent = true, preferQuality = false) }
            }
        }
        return START_STICKY
    }

    override fun onScreenMaybeChanged() {
        if (!autoEnabled) return
        scope.launch { translateOnce(silent = true, preferQuality = false) }
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
                    scope.launch { translateOnce(silent = true, preferQuality = false) }
                }
            },
            onTranslate = {
                scope.launch { translateOnce(silent = false, preferQuality = true) }
            },
            onPolish = { scope.launch { polishWithLlm() } },
            onClear = {
                overlay.clearTranslations()
                lastFingerprint = null
                lastBlocks = emptyList()
            },
            onStop = { stopSelfSafely() },
        )
    }

    private suspend fun collectBlocks(
        a11y: TranslateAccessibilityService,
        silent: Boolean,
    ): List<TextBlock> {
        val nodeBlocks = withContext(Dispatchers.Default) { a11y.snapshotTextBlocks() }
        if (!TranslateAccessibilityService.needsOcrFallback(nodeBlocks)) {
            return TextBlockMerger.merge(nodeBlocks)
        }
        if (!silent) toast("Chrome-like page — OCR fallback…")
        overlay.removeControls()
        overlay.clearTranslations()
        delay(180)
        val bitmap = try {
            a11y.takeBitmapScreenshot()
        } catch (t: Throwable) {
            Log.e(TAG, "a11y screenshot failed", t)
            null
        }
        showControlPanel()
        if (bitmap == null) {
            if (!silent) toast("Screenshot failed — using sparse a11y text")
            return TextBlockMerger.merge(nodeBlocks)
        }
        return try {
            val ocrBlocks = withContext(Dispatchers.Default) { ocr.recognize(bitmap) }
            if (!silent) toast("OCR blocks: ${ocrBlocks.size}")
            val chosen = if (ocrBlocks.size >= nodeBlocks.size) ocrBlocks else nodeBlocks
            TextBlockMerger.merge(chosen)
        } catch (t: TimeoutCancellationException) {
            if (!silent) toast("OCR timed out")
            TextBlockMerger.merge(nodeBlocks)
        } catch (t: Throwable) {
            Log.e(TAG, "OCR failed", t)
            if (!silent) toast("OCR error: ${t.message}")
            TextBlockMerger.merge(nodeBlocks)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun translateOnce(silent: Boolean, preferQuality: Boolean) {
        if (busy) return
        val a11y = TranslateAccessibilityService.instance
        if (a11y == null) {
            if (!silent) toast(getString(R.string.a11y_required))
            return
        }
        busy = true
        try {
            if (!silent) toast("Grabbing text…")
            val blocks = collectBlocks(a11y, silent)
            val fingerprint = blocks.joinToString("\n") { it.text }.hashCode().toString() +
                if (preferQuality) ":q" else ":f"
            if (silent && fingerprint == lastFingerprint) return
            if (blocks.isEmpty()) {
                if (!silent) toast("No text found")
                overlay.clearTranslations()
                lastFingerprint = fingerprint
                lastBlocks = emptyList()
                return
            }
            val limited = blocks
                .filter { it.text.length >= 2 }
                .sortedByDescending { it.text.length }
                .take(if (preferQuality) 16 else 24)

            val prepared = withContext(Dispatchers.Default) {
                limited.mapNotNull { block ->
                    val lang = runCatching { detector.detect(block.text) }.getOrDefault("und")
                    if (lang == "ja") return@mapNotNull null
                    val source = if (lang == "und") "en" else lang
                    if (hybrid.mapLang(source) == null) return@mapNotNull null
                    block to source
                }
            }
            if (prepared.isEmpty()) {
                overlay.clearTranslations()
                lastFingerprint = fingerprint
                lastBlocks = emptyList()
                if (!silent) toast("Nothing to translate")
                return
            }

            if (!silent) {
                val mode = if (preferQuality && hybrid.hasLocalModel()) "ML Kit+LLM" else "ML Kit"
                toast("Translating ${prepared.size} ($mode)…")
            }
            val translated = withContext(Dispatchers.Default) {
                prepared.map { (block, source) ->
                    val out = runCatching {
                        hybrid.translateBlock(
                            text = block.text,
                            sourceLang = source,
                            targetLang = targetLang,
                            preferQuality = preferQuality,
                        )
                    }.getOrElse { err ->
                        Log.e(TAG, "translate failed ($source)", err)
                        block.text
                    }
                    TranslatedBlock(original = block, translated = out, sourceLang = source)
                }
            }
            lastBlocks = translated
            overlay.showTranslations(translated)
            lastFingerprint = fingerprint
            if (!silent) toast("Done: ${translated.size}")
        } catch (t: Throwable) {
            Log.e(TAG, "translateOnce failed", t)
            if (!silent) toast(t.message ?: t.toString())
            showControlPanel()
        } finally {
            busy = false
        }
    }

    private suspend fun polishWithLlm() {
        if (busy) return
        if (!hybrid.hasLocalModel()) {
            toast(getString(R.string.overlay_hybrid_llm_missing))
            return
        }
        if (lastBlocks.isEmpty()) {
            toast("先に訳してから磨を押して")
            return
        }
        busy = true
        try {
            toast(getString(R.string.overlay_polishing))
            val ok = withContext(Dispatchers.Default) {
                hybrid.ensureLlm { msg -> scope.launch { toast(msg) } }
            }
            if (!ok) {
                toast("Local LLM failed to load")
                return
            }
            val polished = withContext(Dispatchers.Default) {
                lastBlocks
                    .sortedByDescending { it.original.text.length }
                    .take(12)
                    .map { block ->
                        val out = runCatching {
                            hybrid.translateQuality(
                                block.original.text,
                                block.sourceLang,
                                targetLang,
                            )
                        }.getOrElse { block.translated }
                        block.translated = out
                        block
                    }
            }
            overlay.showTranslations(lastBlocks)
            toast("Polished ${polished.size} with Local LLM")
        } catch (t: Throwable) {
            Log.e(TAG, "polish failed", t)
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
            .setContentText(getString(R.string.overlay_notification_text_hybrid))
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
        runCatching { hybrid.close() }
        runCatching { ocr.close() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        TranslateAccessibilityService.screenChangeListener = null
        scope.cancel()
        runCatching { overlay.dispose() }
        runCatching { detector.close() }
        runCatching { hybrid.close() }
        runCatching { ocr.close() }
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
