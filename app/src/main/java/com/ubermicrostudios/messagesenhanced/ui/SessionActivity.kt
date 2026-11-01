package com.ubermicrostudios.messagesenhanced.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ubermicrostudios.messagesenhanced.data.net.SessionMonitor
import com.ubermicrostudios.messagesenhanced.ui.login.LoginActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

abstract class SessionActivity : AppCompatActivity() {

    @Inject lateinit var sessionMonitor: SessionMonitor

    protected fun observeAuthLoss() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sessionMonitor.authLost.collect {
                    goToLogin()
                }
            }
        }
    }

    protected fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
