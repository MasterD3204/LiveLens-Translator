package com.livelens.translator.model

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
     *  - Kiểm tra ZIP magic bytes trước khi trả về (MediaPipe .task = ZIP)
     */
    fun resolveGemmaTaskFile(): File? {
        // 1. Đã có trong internal storage → kiểm tra tính hợp lệ rồi dùng
        if (gemmaTaskFile.exists() && gemmaTaskFile.length() > 0) {
            logFileInfo("internal", gemmaTaskFile)
            if (isValidZipFile(gemmaTaskFile)) return gemmaTaskFile
            else {
                Timber.e("File internal bị CORRUPT (không phải ZIP hợp lệ): ${gemmaTaskFile.absolutePath}")
                Timber.e("→ Xóa file hỏng và thử tìm nguồn khác...")
                // KHÔNG xóa tự động — để user quyết định, chỉ log
            }
        }
        // 2. Tìm trong Download
        findTaskFileInDownloads()?.let { f ->
            logFileInfo("Downloads", f)
            if (isValidZipFile(f)) return f
            else Timber.e("File trong Downloads bị CORRUPT: ${f.name}")
        }
        // 3. Tìm trong external files dir của app
        findTaskFileInExternalDir()?.let { f ->
            logFileInfo("external dir", f)
            if (isValidZipFile(f)) return f
            else Timber.e("File trong external dir bị CORRUPT: ${f.name}")
        }
        Timber.w("resolveGemmaTaskFile() — không tìm thấy file .task hợp lệ")
        return null
    }

    /**
     * Kiểm tra file có đúng format ZIP không (magic bytes: PK\x03\x04).
     * MediaPipe .task file là ZIP archive — nếu không phải ZIP → corrupt.
     */
    fun isValidZipFile(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < 4) {
                Timber.w("isValidZipFile(${file.name}): file không tồn tại hoặc quá nhỏ (${file.length()} bytes)")
                return false
            }
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            // ZIP magic: 50 4B 03 04
            val isZip = header[0] == 0x50.toByte() &&
                        header[1] == 0x4B.toByte() &&
                        header[2] == 0x03.toByte() &&
                        header[3] == 0x04.toByte()
            if (isZip) {
                Timber.d("isValidZipFile(${file.name}): ✓ ZIP hợp lệ")
            } else {
                Timber.e("isValidZipFile(${file.name}): ✗ KHÔNG phải ZIP! Header = ${header.joinToString(" ") { "0x%02X".format(it) }}")
                Timber.e("  → File có thể bị: tải dở, sai định dạng, hoặc là HTML error page")
                // Log 100 bytes đầu để debug thêm
                try {
                    val preview = file.readBytes().take(100).toByteArray()
                    Timber.e("  → 100 bytes đầu: ${String(preview, Charsets.UTF_8).replace("\n", "\\n").take(200)}")
                } catch (_: Exception) {}
            }
            isZip
        } catch (e: Exception) {
            Timber.e(e, "isValidZipFile(${file.name}): exception khi đọc")
            false
        }
    }

    private fun logFileInfo(location: String, file: File) {
        val sizeMb = file.length() / 1024.0 / 1024.0
        val lastModified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(file.lastModified()))
        Timber.i("📁 Task file [$location]: ${file.name}")
        Timber.i("   path: ${file.absolutePath}")
        Timber.i("   size: ${"%.2f".format(sizeMb)} MB (${file.length()} bytes)")
        Timber.i("   modified: $lastModified")
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
     * Sau khi copy xong validate ZIP magic bytes.
     *
     * @param sourceFile  File nguồn (từ Download hoặc external)
     * @param onProgress  Callback tiến độ, gọi trên IO thread
     * @return true nếu copy thành công và file là ZIP hợp lệ
     */
    suspend fun importGemmaFromFile(
        sourceFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        Timber.i("importGemmaFromFile(): source=${sourceFile.absolutePath}")
        logFileInfo("source", sourceFile)

        // Validate nguồn trước khi copy
        if (!isValidZipFile(sourceFile)) {
            Timber.e("importGemmaFromFile(): file nguồn KHÔNG phải ZIP hợp lệ — hủy import")
            return@withContext false
        }

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

            val sizeMatch = dest.exists() && dest.length() == sourceFile.length()
            if (!sizeMatch) {
                Timber.e("importGemmaFromFile(): kích thước không khớp (dest=${dest.length()}, src=${sourceFile.length()})")
                dest.delete()
                return@withContext false
            }

            // Validate lại sau khi copy
            if (!isValidZipFile(dest)) {
                Timber.e("importGemmaFromFile(): file sau khi copy BỊ CORRUPT — xóa")
                dest.delete()
                return@withContext false
            }

            Timber.i("importGemmaFromFile(): thành công ✓ ${dest.absolutePath} (${dest.length() / 1024 / 1024} MB)")
            true
        } catch (e: Exception) {
            Timber.e(e, "importGemmaFromFile(): exception")
            gemmaTaskFile.takeIf { it.exists() }?.delete()
            false
        }
    }

    /**
     * Xóa file Gemma internal bị corrupt để cho phép re-import.
     */
    fun deleteCorruptGemmaFile(): Boolean {
        return if (gemmaTaskFile.exists()) {
            val deleted = gemmaTaskFile.delete()
            Timber.i("deleteCorruptGemmaFile(): ${if (deleted) "đã xóa ✓" else "xóa thất bại ✗"} — ${gemmaTaskFile.absolutePath}")
            deleted
        } else {
            Timber.d("deleteCorruptGemmaFile(): file không tồn tại, không cần xóa")
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

        // Gemma: kiểm tra cả internal lẫn Download, validate ZIP integrity
        val gemmaState = when {
            gemmaTaskFile.exists() && gemmaTaskFile.length() > 0 -> {
                if (isValidZipFile(gemmaTaskFile)) {
                    Timber.i("checkAllModels(): Gemma internal = READY ✓ (${gemmaTaskFile.length() / 1024 / 1024} MB)")
                    ModelDownloadState.Ready
                } else {
                    Timber.e("checkAllModels(): Gemma internal file BỊ CORRUPT! Cần re-import.")
                    ModelDownloadState.Corrupt
                }
            }
            findTaskFileInDownloads()?.let { isValidZipFile(it) } == true -> {
                Timber.i("checkAllModels(): Gemma tìm thấy trong Downloads (ZIP hợp lệ)")
                ModelDownloadState.FoundInDownloads
            }
            findTaskFileInDownloads() != null -> {
                Timber.e("checkAllModels(): Gemma trong Downloads nhưng BỊ CORRUPT!")
                ModelDownloadState.Corrupt
            }
            else -> ModelDownloadState.Missing
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
        val gemmaExistsButCorrupt = gemmaTaskFile.exists() && gemmaTaskFile.length() > 0 && !isValidZipFile(gemmaTaskFile)
        return ModelStatusInfo(
            sttReady          = isSttReady(),
            vadReady          = isVadReady(),
            ttsReady          = isTtsReady(),
            diarizationReady  = isDiarizationReady(),
            gemmaReady        = resolvedGemma != null,
            gemmaCorrupt      = gemmaExistsButCorrupt,
            gemmaInDownloads  = !gemmaTaskFile.exists() && findTaskFileInDownloads() != null,
            gemmaFileName     = resolvedGemma?.name ?: (if (gemmaExistsButCorrupt) "${gemmaTaskFile.name} [CORRUPT]" else ""),
            sttSizeMb         = sttEncoderFile.length() / 1024 / 1024,
            gemmaSizeMb       = (resolvedGemma?.length() ?: gemmaTaskFile.takeIf { it.exists() }?.length() ?: 0L) / 1024 / 1024
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
    /** File tồn tại nhưng bị corrupt (không phải ZIP hợp lệ) */
    object Corrupt          : ModelDownloadState()
}

data class ModelStatusInfo(
    val sttReady: Boolean,
    val vadReady: Boolean,
    val ttsReady: Boolean,
    val diarizationReady: Boolean,
    val gemmaReady: Boolean,
    /** true nếu file .task tồn tại nhưng bị corrupt (không phải ZIP hợp lệ) */
    val gemmaCorrupt: Boolean = false,
    /** true nếu file .task nằm trong Download (chưa import) */
    val gemmaInDownloads: Boolean = false,
    /** Tên file .task đang dùng */
    val gemmaFileName: String = "",
    val sttSizeMb: Long,
    val gemmaSizeMb: Long
)