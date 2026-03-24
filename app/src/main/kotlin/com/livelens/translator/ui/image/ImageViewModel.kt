package com.livelens.translator.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livelens.translator.model.GemmaTranslateManager
import com.livelens.translator.model.TranslationMode
import com.livelens.translator.data.repository.TranslationRepository
import com.livelens.translator.util.BitmapUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ImageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gemmaTranslateManager: GemmaTranslateManager,
    private val translationRepository: TranslationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageUiState())
    val uiState: StateFlow<ImageUiState> = _uiState.asStateFlow()

    fun toggleCamera() {
        _uiState.value = _uiState.value.copy(
            showCamera = !_uiState.value.showCamera,
            capturedBitmap = null,
            selectedImageUri = null,
            translationResult = ""
        )
    }

    fun openGallery() {
        // The screen handles launching the gallery picker; we just reset state
        _uiState.value = _uiState.value.copy(
            showCamera = false,
            capturedBitmap = null,
            selectedImageUri = null,
            translationResult = ""
        )
    }

    fun onImageSelected(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            selectedImageUri = uri,
            capturedBitmap = null,
            showCamera = false,
            translationResult = ""
        )
    }

    fun capturePhoto(imageCapture: ImageCapture, context: Context) {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = BitmapUtils.imageProxyToBitmap(image)
                    image.close()
                    _uiState.value = _uiState.value.copy(
                        capturedBitmap = bitmap,
                        selectedImageUri = null,
                        showCamera = false,
                        translationResult = ""
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e(exception, "Photo capture failed")
                    _uiState.value = _uiState.value.copy(
                        error = "Photo capture failed: ${exception.message}"
                    )
                }
            }
        )
    }

    fun translateCurrentImage() {
        val bitmap = _uiState.value.capturedBitmap
            ?: _uiState.value.selectedImageUri?.let { loadBitmapFromUri(it) }
            ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTranslating = true, translationResult = "")

            val accumulatedResult = StringBuilder()

            gemmaTranslateManager.translateImage(bitmap)
                .catch { e ->
                    Timber.e(e, "Image translation failed")
                    _uiState.value = _uiState.value.copy(
                        isTranslating = false,
                        error = "Translation failed: ${e.message}"
                    )
                }
                .collect { token ->
                    accumulatedResult.append(token)
                    _uiState.value = _uiState.value.copy(
                        translationResult = accumulatedResult.toString(),
                        isTranslating = true
                    )
                }

            val finalResult = accumulatedResult.toString()
            _uiState.value = _uiState.value.copy(
                translationResult = finalResult,
                isTranslating = false
            )

            // Persist to history
            if (finalResult.isNotBlank()) {
                translationRepository.insert(
                    originalText = "[Image]",
                    translatedText = finalResult,
                    mode = TranslationMode.IMAGE,
                    hasImage = true
                )
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load bitmap from URI")
            null
        }
    }
}

data class ImageUiState(
    val showCamera: Boolean = false,
    val capturedBitmap: Bitmap? = null,
    val selectedImageUri: Uri? = null,
    val isTranslating: Boolean = false,
    val translationResult: String = "",
    val error: String? = null
)
