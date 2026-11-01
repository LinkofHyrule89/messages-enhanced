package com.ubermicrostudios.messagesenhanced.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ubermicrostudios.messagesenhanced.databinding.ActivityLoginBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.conversations.ConversationsActivity
import com.ubermicrostudios.messagesenhanced.update.UpdateChecker
import com.ubermicrostudios.messagesenhanced.update.UpdatePrompts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : AppCompatActivity() {

    @Inject lateinit var updateChecker: UpdateChecker

    private lateinit var binding: ActivityLoginBinding
    private lateinit var updatePrompts: UpdatePrompts
    private val viewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enable(this)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updatePrompts = UpdatePrompts(this, updateChecker)
        Insets.apply(binding.root, includeIme = true)

        binding.serverUrl.setText(viewModel.state.value.serverUrl)
        binding.serverUrl.doAfterTextChanged { viewModel.onServerUrl(it?.toString().orEmpty()) }
        binding.secret.doAfterTextChanged { viewModel.onSecret(it?.toString().orEmpty()) }
        binding.secret.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.submit()
                true
            } else false
        }
        binding.signIn.setOnClickListener { viewModel.submit() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.signIn.isEnabled = !state.busy
                    binding.signIn.text = if (state.busy) "Signing in…" else "Sign in"
                    binding.error.text = state.error.orEmpty()
                    if (state.loggedIn) {
                        startActivity(
                            Intent(this@LoginActivity, ConversationsActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            },
                        )
                        finish()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::updatePrompts.isInitialized) {
            updatePrompts.onHostResume()
            updatePrompts.checkOnLaunch()
        }
    }

    override fun onDestroy() {
        if (::updatePrompts.isInitialized) updatePrompts.dismiss()
        super.onDestroy()
    }
}
