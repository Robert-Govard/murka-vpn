package org.olcbox.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.olcbox.app.desktop.DesktopPaths
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

class JvmUpdateInstaller(
    directory: Path = DesktopPaths.appDataDir().resolve("updates")
) {
    private val downloads = UpdateDownloadCache(directory.toFile())

    suspend fun downloadAndOpen(
        asset: AppUpdateAsset,
        onProgress: (Float) -> Unit = {}
    ): Result<String> = runCatching {
        val file = downloads.download(asset) { reportProgress(it, onProgress) }
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        when {
            desktop?.isSupported(Desktop.Action.OPEN) == true -> desktop.open(file)
            desktop?.isSupported(Desktop.Action.BROWSE) == true -> desktop.browse(URI(asset.downloadUrl))
            else -> error("No system file handler available for ${asset.name}")
        }
        "Opening ${asset.name}"
    }

    private suspend fun reportProgress(progress: Float, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            onProgress(progress)
        }
    }
}
