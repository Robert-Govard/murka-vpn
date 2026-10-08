package org.olcbox.app.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateDownloadCacheTest {
    @Test
    fun completedDownloadSurvivesRecreationAndNewReleaseIsFetchedAgain() = runBlocking {
        withDownloadServer { directory, url, requests ->
            val asset = AppUpdateAsset("update.apk", url, 8192, "first")
            val first = UpdateDownloadCache(directory).download(asset)
            val restored = UpdateDownloadCache(directory)
            assertEquals(first, restored.downloadedFile(asset))
            assertEquals(first, restored.download(asset))
            assertEquals(1, requests())
            val changed = asset.copy(updatedAt = "second")
            assertNull(restored.downloadedFile(changed))
            val second = restored.download(changed)
            assertContentEquals(first.readBytes(), second.readBytes())
            assertEquals(2, requests())
        }
    }

    @Test
    fun truncatedDownloadCannotBeInstalledOrReused() = runBlocking {
        withDownloadServer { directory, url, _ ->
            val cache = UpdateDownloadCache(directory)
            val asset = AppUpdateAsset("update.apk", url, 8193)
            assertFailsWith<java.io.IOException> { cache.download(asset) }
            assertNull(cache.downloadedFile(asset))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun cancellationRemovesPartialFileAndCanBeRetried() = runBlocking {
        withDownloadServer { directory, url, requests ->
            val cache = UpdateDownloadCache(directory)
            val asset = AppUpdateAsset("update.apk", url, 8192)
            assertFailsWith<CancellationException> {
                cache.download(asset) { throw CancellationException("screen closed") }
            }
            assertNull(cache.downloadedFile(asset))
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertEquals(8192, cache.download(asset).length())
            assertEquals(2, requests())
        }
    }

    @Test
    fun progressIsReportedOncePerPercent() = runBlocking {
        withDownloadServer(size = 1 shl 20) { directory, url, _ ->
            val updates = mutableListOf<Float>()
            UpdateDownloadCache(directory).download(AppUpdateAsset("update.apk", url, 1L shl 20)) { updates += it }
            // Every report hops to the UI thread; one per 8 KB chunk froze big desktop downloads.
            assertTrue(updates.size <= 101, "got ${updates.size} progress reports")
            assertEquals(1f, updates.last())
        }
    }

    private suspend fun withDownloadServer(
        size: Int = 8192,
        block: suspend (java.io.File, String, () -> Int) -> Unit
    ) {
        val directory = Files.createTempDirectory("olcbox-update-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/update.apk") { exchange ->
            requests.incrementAndGet()
            val payload = ByteArray(size) { (it % 251).toByte() }
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            block(directory, "http://127.0.0.1:${server.address.port}/update.apk", requests::get)
        } finally {
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
