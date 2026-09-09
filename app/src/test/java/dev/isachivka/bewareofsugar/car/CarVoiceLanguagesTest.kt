package dev.isachivka.bewareofsugar.car

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two languages the car may hear, and nothing else - REQ-0044 decision 11. A third one added
 * here without the owner's word would be a language the recogniser is asked to guess between, at
 * the cost of the two he actually speaks.
 */
class CarVoiceLanguagesTest {

    @Test
    fun `russian first, english second, and no third`() {
        assertEquals(listOf("ru-RU", "en-US"), CarVoice.LANGUAGES)
    }
}
