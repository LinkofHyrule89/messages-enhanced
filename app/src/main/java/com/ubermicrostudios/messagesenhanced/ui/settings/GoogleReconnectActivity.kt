package com.ubermicrostudios.messagesenhanced.ui.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.databinding.ActivityGoogleReconnectBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.util.GoogleCookies
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GoogleReconnectActivity : SessionActivity() {

    private lateinit var binding: ActivityGoogleReconnectBinding
    private val viewModel: GoogleReconnectViewModel by viewModels()
    private var usedDesktopUa = false
    private var autoSubmitted = false
    private var webViewCleared = false
    private var finishPosted = false
    private val cookieCheck = Runnable {
        if (!::binding.isInitialized) return@Runnable
        checkSignedIn(binding.webView.url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enable(this)
        binding = ActivityGoogleReconnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.apply(binding.reconnectRoot, includeIme = true)
        observeAuthLoss()

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupWebView()
        binding.webView.loadUrl(START_URL)

        binding.continueBtn.setOnClickListener { submitCookies() }
        binding.retry.setOnClickListener { restartSignIn() }
        binding.cancel.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.state.value.phase == GoogleReconnectPhase.WEB &&
                        binding.webView.canGoBack()
                    ) {
                        binding.webView.goBack()
                    } else {
                        finish()
                    }
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.webView.removeCallbacks(cookieCheck)
            clearWebViewSession()
            binding.webView.webChromeClient = null
            binding.webView.webViewClient = WebViewClient()
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    private fun render(state: GoogleReconnectUiState) {
        val web = state.phase == GoogleReconnectPhase.WEB
        binding.webView.isVisible = web
        binding.pairingPanel.isVisible = !web
        binding.continueBtn.isVisible = web
        binding.continueBtn.isEnabled = web && state.continueEnabled
        binding.retry.isVisible = state.phase == GoogleReconnectPhase.ERROR
        binding.pairingSpinner.isVisible =
            state.phase == GoogleReconnectPhase.SENDING ||
                (state.phase == GoogleReconnectPhase.PAIRING && state.emoji.isBlank())
        val emoji = state.emoji
        binding.pairingEmoji.isVisible = emoji.isNotBlank()
        binding.pairingEmoji.text = emoji
        val error = state.error
        binding.statusError.isVisible = web && error.isNotBlank()
        binding.statusError.text = if (web) error else ""
        if (!web && error.isNotBlank()) {
            binding.pairingProgress.text = error
            binding.pairingProgress.setTextColor(getColor(R.color.danger))
        } else if (!web) {
            binding.pairingProgress.text = state.progress
            binding.pairingProgress.setTextColor(getColor(R.color.text))
        } else {
            binding.pairingProgress.text = ""
        }
        if (!web && !webViewCleared) {
            clearWebViewSession()
        }
        if (web) {
            webViewCleared = false
        } else {
            binding.webView.removeCallbacks(cookieCheck)
        }
        if (state.done && !finishPosted) {
            finishPosted = true
            binding.reconnectRoot.postDelayed({
                if (!isFinishing) {
                    setResult(RESULT_OK)
                    finish()
                }
            }, SUCCESS_FINISH_MS)
        }
    }

    private fun restartSignIn() {
        autoSubmitted = false
        usedDesktopUa = false
        webViewCleared = false
        finishPosted = false
        viewModel.retry()
        binding.webView.settings.userAgentString = CHROME_ANDROID_UA
        binding.webView.clearHistory()
        binding.webView.loadUrl(START_URL)
        scheduleCookieCheck()
    }

    private fun submitCookies() {
        viewModel.submit(readAllowedCookies())
    }

    private fun readAllowedCookies(): Map<String, String> {
        val manager = CookieManager.getInstance()
        return GoogleCookies.mergeAndFilter(
            listOf(
                manager.getCookie("https://messages.google.com"),
                manager.getCookie("https://accounts.google.com"),
                manager.getCookie("https://google.com"),
                manager.getCookie("https://www.google.com"),
            ),
        )
    }

    private fun checkSignedIn(url: String?) {
        if (!::binding.isInitialized) return
        if (viewModel.state.value.phase != GoogleReconnectPhase.WEB) return
        if (url.isNullOrBlank() || !url.contains("messages.google.com/web")) return
        val cookies = readAllowedCookies()
        if (!GoogleCookies.looksSignedIn(cookies)) return
        viewModel.notifySignedIn()
        if (!autoSubmitted && GoogleCookies.hasRequired(cookies)) {
            autoSubmitted = true
            viewModel.submit(cookies)
        }
    }

    private fun scheduleCookieCheck() {
        if (!::binding.isInitialized) return
        binding.webView.removeCallbacks(cookieCheck)
        binding.webView.postDelayed(cookieCheck, COOKIE_CHECK_MS)
    }

    private fun maybeSwitchToDesktop(url: String?, title: String?, snippet: String? = null) {
        if (usedDesktopUa) return
        if (!GoogleCookies.looksLikeGoogleBlock(url, title, snippet)) return
        usedDesktopUa = true
        binding.webView.settings.userAgentString = CHROME_DESKTOP_UA
        binding.webView.loadUrl(START_URL)
    }

    private fun inspectPageForBlock(view: WebView?) {
        if (usedDesktopUa || view == null) return
        view.evaluateJavascript(BLOCK_SNIPPET_JS) { encoded ->
            val snippet = encoded
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.replace("\\n", " ")
                ?.replace("\\\"", "\"")
            maybeSwitchToDesktop(view.url, view.title, snippet)
        }
    }

    private fun clearWebViewSession() {
        if (!::binding.isInitialized) return
        webViewCleared = true
        binding.webView.removeCallbacks(cookieCheck)
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
        binding.webView.stopLoading()
        binding.webView.clearCache(true)
        binding.webView.clearFormData()
        binding.webView.clearHistory()
        binding.webView.loadUrl("about:blank")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val webView = binding.webView
        manager.setAcceptThirdPartyCookies(webView, true)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = CHROME_ANDROID_UA
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    maybeSwitchToDesktop(url, view?.title)
                    checkSignedIn(url)
                    return false
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                maybeSwitchToDesktop(url, view?.title)
                checkSignedIn(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                maybeSwitchToDesktop(url, view?.title)
                checkSignedIn(url)
                inspectPageForBlock(view)
                if (viewModel.state.value.phase == GoogleReconnectPhase.WEB) {
                    scheduleCookieCheck()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                maybeSwitchToDesktop(view?.url, title)
            }
        }
    }

    companion object {
        private const val START_URL = "https://messages.google.com/web/"
        private const val COOKIE_CHECK_MS = 800L
        private const val SUCCESS_FINISH_MS = 700L
        private const val BLOCK_SNIPPET_JS =
            "(function(){try{return (document.documentElement.innerText||'').slice(0,800);}catch(e){return '';}})()"

        /** Chrome Android UA without "; wv" so accounts.google.com does not reject the WebView. */
        private const val CHROME_ANDROID_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val CHROME_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
