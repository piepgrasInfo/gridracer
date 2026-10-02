package info.piepgras.gridracer

import info.piepgras.gridracer.multiplayer.AccountInfo
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client half of account deletion, driven message by message without a socket.
 *
 * Deleting an account is the one reply the app cannot verify by looking at the screen
 * afterwards, so what it clears is asserted here rather than left to the UI.
 */
class MultiplayerClientAccountTest {

    private val accounts = mutableListOf<AccountSession?>()
    private val tokens = mutableListOf<String>()

    private fun client() = MultiplayerClient(
        serverUrl = "ws://localhost:1/play",
        serializer = MultiplayerFrameSerializer(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onTokenAssigned = { tokens += it },
        onAccountChanged = { accounts += it },
    )

    private fun account() = AccountInfo(
        publicId = "public-id",
        username = "racer",
        displayName = "Racer",
        rating = 1000,
        matches = 0,
        wins = 0,
    )

    @Test
    fun accountDeletedClearsTheAccountAndAdoptsTheAnonymousToken() {
        val client = client()
        client.handleServerMessage(ServerMessage.Authenticated(account(), "session-token"))
        assertEquals("session-token", accounts.last()?.sessionToken)

        client.handleServerMessage(ServerMessage.AccountDeleted("anonymous-token"))

        assertNull("the account must be gone from the UI state", client.account.value)
        assertNull("the persisted session must be cleared", accounts.last())
        assertEquals(
            "the anonymous token the server issued is what we reconnect with",
            "anonymous-token",
            tokens.last(),
        )
        assertTrue(client.accountDeleted.value)
        assertNull(client.authError.value)
    }

    @Test
    fun clearAccountDeletedResetsTheOneShotFlag() {
        val client = client()
        client.handleServerMessage(ServerMessage.AccountDeleted("anonymous-token"))
        client.clearAccountDeleted()

        assertEquals(false, client.accountDeleted.value)
    }

    /**
     * A refused deletion comes back as a generic [ServerMessage.Error]. It has to reach the
     * delete dialog as a code, not as the server's English prose in the connection-error
     * banner - the same rule the track-sharing errors follow.
     */
    @Test
    fun aRefusedDeletionIsRoutedToTheAuthErrorAsACode() {
        val client = client()
        client.handleServerMessage(ServerMessage.Authenticated(account(), "session-token"))
        client.deleteAccount("password")

        client.handleServerMessage(ServerMessage.Error("Leave the race first", ErrorCode.ALREADY_IN_ROOM))

        assertEquals(MultiplayerClient.AUTH_ERROR_IN_ROOM, client.authError.value)
        assertNull("the generic error banner must stay quiet", client.error.value)
        assertEquals("the account is untouched by a refusal", "racer", client.account.value?.username)
    }

    /**
     * An error that has nothing to do with the deletion must not be swallowed by the dialog
     * just because it arrived while one was in flight.
     */
    @Test
    fun anUnrelatedErrorDuringADeletionStillReachesTheErrorBanner() {
        val client = client()
        client.handleServerMessage(ServerMessage.Authenticated(account(), "session-token"))
        client.deleteAccount("password")

        client.handleServerMessage(ServerMessage.Error("Room not found", ErrorCode.ROOM_NOT_FOUND))

        assertEquals("Room not found", client.error.value)
        assertNull(client.authError.value)
    }

    @Test
    fun anErrorUnrelatedToADeletionStillReachesTheErrorBanner() {
        val client = client()

        client.handleServerMessage(ServerMessage.Error("Room not found", ErrorCode.ROOM_NOT_FOUND))

        assertEquals("Room not found", client.error.value)
        assertNull(client.authError.value)
    }
}
