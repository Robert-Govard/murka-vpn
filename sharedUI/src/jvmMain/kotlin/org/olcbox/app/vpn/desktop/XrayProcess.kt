package org.olcbox.app.vpn.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Desktop Xray process for Remnawave locations: `xray run -c <config>`. */
internal object XrayProcess {
    /** Xray reads geoip.dat/geosite.dat from here (env form of xray.location.asset). */
    const val ASSET_ENV = "XRAY_LOCATION_ASSET"

    fun command(binary: Path, configPath: Path): List<String> =
        listOf(binary.toString(), "run", "-c", configPath.toString())

    /** Writes the config readable by the owner only; it contains server credentials. */
    fun writeConfig(dir: Path, json: String): Path {
        Files.createDirectories(dir)
        val path = Files.createTempFile(dir, "xray-", ".json")
        if (dir.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
        Files.writeString(path, json, StandardCharsets.UTF_8)
        return path
    }

    fun isReadyLine(line: String): Boolean =
        line.contains("core: Xray") && line.trimEnd().endsWith("started")

    fun isFatalLine(line: String): Boolean =
        line.startsWith("Failed to start", ignoreCase = true) || line.startsWith("panic:")
}
