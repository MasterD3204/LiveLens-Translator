package com.livelens.translator.model

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
class ModelLoader(
    private val context: Context
) {
    val modelsDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }

    // --- STT (Zipformer) model files ---
    val sttEncoderFile: File get() = File(modelsDir, "stt/encoder.onnx")
    val sttDecoderFile: File get() = File(modelsDir, "stt/decoder.onnx")
    val sttJoinerFile: File get() = File(modelsDir, "stt/joiner.onnx")
    val sttTokensFile: File get() = File(modelsDir, "stt/tokens.txt")

    // --- VAD (Silero) model file ---
    val vadModelFile: File get() = File(modelsDir, "vad/silero_vad.onnx")

    // --- TTS (Piper Vietnamese) model files ---
    val ttsModelFile: File get() = File(modelsDir, "tts/vi-voice.onnx")
    val ttsModelConfigFile: File get() = File(modelsDir, "tts/vi-voice.onnx.json")

    // --- Speaker Diarization model files ---
    val diarizationSegmentFile: File get() = File(modelsDir, "diarization/seg-model.onnx")
    val diarizationEmbeddingFile: File get() = File(modelsDir, "diarization/wespeaker.onnx")

    // --- Gemma Translate model file (internal destination sau khi import) ---
    val gemmaInternalDir: File get() = File(modelsDir, "gemma").also { it.mkdirs() }
    val gemmaTaskFile: File get() = File(gemmaInternalDir, "gemma-translate-en-vi.task")

    // ─── Gemma: tìm file .task trong các vị trí có thể có ──────────────────

    /**
     * Trả về file .task đang thực sự dùng được:
     *  - Ưu tiên internal storage nếu đã import rồi
     *  - Nếu chưa có, tìm trong Download và external
     *  - Trả về null nếu không tìm thấy ở đâu cả
     */
    fun resolveGemmaTaskFile(): File? {
        // 1. Đã có trong internal storage → dùng luôn
        if (gemmaTaskFile.exists() && gemmaTaskFile.length() > 0) {
            return gemmaTaskFile
        }
        // 2. Tìm trong Download
        findTaskFileInDownloads()?.let { return it }
        // 3. Tìm trong external files dir của app
        findTaskFileInExternalDir()?.let { return it }
        return null
    }

    /**
     * Quét thư mục Download (/sdcard/Download) tìm file *.task.
     * Trả về file đầu tiên tìm thấy, hoặc null.
     */
    fun findTaskFileInDownloads(): File? {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadDir.listFiles { f -> f.isFile && f.extension.equals("task", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }   // lấy file mới nhất nếu có nhiều
                .also { if (it != null) Timber.i("Tìm thấy .task trong Download: ${it.name}") }
        } catch (e: Exception) {
            Timber.e(e, "Không thể quét thư mục Download")
            null
        }
    }

    /**
     * Quét external files dir của app tìm file *.task.
     */
    private fun findTaskFileInExternalDir(): File? {
        return try {
            val extDir = context.getExternalFilesDir(null) ?: return null
            val gemmaExtDir = File(extDir, "models/gemma")
            gemmaExtDir.listFiles { f -> f.isFile && f.extension.equals("task", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
                .also { if (it != null) Timber.i("Tìm thấy .task trong external dir: ${it.name}") }
        } catch (e: Exception) {
            Timber.e(e, "Không thể quét external dir")
            null
        }
    }

    /**
     * Copy một file .task từ nguồn bất kỳ vào internal storage.
     * Hiển thị tiến độ qua callback [onProgress] (0f..1f).
     * Sau khi copy xong, [gemmaTaskFile] sẽ trỏ đúng vào file đó.
     *
     * @param sourceFile  File nguồn (từ Download hoặc external)
     * @param onProgress  Callback tiến độ, gọi trên IO thread
     * @return true nếu copy thành công
     */
    suspend fun importGemmaFromFile(
        sourceFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val dest = gemmaTaskFile
            dest.parentFile?.mkdirs()

            val totalBytes = sourceFile.length().coerceAtLeast(1L)
            var copiedBytes = 0L

            sourceFile.inputStream().buffered(DEFAULT_BUFFER_SIZE * 4).use { input ->
                dest.outputStream().buffered(DEFAULT_BUFFER_SIZE * 4).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copiedBytes += read
                        onProgress(copiedBytes.toFloat() / totalBytes)
                    }
                    output.flush()
                }
            }

            val ok = dest.exists() && dest.length() == sourceFile.length()
            if (ok) {
                Timber.i("Import Gemma thành công: ${dest.absolutePath} (${dest.length() / 1024 / 1024} MB)")
            } else {
                Timber.e("Import Gemma thất bại: kích thước không khớp")
                dest.delete()
            }
            ok
        } catch (e: Exception) {
            Timber.e(e, "Lỗi khi import Gemma model")
            gemmaTaskFile.takeIf { it.exists() }?.delete()
            false
        }
    }

    // --- Download progress state ---
    private val _downloadState = MutableStateFlow<Map<ModelType, ModelDownloadState>>(
        ModelType.values().associateWith { ModelDownloadState.Unknown }
    )
    val downloadState = _downloadState.asStateFlow()

    /** Kiểm tra tất cả model và cập nhật trạng thái. */
    suspend fun checkAllModels() = withContext(Dispatchers.IO) {
        val states = mutableMapOf<ModelType, ModelDownloadState>()
        states[ModelType.STT]         = if (isSttReady()) ModelDownloadState.Ready else ModelDownloadState.Missing
        states[ModelType.VAD]         = if (isVadReady()) ModelDownloadState.Ready else ModelDownloadState.Missing
        states[ModelType.TTS]         = if (isTtsReady()) ModelDownloadState.Ready else ModelDownloadState.Missing
        states[ModelType.DIARIZATION] = if (isDiarizationReady()) ModelDownloadState.Ready else ModelDownloadState.Missing

        // Gemma: kiểm tra cả internal lẫn Download
        val gemmaState = when {
            gemmaTaskFile.exists() && gemmaTaskFile.length() > 0 -> ModelDownloadState.Ready
            findTaskFileInDownloads() != null                    -> ModelDownloadState.FoundInDownloads
            else                                                 -> ModelDownloadState.Missing
        }
        states[ModelType.GEMMA] = gemmaState

        _downloadState.value = states
    }

    fun isSttReady(): Boolean =
        sttEncoderFile.exists() && sttDecoderFile.exists() &&
                sttJoinerFile.exists() && sttTokensFile.exists()

    fun isVadReady(): Boolean = vadModelFile.exists()

    fun isTtsReady(): Boolean = ttsModelFile.exists() && ttsModelConfigFile.exists()

    fun isDiarizationReady(): Boolean =
        diarizationSegmentFile.exists() && diarizationEmbeddingFile.exists()

    /**
     * Gemma sẵn sàng nếu:
     *  - Đã import vào internal storage, HOẶC
     *  - Tồn tại trong Download (dùng trực tiếp đường dẫn)
     */
    fun isGemmaReady(): Boolean = resolveGemmaTaskFile() != null

    /** True nếu đủ model tối thiểu để bắt đầu dịch. */
    fun isCoreReady(): Boolean = isSttReady() && isVadReady() && isGemmaReady()

    /** Cập nhật trạng thái của một model cụ thể. */
    fun updateModelState(type: ModelType, state: ModelDownloadState) {
        _downloadState.value = _downloadState.value.toMutableMap().apply {
            this[type] = state
        }
    }

    /** Tổng dung lượng các file model (bytes). */
    suspend fun getTotalModelsSize(): Long = withContext(Dispatchers.IO) {
        modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Thông tin trạng thái model để hiển thị trên Settings. */
    fun getModelStatusInfo(): ModelStatusInfo {
        val resolvedGemma = resolveGemmaTaskFile()
        return ModelStatusInfo(
            sttReady          = isSttReady(),
            vadReady          = isVadReady(),
            ttsReady          = isTtsReady(),
            diarizationReady  = isDiarizationReady(),
            gemmaReady        = resolvedGemma != null,
            gemmaInDownloads  = !gemmaTaskFile.exists() && findTaskFileInDownloads() != null,
            gemmaFileName     = resolvedGemma?.name ?: "",
            sttSizeMb         = sttEncoderFile.length() / 1024 / 1024,
            gemmaSizeMb       = (resolvedGemma?.length() ?: 0L) / 1024 / 1024
        )
    }
}

enum class ModelType {
    STT, VAD, TTS, DIARIZATION, GEMMA
}

sealed class ModelDownloadState {
    object Unknown          : ModelDownloadState()
    object Missing          : ModelDownloadState()
    object Ready            : ModelDownloadState()
    /** Tìm thấy file .task trong thư mục Download, chưa import vào internal */
    object FoundInDownloads : ModelDownloadState()
    data class Importing(val progress: Float) : ModelDownloadState()  // 0f..1f
    object Failed           : ModelDownloadState()
}

data class ModelStatusInfo(
    val sttReady: Boolean,
    val vadReady: Boolean,
    val ttsReady: Boolean,
    val diarizationReady: Boolean,
    val gemmaReady: Boolean,
    /** true nếu file .task nằm trong Download (chưa import) */
    val gemmaInDownloads: Boolean = false,
    /** Tên file .task đang dùng */
    val gemmaFileName: String = "",
    val sttSizeMb: Long,
    val gemmaSizeMb: Long
)