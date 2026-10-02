package info.piepgras.gridracer.server.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRegistryTest {

    @Test
    fun acceptsUpToThePerIpCapAndRefusesTheNextOne() {
        val registry = ConnectionRegistry(maxPerIp = 3, maxTotal = 100)

        repeat(3) { assertNull(registry.acquire("10.0.0.1")) }

        assertEquals(ConnectionRegistry.Rejection.TOO_MANY_FROM_IP, registry.acquire("10.0.0.1"))
        assertEquals(3, registry.liveConnections("10.0.0.1"))
    }

    @Test
    fun aRefusedConnectionDoesNotConsumeASlot() {
        val registry = ConnectionRegistry(maxPerIp = 1, maxTotal = 100)
        registry.acquire("10.0.0.1")

        // A rejection reserves nothing, so the total must not have moved either - otherwise a
        // client that keeps getting refused slowly fills the server.
        repeat(10) { registry.acquire("10.0.0.1") }

        assertEquals(1, registry.liveConnections())
        assertEquals(1, registry.liveConnections("10.0.0.1"))
    }

    @Test
    fun oneAddressCannotFillTheServerForAnother() {
        val registry = ConnectionRegistry(maxPerIp = 2, maxTotal = 100)
        repeat(2) { registry.acquire("10.0.0.1") }

        assertNull(registry.acquire("10.0.0.2"))
    }

    @Test
    fun theTotalCapBindsEvenAcrossManyAddresses() {
        val registry = ConnectionRegistry(maxPerIp = 100, maxTotal = 4)

        repeat(4) { assertNull(registry.acquire("10.0.0.$it")) }

        assertEquals(ConnectionRegistry.Rejection.SERVER_FULL, registry.acquire("10.0.0.99"))
    }

    @Test
    fun releasingFreesTheSlotAndForgetsTheAddress() {
        val registry = ConnectionRegistry(maxPerIp = 1, maxTotal = 100)
        registry.acquire("10.0.0.1")
        registry.release("10.0.0.1")

        assertEquals(0, registry.liveConnections())
        // The per-IP map must drop the entry, or it is an unbounded leak keyed by an address
        // the caller chooses.
        assertEquals(0, registry.liveConnections("10.0.0.1"))
        assertNull(registry.acquire("10.0.0.1"))
    }

    @Test
    fun concurrentAcquiresFromOneAddressNeverExceedTheCap() {
        val registry = ConnectionRegistry(maxPerIp = 8, maxTotal = 1000)
        val threads = (1..16).map {
            Thread {
                repeat(50) { registry.acquire("10.0.0.1") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(8, registry.liveConnections("10.0.0.1"))
        assertEquals(8, registry.liveConnections())
    }
}
