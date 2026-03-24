package com.livelens.translator.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livelens.translator.data.repository.AppSettings
import com.livelens.translator.data.repository.FontSizePref
import com.livelens.translator.data.repository.SettingsRepository
import com.livelens.translator.data.repository.SubtitlePosition
import com.livelens.translator.model.ModelLoader
import com.livelens.translator.model.ModelStatusInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val modelLoader: ModelLoader
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settingsRepository.appSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppSettings()
    )

    private val _modelStatusInfo = MutableStateFlow<ModelStatusInfo?>(null)
    val modelStatusInfo: StateFlow<ModelStatusInfo?> = _modelStatusInfo.asStateFlow()

    init {
        loadModelStatus()
    }

    private fun loadModelStatus() {
        viewModelScope.launch {
            modelLoader.checkAllModels()
            _modelStatusInfo.value = modelLoader.getModelStatusInfo()
        }
    }

    fun setTtsEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setTtsEnabled(enabled)
    }

    fun setTtsSpeed(speed: Float) = viewModelScope.launch {
        settingsRepository.setTtsSpeed(speed)
    }

    fun setOverlayOpacity(opacity: Float) = viewModelScope.launch {
        settingsRepository.setOverlayOpacity(opacity)
    }

    fun setSubtitlePosition(position: SubtitlePosition) = viewModelScope.launch {
        settingsRepository.setSubtitlePosition(position)
    }

    fun setFontSize(size: FontSizePref) = viewModelScope.launch {
        settingsRepository.setFontSize(size)
    }

    fun setMaxSentences(max: Int) = viewModelScope.launch {
        settingsRepository.setMaxSentences(max)
    }

    fun setDropThreshold(threshold: Int) = viewModelScope.launch {
        settingsRepository.setDropThreshold(threshold)
    }
}
