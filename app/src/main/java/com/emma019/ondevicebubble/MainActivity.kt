package com.emma019.ondevicebubble

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emma019.ondevicebubble.accessibility.TranslateAccessibilityService
import com.emma019.ondevicebubble.databinding.ActivityMainBinding
import com.emma019.ondevicebubble.overlay.BubbleOverlayService
import com.emma019.ondevicebubble.reader.ReaderActivity
import com.emma019.ondevicebubble.translate.EngineRegistry
import com.emma019.ondevicebubble.translate.LanguageDetector
import com.emma019.ondevicebubble.translate.TextPreprocessor
import com.emma019.ondevicebubble.translate.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var engines: List<TranslationEngine>
    private var pendingOverlayStart = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        engines = EngineRegistry.all(this)
        binding.engineSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            engines.map { it.displayName },
        )

        binding.openReaderButton.setOnClickListener {
            startActivity(Intent(this, ReaderActivity::class.java))
        }
        binding.translateButton.setOnClickListener { runTranslate() }
        binding.startOverlayButton.setOnClickListener { startOverlayFlow() }
        binding.stopOverlayButton.setOnClickListener {
            BubbleOverlayService.stop(this)
            Toast.makeText(this, R.string.btn_stop_overlay, Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingOverlayStart && Settings.canDrawOverlays(this)) {
            pendingOverlayStart = false
            continueOverlayStart()
        }
    }

    private fun startOverlayFlow() {
        if (!Settings.canDrawOverlays(this)) {
            pendingOverlayStart = true
            Toast.makeText(this, R.string.overlay_need_permission, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        continueOverlayStart()
    }

    private fun continueOverlayStart() {
        if (!TranslateAccessibilityService.isEnabled()) {
            Toast.makeText(this, R.string.a11y_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        BubbleOverlayService.startA11y(this)
        Toast.makeText(this, R.string.overlay_started_a11y, Toast.LENGTH_SHORT).show()
    }

    private fun runTranslate() {
        val raw = binding.inputText.text?.toString().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(this, R.string.error_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val engine = engines[binding.engineSpinner.selectedItemPosition]
        val cleaned = TextPreprocessor.normalize(raw)
        val detector = LanguageDetector()

        binding.translateButton.isEnabled = false
        binding.statusText.setText(R.string.status_translating)
        binding.outputText.setText("")

        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            try {
                val result = withContext(Dispatchers.Default) {
                    val lang = runCatching { detector.detect(cleaned) }.getOrDefault("en")
                    val source = if (lang == "und" || lang == "ja") "en" else lang
                    engine.prepare { msg ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            binding.statusText.text = msg
                        }
                    }
                    engine.translate(cleaned, source, "ja")
                }
                val ms = System.currentTimeMillis() - started
                binding.outputText.setText(result)
                binding.statusText.text = getString(R.string.status_done, ms)
            } catch (t: Throwable) {
                val message = t.message ?: t.toString()
                binding.statusText.text = message
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                detector.close()
                binding.translateButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        engines.forEach { it.close() }
        super.onDestroy()
    }
}
