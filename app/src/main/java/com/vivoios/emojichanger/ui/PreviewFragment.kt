package com.vivoios.emojichanger.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vivoios.emojichanger.R
import com.vivoios.emojichanger.databinding.FragmentPreviewBinding
import com.vivoios.emojichanger.model.EmojiCategory
import com.vivoios.emojichanger.model.EmojiEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PreviewFragment : Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var emojiAdapter: PreviewEmojiAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        emojiAdapter = PreviewEmojiAdapter()
        binding.rvEmojiPreview.apply {
            layoutManager = GridLayoutManager(requireContext(), 6)
            adapter = emojiAdapter
        }

        observeState()
        loadPreviewEmojis()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.applyStatus.collect { status ->
                    if (status.isActive) {
                        binding.tvPreviewTitle.text = "Preview: ${status.appliedPackName}"
                        binding.tvNoPackMessage.visibility = View.GONE
                        binding.rvEmojiPreview.visibility = View.VISIBLE
                        loadPreviewEmojis()
                    } else {
                        binding.tvNoPackMessage.visibility = View.VISIBLE
                        binding.rvEmojiPreview.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun loadPreviewEmojis() {
        viewLifecycleOwner.lifecycleScope.launch {
            val emojis = withContext(Dispatchers.IO) {
                val emojiDir = viewModel.viewModelScope.let {
                    // Get active emoji directory from engine via ViewModel
                    File(requireContext().filesDir, "active_emoji")
                }

                if (emojiDir.exists()) {
                    emojiDir.walkTopDown()
                        .filter { it.isFile && it.extension.lowercase() in listOf("png", "webp") }
                        .take(120)
                        .map { file ->
                            EmojiEntry(
                                codepoint = file.nameWithoutExtension,
                                name = file.nameWithoutExtension,
                                category = EmojiCategory.SMILEYS_PEOPLE,
                                imagePath = file.absolutePath
                            )
                        }
                        .toList()
                } else {
                    buildFallbackPreview()
                }
            }
            emojiAdapter.submitList(emojis)
        }
    }

    private fun buildFallbackPreview(): List<EmojiEntry> {
        // Show Unicode emojis as fallback when no pack is downloaded
        val emojis = listOf(
            "😀", "😂", "😍", "🥰", "😎", "🤔", "😅", "🎉",
            "❤️", "🔥", "⭐", "🎵", "🌍", "🍕", "🐶", "🐱",
            "🌸", "🦋", "🌊", "🎨", "🚀", "💡", "🏆", "🎮"
        )
        return emojis.map { emoji ->
            EmojiEntry(
                codepoint = emoji.codePointAt(0).toString(16),
                name = emoji,
                category = EmojiCategory.SMILEYS_PEOPLE,
                unicode = emoji
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class PreviewEmojiAdapter : RecyclerView.Adapter<PreviewEmojiAdapter.ViewHolder>() {

    private var items = listOf<EmojiEntry>()

    fun submitList(list: List<EmojiEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_emoji_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: android.widget.ImageView = view.findViewById(R.id.iv_emoji_preview)
        private val textView: android.widget.TextView = view.findViewById(R.id.tv_emoji_fallback)

        fun bind(emoji: EmojiEntry) {
            if (emoji.imagePath.isNotEmpty()) {
                val file = File(emoji.imagePath)
                if (file.exists()) {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(emoji.imagePath)
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = View.VISIBLE
                        textView.visibility = View.GONE
                        return
                    } catch (_: Exception) {}
                }
            }
            imageView.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = emoji.unicode.ifEmpty { emoji.name }
        }
    }
}
