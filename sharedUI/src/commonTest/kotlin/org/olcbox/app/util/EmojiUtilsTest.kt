package org.olcbox.app.util

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiUtilsTest {
    @Test
    fun flagIsOneEmoji() {
        assertEquals("🇳🇱" to "Нидерланды", parseEmojiAndName("🇳🇱 Нидерланды"))
        assertEquals("🇫🇮" to "Финляндия - 2", parseEmojiAndName("🇫🇮 Финляндия - 2"))
    }

    @Test
    fun singleEmojiAndPlainNamesStillWork() {
        assertEquals("🚀" to "Авто выбор", parseEmojiAndName("🚀 Авто выбор"))
        assertEquals("⭐" to "DK-1", parseEmojiAndName("DK-1", "⭐"))
    }
}
