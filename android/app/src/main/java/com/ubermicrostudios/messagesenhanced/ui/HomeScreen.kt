package com.ubermicrostudios.messagesenhanced.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubermicrostudios.messagesenhanced.StatusKind
import com.ubermicrostudios.messagesenhanced.UiState
import com.ubermicrostudios.messagesenhanced.net.CookieLogic

@Composable
fun HomeScreen(
    state: UiState,
    onAddress: (String) -> Unit,
    onSave: (String) -> Boolean,
    onForgetPassword: () -> Unit,
    onOpenWeb: () -> Unit,
    onCheck: () -> Unit,
    onSend: () -> Unit,
    onSignOutGoogle: () -> Unit,
) {
    // The typed password lives only in this field until saved (plain remember,
    // not rememberSaveable, so it never lands in the saved-instance Bundle). It
    // is cleared right after saving and never re-populated from storage.
    var password by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Google sign-in cookies", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Sign in to Google Messages here, then send the sign-in cookies to your Messages Enhanced server.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.address,
            onValueChange = onAddress,
            label = { Text("Server address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (state.passwordSaved) "Server password (saved)" else "Server password") },
            placeholder = { if (state.passwordSaved) Text("•••••••• leave blank to keep") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { if (onSave(password)) password = "" },
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Save settings", fontSize = 16.sp) }
            if (state.passwordSaved) {
                TextButton(onClick = onForgetPassword, modifier = Modifier.height(52.dp)) {
                    Text("Forget password")
                }
            }
        }

        BigButton("Sign in to Google Messages", onOpenWeb, primary = false)
        BigButton(if (state.busy) "Sending…" else "Send to Messages Enhanced", onSend, primary = true, enabled = !state.busy)

        if (state.status.isNotEmpty()) StatusText(state)

        if (state.checked) CookieNamesCard(state)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onCheck) { Text("Check cookies only") }
            TextButton(onClick = onSignOutGoogle) { Text("Sign out of Google in this app") }
        }

        Text(
            "Use your Messages Enhanced server address (https, or http on your own network). " +
                "Cookie values are never shown or stored by this app; only names are displayed.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit, primary: Boolean, enabled: Boolean = true) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(text, fontSize = 18.sp) }
    }
}

@Composable
fun StatusText(state: UiState, modifier: Modifier = Modifier) {
    val color = when (state.statusKind) {
        StatusKind.OK -> OkGreen
        StatusKind.ERROR -> ErrRed
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(state.status, color = color, fontSize = 16.sp, modifier = modifier)
}

@Composable
private fun CookieNamesCard(state: UiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Required cookies", fontWeight = FontWeight.SemiBold)
            for (name in CookieLogic.REQUIRED) {
                val ok = name in state.found
                Text(
                    (if (ok) "✓ " else "✗ ") + name + (if (ok) "" else "  (missing)"),
                    color = if (ok) OkGreen else ErrRed,
                    fontSize = 16.sp,
                )
            }
            if (state.extras.isNotEmpty()) {
                Text(
                    "Also sending: " + state.extras.joinToString(", "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
