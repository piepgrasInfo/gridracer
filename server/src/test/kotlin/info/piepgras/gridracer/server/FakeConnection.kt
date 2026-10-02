package info.piepgras.gridracer.server

import info.piepgras.gridracer.multiplayer.ServerMessage

/**
 * A [Connection] that records what the server sent it instead of writing to a socket.
 *
 * Shared by every server test: with [MessageRouter] the whole protocol can be driven through
 * this, so testing an authorization rule needs no HTTP server and no WebSocket.
 */
class FakeConnection(
    override val id: String = "conn-" + counter.incrementAndGet(),
    /**
     * Distinct per connection by default, matching the production case where two players are
     * two households. Tests that exercise a *per-IP* rule pass the same address to both
     * connections explicitly - which is the only way to tell a per-IP budget apart from a
     * per-connection one.
     */
    override val remoteIp: String = "ip-of-$id",
) : Connection {
    override var playerId: Int? = null
    override var room: Room? = null
    override var token: String? = null
    override var accountId: Long? = null
    override var displayName: String? = null
    override var accountPublicId: String? = null
    override var protocolVersion: Int = info.piepgras.gridracer.multiplayer.PROTOCOL_VERSION

    val sent = mutableListOf<ServerMessage>()
    var closed = false

    override suspend fun send(message: ServerMessage) {
        sent.add(message)
    }

    override suspend fun close() {
        closed = true
    }

    /** The most recent message of the given type, or null. */
    inline fun <reified T : ServerMessage> last(): T? = sent.filterIsInstance<T>().lastOrNull()

    /** Every message of the given type, in order. */
    inline fun <reified T : ServerMessage> all(): List<T> = sent.filterIsInstance<T>()

    fun clearSent() = sent.clear()

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicInteger()
    }
}
