package com.ubermicrostudios.messagesenhanced.ui.thread

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ThreadActivity : SessionActivity() {
    @Volatile
    private var threadReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !threadReady }
        Insets.enable(this)
        setContentView(R.layout.activity_thread)
        observeAuthLoss()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(
                    R.id.thread_host,
                    ThreadFragment().apply { arguments = Bundle(intent.extras ?: Bundle()) },
                )
                .commit()
        }
    }

    fun onThreadReady() {
        threadReady = true
    }
}
