package com.livelens.translator.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Bitmap utilities for image processing and format conversion.
 */
object BitmapUtils {

    /**
     * Convert an [ImageProxy] (from CameraX) to a [Bitmap].
     * Handles JPEG encoded images from ImageCapture.
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Apply rotation from ImageProxy
        val rotation = image.imageInfo.rotationDegrees
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        return bitmap
    }

    /**
     * Resize a bitmap so that its longest side is at most [maxSide] pixels.
     * Maintains aspect ratio. Returns the original if already within bounds.
     */
    fun resizeForInference(bitmap: Bitmap, maxSide: Int = 1024): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSide && h <= maxSide) return bitmap

        val scale = maxSide.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Save a bitmap to a temporary cache file and return its URI path.
     * Used for sharing images.
     */
    fun saveBitmapToCache(bitmap: Bitmap, cacheDir: File, filename: String = "temp_image.jpg"): File {
        val imageDir = File(cacheDir, "images").also { it.mkdirs() }
        val file = File(imageDir, filename)
        try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save bitmap to cache")
        }
        return file
    }

    /**
     * Convert a Bitmap to a JPEG byte array.
     * @param quality JPEG compression quality (0-100)
     */
    fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }

    /**
     * Ensure a bitmap is in ARGB_8888 config for processing.
     */
    fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        return if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
    }
}
