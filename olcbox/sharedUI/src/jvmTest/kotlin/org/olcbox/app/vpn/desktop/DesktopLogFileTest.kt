package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopLogFileTest {
    @Test
    fun appendsTimestampedLinesAndRotates() {
        val dir = Files.createTempDirectory("murka-log")
        try {
            val log = DesktopLogFile(dir.resolve("murka.log"), maxBytes = 200)
            log.append("first")
            val line = Files.readAllLines(dir.resolve("murka.log")).single()
            assertTrue(Regex("""^\d{2}:\d{2}:\d{2}\.\d{3} first$""").matches(line), line)

            repeat(20) { log.append("line $it") }
            assertTrue(Files.size(dir.resolve("murka.log")) <= 200)
            assertTrue(Files.exists(dir.resolve("murka.log.1")))
            assertEquals("line 19", Files.readAllLines(dir.resolve("murka.log")).last().substringAfter(' '))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unwritableTargetNeverThrows() {
        DesktopLogFile(java.nio.file.Path.of("/nonexistent-dir/murka.log")).append("ignored")
    }
}
