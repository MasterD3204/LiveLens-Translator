package com.livelens.translator.ui.modelsetup

import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.livelens.translator.model.ModelDownloadState
import com.livelens.translator.model.ModelType
import java.io.File
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSetupScreen(
    onNavigateUp: () -> Unit,
    viewModel: ModelSetupViewModel = hiltViewModel()
) {
    val progress      by viewModel.downloadProgress.collectAsState()
    val overallStatus by viewModel.overallStatus.collectAsState()
    val taskFiles     by viewModel.taskFilesInDownloads.collectAsState()
    val importProg    by viewModel.importProgress.collectAsState()
    val importMsg     by viewModel.importMessage.collectAsState()

    val snackbarHost = remember { SnackbarHostState() }
    var showFilePicker by remember { mutableStateOf(false) }
    var confirmImportFile by remember { mutableStateOf<File?>(null) }

    // File picker launcher (SAF — dùng khi muốn browse thủ công)
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importGemmaFromUri(uri)
    }

    // Hiển thị snackbar khi có kết quả import
    LaunchedEffect(importMsg) {
        if (importMsg != null) {
            snackbarHost.showSnackbar(importMsg!!)
            viewModel.clearImportMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt AI Models") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.checkModels()
                        viewModel.scanDownloadsForTaskFiles()
                    }) {
                        Icon(Icons.Default.Refresh, "Làm mới")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Trạng thái tổng quan ─────────────────────────────────────────
            item {
                OverallStatusCard(overallStatus)
            }

            // ── Bảng trạng thái model ────────────────────────────────────────
            item {
                Text(
                    "Trạng thái Models",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                ModelStatusTable(progress)
            }

            // ══ GEMMA — Khu vực đặc biệt ════════════════════════════════════
            item {
                Text(
                    "Gemma Translate Model",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                GemmaImportCard(
                    gemmaState        = progress[ModelType.GEMMA] ?: ModelDownloadState.Unknown,
                    importProgress    = importProg,
                    taskFilesFound    = taskFiles,
                    isAlreadyImported = viewModel.isGemmaAlreadyImported(),
                    onImportFromList  = { file -> confirmImportFile = file },
                    onBrowseFile      = { filePicker.launch(arrayOf("*/*")) },
                    onScanAgain       = { viewModel.scanDownloadsForTaskFiles() },
                    onDeleteImported  = { viewModel.deleteImportedGemma() }
                )
            }

            // ── Các model khác từ External Storage ──────────────────────────
            item {
                Text(
                    "Các model khác (STT, VAD, TTS...)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            item {
                OtherModelsCard(
                    externalPath    = viewModel.getExternalModelPath(),
                    onImportOthers  = { viewModel.importOtherModelsFromExternalStorage() },
                    isImporting     = overallStatus == OverallStatus.IMPORTING
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // ── Dialog xác nhận import file đã chọn ─────────────────────────────────
    confirmImportFile?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmImportFile = null },
            icon = { Icon(Icons.Default.Download, null) },
            title = { Text("Xác nhận import") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Import file Gemma sau vào bộ nhớ ứng dụng?")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Kích thước: ${formatFileSize(file.length())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⚠️ Quá trình copy có thể mất vài phút tùy kích thước file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.importGemmaFromDownloads(file)
                    confirmImportFile = null
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImportFile = null }) {
                    Text("Hủy")
                }
            }
        )
    }
}

// ─── Card trạng thái tổng quan ──────────────────────────────────────────────

@Composable
private fun OverallStatusCard(status: OverallStatus) {
    val (bgColor, emoji, message) = when (status) {
        OverallStatus.READY     -> Triple(MaterialTheme.colorScheme.primaryContainer,   "✅", "Tất cả model sẵn sàng")
        OverallStatus.MISSING   -> Triple(MaterialTheme.colorScheme.errorContainer,     "⚠️", "Thiếu model — cần cài đặt")
        OverallStatus.IMPORTING -> Triple(MaterialTheme.colorScheme.tertiaryContainer,  "⏳", "Đang import model…")
        OverallStatus.CHECKING  -> Triple(MaterialTheme.colorScheme.surfaceVariant,     "🔍", "Đang kiểm tra…")
        OverallStatus.ERROR     -> Triple(MaterialTheme.colorScheme.errorContainer,     "❌", "Có lỗi xảy ra")
    }
    Card(colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(emoji, fontSize = 24.sp)
            Column {
                Text(message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "LiveLens chạy 100% on-device, không cần internet.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ─── Bảng trạng thái từng model ─────────────────────────────────────────────

@Composable
private fun ModelStatusTable(progress: Map<ModelType, ModelDownloadState>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val rows = listOf(
                Triple(ModelType.STT,         "STT (Zipformer 30M)",      "~90 MB"),
                Triple(ModelType.VAD,         "VAD (Silero)",              "~2 MB"),
                Triple(ModelType.TTS,         "TTS (Piper Vietnamese)",    "~50 MB"),
                Triple(ModelType.DIARIZATION, "Speaker Diarization",       "~20 MB"),
                Triple(ModelType.GEMMA,       "Gemma Translate (EN→VI)",   "~1.5 GB")
            )
            rows.forEachIndexed { index, (type, name, size) ->
                val state = progress[type] ?: ModelDownloadState.Unknown
                ModelStatusRowItem(name = name, size = size, state = state)
                if (index < rows.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelStatusRowItem(name: String, size: String, state: ModelDownloadState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(size, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state is ModelDownloadState.Importing) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (state) {
            is ModelDownloadState.Ready ->
                Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
            is ModelDownloadState.FoundInDownloads ->
                Icon(Icons.Default.FolderOpen, null,
                    tint = Color(0xFFFF9800), modifier = Modifier.size(20.dp))
            is ModelDownloadState.Missing ->
                Icon(Icons.Default.Error, null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            is ModelDownloadState.Importing ->
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            is ModelDownloadState.Unknown ->
                Icon(Icons.Default.HourglassEmpty, null,
                    tint = Color.Gray, modifier = Modifier.size(20.dp))
            is ModelDownloadState.Failed ->
                Icon(Icons.Default.Error, null,
                    tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
    }
}

// ─── Card riêng cho Gemma ────────────────────────────────────────────────────

@Composable
private fun GemmaImportCard(
    gemmaState: ModelDownloadState,
    importProgress: Float,
    taskFilesFound: List<File>,
    isAlreadyImported: Boolean,
    onImportFromList: (File) -> Unit,
    onBrowseFile: () -> Unit,
    onScanAgain: () -> Unit,
    onDeleteImported: () -> Unit
) {
    val isImporting = importProgress >= 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Tiêu đề + trạng thái ──────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🤖", fontSize = 20.sp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isAlreadyImported               -> "Đã import vào ứng dụng ✅"
                            gemmaState is ModelDownloadState.FoundInDownloads -> "Tìm thấy trong Download 📂"
                            else                            -> "Chưa có model Gemma"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (isAlreadyImported) {
                        Text(
                            "Gemma sẵn sàng dùng",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }

            // ── Thanh tiến độ import ──────────────────────────────────────────
            AnimatedVisibility(visible = isImporting, enter = fadeIn(), exit = fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Đang copy…", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${(importProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth(),
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        "Vui lòng không thoát ứng dụng trong khi import.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── Danh sách file tìm thấy trong Download ────────────────────────
            if (!isImporting && !isAlreadyImported) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "File .task trong thư mục Download:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = onScanAgain,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Quét lại", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (taskFilesFound.isEmpty()) {
                    // Không tìm thấy file nào
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Không tìm thấy file .task trong Download.\nHãy tải file Gemma .task về máy rồi quét lại.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Hiển thị danh sách file tìm thấy
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        taskFilesFound.forEach { file ->
                            TaskFileItem(
                                file = file,
                                onImport = { onImportFromList(file) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Nút browse thủ công
                OutlinedButton(
                    onClick = onBrowseFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Chọn file thủ công (Browse…)")
                }
            }

            // ── Nút xóa (chỉ hiện khi đã import) ─────────────────────────────
            if (isAlreadyImported && !isImporting) {
                HorizontalDivider()
                TextButton(
                    onClick = onDeleteImported,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Xóa model đã import (giải phóng bộ nhớ)")
                }
            }
        }
    }
}

// ─── Card cho model STT/VAD/TTS ─────────────────────────────────────────────

@Composable
private fun OtherModelsCard(
    externalPath: String,
    onImportOthers: () -> Unit,
    isImporting: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Đặt file vào thư mục này trên thiết bị:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = externalPath,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider()

            Text(
                text = """
Cấu trúc thư mục:
models/
├── stt/encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt
├── vad/silero_vad.onnx
├── tts/vi-voice.onnx, vi-voice.onnx.json
└── diarization/seg-model.onnx, wespeaker.onnx
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onImportOthers,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isImporting
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Import STT / VAD / TTS / Diarization")
            }
        }
    }
}

// ─── Item một file .task tìm thấy ───────────────────────────────────────────

@Composable
private fun TaskFileItem(file: File, onImport: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📦", fontSize = 20.sp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatFileSize(file.length()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(
            onClick = onImport,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("Import", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ─── Helpers ────────────────────────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String {
    val df = DecimalFormat("#.##")
    return when {
        bytes >= 1024L * 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
        bytes >= 1024L * 1024        -> "${df.format(bytes.toDouble() / (1024 * 1024))} MB"
        bytes >= 1024L               -> "${df.format(bytes.toDouble() / 1024)} KB"
        else                         -> "$bytes B"
    }
}
