package com.livelens.translator

import android.graphics.Bitmap
import com.livelens.translator.util.BitmapUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class BitmapUtilsTest {

    @Test
    fun `resizeForInference returns original bitmap when within bounds`() {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val result = BitmapUtils.resizeForInference(bitmap, maxSide = 1024)
        assertSame(bitmap, result)
    }

    @Test
    fun `resizeForInference resizes wide bitmap correctly`() {
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val result = BitmapUtils.resizeForInference(bitmap, maxSide = 1024)
        assertNotSame(bitmap, result)
        assertEquals(1024, result.width)
        assertEquals(512, result.height)
    }

    @Test
    fun `resizeForInference resizes tall bitmap correctly`() {
        val bitmap = Bitmap.createBitmap(512, 2048, Bitmap.Config.ARGB_8888)
        val result = BitmapUtils.resizeForInference(bitmap, maxSide = 1024)
        assertNotSame(bitmap, result)
        assertEquals(256, result.width)
        assertEquals(1024, result.height)
    }

    @Test
    fun `resizeForInference handles square bitmap`() {
        val bitmap = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        val result = BitmapUtils.resizeForInference(bitmap, maxSide = 1024)
        assertEquals(1024, result.width)
        assertEquals(1024, result.height)
    }

    @Test
    fun `ensureArgb8888 returns same bitmap if already correct config`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = BitmapUtils.ensureArgb8888(bitmap)
        assertSame(bitmap, result)
    }
}
