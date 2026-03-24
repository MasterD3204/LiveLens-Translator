package com.livelens.translator

import com.livelens.translator.data.db.TranslationDao
import com.livelens.translator.data.db.TranslationEntity
import com.livelens.translator.data.repository.TranslationRepository
import com.livelens.translator.model.TranslationMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TranslationRepositoryTest {

    private lateinit var dao: TranslationDao
    private lateinit var repository: TranslationRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        repository = TranslationRepository(dao)
    }

    @Test
    fun `insert calls dao insert`() = runTest {
        coEvery { dao.insert(any()) } returns 42L
        coEvery { dao.getCount() } returns 1

        val id = repository.insert(
            originalText = "Hello",
            translatedText = "Xin chào",
            mode = TranslationMode.CONVERSATION
        )

        assertEquals(42L, id)
        coVerify { dao.insert(any()) }
    }

    @Test
    fun `insert enforces 500 entry cap by pruning oldest`() = runTest {
        coEvery { dao.insert(any()) } returns 501L
        coEvery { dao.getCount() } returns 501

        repository.insert("Hello", "Xin chào", TranslationMode.CONVERSATION)

        coVerify { dao.deleteOldest(1) }
    }

    @Test
    fun `insert does not prune when under cap`() = runTest {
        coEvery { dao.insert(any()) } returns 100L
        coEvery { dao.getCount() } returns 100

        repository.insert("Hello", "Xin chào", TranslationMode.CONVERSATION)

        coVerify(exactly = 0) { dao.deleteOldest(any()) }
    }

    @Test
    fun `deleteById delegates to dao`() = runTest {
        repository.deleteById(123L)
        coVerify { dao.deleteById(123L) }
    }

    @Test
    fun `deleteAll delegates to dao`() = runTest {
        repository.deleteAll()
        coVerify { dao.deleteAll() }
    }
}
