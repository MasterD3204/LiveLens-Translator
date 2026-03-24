#!/usr/bin/env bash
# =============================================================================
# download-sherpa-onnx.sh
#
# Tải AAR của sherpa-onnx từ GitHub Releases và đặt vào app/libs/.
# Chạy script này MỘT LẦN trước khi build lần đầu.
#
# Cách dùng:
#   chmod +x scripts/download-sherpa-onnx.sh
#   ./scripts/download-sherpa-onnx.sh
# =============================================================================

set -e

SHERPA_VERSION="1.12.32"
AAR_NAME="sherpa-onnx-${SHERPA_VERSION}.aar"
DOWNLOAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${AAR_NAME}"
DEST_DIR="$(dirname "$0")/../app/libs"

echo "============================================"
echo " sherpa-onnx AAR Downloader"
echo " Version : ${SHERPA_VERSION}"
echo " Dest    : ${DEST_DIR}/${AAR_NAME}"
echo "============================================"

# Tạo thư mục nếu chưa có
mkdir -p "${DEST_DIR}"

# Kiểm tra nếu đã tồn tại
if [ -f "${DEST_DIR}/${AAR_NAME}" ]; then
    echo "[OK] ${AAR_NAME} đã tồn tại, bỏ qua download."
    exit 0
fi

# Download
echo "[>>] Đang tải ${AAR_NAME} (~30 MB)..."
if command -v curl &> /dev/null; then
    curl -L --progress-bar "${DOWNLOAD_URL}" -o "${DEST_DIR}/${AAR_NAME}"
elif command -v wget &> /dev/null; then
    wget --show-progress "${DOWNLOAD_URL}" -O "${DEST_DIR}/${AAR_NAME}"
else
    echo "[ERR] Cần cài curl hoặc wget để chạy script này."
    exit 1
fi

echo "[OK] Download xong: ${DEST_DIR}/${AAR_NAME}"
echo ""
echo "Bây giờ có thể build project bình thường:"
echo "  ./gradlew assembleDebug"
