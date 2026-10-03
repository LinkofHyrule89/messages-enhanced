package com.ubermicrostudios.messagesenhanced.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.ubermicrostudios.messagesenhanced.UiState
import com.ubermicrostudios.messagesenhanced.net.WebPolicy

/**
 * Google Messages for web inside this app's own WebView. The owner signs in
 * himself; no scripts are injected into Google pages.
 */
@Composable
fun WebScreen(
    state: UiState,
    onDone: () -> Unit,
    onSend: () -> Unit,
    onBlocked: (Boolean) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onDone()
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text("Done", fontSize = 16.sp) }
                    Button(
                        onClick = onSend,
                        enabled = !state.busy,
                        modifier = Modifier.weight(2f).height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    ) { Text(if (state.busy) "Sending…" else "Send to Messages Enhanced", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                if (state.googleBlocked) {
                    Text(
                        "Google blocked sign-in inside this app (\"This browser or app may not be secure\"). " +
                            "This app can't work around that. Try again later, or use the Chrome extension on a computer instead.",
                        color = ErrRed,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.status.isNotEmpty() && state.screen == com.ubermicrostudios.messagesenhanced.Screen.WEB) {
                    StatusText(state, Modifier.padding(top = 8.dp))
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = BrandBlue)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                createGoogleWebView(ctx,
                    onLoading = { loading = it },
                    onUrl = { url -> onBlocked(WebPolicy.isGoogleSignInBlocked(url)) },
                ).also { webView = it; it.loadUrl(WebPolicy.MESSAGES_WEB_URL) }
            },
            onRelease = { wv ->
                CookieManager.getInstance().flush()
                wv.stopLoading()
                wv.destroy()
            },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createGoogleWebView(
    ctx: android.content.Context,
    onLoading: (Boolean) -> Unit,
    onUrl: (String?) -> Unit,
): WebView {
    val wv = WebView(ctx)
    val cm = CookieManager.getInstance()
    cm.setAcceptCookie(true)
    cm.setAcceptThirdPartyCookies(wv, true)

    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // Normal mobile Chrome UA: same Chrome/Android versions, minus "; wv"
        // and the "Version/4.0" WebView marker.
        userAgentString = WebPolicy.chromeLikeUserAgent(WebSettings.getDefaultUserAgent(ctx))
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
    }

    wv.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Stay on https inside the WebView; ignore intent:, market:, http:, etc.
            return !WebPolicy.isAllowedInWebView(request.url.toString())
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            onLoading(true)
            onUrl(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            onLoading(false)
            onUrl(url)
            CookieManager.getInstance().flush()
        }
    }
    return wv
}
