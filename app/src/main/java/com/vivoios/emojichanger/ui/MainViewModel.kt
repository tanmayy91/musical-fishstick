package com.vivoios.emojichanger.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vivoios.emojichanger.db.AppDatabase
import com.vivoios.emojichanger.engine.*
import com.vivoios.emojichanger.model.*
import com.vivoios.emojichanger.util.PreferencesRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"
    private val db = AppDatabase.getInstance(application)
    private val packDao = db.emojiPackDao()
    private val prefsRepo = PreferencesRepository(application)

    val deviceInfo: DeviceDetector.DeviceInfo by lazy { DeviceDetector.detect() }

    private val engine by lazy {
        EmojiEngine(application, packDao, deviceInfo)
    }

    private val downloader by lazy { PackDownloader(application, packDao) }

    // ── State flows ──────────────────────────────────────────────────────────

    val packs: StateFlow<List<EmojiPack>> = packDao.getAllPacks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = prefsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _applyStatus = MutableStateFlow(ApplyStatus())
    val applyStatus: StateFlow<ApplyStatus> = _applyStatus.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private val _adbCommands = MutableStateFlow<List<String>>(emptyList())
    val adbCommands: StateFlow<List<String>> = _adbCommands.asStateFlow()

    private val _fontInstallResult = MutableStateFlow<FontInstallManager.InstallResult?>(null)
    val fontInstallResult: StateFlow<FontInstallManager.InstallResult?> = _fontInstallResult.asStateFlow()

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        initDefaultPacks()
        restoreApplyStatus()
    }

    private fun initDefaultPacks() {
        viewModelScope.launch {
            // Insert default pack definitions if not already present
            val existing = packDao.getPackById(PackDownloader.DEFAULT_PACKS[0].id)
            if (existing == null) {
                packDao.insertPacks(PackDownloader.DEFAULT_PACKS)
                Log.i(TAG, "Inserted ${PackDownloader.DEFAULT_PACKS.size} default pack definitions")
            }
        }
    }

    private fun restoreApplyStatus() {
        viewModelScope.launch {
            val applied = packDao.getAppliedPack()
            if (applied != null) {
                _applyStatus.value = ApplyStatus(
                    mode = ApplyMode.HIGH_COMPAT,
                    isActive = true,
                    appliedPackId = applied.id,
                    appliedPackName = applied.name,
                    message = "${applied.name} is active",
                    coveragePercent = applied.overallScore.coerceAtLeast(60)
                )
            }
        }
    }

    // ── One-click smart setup ─────────────────────────────────────────────────

    fun oneClickSmartSetup() {
        viewModelScope.launch {
            _isLoading.value = true
            _uiEvent.emit(UiEvent.ShowProgress("Detecting device…"))

            try {
                // 1. Detect device
                val info = deviceInfo
                _uiEvent.emit(UiEvent.ShowProgress(
                    "Detected: ${info.manufacturer} ${info.model}\nFuntouch OS: ${info.funtouchOsVersion ?: "Unknown"}"
                ))

                // 2. Download both packs
                _uiEvent.emit(UiEvent.ShowProgress("Downloading iOS emoji packs…"))
                val downloadResults = mutableListOf<EmojiPack>()

                for (pack in PackDownloader.DEFAULT_PACKS) {
                    _uiEvent.emit(UiEvent.ShowProgress("Downloading ${pack.name}…"))
                    downloader.downloadPack(pack) { dl, total ->
                        val progress = if (total > 0) ((dl * 100) / total).toInt() else 0
                        val current = _downloadProgress.value.toMutableMap()
                        current[pack.id] = progress
                        _downloadProgress.value = current
                    }.onSuccess { dir ->
                        val updated = pack.copy(
                            isDownloaded = true,
                            localPath = dir.absolutePath,
                            downloadState = DownloadState.READY
                        )
                        downloadResults.add(updated)
                        packDao.updateDownloadComplete(
                            pack.id, true, dir.absolutePath, DownloadState.READY.name
                        )
                    }.onFailure { e ->
                        Log.e(TAG, "Download failed for ${pack.id}: ${e.message}")
                        _uiEvent.emit(UiEvent.ShowSnackbar("Download failed: ${e.message}"))
                    }
                }

                if (downloadResults.isEmpty()) {
                    _uiEvent.emit(UiEvent.ShowSnackbar("All downloads failed. Check internet connection."))
                    _isLoading.value = false
                    return@launch
                }

                // 3. Select best pack
                _uiEvent.emit(UiEvent.ShowProgress("Comparing emoji packs…"))
                val selector = PackSelector(packDao, info)
                val selectionResult = selector.selectBestPack(downloadResults)
                val bestPack = selectionResult.winner
                _uiEvent.emit(UiEvent.ShowProgress("Best pack: ${bestPack.name}"))

                // 4. Apply best pack (includes system font installation)
                _uiEvent.emit(UiEvent.ShowProgress("Installing iOS emoji font system-wide…"))
                val status = engine.applyPack(bestPack)
                _applyStatus.value = status

                // 5. Run FontInstallManager for system font paths
                val packDir = File(bestPack.localPath)
                if (packDir.exists()) {
                    _uiEvent.emit(UiEvent.ShowProgress("Applying to system font directories…"))
                    val fontResult = engine.installFontSystemWide(packDir)
                    _fontInstallResult.value = fontResult
                    val adbCmds = engine.getAdbCommands(packDir)
                    _adbCommands.value = adbCmds
                }

                prefsRepo.setSelectedPack(bestPack.id)
                _uiEvent.emit(UiEvent.ApplySuccess(status))

            } catch (e: Exception) {
                Log.e(TAG, "Smart setup error: ${e.message}", e)
                _uiEvent.emit(UiEvent.ShowSnackbar("Setup failed: ${e.message}"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Apply a specific pack ─────────────────────────────────────────────────

    fun applyPack(pack: EmojiPack) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val status = engine.applyPack(pack)
                _applyStatus.value = status

                // Also run system font installation
                val packDir = File(pack.localPath)
                if (packDir.exists()) {
                    val fontResult = engine.installFontSystemWide(packDir)
                    _fontInstallResult.value = fontResult
                    _adbCommands.value = engine.getAdbCommands(packDir)
                }

                prefsRepo.setSelectedPack(pack.id)
                _uiEvent.emit(UiEvent.ApplySuccess(status))
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowSnackbar("Apply failed: ${e.message}"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Restore defaults ──────────────────────────────────────────────────────

    fun restoreDefault() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val status = engine.restoreDefault()
                _applyStatus.value = status
                prefsRepo.setSelectedPack(null)
                _fontInstallResult.value = null
                _adbCommands.value = emptyList()
                _uiEvent.emit(UiEvent.ShowSnackbar("Default Vivo emojis restored ✓"))
            } catch (e: Exception) {
                _uiEvent.emit(UiEvent.ShowSnackbar("Restore failed: ${e.message}"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Download a single pack ────────────────────────────────────────────────

    fun downloadPack(pack: EmojiPack) {
        viewModelScope.launch {
            _isLoading.value = true
            downloader.downloadPack(pack) { dl, total ->
                val progress = if (total > 0) ((dl * 100) / total).toInt() else 0
                val current = _downloadProgress.value.toMutableMap()
                current[pack.id] = progress
                _downloadProgress.value = current
            }.onSuccess {
                _uiEvent.emit(UiEvent.ShowSnackbar("${pack.name} downloaded ✓"))
            }.onFailure { e ->
                _uiEvent.emit(UiEvent.ShowSnackbar("Download failed: ${e.message}"))
            }
            _isLoading.value = false
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun setAutoReapplyOnBoot(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setAutoReapplyOnBoot(enabled)
    }
    fun setDarkMode(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setDarkMode(enabled)
    }
    fun setHapticFeedback(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setHapticFeedback(enabled)
    }
    fun setAutoUpdatePacks(enabled: Boolean) = viewModelScope.launch {
        prefsRepo.setAutoUpdatePacks(enabled)
    }

    fun openKeyboardSettings() = viewModelScope.launch {
        _uiEvent.emit(UiEvent.OpenSettings(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    fun openAccessibilitySettings() = viewModelScope.launch {
        _uiEvent.emit(UiEvent.OpenSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

sealed class UiEvent {
    data class ShowProgress(val message: String) : UiEvent()
    data class ApplySuccess(val status: ApplyStatus) : UiEvent()
    data class ShowSnackbar(val message: String) : UiEvent()
    data class OpenSettings(val action: String) : UiEvent()
}
