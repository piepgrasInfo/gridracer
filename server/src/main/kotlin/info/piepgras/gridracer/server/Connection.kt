package info.piepgras.gridracer.server

import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.ServerMessage
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Outbound channel for a single human player. Implementations wrap whatever
 * transport carries the [ServerMessage] stream (WebSocket, test fixture, etc.).
 */
interface Connection {
    /** The player id once this connection has been assigned to a room, or `null` until then. */
    var playerId: Int?
    /** The room this connection has joined, or `null` until then. */
    var room: Room?
    /** Persistent player token used for reconnects and authentication. */
    var token: String?
    /**
     * The account id when this connection has logged in, or null for an anonymous player.
     * Set by [MessageRouter] when a session token resolves; cleared on logout. Carried so
     * rating writes (Phase 5) and room seating can attribute the player to an account
     * without another DB lookup under a lock.
     */
    var accountId: Long?
    /**
     * The display name of the logged-in account, or null for an anonymous player. Set by
     * [MessageRouter] during `Identify` from the account row; used by the matchmaker as the
     * player's name in a matchmade room so the name does not depend on a rating lookup.
     */
    var displayName: String?

    /**
     * The account's opaque public id, or null for an anonymous player.
     *
     * Distinct from [accountId], which is the database row id and must never leave the server.
     * This is the wire-safe handle the leaderboard already uses, carried so a race log can name
     * who played without the log becoming a list of internal ids - or, worse, credentials.
     */
    var accountPublicId: String?

    /**
     * The protocol version the client declared in its `Hello`, or 0 before it has sent one.
     * Rooms read it to keep clients that cannot play by a room's rules out of it - see
     * `Room.GRAVEL_PROTOCOL`.
     */
    var protocolVersion: Int

    /**
     * A stable key for this connection, used for per-connection rate limiting. Distinct from
     * [token], which the client chooses and can change mid-session.
     */
    val id: String

    /**
     * The address this connection is attributed to, already bucketed - see
     * `security.ClientIp`. Used for per-IP budgets.
     */
    val remoteIp: String

    /**
     * Hands [message] to the transport. **Implementations must not block on the peer.**
     *
     * [Room] broadcasts to every recipient while holding its mutex, and [RoomManager] calls
     * into [Room] while holding its own - so a `send` that suspends until a slow client reads
     * stalls that client's entire race, and can stall the `/rooms` listing (which walks every
     * room's mutex) for the whole server. A client that cannot keep up is disconnected
     * instead; it has already lost the race it is failing to follow.
     */
    suspend fun send(message: ServerMessage)

    /** Flushes anything already queued, then closes the transport. */
    suspend fun close()
}

/**
 * [Connection] backed by a Ktor WebSocket session.
 *
 * Outbound messages go through a bounded queue drained by one writer coroutine rather than
 * straight to `session.outgoing`, which suspends once the peer stops reading. See
 * [Connection.send] for why that mattered.
 */
class WebSocketConnection(
    private val session: DefaultWebSocketSession,
    private val serializer: MultiplayerFrameSerializer,
    override val remoteIp: String = "unknown",
) : Connection {
    override var playerId: Int? = null
    override var room: Room? = null
    override var token: String? = null
    override var accountId: Long? = null
    override var displayName: String? = null
    override var accountPublicId: String? = null
    override var protocolVersion: Int = 0
    override val id: String = UUID.randomUUID().toString()

    private val log = LoggerFactory.getLogger(WebSocketConnection::class.java)

    /**
     * Deep enough to absorb a full-state resync plus a burst of deltas, shallow enough that a
     * peer which has stopped reading is noticed within a turn or two rather than after
     * megabytes have piled up in the server's heap.
     */
    private val outbox = Channel<ServerMessage>(capacity = OUTBOX_CAPACITY)

    /** Set once, so a client that overflows is logged and closed exactly one time. */
    private val overflowed = AtomicBoolean(false)

    private val writer = session.launch {
        try {
            // Draining to completion (rather than until cancelled) is what makes `close()`
            // ordered: it closes the channel, and everything already queued - the `Kicked`
            // message that explains the disconnect, for instance - still goes out first.
            for (message in outbox) {
                val bytes = runCatching { serializer.encodeServerMessage(message) }
                    .onFailure { log.warn("Encoding {} failed", message::class.simpleName, it) }
                    .getOrNull() ?: continue
                session.outgoing.send(Frame.Binary(true, bytes))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The peer went away mid-write. The read loop's `finally` handles the cleanup;
            // there is nothing to do here but stop.
            log.debug("Outbound writer for a connection stopped", e)
        } finally {
            runCatching { session.close() }
        }
    }

    override suspend fun send(message: ServerMessage) {
        if (outbox.trySend(message).isSuccess) return
        // Either the queue is full (the peer is not reading) or it is already closed. Only the
        // first overflow is worth a log line and a close; the rest are the same event.
        if (overflowed.compareAndSet(false, true)) {
            log.info("Dropping a connection from {}: outbound queue full", remoteIp)
            outbox.close()
        }
    }

    override suspend fun close() {
        // Closing the channel rather than the session: the writer drains what is queued and
        // closes the session itself, so a message sent immediately before close still lands.
        outbox.close()
    }

    /**
     * Releases the writer coroutine. Called from the socket's `finally` after the read loop
     * ends, so a connection whose peer vanished without a close handshake does not leave a
     * coroutine parked on `outgoing.send`.
     */
    fun shutdown() {
        outbox.close()
        writer.cancel()
    }

    private companion object {
        const val OUTBOX_CAPACITY = 64
    }
}
