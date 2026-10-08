package org.olcbox.app.vpn.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Copy of the in-app log on disk (murka.log + one rotated murka.log.1), so a failed
 * connection can be diagnosed after the app is closed. Never throws: logging must not
 * break the VPN.
 */
internal class DesktopLogFile(
    private val path: Path,
    private val maxBytes: Long = 2L * 1024 * 1024
) {
    @Synchronized
    fun append(message: String) {
        runCatching {
            val line = "${LocalTime.now().format(TIME)} $message\n"
            if (Files.exists(path) && Files.size(path) + line.toByteArray().size > maxBytes) {
                Files.move(path, path.resolveSibling("${path.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
