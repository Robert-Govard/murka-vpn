package org.olcbox.app.vpn.failover

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Checks that traffic goes through the local SOCKS5 proxy of the tunnel:
 * SOCKS5 (RFC 1928) with username/password (RFC 1929), CONNECT by host name,
 * then a plain HTTP GET. Success is HTTP 204 or 200.
 */
object Socks5HttpProbe {
    fun probe(
        socksHost: String,
        socksPort: Int,
        username: String,
        password: String,
        targetHost: String = "connectivitycheck.gstatic.com",
        targetPort: Int = 80,
        path: String = "/generate_204",
        timeoutMs: Int = 8_000
    ): Boolean = runCatching {
        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            val useAuth = username.isNotEmpty()
            out.write(if (useAuth) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0))
            out.flush()
            val greeting = ByteArray(2).also { input.readFully(it) }
            if (greeting[0].toInt() != 5) throw IOException("not a SOCKS5 server")
            when (greeting[1].toInt()) {
                0 -> Unit
                2 -> {
                    val user = username.encodeToByteArray()
                    val pass = password.encodeToByteArray()
                    out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                    out.flush()
                    val auth = ByteArray(2).also { input.readFully(it) }
                    if (auth[1].toInt() != 0) throw IOException("SOCKS5 auth rejected")
                }
                else -> throw IOException("no acceptable SOCKS5 auth method")
            }

            val host = targetHost.encodeToByteArray()
            out.write(
                byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host +
                    byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())
            )
            out.flush()
            val reply = ByteArray(4).also { input.readFully(it) }
            if (reply[1].toInt() != 0) throw IOException("SOCKS5 connect failed: ${reply[1]}")
            val addressLength = when (reply[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> input.readUnsignedByte()
                else -> throw IOException("bad SOCKS5 address type")
            }
            input.readFully(ByteArray(addressLength + 2))

            out.write("GET $path HTTP/1.1\r\nHost: $targetHost\r\nUser-Agent: MurkaVPN\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.flush()
            val statusLine = input.bufferedReader().readLine() ?: throw IOException("empty HTTP response")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            code == 204 || code == 200
        }
    }.getOrDefault(false)
}
