package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.ubermicrostudios.messagesenhanced.util.CircleAvatars
import com.ubermicrostudios.messagesenhanced.util.circleAvatar
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.cache.InboxCache
import com.ubermicrostudios.messagesenhanced.data.model.ConversationDto
import com.ubermicrostudios.messagesenhanced.data.prefs.AppPreferences
import com.ubermicrostudios.messagesenhanced.databinding.ActivityConversationsBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.ui.settings.GoogleReconnectActivity
import com.ubermicrostudios.messagesenhanced.ui.settings.SettingsActivity
import com.ubermicrostudios.messagesenhanced.ui.thread.DetailsSheet
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadActivity
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadFragment
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadMenu
import com.ubermicrostudios.messagesenhanced.ui.thread.ThreadViewModel
import com.ubermicrostudios.messagesenhanced.update.UpdateChecker
import com.ubermicrostudios.messagesenhanced.update.UpdatePrompts
import com.ubermicrostudios.messagesenhanced.update.WhatsNewPrompts
import com.ubermicrostudios.messagesenhanced.util.ConversationOrder
import com.ubermicrostudios.messagesenhanced.util.LastThreadRestore
import com.ubermicrostudios.messagesenhanced.util.HealthBanner
import com.ubermicrostudios.messagesenhanced.util.Participants
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ConversationsActivity : SessionActivity() {

    @Inject lateinit var updateChecker: UpdateChecker
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var inboxCache: InboxCache
    @Inject lateinit var draftStore: com.ubermicrostudios.messagesenhanced.data.prefs.DraftStore
    private val refreshDrafts = Runnable { if (::adapter.isInitialized) adapter.notifyDataSetChanged() }
    private val draftListener: () -> Unit = {
        window.decorView.removeCallbacks(refreshDrafts)
        window.decorView.postDelayed(refreshDrafts, 300)
    }
    @Inject lateinit var whatsNew: WhatsNewPrompts
    @Inject lateinit var shareShortcuts: com.ubermicrostudios.messagesenhanced.share.ShareShortcuts

    private lateinit var binding: ActivityConversationsBinding
    private lateinit var updatePrompts: UpdatePrompts
    private val viewModel: ConversationsViewModel by viewModels()
    private lateinit var adapter: ConversationAdapter
    private lateinit var searchAdapter: SearchAdapter
    private var writingSearch = false
    private var shownProfileKey: String? = null
    private var threadServerError = false
    private var bannerPhase = BannerPhase.HIDDEN
    private var bannerGeneration = 0
    private var shownHealthBanner: HealthBanner.Banner? = null
    /** Holds the splash until a tablet cold start has the cached thread ready to draw. */
    private var restoreGate = false
    private var listScrollRestored = false

    private val twoPane: Boolean
        get() = binding.threadContainer != null

    private val newChat = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val id = result.data?.getStringExtra(NewChatActivity.EXTRA_CONV).orEmpty()
        if (id.isBlank()) return@registerForActivityResult
        val name = result.data?.getStringExtra(NewChatActivity.EXTRA_NAME).orEmpty()
        val group = result.data?.getBooleanExtra(NewChatActivity.EXTRA_GROUP, false) == true
        viewModel.refresh()
        val known = viewModel.state.value.conversations.firstOrNull { it.conversationId == id }
        openThread(known ?: ConversationDto(conversationId = id, name = name, isGroup = group))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            if (!viewModel.hasSession()) false
            else restoreGate || !viewModel.state.value.ready
        }
        if (!viewModel.hasSession()) {
            goToLogin()
            return
        }
        Insets.enable(this)
        binding = ActivityConversationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Tablet: clip the thread pane to bg_thread_pane's rounded top-right corner.
        binding.threadContainer?.apply {
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        updatePrompts = UpdatePrompts(this, updateChecker)
        val refreshSpan = (64 * resources.displayMetrics.density).toInt()
        if (twoPane) {
            Insets.applyTabletShell(
                root = binding.root,
                appBar = binding.appBar,
                chrome = binding.listChrome,
                scrolling = listOf(binding.list, binding.searchResults),
                fab = binding.startChat,
            ) { height ->
                binding.refresh.setProgressViewOffset(false, height, height + refreshSpan)
            }
        } else {
            Insets.applyScrollingChrome(
                root = binding.root,
                chrome = binding.listChrome,
                scrolling = listOf(binding.list, binding.searchResults),
                insetEnd = true,
                fab = binding.startChat,
            ) { height ->
                binding.refresh.setProgressViewOffset(false, height, height + refreshSpan)
            }
        }
        binding.startChat.setOnClickListener {
            newChat.launch(Intent(this, NewChatActivity::class.java))
        }
        observeAuthLoss()

        adapter = ConversationAdapter(
            origin = { viewModel.origin() },
            onClick = { openThread(it) },
            onMenu = { conversation, anchor -> showConversationMenu(conversation, anchor) },
            draftOf = { id -> draftStore.get(id) },
        )
        draftStore.addListener(draftListener)
        searchAdapter = SearchAdapter(
            onConversation = { conversation ->
                clearSearchField()
                openThread(resolveConversation(conversation))
            },
            onMessage = { hit ->
                val known = viewModel.state.value.conversations
                    .firstOrNull { it.conversationId == hit.message.conversationId }
                val conversation = known ?: ConversationDto(
                    conversationId = hit.message.conversationId,
                    name = hit.title,
                )
                if (!twoPane) clearSearchField()
                openThread(conversation, hit.message.messageId, hit.message.timestampMs)
            },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        adapter.submitList(ConversationOrder.rows(viewModel.state.value.conversations)) {
            restoreListScroll()
        }
        binding.searchResults.layoutManager = LinearLayoutManager(this)
        binding.searchResults.adapter = searchAdapter

        binding.profile.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.healthBanner.setOnClickListener {
            val banner = shownHealthBanner ?: return@setOnClickListener
            if (!HealthBanner.opensGoogleReconnect(banner)) return@setOnClickListener
            startActivity(Intent(this, GoogleReconnectActivity::class.java))
        }
        binding.profile.contentDescription = getString(R.string.profile_open_settings)
        binding.searchButton.setOnClickListener { onSearchButton() }
        binding.search.doAfterTextChanged {
            if (writingSearch) return@doAfterTextChanged
            viewModel.onSearchInput(it?.toString().orEmpty())
        }
        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.submitSearch()
                true
            } else {
                false
            }
        }
        binding.search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            binding.search.postDelayed({
                if (!binding.search.hasFocus() && binding.search.text.isNullOrBlank() && viewModel.state.value.search.open) {
                    viewModel.closeSearch()
                }
            }, 150)
        }
        binding.refresh.setOnRefreshListener { viewModel.refresh(userInitiated = true) }
        binding.refresh.setProgressBackgroundColorSchemeColor(
            com.google.android.material.color.MaterialColors.getColor(binding.refresh, com.google.android.material.R.attr.colorSurfaceContainerHigh),
        )
        binding.refresh.setColorSchemeColors(
            com.google.android.material.color.MaterialColors.getColor(binding.refresh, androidx.appcompat.R.attr.colorPrimary),
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value.search.open) {
                    clearSearchField()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        val listListener = FragmentResultListener { _, _ -> viewModel.onResume() }
        supportFragmentManager.setFragmentResultListener(ThreadFragment.RESULT_LIST, this, listListener)
        supportFragmentManager.setFragmentResultListener(ThreadFragment.RESULT_CLOSE, this) { _, _ ->
            threadServerError = false
            supportFragmentManager.findFragmentById(R.id.thread_container)?.let { fragment ->
                supportFragmentManager.beginTransaction().remove(fragment).commit()
            }
            binding.threadPlaceholder?.isVisible = true
            viewModel.select(null)
            adapter.selectedId = null
            viewModel.onResume()
        }
        supportFragmentManager.setFragmentResultListener(ThreadFragment.RESULT_SERVER_ERROR, this) { _, bundle ->
            threadServerError = bundle.getBoolean(ThreadFragment.EXTRA_SERVER_ERROR, false)
            if (::binding.isInitialized) bindHealthBanner(viewModel.state.value)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }

        handleOpenIntent(intent)
        restoreLastThread(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            if (!viewModel.hasSession()) {
                goToLogin()
                return
            }
            viewModel.onResume()
            updatePrompts.onHostResume()
            updatePrompts.checkOnLaunch()
            whatsNew.maybeShow(this)
        }
    }

    override fun onStop() {
        if (::binding.isInitialized) {
            viewModel.saveListScroll(binding.list.layoutManager?.onSaveInstanceState())
        }
        super.onStop()
    }

    override fun onDestroy() {
        draftStore.removeListener(draftListener)
        window.decorView.removeCallbacks(refreshDrafts)
        if (::binding.isInitialized) binding.healthBanner.animate().cancel()
        if (::updatePrompts.isInitialized) updatePrompts.dismiss()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    private fun render(state: ConversationsUiState) {
        state.closeThreadId?.let { id ->
            closeThreadPane(id)
            viewModel.consumeCloseThread()
        }
        adapter.colors = state.colors
        adapter.typingNames = state.typingNames
        adapter.selectedId = state.selectedId
        adapter.submitList(ConversationOrder.rows(state.conversations)) {
            restoreListScroll()
        }
        shareShortcuts.update(state.conversations)
        state.notice?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeNotice()
        }
        val query = state.search.query.trim()
        val searching = query.isNotEmpty()
        binding.search.isVisible = state.search.open
        if (!writingSearch && binding.search.text?.toString().orEmpty() != state.search.query) {
            writingSearch = true
            binding.search.setText(state.search.query)
            binding.search.setSelection(binding.search.text?.length ?: 0)
            writingSearch = false
        }
        binding.refresh.isVisible = !searching
        binding.refresh.isRefreshing = state.refreshing && !searching
        searchAdapter.query = query
        val rows = searchAdapter.rows(
            getString(R.string.search_conversations),
            getString(R.string.search_messages),
            state.search,
        )
        searchAdapter.submitList(rows)
        binding.searchResults.isVisible = state.search.phase == SearchPhase.RESULTS
        binding.progress.isVisible = (state.loading && state.conversations.isEmpty() && !searching) ||
            state.search.phase == SearchPhase.LOADING
        binding.empty.isVisible = when {
            state.search.phase == SearchPhase.SHORT -> true
            state.search.phase == SearchPhase.EMPTY -> true
            !searching && state.ready && !state.loading && state.conversations.isEmpty() -> true
            else -> false
        }
        binding.empty.text = when {
            state.search.phase == SearchPhase.SHORT -> getString(R.string.search_keep_typing)
            state.search.phase == SearchPhase.EMPTY && !state.search.messageError.isNullOrBlank() ->
                getString(R.string.search_failed, state.search.messageError)
            state.search.phase == SearchPhase.EMPTY -> getString(R.string.search_no_results, query)
            else -> getString(R.string.empty_conversations)
        }
        binding.error.isVisible = !state.error.isNullOrBlank()
        binding.error.text = state.error.orEmpty()
        bindHealthBanner(state)
        renderProfile(state.profile)
    }

    /**
     * Health banner sits under the app bar. It fades out when the connection recovers.
     * A server-unreachable error (this screen, or the open thread on a tablet) hides it
     * immediately so only that error shows.
     */
    private fun bindHealthBanner(state: ConversationsUiState) {
        val unreachable = !state.error.isNullOrBlank() || threadServerError || threadReportsServerError()
        val banner = HealthBanner.forDisplay(state.healthBanner, unreachable)
        if (banner == null) dismissHealthBanner(immediate = unreachable) else presentHealthBanner(banner)
    }

    private fun threadReportsServerError(): Boolean {
        if (binding.threadContainer == null) return false
        val fragment = supportFragmentManager.findFragmentById(R.id.thread_container) as? ThreadFragment
        return fragment?.isServerErrorVisible() == true
    }

    private fun presentHealthBanner(banner: HealthBanner.Banner) {
        paintHealthBanner(banner)
        val view = binding.healthBanner
        if (bannerPhase == BannerPhase.SHOWN || bannerPhase == BannerPhase.ENTERING) return
        val generation = ++bannerGeneration
        bannerPhase = BannerPhase.ENTERING
        view.animate().cancel()
        view.alpha = 0f
        view.isVisible = true
        view.animate().alpha(1f).setDuration(BANNER_FADE_MS).withEndAction {
            if (generation != bannerGeneration) return@withEndAction
            bannerPhase = BannerPhase.SHOWN
            view.alpha = 1f
        }.start()
    }

    private fun dismissHealthBanner(immediate: Boolean) {
        val view = binding.healthBanner
        if (bannerPhase == BannerPhase.HIDDEN) return
        val generation = ++bannerGeneration
        view.animate().cancel()
        if (immediate || !view.isVisible) {
            bannerPhase = BannerPhase.HIDDEN
            shownHealthBanner = null
            view.alpha = 1f
            view.isVisible = false
            return
        }
        bannerPhase = BannerPhase.EXITING
        view.animate().alpha(0f).setDuration(BANNER_FADE_MS).withEndAction {
            if (generation != bannerGeneration) return@withEndAction
            bannerPhase = BannerPhase.HIDDEN
            shownHealthBanner = null
            view.isVisible = false
            view.alpha = 1f
        }.start()
    }

    private fun paintHealthBanner(banner: HealthBanner.Banner) {
        shownHealthBanner = banner
        val view = binding.healthBanner
        val reconnect = HealthBanner.opensGoogleReconnect(banner)
        view.isClickable = reconnect
        view.isFocusable = reconnect
        val warn = banner.severity == HealthBanner.Severity.WARN
        val bgAttr = if (warn) {
            com.google.android.material.R.attr.colorTertiaryContainer
        } else {
            com.google.android.material.R.attr.colorErrorContainer
        }
        val fgAttr = if (warn) {
            com.google.android.material.R.attr.colorOnTertiaryContainer
        } else {
            com.google.android.material.R.attr.colorOnErrorContainer
        }
        val bg = com.google.android.material.color.MaterialColors.getColor(view, bgAttr, if (warn) 0xFF3A3000.toInt() else 0xFF6B2A1A.toInt())
        val fg = com.google.android.material.color.MaterialColors.getColor(view, fgAttr, if (warn) 0xFFFFE08A.toInt() else 0xFFFFFFFF.toInt())
        view.setCardBackgroundColor(bg)
        binding.healthBannerText.text = banner.text
        binding.healthBannerText.setTextColor(fg)
        binding.healthBannerIcon.imageTintList = ColorStateList.valueOf(fg)
    }

    private fun renderProfile(profile: ProfileUi) {
        val key = listOf(profile.photoUrl, profile.fallbackUrl, profile.initial, profile.colorSeed).joinToString("|")
        binding.profile.contentDescription =
            getString(R.string.profile_open_settings) + ", " + formatPhoneForDisplay(profile.name)
        if (key == shownProfileKey) return
        shownProfileKey = key
        val color = Participants.avatarColor(profile.colorSeed)
        binding.profileInitials.text = profile.initial
        binding.profileInitials.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val url = profile.photoUrl ?: profile.fallbackUrl
        if (url.isNullOrBlank()) {
            binding.profilePhoto.isVisible = false
            binding.profilePhoto.setImageDrawable(null)
            return
        }
        binding.profilePhoto.isVisible = true
        CircleAvatars.clip(binding.profilePhoto)
        val accountPhoto = profile.photoUrl != null
        binding.profilePhoto.load(url) {
            circleAvatar(url)
            listener(
                onError = { _, _ ->
                    if (accountPhoto) {
                        shownProfileKey = null
                        viewModel.onProfilePhotoError()
                    } else {
                        binding.profilePhoto.isVisible = false
                    }
                },
            )
        }
    }

    private fun restoreListScroll() {
        if (listScrollRestored) return
        if (adapter.itemCount == 0) return
        listScrollRestored = true
        viewModel.listScroll()?.let { binding.list.layoutManager?.onRestoreInstanceState(it) }
    }

    private fun onSearchButton() {
        val open = viewModel.state.value.search.open
        val blank = viewModel.state.value.search.query.isBlank()
        if (open && blank) {
            clearSearchField()
            return
        }
        viewModel.onSearchButton()
        binding.search.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.search, 0)
    }

    private fun clearSearchField() {
        writingSearch = true
        binding.search.setText("")
        writingSearch = false
        binding.search.clearFocus()
        viewModel.closeSearch()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.search.windowToken, 0)
    }

    private fun resolveConversation(conversation: ConversationDto): ConversationDto =
        viewModel.state.value.conversations.firstOrNull { it.conversationId == conversation.conversationId }
            ?: conversation

    private fun handleOpenIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_THREAD) return
        val conv = intent.getStringExtra(EXTRA_CONV).orEmpty()
        if (conv.isBlank()) return
        val match = viewModel.state.value.conversations.firstOrNull { it.conversationId == conv }
        if (match != null) {
            openThread(match)
        } else {
            openThread(
                ConversationDto(conversationId = conv, name = getString(R.string.thread_title)),
            )
        }
    }

    private fun showConversationMenu(conversation: ConversationDto, anchor: View) {
        val current = resolveConversation(conversation)
        ConversationRowMenu.show(
            anchor,
            current,
            ConversationRowMenu.Actions(
                details = { showConversationDetails(current) },
                refresh = { refreshConversation(current) },
                pin = { viewModel.pin(current, current.localPinnedAtMs <= 0L) },
                archive = { viewModel.archive(current, getString(R.string.archived_notice)) },
                trash = { confirmTrash(current) },
            ),
        )
    }

    private fun showConversationDetails(conversation: ConversationDto) {
        val me = Participants.parse(conversation.participants).firstOrNull { it.me }
        DetailsSheet.show(this, conversation, viewModel.origin(), Participants.profileName(me))
    }

    private fun refreshConversation(conversation: ConversationDto) {
        val name = formatPhoneForDisplay(Participants.displayName(conversation))
        Toast.makeText(this, getString(R.string.refreshing_conversation, name), Toast.LENGTH_SHORT).show()
        viewModel.refreshConversation(conversation)
    }

    private fun confirmTrash(conversation: ConversationDto) {
        val name = formatPhoneForDisplay(Participants.displayName(conversation))
        val dialog = AlertDialog.Builder(this)
            .setMessage(getString(R.string.trash_confirm, name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.menu_trash) { _, _ -> viewModel.trash(conversation) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ThreadMenu.DANGER)
        }
        dialog.show()
    }

    /** Tablet: leave the empty pane when the open conversation was just archived or trashed. */
    private fun closeThreadPane(conversationId: String) {
        if (binding.threadContainer == null) return
        val fragment = supportFragmentManager.findFragmentById(R.id.thread_container)
        if (fragment != null) {
            val openId = fragment.arguments?.getString(ThreadViewModel.ARG_ID).orEmpty()
            if (openId != conversationId) return
            supportFragmentManager.beginTransaction().remove(fragment).commit()
        }
        binding.threadPlaceholder?.isVisible = true
        threadServerError = false
    }

    /**
     * Tablet cold start: put the last opened conversation in the split pane from
     * the inbox cache, then let the thread refresh in the background. Phones stay
     * on the list. A notification intent and a restored activity win over this.
     */
    private fun restoreLastThread(savedInstanceState: Bundle?) {
        val decision = LastThreadRestore.decide(
            twoPane = twoPane,
            hasSavedState = savedInstanceState != null,
            openingFromIntent = intent?.action == ACTION_OPEN_THREAD,
            lastId = prefs.lastSelectedConversationId(),
            cached = inboxCache.peekConversations(),
        )
        when (decision) {
            LastThreadRestore.Decision.Skip -> Unit
            is LastThreadRestore.Decision.Open -> {
                if (!inboxCache.peekMessages(decision.conversation.conversationId).isNullOrEmpty()) {
                    openThread(decision.conversation, immediate = true)
                } else {
                    restoreGate = true
                    lifecycleScope.launch {
                        try {
                            openCachedThread(decision.conversation)
                        } finally {
                            restoreGate = false
                        }
                    }
                }
            }
            LastThreadRestore.Decision.WaitForCache -> {
                restoreGate = true
                val id = prefs.lastSelectedConversationId()
                lifecycleScope.launch {
                    try {
                        val next = LastThreadRestore.decide(
                            twoPane = true,
                            hasSavedState = false,
                            openingFromIntent = intent?.action == ACTION_OPEN_THREAD,
                            lastId = id,
                            cached = inboxCache.readConversations(),
                        )
                        val conversation = (next as? LastThreadRestore.Decision.Open)?.conversation ?: return@launch
                        openCachedThread(conversation)
                    } finally {
                        restoreGate = false
                    }
                }
            }
        }
    }

    /** Loads this chat's saved messages, then attaches the pane so the first frame has them. */
    private suspend fun openCachedThread(conversation: ConversationDto) {
        if (inboxCache.peekMessages(conversation.conversationId).isNullOrEmpty()) {
            inboxCache.readMessages(conversation.conversationId)
        }
        if (supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentById(R.id.thread_container) != null) return
        openThread(conversation, immediate = true)
    }

    private fun openThread(conv: ConversationDto, jumpId: String? = null, jumpTs: Long = 0L, immediate: Boolean = false) {
        viewModel.select(conv.conversationId)
        adapter.selectedId = conv.conversationId
        if (conv.conversationId.isNotBlank()) prefs.setLastSelectedConversationId(conv.conversationId)
        if (twoPane) {
            val transaction = supportFragmentManager.beginTransaction()
                .replace(R.id.thread_container, ThreadFragment.newInstance(conv, jumpId, jumpTs))
            if (immediate && !supportFragmentManager.isStateSaved) transaction.commitNow() else transaction.commit()
            binding.threadPlaceholder?.isVisible = false
        } else {
            startActivity(
                Intent(this, ThreadActivity::class.java).also {
                    ThreadFragment.writeIntent(it, conv, jumpId, jumpTs)
                },
            )
        }
    }

    private enum class BannerPhase { HIDDEN, ENTERING, SHOWN, EXITING }

    companion object {
        const val ACTION_OPEN_THREAD = "com.ubermicrostudios.messagesenhanced.OPEN_THREAD"
        const val EXTRA_CONV = "conv"
        private const val BANNER_FADE_MS = 180L
    }
}
