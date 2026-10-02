package info.piepgras.gridracer.server.security

import info.piepgras.gridracer.multiplayer.ClientMessage
import info.piepgras.gridracer.server.Connection

/**
 * Every rate-limiter key the server uses, built in one place.
 *
 * These were string literals concatenated at each call site, and two of them shipped with a
 * `${'$'}`-escaped template that produced the *constant* text `${connection.id}` rather than
 * interpolating. A constant key means one shared bucket: every connection on the server drew
 * from the same per-message allowance, and every wrong room password anywhere drew from the
 * same five-guess budget. Naming the keys is what stops that from being possible again - a
 * function cannot silently forget its arguments the way a template can.
 *
 * The two dimensions are deliberate and independent:
 *
 * - **per connection** ([perMessage], [roomPassword]) bounds what one socket can do.
 * - **per IP** ([ip], [ipPerMessage]) bounds what one *address* can do, because a client that
 *   wants more allowance only has to open another socket - [Connection.id] is a fresh UUID
 *   every time. Per-IP budgets are set generously: a household behind CGNAT shares an address.
 */
object RateKeys {

    /** All messages from one connection. */
    fun all(connection: Connection): String = connection.id

    /** One message type from one connection. */
    fun perMessage(connection: Connection, message: ClientMessage): String =
        "${connection.id}:${message::class.simpleName}"

    /** Wrong room-password attempts, budgeted per (connection, room). */
    fun roomPassword(connection: Connection, roomCode: String): String =
        "pw:${connection.id}:$roomCode"

    /** Account register/login attempts from one connection. */
    fun auth(connection: Connection): String = "auth:${connection.id}"

    /** All messages from one address, across every socket it has open. */
    fun ip(remoteIp: String): String = "ip:$remoteIp"

    /** One message type from one address, across every socket it has open. */
    fun ipPerMessage(remoteIp: String, message: ClientMessage): String =
        "ip:$remoteIp:${message::class.simpleName}"

    /** Account register/login attempts from one address - the brute-force path. */
    fun ipAuth(remoteIp: String): String = "ip-auth:$remoteIp"

    /** The public `/rooms` listing, budgeted per address (there is no connection there). */
    fun roomList(remoteIp: String): String = "rooms:$remoteIp"
}
