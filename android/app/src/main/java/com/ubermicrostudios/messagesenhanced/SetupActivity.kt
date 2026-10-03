package com.ubermicrostudios.messagesenhanced

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubermicrostudios.messagesenhanced.net.ServerAddress
import com.ubermicrostudios.messagesenhanced.ui.BrandBlue
import com.ubermicrostudios.messagesenhanced.ui.ErrRed
import com.ubermicrostudios.messagesenhanced.ui.METheme
import com.ubermicrostudios.messagesenhanced.ui.OkGreen
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * First-run / settings screen: the user's own Messages Enhanced server address.
 * It must be https:// and must answer like a Messages Enhanced server before it
 * is saved. Also links to the cookie sender.
 */
class SetupActivity : ComponentActivity() {
    companion object {
        /** Set when the web app couldn't be opened; the screen then shows the log. */
        const val EXTRA_FROM_FAILURE = "from_failure"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = SecurePrefs(this)
        setContent {
            METheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    var address by remember { mutableStateOf(prefs.serverAddress) }
                    var status by remember { mutableStateOf("") }
                    var ok by remember { mutableStateOf(false) }
                    var busy by remember { mutableStateOf(false) }
                    var saved by remember { mutableStateOf(prefs.serverAddress) }
                    var log by remember { mutableStateOf(LaunchLog.read(this@SetupActivity)) }
                    val fromFailure = intent?.getBooleanExtra(EXTRA_FROM_FAILURE, false) == true
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Messages Enhanced", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Enter the https address of your own Messages Enhanced server: just the domain, or the full web app address. The app finds the start page itself.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it; status = "" },
                            label = { Text("Server address") },
                            placeholder = { Text("https://messages.example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            enabled = !busy,
                            onClick = {
                                val typed = address.trim().let { if (it.isNotEmpty() && !it.contains("://")) "https://$it" else it }
                                val norm = ServerAddress.normalize(typed)
                                if (!norm.ok || !norm.isHttps) {
                                    ok = false
                                    status = if (!norm.ok) norm.error ?: "Invalid address." else "The address must start with https://."
                                    return@Button
                                }
                                val base = ServerAddress.basePath(typed)
                                busy = true; status = "Checking ${norm.origin}$base…"; ok = false
                                Thread {
                                    val found = probe(norm.origin, base)
                                    runOnUiThread {
                                        busy = false
                                        val start = found.first
                                        if (start != null) {
                                            prefs.serverAddress = start
                                            address = start
                                            saved = start
                                            ok = true; status = "Connected. Saved: $start"
                                            try {
                                                startActivity(Intent(this@SetupActivity, TwaActivity::class.java))
                                                finish()
                                            } catch (t: Throwable) {
                                                LaunchLog.record(this@SetupActivity, "Couldn't open the web app", t)
                                                log = LaunchLog.read(this@SetupActivity)
                                                ok = false; status = "Saved, but the web app couldn't open. See the log below."
                                            }
                                        } else { ok = false; status = found.second ?: "Couldn't check the server." }
                                    }
                                }.start()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(if (busy) "Checking…" else "Test and open", fontSize = 18.sp) }
                        if (status.isNotEmpty()) Text(status, color = if (ok) OkGreen else ErrRed)
                        if (saved.isNotBlank() && !ok) {
                            Text(
                                "Saved: ${ServerAddress.launchUrl(saved) ?: saved}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@SetupActivity, CookieSenderActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Send Google sign-in cookies…") }
                        if (log.isNotEmpty()) {
                            Text(
                                if (fromFailure) "The web app couldn't open. Details:" else "Recent problems:",
                                color = if (fromFailure) ErrRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                            )
                            SelectionContainer {
                                Text(log, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { LaunchLog.clear(this@SetupActivity); log = "" }) { Text("Clear log") }
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds the web app's start URL for [origin] + [base] (the path the user
     * typed, minus "/app..."), from the server's web app manifest. Tries the
     * typed path first, then the bare origin. (start URL, null) on success,
     * else (null, error to show).
     */
    private fun probe(origin: String, base: String): Pair<String?, String?> {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        var lastErr: String? = null
        for (b in if (base.isEmpty()) listOf("") else listOf(base, "")) {
            val manifestUrl = "${origin}${b}/app/manifest.webmanifest"
            try {
                client.newCall(Request.Builder().url(manifestUrl).build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        lastErr = "The server answered HTTP ${r.code} for $manifestUrl. Is this a Messages Enhanced server?"
                        return@use
                    }
                    val startRel = try { org.json.JSONObject(body).optString("start_url", "") } catch (_: Exception) { "" }
                    if (startRel.isEmpty()) {
                        lastErr = "That server doesn't look like Messages Enhanced (no start_url in its web app manifest)."
                        return@use
                    }
                    val start = r.request.url.resolve(startRel)?.toString()
                    if (start == null || !start.startsWith(origin)) {
                        lastErr = "The server's start page ($startRel) is on a different address."
                        return@use
                    }
                    return Pair(start, null)
                }
            } catch (e: Exception) {
                lastErr = "Couldn't reach $origin (${e.javaClass.simpleName}: ${e.message ?: "no details"})."
            }
        }
        return Pair(null, lastErr)
    }
}
