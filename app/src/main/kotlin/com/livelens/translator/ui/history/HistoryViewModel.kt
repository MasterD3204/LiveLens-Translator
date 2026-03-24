package com.livelens.translator.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livelens.translator.data.db.TranslationEntity
import com.livelens.translator.data.repository.TranslationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: TranslationRepository
) : ViewModel() {

    private val _groupedTranslations = MutableStateFlow<Map<String, List<TranslationEntity>>>(emptyMap())
    val groupedTranslations: StateFlow<Map<String, List<TranslationEntity>>> = _groupedTranslations.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        observeTranslations()
    }

    private fun observeTranslations() {
        viewModelScope.launch {
            repository.observeAll()
                .catch { e ->
                    Timber.e(e, "Error observing translations")
                    _isLoading.value = false
                }
                .collect { translations ->
                    _groupedTranslations.value = groupByDate(translations)
                    _isLoading.value = false
                }
        }
    }

    fun deleteTranslation(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteById(id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete translation $id")
            }
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            try {
                repository.deleteAll()
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete all translations")
            }
        }
    }

    private fun groupByDate(translations: List<TranslationEntity>): Map<String, List<TranslationEntity>> {
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

        return translations.groupBy { entity ->
            val cal = Calendar.getInstance().apply { timeInMillis = entity.timestamp }
            when {
                isSameDay(cal, today) -> "Today"
                isSameDay(cal, yesterday) -> "Yesterday"
                else -> dateFormat.format(Date(entity.timestamp))
            }
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}
