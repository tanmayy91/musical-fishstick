package com.vivoios.emojichanger.service

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.inputmethodservice.InputMethodService
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vivoios.emojichanger.R
import com.vivoios.emojichanger.db.AppDatabase
import com.vivoios.emojichanger.engine.EmojiEngine
import com.vivoios.emojichanger.engine.DeviceDetector
import com.vivoios.emojichanger.model.EmojiCategory
import com.vivoios.emojichanger.model.EmojiEntry
import kotlinx.coroutines.*
import java.io.File

/**
 * Custom iOS Emoji Keyboard (Input Method Service).
 *
 * This keyboard is the guaranteed fallback — it works in every app that accepts
 * text input. It renders iOS emoji images as image spans when inserting characters
 * so the recipient sees iOS-style emojis.
 */
class EmojiKeyboardService : InputMethodService() {

    private val TAG = "EmojiKeyboard"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var engine: EmojiEngine
    private lateinit var keyboardView: View

    private var currentCategory = EmojiCategory.SMILEYS_PEOPLE
    private var recentEmojis = mutableListOf<EmojiEntry>()
    private var allEmojis = mapOf<EmojiCategory, List<EmojiEntry>>()
    private var searchResults = listOf<EmojiEntry>()
    private var isSearchMode = false

    private var emojiAdapter: EmojiGridAdapter? = null
    private var categoryTabLayout: LinearLayout? = null
    private var emojiRecyclerView: RecyclerView? = null
    private var searchEditText: EditText? = null
    private var deleteButton: ImageButton? = null

    companion object {
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(applicationContext)
        val deviceInfo = DeviceDetector.detect()
        engine = EmojiEngine(applicationContext, db.emojiPackDao(), deviceInfo)
        isRunning = true
        Log.i(TAG, "EmojiKeyboardService created")
    }

    override fun onCreateInputView(): View {
        keyboardView = LayoutInflater.from(this)
            .inflate(R.layout.keyboard_emoji, null)

        initViews()
        loadEmojis()

        return keyboardView
    }

    private fun initViews() {
        emojiRecyclerView = keyboardView.findViewById(R.id.rv_emojis)
        categoryTabLayout = keyboardView.findViewById(R.id.ll_categories)
        searchEditText = keyboardView.findViewById(R.id.et_search)
        deleteButton = keyboardView.findViewById(R.id.btn_delete)

        emojiAdapter = EmojiGridAdapter(this) { emoji ->
            insertEmoji(emoji)
        }

        emojiRecyclerView?.apply {
            layoutManager = GridLayoutManager(this@EmojiKeyboardService, 8)
            adapter = emojiAdapter
        }

        deleteButton?.setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
            vibrateKey()
        }
        deleteButton?.setOnLongClickListener {
            currentInputConnection?.deleteSurroundingText(10, 0)
            true
        }

        searchEditText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    isSearchMode = false
                    showCategory(currentCategory)
                } else {
                    isSearchMode = true
                    searchEmojis(query)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        setupCategoryTabs()
    }

    private fun setupCategoryTabs() {
        val categories = EmojiCategory.values()
        categories.forEach { category ->
            val tabView = TextView(this).apply {
                text = category.icon
                textSize = 20f
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    currentCategory = category
                    showCategory(category)
                    updateCategoryTabSelection(this, categoryTabLayout)
                }
            }
            categoryTabLayout?.addView(tabView)
        }
    }

    private fun updateCategoryTabSelection(selected: View, container: LinearLayout?) {
        container ?: return
        for (i in 0 until (container.childCount)) {
            val child = container.getChildAt(i)
            child.alpha = if (child == selected) 1.0f else 0.5f
        }
    }

    private fun loadEmojis() {
        serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                buildEmojiList()
            }
            allEmojis = loaded
            showCategory(currentCategory)
        }
    }

    private fun buildEmojiList(): Map<EmojiCategory, List<EmojiEntry>> {
        val emojiDir = engine.getActiveEmojiDir()
        if (!emojiDir.exists()) {
            return buildFallbackEmojiList()
        }

        val result = mutableMapOf<EmojiCategory, MutableList<EmojiEntry>>()
        EmojiCategory.values().forEach { result[it] = mutableListOf() }

        emojiDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in listOf("png", "webp") }
            .forEach { file ->
                val codepoint = file.nameWithoutExtension
                    .removePrefix("emoji_u")
                    .lowercase()
                val entry = EmojiEntry(
                    codepoint = codepoint,
                    name = codepointToName(codepoint),
                    category = codepointToCategory(codepoint),
                    imagePath = file.absolutePath,
                    unicode = codepointToUnicode(codepoint)
                )
                result[entry.category]?.add(entry)
            }

        return result
    }

    private fun buildFallbackEmojiList(): Map<EmojiCategory, List<EmojiEntry>> {
        // Provide built-in Unicode emoji list as fallback (no image, use system rendering)
        val smileys = listOf(
            "1f600", "1f601", "1f602", "1f603", "1f604", "1f605", "1f606", "1f607",
            "1f608", "1f609", "1f60a", "1f60b", "1f60c", "1f60d", "1f60e", "1f60f",
            "1f610", "1f611", "1f612", "1f613", "1f614", "1f615", "1f616", "1f617",
            "1f618", "1f619", "1f61a", "1f61b", "1f61c", "1f61d", "1f61e", "1f61f",
            "1f620", "1f621", "1f622", "1f623", "1f624", "1f625", "1f626", "1f627"
        )
        return mapOf(
            EmojiCategory.SMILEYS_PEOPLE to smileys.map {
                EmojiEntry(it, codepointToName(it), EmojiCategory.SMILEYS_PEOPLE,
                    unicode = codepointToUnicode(it))
            }
        )
    }

    private fun showCategory(category: EmojiCategory) {
        val emojis = if (category == EmojiCategory.RECENT) {
            recentEmojis.toList()
        } else {
            allEmojis[category] ?: emptyList()
        }
        emojiAdapter?.submitList(emojis)
    }

    private fun searchEmojis(query: String) {
        serviceScope.launch {
            val results = withContext(Dispatchers.Default) {
                allEmojis.values.flatten().filter { emoji ->
                    emoji.name.contains(query, ignoreCase = true) ||
                            emoji.keywords.any { it.contains(query, ignoreCase = true) }
                }
            }
            emojiAdapter?.submitList(results)
        }
    }

    private fun insertEmoji(emoji: EmojiEntry) {
        val ic: InputConnection = currentInputConnection ?: return
        vibrateKey()

        if (emoji.imagePath.isNotEmpty()) {
            // Insert image span with iOS emoji image
            val file = File(emoji.imagePath)
            if (file.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(emoji.imagePath)
                    val drawable = BitmapDrawable(resources, bitmap).also {
                        it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
                    }
                    val ssb = SpannableStringBuilder(emoji.unicode)
                    ssb.setSpan(
                        ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                        0, emoji.unicode.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    ic.commitText(ssb, 1)
                } catch (e: Exception) {
                    // Fallback: insert Unicode character
                    ic.commitText(emoji.unicode, 1)
                }
            } else {
                ic.commitText(emoji.unicode, 1)
            }
        } else {
            ic.commitText(emoji.unicode, 1)
        }

        // Track in recent
        recentEmojis.removeAll { it.codepoint == emoji.codepoint }
        recentEmojis.add(0, emoji)
        if (recentEmojis.size > 32) recentEmojis.removeAt(recentEmojis.lastIndex)
    }

    private fun vibrateKey() {
        try {
            val vibrator = getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(30,
                android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun codepointToName(cp: String): String {
        return cp.split("_").joinToString(" ") { it.uppercase() }
    }

    private fun codepointToCategory(cp: String): EmojiCategory {
        val value = cp.toLongOrNull(16) ?: return EmojiCategory.SYMBOLS
        return when (value) {
            in 0x1F600..0x1F64F -> EmojiCategory.SMILEYS_PEOPLE
            in 0x1F400..0x1F4FF -> EmojiCategory.ANIMALS_NATURE
            in 0x1F300..0x1F3FF -> EmojiCategory.TRAVEL_PLACES
            in 0x1F680..0x1F6FF -> EmojiCategory.TRAVEL_PLACES
            in 0x1F700..0x1F9FF -> EmojiCategory.OBJECTS
            in 0x2600..0x27BF -> EmojiCategory.SYMBOLS
            in 0x1F1E0..0x1F1FF -> EmojiCategory.FLAGS
            else -> EmojiCategory.SYMBOLS
        }
    }

    private fun codepointToUnicode(cp: String): String {
        return try {
            val parts = cp.split("_")
            parts.joinToString("") { part ->
                val code = part.toLong(16).toInt()
                String(Character.toChars(code))
            }
        } catch (e: Exception) {
            ""
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        searchEditText?.text?.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceScope.cancel()
    }
}

/**
 * RecyclerView adapter for the emoji grid.
 */
class EmojiGridAdapter(
    private val context: Context,
    private val onEmojiClick: (EmojiEntry) -> Unit
) : RecyclerView.Adapter<EmojiGridAdapter.EmojiViewHolder>() {

    private var items = listOf<EmojiEntry>()

    fun submitList(list: List<EmojiEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): EmojiViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_emoji_key, parent, false)
        return EmojiViewHolder(view)
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        holder.bind(items[position], onEmojiClick)
    }

    override fun getItemCount() = items.size

    class EmojiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.iv_emoji)
        private val textView: TextView = view.findViewById(R.id.tv_emoji_fallback)

        fun bind(emoji: EmojiEntry, onClick: (EmojiEntry) -> Unit) {
            if (emoji.imagePath.isNotEmpty()) {
                val file = File(emoji.imagePath)
                if (file.exists()) {
                    imageView.visibility = View.VISIBLE
                    textView.visibility = View.GONE
                    try {
                        val bitmap = BitmapFactory.decodeFile(emoji.imagePath)
                        imageView.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                        showFallback(emoji)
                    }
                } else {
                    showFallback(emoji)
                }
            } else {
                showFallback(emoji)
            }
            itemView.setOnClickListener { onClick(emoji) }
        }

        private fun showFallback(emoji: EmojiEntry) {
            imageView.visibility = View.GONE
            textView.visibility = View.VISIBLE
            textView.text = emoji.unicode
        }
    }
}
