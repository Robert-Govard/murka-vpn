package org.olcbox.app.vpn

import android.content.Context
import java.io.File
import java.io.IOException
import xraymobile.Xraymobile

/**
 * Runs Xray (from the murka-core AAR) for Remnawave locations. The VPN
 * service owns one instance; olcRTC locations keep using mobile.Runtime.
 */
class XrayCore(private val context: Context) {
    @Volatile
    private var runtime: xraymobile.Runtime? = null

    val isRunning: Boolean
        get() = runtime?.isRunning == true

    /** Starts Xray with a prepared config; [protect] keeps its sockets out of the VPN. */
    @Synchronized
    fun start(configJson: String, protect: (Int) -> Boolean) {
        stop()
        Xraymobile.setAssetDir(ensureAssets().absolutePath)
        Xraymobile.setProtector(object : xraymobile.SocketProtector {
            override fun protect(fd: Long): Boolean = protect(fd.toInt())
        })
        val next = Xraymobile.new_()
        next.start(configJson)
        runtime = next
    }

    @Synchronized
    fun stop() {
        val current = runtime ?: return
        runtime = null
        runCatching { current.stop() }
    }

    /** Copies geoip.dat/geosite.dat out of the APK once per Xray asset version. */
    private fun ensureAssets(): File {
        val dir = File(context.filesDir, "xray")
        val marker = File(dir, ".version")
        val version = context.assets.open("xray/VERSION").bufferedReader().use { it.readText().trim() }
        val installed = marker.isFile && marker.readText() == version &&
            GEO_FILES.all { File(dir, it).isFile }
        if (installed) return dir

        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        for (name in GEO_FILES) {
            val tmp = File(dir, "$name.tmp")
            context.assets.open("xray/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(File(dir, name))) throw IOException("cannot install $name")
        }
        marker.writeText(version)
        return dir
    }

    private companion object {
        val GEO_FILES = listOf("geoip.dat", "geosite.dat")
    }
}
