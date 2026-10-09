package org.olcbox.app.telemetry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

@Serializable
private data class TelemetryState(
    val enabled: Boolean = true,
    val lastHelloAt: Long = 0,
    val lastConnectReportAt: Long = 0,
)

/** Telemetry state in [dir]/telemetry.json; the last crash in [dir]/pending-crash.txt. */
class FileTelemetryStore(private val dir: File) : TelemetryStore {
    private val stateFile = File(dir, "telemetry.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    private fun read(): TelemetryState =
        runCatching { json.decodeFromString<TelemetryState>(stateFile.readText()) }.getOrDefault(TelemetryState())

    @Synchronized
    private fun write(state: TelemetryState) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "telemetry.json.tmp")
            tmp.writeText(json.encodeToString(TelemetryState.serializer(), state))
            tmp.renameTo(stateFile)
        }
    }

    override var enabled: Boolean
        get() = read().enabled
        set(value) = write(read().copy(enabled = value))
    override var lastHelloAt: Long
        get() = read().lastHelloAt
        set(value) = write(read().copy(lastHelloAt = value))
    override var lastConnectReportAt: Long
        get() = read().lastConnectReportAt
        set(value) = write(read().copy(lastConnectReportAt = value))

    override fun pendingCrash(): String? = CrashRecorder.file(dir).takeIf { it.isFile }?.readText()?.ifBlank { null }
    override fun clearPendingCrash() {
        CrashRecorder.file(dir).delete()
    }
}

/** Saves an uncaught exception so the next start can report it. */
object CrashRecorder {
    internal fun file(dir: File) = File(dir, "pending-crash.txt")

    fun install(dir: File, appVersion: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file(dir).writeText("${error}\n\nthread ${thread.name}, v$appVersion\n\n${trace.take(20_000)}")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
