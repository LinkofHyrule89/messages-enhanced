package com.ubermicrostudios.messagesenhanced.ui.settings

import android.Manifest
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.databinding.ActivitySettingsBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.update.UpdateChecker
import com.ubermicrostudios.messagesenhanced.update.UpdatePrompts
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : SessionActivity() {

    @Inject lateinit var updateChecker: UpdateChecker
    @Inject lateinit var appPrefs: com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var updatePrompts: UpdatePrompts
    private val viewModel: SettingsViewModel by viewModels()
    private var renderingGrok = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setNotifications(true)
        } else {
            binding.notifications.isChecked = false
            Toast.makeText(this, R.string.notifications_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enable(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updatePrompts = UpdatePrompts(this, updateChecker)
        Insets.apply(binding.root, includeIme = true)
        observeAuthLoss()

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val initial = viewModel.state.value
        binding.serverUrl.setText(initial.serverUrl)
        binding.notifications.isChecked = initial.notifications
        binding.hideText.isChecked = initial.hideText
        binding.showDeleted.isChecked = appPrefs.showDeletedPlaceholders
        binding.showDeleted.setOnCheckedChangeListener { _, checked -> appPrefs.showDeletedPlaceholders = checked }
        binding.holidayAnimations.isChecked = appPrefs.holidayAnimations
        binding.holidayAnimations.setOnCheckedChangeListener { _, checked -> appPrefs.holidayAnimations = checked }
        binding.animatedEmojis.isChecked = appPrefs.animatedEmojis
        binding.animatedEmojis.setOnCheckedChangeListener { _, checked -> appPrefs.animatedEmojis = checked }
        binding.autoCleanShared.isChecked = appPrefs.autoCleanSharedLinks
        binding.autoCleanShared.setOnCheckedChangeListener { _, checked -> appPrefs.autoCleanSharedLinks = checked }
        binding.cleanRule.setText(appPrefs.cleanLinksRule)
        binding.cleanRule.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val rule = s?.toString().orEmpty()
                if (com.ubermicrostudios.messagesenhanced.util.LinkCleaner.isValid(rule)) {
                    binding.cleanRuleLayout.error = null
                    appPrefs.cleanLinksRule = rule
                } else {
                    binding.cleanRuleLayout.error = getString(R.string.clean_links_rule_invalid)
                }
            }
        })
        binding.cleanRuleReset.setOnClickListener {
            appPrefs.resetCleanLinksRule()
            binding.cleanRule.setText(appPrefs.cleanLinksRule)
        }
        binding.version.text = getString(R.string.app_version, initial.version)

        binding.serverUrl.doAfterTextChanged { viewModel.onServerUrl(it?.toString().orEmpty()) }
        binding.saveServer.setOnClickListener { viewModel.saveServerUrl() }
        binding.reconnectGoogle.setOnClickListener {
            startActivity(Intent(this, GoogleReconnectActivity::class.java))
        }
        binding.autoGoogleRepair.setOnCheckedChangeListener { _, checked ->
            if (checked == viewModel.state.value.autoGoogleRepair) return@setOnCheckedChangeListener
            viewModel.setAutoGoogleRepair(checked)
        }
        binding.forgetGoogleSession.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.forget_google_session_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.forgetGoogleSession() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.notifications.setOnCheckedChangeListener { _, checked ->
            if (checked == viewModel.state.value.notifications) return@setOnCheckedChangeListener
            if (checked) enableNotifications() else viewModel.setNotifications(false)
        }
        binding.hideText.setOnCheckedChangeListener { _, checked ->
            if (checked == viewModel.state.value.hideText) return@setOnCheckedChangeListener
            viewModel.setHideText(checked)
        }
        binding.testNotification.setOnClickListener { viewModel.sendTest() }
        binding.refreshEverything.setOnClickListener { viewModel.refreshEverything() }
        binding.logout.setOnClickListener { viewModel.logout() }
        binding.autoUpdate.isChecked = updateChecker.autoUpdate
        binding.autoUpdate.setOnCheckedChangeListener { _, checked ->
            updateChecker.autoUpdate = checked
        }
        binding.checkUpdates.setOnClickListener { updatePrompts.checkFromSettings(binding.checkUpdates) }
        binding.grokEnable.setOnCheckedChangeListener { _, checked ->
            if (renderingGrok || checked == viewModel.state.value.grokEnabled) return@setOnCheckedChangeListener
            viewModel.setGrokEnabled(checked)
        }
        binding.groqEnable.setOnCheckedChangeListener { _, checked ->
            if (renderingGrok || checked == viewModel.state.value.groqEnabled) return@setOnCheckedChangeListener
            viewModel.setGroqEnabled(checked)
        }
        binding.grokTrigger.setOnCheckedChangeListener { _, checkedId ->
            if (renderingGrok || checkedId == View.NO_ID) return@setOnCheckedChangeListener
            val trigger = if (checkedId == binding.triggerEveryone.id) "everyone" else "me"
            viewModel.setGrokTrigger(trigger)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    renderGrok(state)
                    binding.appearance.text = getString(R.string.appearance_label, appearanceLabel(state.appearance))
                    binding.notifications.isChecked = state.notifications
                    binding.hideText.isChecked = state.hideText
                    binding.autoGoogleRepair.isChecked = state.autoGoogleRepair
                    binding.autoGoogleRepair.isEnabled = !state.busy
                    binding.forgetGoogleSession.isEnabled = !state.busy
                    binding.hideText.isEnabled = state.notifications && !state.busy
                    binding.testNotification.isEnabled = state.notifications && !state.busy
                    binding.notifications.isEnabled = !state.busy
                    binding.refreshEverything.isEnabled = !state.busy
                    binding.logout.isEnabled = !state.busy
                    binding.saveServer.isEnabled = !state.busy
                    state.message?.let {
                        Toast.makeText(this@SettingsActivity, it, Toast.LENGTH_SHORT).show()
                        viewModel.consumeMessage()
                    }
                    if (state.loggedOut) goToLogin()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            viewModel.onResume()
            updatePrompts.onHostResume()
        }
    }

    override fun onDestroy() {
        if (::updatePrompts.isInitialized) updatePrompts.dismiss()
        super.onDestroy()
    }

    private fun renderGrok(state: SettingsUiState) {
        binding.grokUnavailable.isVisible = state.grokUnavailable
        binding.grokControls.isVisible = state.grokVisible && !state.grokUnavailable
        val controlsOn = !state.busy && !state.grokUnavailable
        renderingGrok = true
        binding.grokEnable.isChecked = state.grokEnabled
        binding.groqEnable.isChecked = state.groqEnabled
        binding.grokTrigger.check(
            if (state.grokTrigger == "everyone") binding.triggerEveryone.id else binding.triggerMe.id,
        )
        renderingGrok = false
        binding.grokEnable.isEnabled = controlsOn && (state.grokKey || state.grokEnabled)
        binding.groqEnable.isEnabled = controlsOn && (state.groqKey || state.groqEnabled)
        binding.triggerMe.isEnabled = controlsOn
        binding.triggerEveryone.isEnabled = controlsOn
    }

    private fun appearanceLabel(value: String): String {
        if (value.isBlank() || value.equals("dark", ignoreCase = true)) {
            return getString(R.string.appearance_dark)
        }
        return value.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
    }

    private fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setNotifications(true)
        }
    }
}
