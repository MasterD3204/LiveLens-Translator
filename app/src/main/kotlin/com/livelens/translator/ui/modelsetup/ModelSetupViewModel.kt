package com.livelens.translator.ui.modelsetup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livelens.translator.model.ModelDownloadState
import com.livelens.translator.model.ModelLoader
import com.livelens.translator.model.ModelType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ModelSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val modelLoader: ModelLoader
) : ViewModel() {

    private val _downloadProgress = MutableStateFlow<Map<ModelType, ModelDownloadState>>(
        ModelType.values().associateWith { ModelDownloadState.Unknown }
    )
    val downloadProgress: StateFlow<Map<ModelType, ModelDownloadState>> = _downloadProgress.asStateFlow()

    private val _overallStatus = MutableStateFlow(OverallStatus.CHECKING)
    val overallStatus: StateFlow<OverallStatus> = _overallStatus.asStateFlow()

    /** Danh sách file .task tìm thấy trong thư mục Download */
    private val _taskFilesInDownloads = MutableStateFlow<List<File>>(emptyList())
    val taskFilesInDownloads: StateFlow<List<File>> = _taskFilesInDownloads.asStateFlow()

    /** Tiến độ import (0f..1f), -1f = không đang import */
    private val _importProgress = MutableStateFlow(-1f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    /** Thông báo kết quả import */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    init {
        checkModels()
        scanDownloadsForTaskFiles()
    }

    // ─── Kiểm tra trạng thái model ────────────────────────────────────────────

    fun checkModels() {
        viewModelScope.launch {
            _overallStatus.value = OverallStatus.CHECKING
            modelLoader.checkAllModels()
            _downloadProgress.value = modelLoader.downloadState.value
            _overallStatus.value = if (modelLoader.isCoreReady())
                OverallStatus.READY else OverallStatus.MISSING
        }
    }

    // ─── Quét Download tìm file .task ─────────────────────────────────────────

    /**
     * Quét thư mục Download tìm tất cả file .task.
     * Kết quả cập nhật vào [taskFilesInDownloads].
     */
    fun scanDownloadsForTaskFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val found = downloadDir
                    .listFiles { f -> f.isFile && f.extension.equals("task", ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()

                _taskFilesInDownloads.value = found
                Timber.i("Tìm thấy ${found.size} file .task trong Download: ${found.map { it.name }}")
            } catch (e: Exception) {
                Timber.e(e, "Lỗi quét Download")
                _taskFilesInDownloads.value = emptyList()
            }
        }
    }

    // ─── Import Gemma từ thư mục Download (trực tiếp) ─────────────────────────

    /**
     * Import file .task từ thư mục Download vào internal storage.
     * Hiển thị thanh tiến độ trong quá trình copy.
     *
     * @param sourceFile  File .task đã tìm thấy trong Download
     */
    fun importGemmaFromDownloads(sourceFile: File) {
        viewModelScope.launch {
            _overallStatus.value = OverallStatus.IMPORTING
            _importProgress.value = 0f
            _importMessage.value = null

            modelLoader.updateModelState(
                ModelType.GEMMA,
                ModelDownloadState.Importing(0f)
            )

            val success = modelLoader.importGemmaFromFile(sourceFile) { progress ->
                _importProgress.value = progress
                modelLoader.updateModelState(
                    ModelType.GEMMA,
                    ModelDownloadState.Importing(progress)
                )
            }

            _importProgress.value = -1f

            if (success) {
                _importMessage.value = "✅ Import thành công: ${sourceFile.name}"
                Timber.i("Gemma import thành công từ Downloads")
            } else {
                _importMessage.value = "❌ Import thất bại. Vui lòng thử lại."
                Timber.e("Gemma import thất bại")
            }

            checkModels()
        }
    }

    // ─── Import Gemma từ URI (file picker / SAF) ──────────────────────────────

    /**
     * Import file .task được chọn qua Storage Access Framework (file picker).
     * Dùng khi người dùng tap "Chọn file..." và browse đến file.
     *
     * @param uri  URI trả về từ ActivityResultContracts.OpenDocument
     */
    fun importGemmaFromUri(uri: Uri) {
        viewModelScope.launch {
            _overallStatus.value = OverallStatus.IMPORTING
            _importProgress.value = 0f
            _importMessage.value = null

            modelLoader.updateModelState(
                ModelType.GEMMA,
                ModelDownloadState.Importing(0f)
            )

            val success = withContext(Dispatchers.IO) {
                try {
                    val dest = modelLoader.gemmaTaskFile
                    dest.parentFile?.mkdirs()

                    // Lấy kích thước file từ ContentResolver để tính tiến độ
                    val totalBytes = context.contentResolver
                        .query(uri, null, null, null, null)
                        ?.use { cursor ->
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (cursor.moveToFirst() && sizeIndex >= 0)
                                cursor.getLong(sizeIndex)
                            else -1L
                        } ?: -1L

                    context.contentResolver.openInputStream(uri)?.buffered(DEFAULT_BUFFER_SIZE * 4)
                        ?.use { input ->
                            dest.outputStream().buffered(DEFAULT_BUFFER_SIZE * 4).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                                var copiedBytes = 0L
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    copiedBytes += read
                                    if (totalBytes > 0) {
                                        val progress = copiedBytes.toFloat() / totalBytes
                                        _importProgress.value = progress
                                        modelLoader.updateModelState(
                                            ModelType.GEMMA,
                                            ModelDownloadState.Importing(progress)
                                        )
                                    }
                                }
                                output.flush()
                            }
                        } ?: run {
                            Timber.e("Không thể mở URI: $uri")
                            return@withContext false
                        }

                    // Lấy tên file gốc để đổi tên nếu cần
                    val originalName = DocumentFile.fromSingleUri(context, uri)?.name ?: ""
                    Timber.i("Đã import từ URI: $originalName → ${dest.name} (${dest.length() / 1024 / 1024} MB)")
                    dest.exists() && dest.length() > 0
                } catch (e: Exception) {
                    Timber.e(e, "Lỗi import từ URI")
                    modelLoader.gemmaTaskFile.takeIf { it.exists() }?.delete()
                    false
                }
            }

            _importProgress.value = -1f

            if (success) {
                _importMessage.value = "✅ Import thành công!"
            } else {
                _importMessage.value = "❌ Import thất bại. Vui lòng thử lại."
            }

            checkModels()
        }
    }

    // ─── Import tất cả model khác từ External Storage ─────────────────────────

    /**
     * Copy các model STT/VAD/TTS/Diarization từ external storage vào internal.
     * Chỉ copy các model khác (không đụng đến Gemma — dùng hàm riêng ở trên).
     */
    fun importOtherModelsFromExternalStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            _overallStatus.value = OverallStatus.IMPORTING

            val externalModelsDir = File(context.getExternalFilesDir(null), "models")
            if (!externalModelsDir.exists()) {
                Timber.w("Thư mục external models không tồn tại: ${externalModelsDir.absolutePath}")
                _overallStatus.value = OverallStatus.MISSING
                return@launch
            }

            var copied = 0
            externalModelsDir.walkTopDown()
                .filter { it.isFile && !it.extension.equals("task", ignoreCase = true) }
                .forEach { file ->
                    val relativePath = file.relativeTo(externalModelsDir).path
                    val destFile = File(modelLoader.modelsDir, relativePath)
                    destFile.parentFile?.mkdirs()
                    file.copyTo(destFile, overwrite = true)
                    copied++
                    Timber.d("Đã copy: $relativePath")
                }

            Timber.i("Đã import $copied file model từ external storage")
            checkModels()
        }
    }

    // ─── Xóa Gemma đã import ──────────────────────────────────────────────────

    fun deleteImportedGemma() {
        viewModelScope.launch(Dispatchers.IO) {
            val f = modelLoader.gemmaTaskFile
            if (f.exists()) {
                f.delete()
                Timber.i("Đã xóa Gemma model khỏi internal storage")
            }
            checkModels()
            scanDownloadsForTaskFiles()
        }
    }

    fun clearImportMessage() {
        _importMessage.value = null
    }

    // ─── Getters hiển thị trên UI ─────────────────────────────────────────────

    fun getExternalModelPath(): String =
        "${context.getExternalFilesDir(null)?.absolutePath}/models/"

    fun getInternalModelPath(): String = modelLoader.modelsDir.absolutePath

    fun isGemmaAlreadyImported(): Boolean =
        modelLoader.gemmaTaskFile.exists() && modelLoader.gemmaTaskFile.length() > 0
}

enum class OverallStatus {
    CHECKING, READY, MISSING, IMPORTING, ERROR
}
