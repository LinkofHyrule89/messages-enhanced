package com.ubermicrostudios.messagesenhanced.ui.conversations

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ubermicrostudios.messagesenhanced.R
import com.ubermicrostudios.messagesenhanced.data.model.ContactDto
import com.ubermicrostudios.messagesenhanced.databinding.ActivityNewChatBinding
import com.ubermicrostudios.messagesenhanced.ui.Insets
import com.ubermicrostudios.messagesenhanced.ui.SessionActivity
import com.ubermicrostudios.messagesenhanced.util.formatPhoneForDisplay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NewChatActivity : SessionActivity() {

    private lateinit var binding: ActivityNewChatBinding
    private val viewModel: NewChatViewModel by viewModels()
    private lateinit var adapter: ContactAdapter
    private var writing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enable(this)
        binding = ActivityNewChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.apply(binding.newChatRoot, includeIme = true)
        observeAuthLoss()

        binding.back.imageTintList = ColorStateList.valueOf(getColor(R.color.text))
        binding.back.setOnClickListener { finish() }
        adapter = ContactAdapter(
            origin = { viewModel.origin() },
            onContact = { viewModel.pick(it) },
            onSendTo = { viewModel.pickTyped(it) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.to.doAfterTextChanged {
            if (writing) return@doAfterTextChanged
            viewModel.onQuery(it?.toString().orEmpty())
        }
        binding.group.setOnClickListener { viewModel.toggleGroup() }
        binding.next.setOnClickListener { viewModel.next() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is NewChatEffect.Toast -> Toast.makeText(this@NewChatActivity, effect.text, Toast.LENGTH_SHORT).show()
                        is NewChatEffect.Open -> {
                            setResult(
                                RESULT_OK,
                                Intent()
                                    .putExtra(EXTRA_CONV, effect.conversation.conversationId)
                                    .putExtra(EXTRA_NAME, effect.conversation.name)
                                    .putExtra(EXTRA_GROUP, effect.conversation.isGroup),
                            )
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun render(state: NewChatUiState) {
        binding.title.text = state.title
        binding.group.text = state.groupLabel
        binding.next.isVisible = state.nextVisible
        binding.next.isEnabled = state.nextEnabled
        binding.next.alpha = if (state.nextEnabled) 1f else 0.4f
        binding.next.text = state.nextLabel
        if (!writing && binding.to.text?.toString().orEmpty() != state.query) {
            writing = true
            binding.to.setText(state.query)
            binding.to.setSelection(binding.to.text?.length ?: 0)
            writing = false
        }
        renderChips(state.chips, state.group)
        adapter.submit(state.rows)
    }

    private fun renderChips(chips: List<ContactDto>, group: Boolean) {
        binding.chipScroll.isVisible = group && chips.isNotEmpty()
        binding.chips.removeAllViews()
        if (!group) return
        val density = resources.displayMetrics.density
        chips.forEach { chip ->
            val view = TextView(this).apply {
                text = formatPhoneForDisplay(chip.displayName) + "  ×"
                setTextColor(getColor(R.color.text))
                textSize = 14f
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(0xFF2A3340.toInt())
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                params.marginEnd = (8 * density).toInt()
                layoutParams = params
                setOnClickListener { viewModel.removeChip(chip) }
            }
            binding.chips.addView(view)
        }
    }

    companion object {
        const val EXTRA_CONV = "conv"
        const val EXTRA_NAME = "name"
        const val EXTRA_GROUP = "group"
    }
}
