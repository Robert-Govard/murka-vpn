package org.olcbox.app.vpn.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import org.olcbox.app.desktop.DesktopOs
import org.olcbox.app.desktop.DesktopPaths

/**
 * Ties helper processes (Xray, olcRTC, tun2socks) to the app with a Windows job object
 * that kills them when the app's last handle closes. Without it a crash, a forced quit
 * or an update leaves tun2socks running: routes keep pointing at a dead tunnel and the
 * PC has no internet. No-op on other systems.
 */
internal object WindowsProcessJob {
    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001

    // JOBOBJECT_EXTENDED_LIMIT_INFORMATION on 64-bit Windows: 144 bytes, LimitFlags at offset 16.
    private const val LIMIT_INFO_SIZE = 144L
    private const val LIMIT_FLAGS_OFFSET = 16L

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, length: Int): Boolean
        fun OpenProcess(access: Int, inheritHandle: Boolean, processId: Int): Pointer?
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private val kernel32: Kernel32? by lazy {
        runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    /** Never closed: the handle lives as long as the app, and its closing is what kills the helpers. */
    private val job: Pointer? by lazy {
        val k = kernel32 ?: return@lazy null
        val handle = k.CreateJobObjectW(null, null) ?: return@lazy null
        val info = Memory(LIMIT_INFO_SIZE).apply {
            clear()
            setInt(LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
        }
        if (!k.SetInformationJobObject(handle, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, LIMIT_INFO_SIZE.toInt())) {
            k.CloseHandle(handle)
            return@lazy null
        }
        handle
    }

    /** Returns false when the process could not be tied to the app (it then outlives a crash). */
    fun adopt(process: Process): Boolean {
        if (DesktopPaths.os != DesktopOs.Windows) return true
        val k = kernel32 ?: return false
        val job = job ?: return false
        val handle = k.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, process.pid().toInt()) ?: return false
        return try {
            k.AssignProcessToJobObject(job, handle)
        } finally {
            k.CloseHandle(handle)
        }
    }
}
