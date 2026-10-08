package org.olcbox.app.vpn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDnsResolverTest {
    // `scutil --dns` on a Mac behind a router, with a VPN client's fake-IP TUN running.
    private val scutil = """
        DNS configuration

        resolver #1
          nameserver[0] : 192.168.50.1
          if_index : 15 (en0)
          flags    : Request A records
          reach    : 0x00020002 (Reachable,Directly Reachable Address)

        resolver #2
          domain   : local
          options  : mdns
          timeout  : 5

        DNS configuration (for scoped queries)

        resolver #1
          nameserver[0] : 192.168.50.1
          if_index : 15 (en0)
    """.trimIndent()

    @Test
    fun macUsesFirstSystemResolver() {
        assertEquals("192.168.50.1:53", DesktopDnsResolver.selectMacDnsServer(scutil))
    }

    @Test
    fun macSkipsLoopbackAndHandlesIpv6() {
        val out = "resolver #1\n  nameserver[0] : 127.0.0.1\n  nameserver[1] : 2a00:1450::1\n"
        assertEquals("[2a00:1450::1]:53", DesktopDnsResolver.selectMacDnsServer(out))
        assertNull(DesktopDnsResolver.selectMacDnsServer("No DNS configuration available\n"))
    }

    /** Real output from a Mac whose VPN client took over DNS: resolver #1 has no nameserver. */
    private val scutilBehindVpnClient = """
        DNS configuration

        resolver #1
          flags    : Request A records
          reach    : 0x00000000 (Not Reachable)

        resolver #2
          domain   : local
          options  : mdns
          timeout  : 5
          flags    : Request A records
          reach    : 0x00000000 (Not Reachable)
          order    : 300000

        resolver #3
          domain   : 254.169.in-addr.arpa
          options  : mdns
          timeout  : 5
          flags    : Request A records
          reach    : 0x00000000 (Not Reachable)
          order    : 300200

        resolver #4
          domain   : 8.e.f.ip6.arpa
          options  : mdns
          timeout  : 5
          flags    : Request A records
          reach    : 0x00000000 (Not Reachable)
          order    : 300400

        resolver #5
          domain   : 9.e.f.ip6.arpa
          options  : mdns
          timeout  : 5
          flags    : Request A records
          reach    : 0x00000000 (Not Reachable)
          order    : 300600

        resolver #6
          domain   : a.e.f.ip6.arpa
          options  : mdns
          timeout  : 5
          flags    : Request A records
          reach    : 0x00000000 (Not Reachable)
          order    : 300800

        resolver #7
          domain   : b.e.f.ip6.arpa
          options  : mdns
          timeout  : 5
          flags    : Request A records
          reach    : 0x00000000 (Not Reachable)
          order    : 301000

        DNS configuration (for scoped queries)

        resolver #1
          nameserver[0] : 192.168.50.1
          if_index : 11 (en0)
          flags    : Scoped, Request A records
          reach    : 0x00020002 (Reachable,Directly Reachable Address)
    """.trimIndent()

    @Test
    fun macFallsBackToScopedResolverWhenMainHasNone() {
        assertEquals("192.168.50.1:53", DesktopDnsResolver.selectMacDnsServer(scutilBehindVpnClient))
    }

    @Test
    fun windowsUsesFirstServerOfDefaultInterface() {
        assertEquals("192.168.1.1:53", DesktopDnsResolver.selectWindowsDnsServer("192.168.1.1\r\n77.88.8.8\r\n"))
        assertNull(DesktopDnsResolver.selectWindowsDnsServer("\r\n"))
    }
}
