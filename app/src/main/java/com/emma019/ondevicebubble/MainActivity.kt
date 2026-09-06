package com.emma019.ondevicebubble

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emma019.ondevicebubble.databinding.ActivityMainBinding
import com.emma019.ondevicebubble.translate.EngineRegistry
import com.emma019.ondevicebubble.translate.TextPreprocessor
import com.emma019.ondevicebubble.translate.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var engines: List<TranslationEngine>

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

        binding.translateButton.setOnClickListener { runTranslate() }
    }

    private fun runTranslate() {
        val raw = binding.inputText.text?.toString().orEmpty()
        if (raw.isBlank()) {
            Toast.makeText(this, R.string.error_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val engine = engines[binding.engineSpinner.selectedItemPosition]
        val cleaned = TextPreprocessor.normalize(raw)

        binding.translateButton.isEnabled = false
        binding.statusText.setText(R.string.status_translating)
        binding.outputText.setText("")

        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            try {
                val result = withContext(Dispatchers.Default) {
                    engine.prepare { msg ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            binding.statusText.text = msg
                        }
                    }
                    engine.translate(cleaned, "en", "ja")
                }
                val ms = System.currentTimeMillis() - started
                binding.outputText.setText(result)
                binding.statusText.text = getString(R.string.status_done, ms)
            } catch (t: Throwable) {
                val message = t.message ?: t.toString()
                binding.statusText.text = message
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                binding.translateButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        engines.forEach { it.close() }
        super.onDestroy()
    }
}
