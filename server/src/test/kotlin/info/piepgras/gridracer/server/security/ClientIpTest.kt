package info.piepgras.gridracer.server.security

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientIpTest {

    @Test
    fun usesTheSocketAddressWhenNotBehindAProxy() {
        val ip = ClientIp.resolve(
            remoteAddress = "203.0.113.7",
            forwardedForHeader = "1.2.3.4",
            trustProxy = false,
        )

        // A directly-connected client can send the header itself, so without a proxy in front
        // believing it would hand out free identities to anything limited per IP.
        assertEquals("203.0.113.7", ip)
    }

    @Test
    fun takesTheRightmostForwardedEntry() {
        // Only the rightmost entry was appended by our own proxy; everything left of it is
        // whatever the client claimed.
        val ip = ClientIp.resolve(
            remoteAddress = "10.0.0.1",
            forwardedForHeader = "9.9.9.9, 8.8.8.8, 203.0.113.7",
            trustProxy = true,
        )

        assertEquals("203.0.113.7", ip)
    }

    @Test
    fun fallsBackToTheSocketWhenTheHeaderIsMissingOrEmpty() {
        assertEquals("203.0.113.7", ClientIp.resolve("203.0.113.7", null, trustProxy = true))
        assertEquals("203.0.113.7", ClientIp.resolve("203.0.113.7", "   ", trustProxy = true))
    }

    @Test
    fun bucketsIpv6BySixtyFour() {
        // A single client is routinely handed a /64, so limiting by full address would give
        // an attacker 2^64 free identities.
        val a = ClientIp.bucket("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd")
        val b = ClientIp.bucket("2001:db8:1234:5678:1111:2222:3333:4444")

        assertEquals(a, b)
        assertEquals("2001:db8:1234:5678::/64", a)
    }

    @Test
    fun differentIpv6PrefixesAreDifferentBuckets() {
        val a = ClientIp.bucket("2001:db8:1234:5678::1")
        val b = ClientIp.bucket("2001:db8:1234:9999::1")

        assertEquals("2001:db8:1234:5678::/64", a)
        assertEquals("2001:db8:1234:9999::/64", b)
    }

    @Test
    fun stripsPortsAndZonesAndBrackets() {
        assertEquals("203.0.113.7", ClientIp.bucket("203.0.113.7:54321"))
        assertEquals("2001:db8:1234:5678::/64", ClientIp.bucket("[2001:db8:1234:5678::1]"))
        assertEquals("fe80:0:0:0::/64", ClientIp.bucket("fe80:0:0:0::1%eth0"))
    }
}
