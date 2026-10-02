package info.piepgras.gridracer.server.db

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing using the JDK's PBKDF2WithHmacSHA256 - no new crypto dependency.
 *
 * A stored hash is self-describing: `pbkdf2_sha256$<iterations>$<base64 salt>$<base64 hash>`,
 * so the iteration count can be raised later without re-reading old hashes to guess at their
 * parameters. Verification parses the prefix and recomputes with the recorded iterations.
 *
 * The iteration count is deliberately high (600k) - this is the same cost the room password
 * comparison deliberately avoids (see [RoomManager], where a short-lived room password is
 * compared in constant time without a KDF). An account password is long-lived and worth the
 * cost; a room password is not, and running 600k iterations per join would be a DoS lever.
 */
class Pbkdf2PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val keyLengthBits: Int = DEFAULT_KEY_LENGTH_BITS,
    private val random: SecureRandom = SecureRandom(),
) {

    /** Hashes [password] and returns the self-describing string to store. */
    fun hash(password: CharArray): String {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        val derived = derive(password, salt, iterations, keyLengthBits)
        return "$ALGORITHM${'$'}$iterations${'$'}${b64(salt)}${'$'}${b64(derived)}"
    }

    /** True if [password] matches the previously [hash]ed value. Constant-time over the derived key. */
    fun verify(password: CharArray, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != ALGORITHM) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        val derived = derive(password, salt, iterations, expected.size * Byte.SIZE_BITS)
        return constantTimeEquals(derived, expected)
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    companion object {
        private const val ALGORITHM = "pbkdf2_sha256"
        private const val SALT_BYTES = 16
        /** PBKDF2 iterations for a new hash; see the class description for why it is this high. */
        const val DEFAULT_ITERATIONS = 600_000
        /** Length of the derived key, in bits. */
        const val DEFAULT_KEY_LENGTH_BITS = 256
    }
}
