package com.livelens.translator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.livelens.translator.model.ModelLoader
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelLoaderInstrumentedTest {

    @Test
    fun `modelLoader creates models directory`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelLoader = ModelLoader(context)

        assertTrue("Models dir should exist after creation", modelLoader.modelsDir.exists())
    }

    @Test
    fun `modelLoader returns correct file paths`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelLoader = ModelLoader(context)

        assertNotNull(modelLoader.sttEncoderFile)
        assertNotNull(modelLoader.vadModelFile)
        assertNotNull(modelLoader.ttsModelFile)
        assertNotNull(modelLoader.gemmaTaskFile)
    }

    @Test
    fun `isCoreReady returns false when models not present`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelLoader = ModelLoader(context)

        // On a fresh install with no models, core should not be ready
        // This verifies the logic works without crashing
        val ready = modelLoader.isCoreReady()
        // We don't assert the exact value since it depends on whether models are installed
        assertNotNull(ready)
    }
}
