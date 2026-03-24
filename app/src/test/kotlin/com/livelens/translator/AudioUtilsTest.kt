package com.livelens.translator

import com.livelens.translator.util.AudioUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioUtilsTest {

    @Test
    fun `shortToFloat converts max short value to 1f`() {
        val shorts = shortArrayOf(32767)
        val floats = AudioUtils.shortToFloat(shorts)
        assertEquals(1.0f, floats[0], 0.001f)
    }

    @Test
    fun `shortToFloat converts min short value to approximately -1f`() {
        val shorts = shortArrayOf(Short.MIN_VALUE)
        val floats = AudioUtils.shortToFloat(shorts)
        assertTrue(abs(floats[0] + 1.0f) < 0.01f)
    }

    @Test
    fun `floatToShort converts 1f to max short value`() {
        val floats = floatArrayOf(1.0f)
        val shorts = AudioUtils.floatToShort(floats)
        assertEquals(32767, shorts[0].toInt())
    }

    @Test
    fun `shortToFloat and floatToShort are inverse operations`() {
        val original = shortArrayOf(1000, -5000, 0, 32767)
        val converted = AudioUtils.floatToShort(AudioUtils.shortToFloat(original))
        original.forEachIndexed { i, v ->
            assertTrue("Index $i: expected ~$v got ${converted[i]}", abs(v - converted[i]) <= 1)
        }
    }

    @Test
    fun `computeRms returns 0 for silent audio`() {
        val silence = FloatArray(1000) { 0f }
        assertEquals(0f, AudioUtils.computeRms(silence), 0.001f)
    }

    @Test
    fun `computeRms returns expected value for full-scale sine wave`() {
        val sineWave = FloatArray(1000) { i ->
            kotlin.math.sin(2 * Math.PI * i / 100).toFloat()
        }
        val rms = AudioUtils.computeRms(sineWave)
        // RMS of sine wave ≈ 0.707
        assertTrue("RMS $rms should be ~0.707", abs(rms - 0.707f) < 0.05f)
    }

    @Test
    fun `computeRms returns 0 for empty array`() {
        assertEquals(0f, AudioUtils.computeRms(floatArrayOf()), 0.001f)
    }
}
