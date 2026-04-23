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
import com.google.android.material.snackbar.Snackbar
import com.vivoios.emojichanger.databinding.FragmentSettingsBinding
import com.vivoios.emojichanger.service.EmojiAccessibilityService
import com.vivoios.emojichanger.service.EmojiKeyboardService
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeSettings()
    }

    override fun onResume() {
        super.onResume()
        // Refresh service status when user returns from Settings
        updateServiceStatus()
    }

    private fun setupListeners() {
        binding.switchAutoReapply.setOnCheckedChangeListener { _, checked ->
            viewModel.setAutoReapplyOnBoot(checked)
        }
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            viewModel.setDarkMode(checked)
        }
        binding.switchHaptic.setOnCheckedChangeListener { _, checked ->
            viewModel.setHapticFeedback(checked)
        }
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            viewModel.setAutoUpdatePacks(checked)
        }

        binding.btnSetupKeyboard.setOnClickListener {
            viewModel.openKeyboardSettings()
        }
        binding.btnSetupAccessibility.setOnClickListener {
            viewModel.openAccessibilitySettings()
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settings.collect { settings ->
                        binding.switchAutoReapply.isChecked = settings.autoReapplyOnBoot
                        binding.switchDarkMode.isChecked = settings.darkModeEnabled
                        binding.switchHaptic.isChecked = settings.hapticFeedbackEnabled
                        binding.switchAutoUpdate.isChecked = settings.autoUpdatePacks
                    }
                }
                launch {
                    viewModel.uiEvent.collect { event ->
                        when (event) {
                            is UiEvent.OpenSettings -> {
                                startActivity(android.content.Intent(event.action))
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    private fun updateServiceStatus() {
        val keyboardActive = EmojiKeyboardService.isRunning
        val accessibilityActive = EmojiAccessibilityService.isRunning
        binding.tvKeyboardStatus.text = if (keyboardActive) "✅ Active" else "⚪ Not Active — tap to enable"
        binding.tvAccessibilityStatus.text = if (accessibilityActive) "✅ Active" else "⚪ Not Active — tap to enable"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
