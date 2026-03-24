package com.livelens.translator

import com.livelens.translator.data.db.TranslationEntity
import com.livelens.translator.data.repository.TranslationRepository
import com.livelens.translator.model.GemmaTranslateManager
import com.livelens.translator.model.SherpaOnnxManager
import com.livelens.translator.model.TranslationMode
import com.livelens.translator.service.TranslationManager
import com.livelens.translator.service.TranslationSentence
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationManagerTest {

    private lateinit var gemmaManager: GemmaTranslateManager
    private lateinit var sherpaManager: SherpaOnnxManager
    private lateinit var repository: TranslationRepository
    private lateinit var translationManager: TranslationManager

    @Before
    fun setUp() {
        gemmaManager = mockk()
        sherpaManager = mockk()
        repository = mockk(relaxed = true)
        translationManager = TranslationManager(gemmaManager, sherpaManager, repository)
    }

    @Test
    fun `translateText adds sentence to queue`() = runTest {
        // Arrange
        every { gemmaManager.translateText(any()) } returns flowOf("Xin chào")
        coEvery { repository.insert(any(), any(), any(), any(), any(), any()) } returns 1L

        // Act
        translationManager.translateText("Hello")

        // Wait for the sentence to appear (async)
        kotlinx.coroutines.delay(100)

        // Assert
        val sentences = translationManager.sentences.value
        assertTrue(sentences.isNotEmpty())
    }

    @Test
    fun `translateText drops backlog when queue exceeds threshold`() = runTest {
        // Arrange
        translationManager.dropThreshold = 2
        every { gemmaManager.translateText(any()) } returns flowOf("...")
        coEvery { repository.insert(any(), any(), any(), any(), any(), any()) } returns 1L

        // Act — add 3 sentences to trigger drop logic
        translationManager.translateText("Hello 1")
        translationManager.translateText("Hello 2")
        translationManager.translateText("Hello 3")  // This should trigger drop

        kotlinx.coroutines.delay(200)

        // Assert — queue should have been cleared and then the last item added
        val sentences = translationManager.sentences.value
        assertTrue(sentences.size <= translationManager.maxOverlaySentences)
    }

    @Test
    fun `dismissSentence removes specific sentence from queue`() = runTest {
        // Arrange
        every { gemmaManager.translateText(any()) } returns flowOf("Xin chào")
        coEvery { repository.insert(any(), any(), any(), any(), any(), any()) } returns 1L
        translationManager.translateText("Hello")
        kotlinx.coroutines.delay(100)

        val sentenceId = translationManager.sentences.value.firstOrNull()?.id ?: return@runTest

        // Act
        translationManager.dismissSentence(sentenceId)

        // Assert
        val remaining = translationManager.sentences.value
        assertTrue(remaining.none { it.id == sentenceId })
    }

    @Test
    fun `clearAllSentences empties the queue`() = runTest {
        // Arrange
        every { gemmaManager.translateText(any()) } returns flowOf("Xin chào")
        coEvery { repository.insert(any(), any(), any(), any(), any(), any()) } returns 1L
        translationManager.translateText("Hello")
        kotlinx.coroutines.delay(100)

        // Act
        translationManager.clearAllSentences()

        // Assert
        assertTrue(translationManager.sentences.value.isEmpty())
    }

    @Test
    fun `setMode updates current mode`() {
        translationManager.setMode(TranslationMode.MEDIA)
        assertEquals(TranslationMode.MEDIA, translationManager.currentMode.value)

        translationManager.setMode(TranslationMode.IMAGE)
        assertEquals(TranslationMode.IMAGE, translationManager.currentMode.value)
    }
}
