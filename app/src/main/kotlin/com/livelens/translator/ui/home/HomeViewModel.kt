package com.livelens.translator.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livelens.translator.data.db.TranslationEntity
import com.livelens.translator.data.repository.TranslationRepository
import com.livelens.translator.model.ModelLoader
import com.livelens.translator.model.ModelStatusInfo
import com.livelens.translator.service.TranslatorAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val translationRepository: TranslationRepository,
    private val modelLoader: ModelLoader
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == TranslatorAccessibilityService.ACTION_SERVICE_STATUS_REPLY) {
                val isRunning = intent.getBooleanExtra(
                    TranslatorAccessibilityService.EXTRA_SERVICE_RUNNING, false
                )
                _uiState.value = _uiState.value.copy(serviceRunning = isRunning)
            }
        }
    }

    init {
        loadRecentTranslations()
        loadModelStatus()
        context.registerReceiver(
            serviceStatusReceiver,
            IntentFilter(TranslatorAccessibilityService.ACTION_SERVICE_STATUS_REPLY),
            Context.RECEIVER_NOT_EXPORTED
        )
        queryServiceStatus()
    }

    private fun loadRecentTranslations() {
        viewModelScope.launch {
            try {
                val recent = translationRepository.getRecentFive()
                _uiState.value = _uiState.value.copy(recentTranslations = recent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load recent translations")
            }
        }
    }

    private fun loadModelStatus() {
        viewModelScope.launch {
            modelLoader.checkAllModels()
            val status = modelLoader.getModelStatusInfo()
            _uiState.value = _uiState.value.copy(modelStatusInfo = status)
        }
    }

    private fun queryServiceStatus() {
        val isEnabled = TranslatorAccessibilityService.isEnabled(context)
        _uiState.value = _uiState.value.copy(serviceRunning = isEnabled)
        if (isEnabled) {
            val statusIntent = Intent(TranslatorAccessibilityService.ACTION_SERVICE_STATUS)
            context.sendBroadcast(statusIntent)
        }
    }

    fun checkServiceStatus() {
        queryServiceStatus()
    }

    fun refreshRecent() = loadRecentTranslations()

    override fun onCleared() {
        super.onCleared()
        try { context.unregisterReceiver(serviceStatusReceiver) } catch (_: Exception) {}
    }
}

data class HomeUiState(
    val serviceRunning: Boolean = false,
    val recentTranslations: List<TranslationEntity> = emptyList(),
    val modelStatusInfo: ModelStatusInfo? = null
)
