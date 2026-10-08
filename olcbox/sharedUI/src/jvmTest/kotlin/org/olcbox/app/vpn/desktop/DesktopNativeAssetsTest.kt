package org.olcbox.app.vpn.desktop

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DesktopNativeAssetsTest {
    /** Readers must never see a half-written binary while installs run in parallel. */
    @Test
    fun installAtomicallyIsSafeUnderConcurrency() {
        val bytes = Random(7).nextBytes(4 shl 20)
        val target = Files.createTempDirectory("assets").resolve("xray-test")
        val pool = Executors.newFixedThreadPool(17)
        val start = CountDownLatch(1)
        val partialReads = AtomicInteger()
        val failures = AtomicInteger()
        repeat(16) {
            pool.execute {
                start.await()
                runCatching { DesktopNativeAssets.installAtomically(ByteArrayInputStream(bytes), target, executable = true) }
                    .onFailure { failures.incrementAndGet() }
            }
        }
        pool.execute {
            start.await()
            repeat(200) {
                if (Files.exists(target)) {
                    val size = runCatching { Files.size(target) }.getOrDefault(bytes.size.toLong())
                    if (size != bytes.size.toLong()) partialReads.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)

        assertEquals(0, failures.get(), "installs failed")
        assertEquals(0, partialReads.get(), "a reader saw a partial file")
        assertContentEquals(bytes, Files.readAllBytes(target))
        assertEquals(listOf("xray-test"), Files.list(target.parent).use { s -> s.map { it.fileName.toString() }.toList() })
        assert(Files.isExecutable(target))
    }
}
