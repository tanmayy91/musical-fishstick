package com.vivoios.emojichanger.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.vivoios.emojichanger.R
import com.vivoios.emojichanger.databinding.FragmentHomeBinding
import com.vivoios.emojichanger.model.ApplyMode
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeState()
    }

    private fun setupClickListeners() {
        // One-click smart apply
        binding.btnApplyIos.setOnClickListener {
            viewModel.oneClickSmartSetup()
        }

        // Restore default
        binding.btnRestoreDefault.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Restore Default Emojis")
                .setMessage("This will remove iOS emojis and restore the original Vivo emojis.")
                .setPositiveButton("Restore") { _, _ -> viewModel.restoreDefault() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Enable keyboard
        binding.btnEnableKeyboard.setOnClickListener {
            viewModel.openKeyboardSettings()
        }

        // Enable accessibility
        binding.btnEnableAccessibility.setOnClickListener {
            viewModel.openAccessibilitySettings()
        }

        // Show ADB commands
        binding.btnShowAdb.setOnClickListener {
            showAdbCommandsDialog()
        }

        // Device info card
        binding.cardDeviceInfo.setOnClickListener {
            showDeviceInfoDialog()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressIndicator.visibility = if (loading) View.VISIBLE else View.GONE
                        binding.btnApplyIos.isEnabled = !loading
                        binding.btnRestoreDefault.isEnabled = !loading
                    }
                }

                launch {
                    viewModel.applyStatus.collect { status ->
                        updateStatusCard(status)
                    }
                }

                launch {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is UiEvent.ShowProgress -> {
                                binding.tvProgress.text = event.message
                                binding.tvProgress.visibility = View.VISIBLE
                            }
                            is UiEvent.ApplySuccess -> {
                                binding.tvProgress.visibility = View.GONE
                                Snackbar.make(binding.root,
                                    "✅ iOS Emojis Applied!", Snackbar.LENGTH_LONG).show()
                            }
                            is UiEvent.ShowSnackbar -> {
                                binding.tvProgress.visibility = View.GONE
                                Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
                            }
                            is UiEvent.OpenSettings -> {
                                startActivity(Intent(event.action))
                            }
                        }
                    }
                }

                launch {
                    viewModel.fontInstallResult.collect { result ->
                        result ?: return@collect
                        val coverage = result.bestCoverage
                        binding.tvFontCoverage.text = "Font coverage: $coverage%"
                        binding.tvFontCoverage.visibility = View.VISIBLE
                        binding.progressCoverage.progress = coverage
                        binding.progressCoverage.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun updateStatusCard(status: com.vivoios.emojichanger.model.ApplyStatus) {
        val (label, color) = when (status.mode) {
            ApplyMode.FULL_SYSTEM -> "🟢 Full System-Wide Support" to
                    requireContext().getColor(R.color.status_full)
            ApplyMode.HIGH_COMPAT -> "🔵 High Compatibility Mode" to
                    requireContext().getColor(R.color.status_high)
            ApplyMode.PARTIAL -> "🟡 Partial Support" to
                    requireContext().getColor(R.color.status_partial)
            ApplyMode.KEYBOARD_BACKUP -> "🟠 Keyboard Backup Active" to
                    requireContext().getColor(R.color.status_keyboard)
            ApplyMode.NONE -> "⚪ Not Active" to
                    requireContext().getColor(R.color.status_none)
        }
        binding.tvStatus.text = label
        binding.tvStatusMessage.text = status.message
        binding.cardStatus.strokeColor = color

        if (status.isActive) {
            binding.tvAppliedPack.text = "Active: ${status.appliedPackName}"
            binding.tvAppliedPack.visibility = View.VISIBLE
            binding.progressCoverage.progress = status.coveragePercent
        } else {
            binding.tvAppliedPack.visibility = View.GONE
        }
    }

    private fun showAdbCommandsDialog() {
        val commands = viewModel.adbCommands.value
        if (commands.isEmpty()) {
            Snackbar.make(binding.root,
                "Apply a pack first to generate ADB commands", Snackbar.LENGTH_SHORT).show()
            return
        }

        val commandText = commands.joinToString("\n")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("💻 ADB Commands — Full System Font")
            .setMessage("Run these commands from your PC (Developer Mode + USB Debugging):\n\n$commandText")
            .setPositiveButton("Copy to Clipboard") { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ADB Commands", commandText))
                Snackbar.make(binding.root, "Copied to clipboard!", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDeviceInfoDialog() {
        val info = viewModel.deviceInfo
        val text = """
            📱 Device Information
            ═══════════════════
            Manufacturer: ${info.manufacturer}
            Model: ${info.model}
            Brand: ${info.brand}
            Android: ${info.androidVersionName} (API ${info.androidVersion})
            Funtouch OS: ${info.funtouchOsVersion ?: "Not detected"}
            Vivo Device: ${if (info.isVivoDevice) "✅ Yes" else "❌ No"}
            Funtouch 14+: ${if (info.isFuntouchOs14) "✅ Yes" else "❌ No"}
            
            🔧 Supported Methods:
            ${info.supportedMethods.joinToString("\n• ", "• ")}
        """.trimIndent()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Device Info")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
