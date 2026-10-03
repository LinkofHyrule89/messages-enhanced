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
import androidx.compose.foundation.text.KeyboardOptions
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
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Messages Enhanced", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Enter the https address of your own Messages Enhanced server.",
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
                                busy = true; status = "Checking ${norm.origin}…"; ok = false
                                Thread {
                                    val err = probe(norm.origin)
                                    runOnUiThread {
                                        busy = false
                                        if (err == null) {
                                            prefs.serverAddress = norm.origin
                                            address = norm.origin
                                            ok = true; status = "Connected. Saved."
                                            startActivity(Intent(this@SetupActivity, TwaActivity::class.java))
                                            finish()
                                        } else { ok = false; status = err }
                                    }
                                }.start()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { Text(if (busy) "Checking…" else "Test and open", fontSize = 18.sp) }
                        if (status.isNotEmpty()) Text(status, color = if (ok) OkGreen else ErrRed)
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@SetupActivity, CookieSenderActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Send Google sign-in cookies…") }
                    }
                }
            }
        }
    }

    /** Null when [origin] answers like a Messages Enhanced server, else an error to show. */
    private fun probe(origin: String): String? = try {
        val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url("$origin/app/manifest.webmanifest").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                !r.isSuccessful -> "The server answered HTTP ${r.code}. Is this a Messages Enhanced server?"
                !body.contains("\"start_url\"") || !body.contains("/app/") -> "That server doesn't look like Messages Enhanced."
                else -> null
            }
        }
    } catch (e: Exception) {
        "Couldn't reach $origin (${e.javaClass.simpleName}: ${e.message ?: "no details"})."
    }
}
