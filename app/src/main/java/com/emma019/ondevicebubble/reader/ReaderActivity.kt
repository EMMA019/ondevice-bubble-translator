package com.emma019.ondevicebubble.reader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.emma019.ondevicebubble.databinding.ActivityReaderBinding
import com.emma019.ondevicebubble.translate.HybridTranslator
import com.emma019.ondevicebubble.translate.LanguageDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Always-on Japanese reading browser: load URL in WebView,
 * then replace main text nodes in-place via ML Kit.
 */
class ReaderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReaderBinding
    private lateinit var hybrid: HybridTranslator
    private val detector = LanguageDetector()
    private var translateJob: Job? = null
    private var autoTranslate = true
    private var lastTranslatedUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hybrid = HybridTranslator(this)

        binding.toolbar.setNavigationOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
            else finish()
        }
        binding.goButton.setOnClickListener { loadFromBar() }
        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadFromBar()
                true
            } else false
        }
        binding.autoChip.setOnClickListener {
            autoTranslate = !autoTranslate
            refreshAutoChip()
            if (autoTranslate) scheduleTranslate()
        }
        refreshAutoChip()

        val ws = binding.webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.loadsImagesAutomatically = true
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.userAgentString = ws.userAgentString + " OnDeviceReader/0.6"

        binding.webView.addJavascriptInterface(JsBridge(), JS_INTERFACE)
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.statusText.text = getString(com.emma019.ondevicebubble.R.string.reader_loading)
                if (url != null) binding.urlBar.setText(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) binding.urlBar.setText(url)
                binding.statusText.text = getString(com.emma019.ondevicebubble.R.string.reader_loaded)
                injectCss()
                if (autoTranslate && url != lastTranslatedUrl) {
                    // Let layout settle, then collect text.
                    binding.webView.postDelayed({ scheduleTranslate() }, 450)
                }
            }
        }

        val start = intent?.getStringExtra(EXTRA_URL)
            ?: DEFAULT_URL
        binding.urlBar.setText(start)
        binding.webView.loadUrl(normalizeUrl(start))
    }

    private fun refreshAutoChip() {
        binding.autoChip.text = if (autoTranslate) {
            getString(com.emma019.ondevicebubble.R.string.reader_auto_on)
        } else {
            getString(com.emma019.ondevicebubble.R.string.reader_auto_off)
        }
    }

    private fun loadFromBar() {
        val raw = binding.urlBar.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) return
        lastTranslatedUrl = null
        binding.webView.loadUrl(normalizeUrl(raw))
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(t)}"
        }
    }

    private fun injectCss() {
        val css = CSS_HIDE_ADS.replace("'", "\\'")
        val js = "(function(){var s=document.getElementById('obt-css');" +
            "if(!s){s=document.createElement('style');s.id='obt-css';" +
            "document.documentElement.appendChild(s);}s.textContent='$css';})();"
        binding.webView.evaluateJavascript(js, null)
    }

    private fun scheduleTranslate() {
        if (!autoTranslate) return
        translateJob?.cancel()
        binding.statusText.text = getString(com.emma019.ondevicebubble.R.string.reader_collecting)
        binding.webView.evaluateJavascript(COLLECT_JS, null)
    }

    private fun onTextsCollected(json: String) {
        translateJob?.cancel()
        translateJob = lifecycleScope.launch {
            try {
                val arr = JSONArray(json)
                if (arr.length() == 0) {
                    binding.statusText.text = getString(com.emma019.ondevicebubble.R.string.reader_no_text)
                    return@launch
                }
                binding.statusText.text = getString(
                    com.emma019.ondevicebubble.R.string.reader_translating,
                    arr.length(),
                )
                val out = JSONObject()
                var done = 0
                withContext(Dispatchers.Default) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.getString("id")
                        val text = obj.getString("text")
                        val translated = translateOne(text)
                        synchronized(out) { out.put(id, translated) }
                        done++
                    }
                }
                val escaped = JSONObject.quote(out.toString())
                binding.webView.evaluateJavascript(
                    "window.__obtApply && window.__obtApply($escaped);",
                    null,
                )
                lastTranslatedUrl = binding.webView.url
                binding.statusText.text = getString(
                    com.emma019.ondevicebubble.R.string.reader_done,
                    done,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "translate failed", t)
                binding.statusText.text = t.message ?: t.toString()
                Toast.makeText(this@ReaderActivity, t.message ?: "error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun translateOne(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length < 2) return text
        val lang = runCatching { detector.detect(trimmed) }.getOrDefault("und")
        if (lang == "ja") return text
        val source = if (lang == "und") "en" else lang
        if (hybrid.mapLang(source) == null) return text
        return runCatching {
            hybrid.translateFast(trimmed, source, "ja")
        }.getOrElse {
            Log.e(TAG, "mlkit fail", it)
            text
        }
    }

    override fun onDestroy() {
        translateJob?.cancel()
        runCatching { detector.close() }
        runCatching { hybrid.close() }
        binding.webView.destroy()
        super.onDestroy()
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onTexts(json: String) {
            runOnUiThread { onTextsCollected(json) }
        }
    }

    companion object {
        private const val TAG = "ReaderActivity"
        const val EXTRA_URL = "url"
        private const val JS_INTERFACE = "AndroidReader"
        private const val DEFAULT_URL = "https://www.bbc.com/news"

        // Light clutter hide — full filter lists later.
        private const val CSS_HIDE_ADS =
            "iframe[id*=\"google_ads\"],iframe[src*=\"doubleclick\"],iframe[src*=\"adservice\"]," +
                ".ad,.ads,.advert,.advertisement,[class*=\"ad-slot\"],[id*=\"ad-slot\"]," +
                "[data-ad],[aria-label*=\"advert\" i],.promo-banner,.sticky-ad{display:none!important;}"

        /**
         * Collect readable text nodes under article/main (fallback body),
         * stash node refs, ask Android to translate.
         */
        private val COLLECT_JS = """
            (function(){
              try {
                window.__obtNodes = {};
                var roots = document.querySelectorAll('article, main, [role="main"], .article-body, .story-body, .ssrcss-1ocoo3l-Wrap');
                var root = roots.length ? roots[0] : document.body;
                if (!root) { AndroidReader.onTexts('[]'); return; }
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
                  acceptNode: function(n) {
                    if (!n || !n.nodeValue || !n.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                    var p = n.parentElement;
                    if (!p) return NodeFilter.FILTER_REJECT;
                    var tag = (p.tagName || '').toUpperCase();
                    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' ||
                        tag === 'TEXTAREA' || tag === 'INPUT' || tag === 'CODE' ||
                        tag === 'PRE' || tag === 'SVG' || tag === 'BUTTON') {
                      return NodeFilter.FILTER_REJECT;
                    }
                    if (p.closest && p.closest('nav, header, footer, [role="navigation"]')) {
                      return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                  }
                });
                var payload = [];
                var i = 0;
                var MAX = 80;
                while (walker.nextNode() && payload.length < MAX) {
                  var t = walker.currentNode;
                  var s = t.nodeValue;
                  if (!s || s.trim().length < 3) continue;
                  // Skip pure numbers / symbols
                  if (/^[\\d\\s\\p{P}\\p{S}%+▲▼↑↓]+$/u.test(s.trim())) continue;
                  var id = 'n' + (i++);
                  window.__obtNodes[id] = t;
                  payload.push({id: id, text: s});
                }
                window.__obtApply = function(mapJson) {
                  try {
                    var map = (typeof mapJson === 'string') ? JSON.parse(mapJson) : mapJson;
                    Object.keys(map).forEach(function(id) {
                      var n = window.__obtNodes[id];
                      if (n) n.nodeValue = map[id];
                    });
                  } catch (e) {}
                };
                AndroidReader.onTexts(JSON.stringify(payload));
              } catch (e) {
                AndroidReader.onTexts('[]');
              }
            })();
        """.trimIndent()
    }
}
