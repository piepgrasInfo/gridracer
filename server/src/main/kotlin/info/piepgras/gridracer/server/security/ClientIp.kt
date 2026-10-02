package info.piepgras.gridracer.server.security

/**
 * Works out which address to attribute a connection to, for per-IP budgets.
 *
 * Two details here are easy to get wrong and both are load-bearing:
 *
 * - **Take the rightmost `X-Forwarded-For` entry.** The leftmost is whatever the client
 *   claimed; only the rightmost was appended by our own proxy. Trusting the left is how
 *   per-IP limits become trivially bypassable.
 * - **Bucket IPv6 by /64.** A single client is routinely handed a /64, so limiting by full
 *   address gives an attacker 2^64 free identities.
 *
 * Forwarded headers are only consulted when the deployment says it is behind a proxy;
 * otherwise a direct client could simply send the header itself.
 */
object ClientIp {

    /**
     * The address to charge a connection's per-IP budgets to.
     *
     * @param remoteAddress The socket's peer address.
     * @param forwardedForHeader The `X-Forwarded-For` header, if any.
     * @param trustProxy Whether a proxy we run sits in front; only then is the header consulted,
     *     and only its rightmost entry.
     * @return The address, with IPv6 bucketed to its /64.
     */
    fun resolve(remoteAddress: String, forwardedForHeader: String?, trustProxy: Boolean): String {
        if (trustProxy && !forwardedForHeader.isNullOrBlank()) {
            forwardedForHeader.split(',')
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() }
                ?.let { return bucket(it) }
        }
        return bucket(remoteAddress)
    }

    /** Normalises to the unit a budget should be keyed by. */
    fun bucket(raw: String): String {
        val address = raw.substringBefore('%').trim().removeSurrounding("[", "]")

        // Not an IPv6 address if there are fewer than two colons - "1.2.3.4:5678" has one.
        if (address.count { it == ':' } < 2) {
            return address.substringBeforeLast(':').ifBlank { address }
        }

        // IPv6: keep the first four groups, which is the /64.
        val groups = address.split(':')
        val prefix = groups.take(4).joinToString(":")
        return "$prefix::/64"
    }
}
