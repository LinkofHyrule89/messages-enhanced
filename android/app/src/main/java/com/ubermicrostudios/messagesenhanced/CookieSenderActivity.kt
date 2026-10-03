package com.ubermicrostudios.messagesenhanced

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ubermicrostudios.messagesenhanced.ui.HomeScreen
import com.ubermicrostudios.messagesenhanced.ui.METheme
import com.ubermicrostudios.messagesenhanced.ui.WebScreen

class CookieSenderActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            METheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                ) {
                    val state by vm.state.collectAsStateWithLifecycle()
                    when (state.screen) {
                        Screen.HOME -> HomeScreen(
                            state = state,
                            onAddress = vm::setAddress,
                            onSave = vm::saveSettings,
                            onForgetPassword = vm::forgetPassword,
                            onOpenWeb = vm::openWeb,
                            onCheck = vm::checkCookies,
                            onSend = vm::send,
                            onSignOutGoogle = vm::clearGoogleSession,
                        )
                        Screen.WEB -> WebScreen(
                            state = state,
                            onDone = vm::closeWeb,
                            onSend = vm::send,
                            onBlocked = vm::setGoogleBlocked,
                        )
                    }
                }
            }
        }
    }
}
