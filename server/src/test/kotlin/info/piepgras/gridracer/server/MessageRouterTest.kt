package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.AuthRejection
import info.piepgras.gridracer.multiplayer.ClientMessage
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.MultiplayerFrameSerializer
import info.piepgras.gridracer.multiplayer.PROTOCOL_VERSION
import info.piepgras.gridracer.multiplayer.ServerFeature
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.server.db.AuthService
import info.piepgras.gridracer.server.db.Database
import info.piepgras.gridracer.server.db.LeaderboardService
import info.piepgras.gridracer.server.db.TrackShareService
import info.piepgras.gridracer.server.security.Limits
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [MessageRouter] - every authorization and validation decision the server makes.
 *
 * This surface had no tests at all before the router was extracted, which is why the security
 * bugs it contained went unnoticed. Several tests below are regression tests for those bugs;
 * each says which hole it closes.
 */
class MessageRouterTest {

    private val db = Database(":memory:")
    private val leaderboard = LeaderboardService(db, consumerScope = CoroutineScope(Dispatchers.Default))
    private val trackShare = TrackShareService(db)
    private val auth = AuthService(db)
    private val roomManager = RoomManager(auth = auth, leaderboard = leaderboard, trackShare = trackShare)
    private val router = MessageRouter(roomManager)
    private val routerWithServerId = MessageRouter(roomManager, serverId = "11111111-1111-1111-1111-111111111111", serverName = "TestServer")
    private val serializer = MultiplayerFrameSerializer()

    @After
    fun cleanup() {
        leaderboard.close()
        db.close()
        File("gridracer.sqlite").delete()
    }

    private fun identified(): FakeConnection = FakeConnection().also { conn ->
        runBlocking { router.handle(ClientMessage.Identify(null), conn) }
        conn.clearSent()
    }

    private fun hostMessage(name: String = "Host") = ClientMessage.Host(
        track = Tracks.OVAL,
        laps = 1,
        maxPlayers = 2,
        hostName = name,
    )

    // -----------------------------------------------------------------------------------
    // Handshake
    // -----------------------------------------------------------------------------------

    @Test
    fun helloIsAnsweredWithTheServersVersionAndFeatures() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Hello(), conn)

        val welcome = conn.last<ServerMessage.Welcome>()
        assertNotNull(welcome)
        assertEquals(PROTOCOL_VERSION, welcome!!.protocolVersion)
        assertEquals(MIN_SUPPORTED_PROTOCOL_VERSION, welcome.minSupportedVersion)
        assertEquals(SUPPORTED_FEATURES, welcome.features)
    }

    @Test
    fun theWelcome_advertisesTheGravelTrapAndComputerLevels_andTheClientsVersionIsKept() = runBlocking {
        val conn = FakeConnection().apply { protocolVersion = 0 }
        router.handle(ClientMessage.Hello(protocolVersion = 8), conn)

        val features = conn.last<ServerMessage.Welcome>()!!.features
        assertTrue(ServerFeature.GRAVEL_TRAP in features && ServerFeature.AI_LEVELS in features)
        // Kept for the rooms, which keep a client this old out of a gravel race.
        assertEquals(8, conn.protocolVersion)
    }

    @Test
    fun helloDoesNotNeedIdentifyFirst() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Hello(), conn)

        // The handshake has to precede identity, or there is nowhere to report a version
        // mismatch except by failing every later message.
        assertNull(conn.last<ServerMessage.Error>())
    }

    @Test
    fun aClientTooOldToUnderstandUsIsToldSoOnce() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Hello(protocolVersion = MIN_SUPPORTED_PROTOCOL_VERSION - 1), conn)

        assertEquals(ErrorCode.UNSUPPORTED, conn.last<ServerMessage.Error>()?.code)
        assertNull(conn.last<ServerMessage.Welcome>())
    }

    @Test
    fun anUnknownMessageIsRefusedRatherThanIgnoredSilently() = runBlocking {
        // What a message from a newer client decodes to here. Answering means the peer
        // learns it is unsupported instead of watching messages vanish.
        val conn = FakeConnection()
        router.handle(ClientMessage.Unknown, conn)

        assertEquals(ErrorCode.UNSUPPORTED, conn.last<ServerMessage.Error>()?.code)
    }

    // -----------------------------------------------------------------------------------
    // Identify
    // -----------------------------------------------------------------------------------

    @Test
    fun identifyWithNoTokenMintsOne() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Identify(null), conn)

        val identified = conn.last<ServerMessage.Identified>()
        assertNotNull(identified)
        assertEquals(identified!!.token, conn.token)
        assertTrue(identified.token.isNotBlank())
    }

    @Test
    fun identifyWithAnUnknownTokenIsSilentlyGivenADifferentOne() = runBlocking {
        val made_up = "not-a-token-this-server-issued"
        val conn = FakeConnection()
        router.handle(ClientMessage.Identify(made_up), conn)

        // Substituted without saying so, so the client cannot tell "the server forgot me"
        // from "I made that up" - it just quietly becomes a different player.
        val issued = conn.last<ServerMessage.Identified>()?.token
        assertNotNull(issued)
        assertTrue("an unknown token should not be honoured", issued != made_up)
        assertEquals(issued, conn.token)
    }

    @Test
    fun everyMessageExceptIdentifyAndSpectateRequiresIdentifyFirst() = runBlocking {
        val conn = FakeConnection()
        router.handle(hostMessage(), conn)

        assertEquals(ErrorCode.IDENTIFY_FIRST, conn.last<ServerMessage.Error>()?.code)
        assertNull(conn.room)
    }

    @Test
    fun spectateIsExemptFromTheIdentifyGate() = runBlocking {
        // Not "Identify first" - it gets as far as looking the room up and failing on that.
        val conn = FakeConnection()
        router.handle(ClientMessage.Spectate("NOPE"), conn)

        assertEquals(ErrorCode.ROOM_NOT_FOUND, conn.last<ServerMessage.Error>()?.code)
    }

    // -----------------------------------------------------------------------------------
    // Known-bad behaviour, asserted so the fix is visible in the diff that changes it
    // -----------------------------------------------------------------------------------

    @Test
    fun theLeaderboardCarriesNoTokens() = runBlocking {
        // Regression test for an account-takeover hole: the ratings response used to be a
        // list of PlayerRating, whose `token` field is the same bearer credential Identify
        // accepts. Any anonymous client could harvest every account and then present one as
        // its own. Rows are keyed by an opaque publicId now, and the session token never
        // appears in the leaderboard payload.
        val victim = FakeConnection()
        router.handle(ClientMessage.Register("victim1", "password1", "Victim"), victim)
        val victimToken = victim.token!!

        val attacker = identified()
        router.handle(ClientMessage.RequestLeaderboard(), attacker)

        val page = attacker.last<ServerMessage.LeaderboardUpdate>()?.page
        assertNotNull(page)
        // Belt and braces: serialise the whole reply and look for the token anywhere in it,
        // so this still catches a token reintroduced through some other field.
        val wire = serializer.encodeServerMessageToJson(attacker.sent.last())
        assertFalse("a session token reached the wire: $wire", victimToken in wire)
        assertTrue(page!!.entries.none { it.publicId == victimToken })
    }

    @Test
    fun aPublicIdIsNotAccepptedAsIdentity() = runBlocking {
        // publicId is what the leaderboard exposes, so it must be worthless as a credential.
        val victim = FakeConnection()
        router.handle(ClientMessage.Register("victim2", "password1", "Victim"), victim)

        val reader = identified()
        router.handle(ClientMessage.RequestLeaderboard(), reader)
        val publicId = reader.last<ServerMessage.LeaderboardUpdate>()?.page?.entries?.firstOrNull()?.publicId
        assertNotNull(publicId)

        val attacker = FakeConnection()
        router.handle(ClientMessage.Identify(publicId), attacker)

        // Presenting it gets you a fresh identity, not the victim's.
        assertTrue("a public id must not be honoured as a token", attacker.token != publicId)
        assertTrue(attacker.token != victim.token)
    }

    @Test
    fun hostingTwiceIsRefusedRatherThanOrphaningTheFirstRoom() = runBlocking {
        // Regression test: Host used to overwrite connection.room, leaving the previous room
        // with a host nobody was attached to. It was never marked finished, so it was never
        // reaped - an unbounded leak reachable from a one-line client loop.
        val conn = identified()
        router.handle(hostMessage(), conn)
        val first = conn.room!!.code
        conn.clearSent()

        router.handle(hostMessage(), conn)

        assertEquals(ErrorCode.ALREADY_IN_ROOM, conn.last<ServerMessage.Error>()?.code)
        assertEquals(first, conn.room?.code)
        assertEquals(1, roomManager.listRooms().size)
    }

    @Test
    fun spectatingWhileSeatedIsRefused() = runBlocking {
        // Regression test: this used to move the connection to the watched room while its
        // seat in the first room stayed put. Cleanup only ever follows one room, so that
        // race stalled on the abandoned player's turn forever.
        val host = identified()
        router.handle(hostMessage(), host)
        val playing = host.room!!.code

        val other = identified()
        router.handle(hostMessage("Other"), other)
        val watching = other.room!!.code

        host.clearSent()
        router.handle(ClientMessage.Spectate(watching), host)

        assertEquals(ErrorCode.ALREADY_IN_ROOM, host.last<ServerMessage.Error>()?.code)
        assertEquals(playing, host.room?.code)
        assertEquals(0, host.playerId)
    }

    // -----------------------------------------------------------------------------------
    // Rooms
    // -----------------------------------------------------------------------------------

    @Test
    fun hostingCreatesARoomAndSeatsTheHost() = runBlocking {
        val conn = identified()
        router.handle(hostMessage("Martin"), conn)

        val joined = conn.last<ServerMessage.RoomJoined>()
        assertNotNull(joined)
        assertEquals(0, joined!!.playerId)
        assertTrue(joined.isHost)
        assertEquals(joined.roomCode, conn.room?.code)

        val lobby = conn.last<ServerMessage.LobbyUpdate>()
        assertEquals(listOf("Martin"), lobby?.players?.map { it.name })
    }

    @Test
    fun joiningAnUnknownRoomReportsAGenericFailure() = runBlocking {
        val conn = identified()
        router.handle(ClientMessage.Join("ZZZZ", "Guest"), conn)

        assertEquals(ErrorCode.ROOM_NOT_FOUND, conn.last<ServerMessage.Error>()?.code)
        assertNull(conn.room)
    }

    @Test
    fun joiningPutsBothPlayersInTheSameRoom() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        val code = host.room!!.code

        val guest = identified()
        router.handle(ClientMessage.Join(code, "Guest"), guest)

        assertEquals(code, guest.room?.code)
        assertEquals(1, guest.playerId)
    }

    @Test
    fun roomCodesAreNormalisedOnLookup() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        val code = host.room!!.code

        val guest = identified()
        router.handle(ClientMessage.Join(code.lowercase(), "Guest"), guest)

        // Codes used to be looked up verbatim, so a code read aloud and typed back in
        // lowercase simply failed.
        assertEquals(code, guest.room?.code)
    }

    @Test
    fun leavingClearsTheSeat() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)

        router.handle(ClientMessage.Leave, host)

        assertNull(host.room)
        assertNull(host.playerId)
    }

    // -----------------------------------------------------------------------------------
    // Private rooms
    // -----------------------------------------------------------------------------------

    private fun privateHost(password: String, name: String = "Host") =
        hostMessage(name).copy(password = password)

    @Test
    fun aPrivateRoomAdvertisesThatItIsLockedButStillAppears() = runBlocking {
        val host = identified()
        router.handle(privateHost("hunter2"), host)

        // Listed, not hidden: the room code is published by /rooms either way, so the
        // password is the security boundary and the lock is just an honest label.
        val summary = roomManager.listRooms().single()
        assertTrue(summary.isPasswordProtected)
        assertEquals(host.room!!.code, summary.roomCode)
    }

    @Test
    fun theRightPasswordJoins() = runBlocking {
        val host = identified()
        router.handle(privateHost("hunter2"), host)
        val code = host.room!!.code

        val guest = identified()
        router.handle(ClientMessage.Join(code, "Guest", "hunter2"), guest)

        assertEquals(code, guest.room?.code)
        assertEquals(1, guest.playerId)
    }

    @Test
    fun theWrongPasswordDoesNot() = runBlocking {
        val host = identified()
        router.handle(privateHost("hunter2"), host)
        val code = host.room!!.code

        val guest = identified()
        router.handle(ClientMessage.Join(code, "Guest", "wrong"), guest)

        assertEquals(ErrorCode.WRONG_ROOM_PASSWORD, guest.last<ServerMessage.Error>()?.code)
        assertNull(guest.room)
    }

    @Test
    fun omittingThePasswordSaysSoRatherThanClaimingItIsWrong() = runBlocking {
        val host = identified()
        router.handle(privateHost("hunter2"), host)
        val code = host.room!!.code

        val guest = identified()
        router.handle(ClientMessage.Join(code, "Guest"), guest)

        assertEquals(ErrorCode.ROOM_PASSWORD_REQUIRED, guest.last<ServerMessage.Error>()?.code)
    }

    @Test
    fun aPasswordOnAPublicRoomIsIgnored() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        val code = host.room!!.code

        val guest = identified()
        router.handle(ClientMessage.Join(code, "Guest", "unnecessary"), guest)

        assertEquals(code, guest.room?.code)
    }

    @Test
    fun repeatedWrongPasswordsAreRateLimited() = runBlocking {
        val host = identified()
        router.handle(privateHost("hunter2"), host)
        val code = host.room!!.code

        val guest = identified()
        repeat(5) { router.handle(ClientMessage.Join(code, "Guest", "wrong"), guest) }
        guest.clearSent()

        router.handle(ClientMessage.Join(code, "Guest", "wrong"), guest)

        assertEquals(ErrorCode.RATE_LIMITED, guest.last<ServerMessage.Error>()?.code)
    }

    /**
     * The per-message budgets shipped keyed by a `${'$'}`-escaped template that rendered the
     * *constant* text `${connection.id}` - so every connection on the server drew from one
     * bucket and one client could exhaust the move allowance for everybody. The two players
     * below must be able to spend their own budgets independently.
     */
    @Test
    fun perMessageBudgetsAreKeyedPerConnectionNotGlobally() = runBlocking {
        // JOIN is capacity 10/minute. One connection spends the whole allowance...
        val greedy = identified()
        repeat(12) { router.handle(ClientMessage.Join("ZZZZZ", "Greedy"), greedy) }
        assertEquals(
            "the greedy connection should have exhausted its own JOIN budget",
            ErrorCode.RATE_LIMITED,
            greedy.last<ServerMessage.Error>()?.code,
        )

        // ...and a different connection must still have its own.
        val innocent = identified()
        router.handle(ClientMessage.Join("ZZZZZ", "Innocent"), innocent)
        assertEquals(
            "an unrelated connection must not inherit another's spent budget",
            ErrorCode.ROOM_NOT_FOUND,
            innocent.last<ServerMessage.Error>()?.code,
        )
    }

    /** Same shape for the wrong-room-password budget, which had the same constant-key bug. */
    @Test
    fun wrongPasswordBudgetsAreKeyedPerConnectionNotGlobally() = runBlocking {
        val host = identified()
        router.handle(privateHost("hunter2"), host)
        val code = host.room!!.code

        val guesser = identified()
        repeat(6) { router.handle(ClientMessage.Join(code, "Guesser", "wrong"), guesser) }
        assertEquals(ErrorCode.RATE_LIMITED, guesser.last<ServerMessage.Error>()?.code)

        val innocent = identified()
        router.handle(ClientMessage.Join(code, "Innocent", "wrong"), innocent)
        assertEquals(
            "one bad guesser must not lock everyone else out of the room",
            ErrorCode.WRONG_ROOM_PASSWORD,
            innocent.last<ServerMessage.Error>()?.code,
        )
    }

    /**
     * The per-connection budgets are advisory on their own: `Connection.id` is a fresh UUID,
     * so a client that wants more only has to reconnect. The per-IP budget is what binds.
     */
    @Test
    fun authAttemptsAreBudgetedPerAddressAcrossReconnects() = runBlocking {
        val attackerIp = "203.0.113.7"
        // IP_AUTH is 12/minute; AUTH is 5/minute per connection. Reconnecting after every
        // four guesses stays under the per-connection budget forever.
        var lastRejection: ServerMessage.AuthRejected? = null
        repeat(5) { round ->
            val socket = FakeConnection(id = "attacker-$round", remoteIp = attackerIp)
            runBlocking { router.handle(ClientMessage.Identify(null), socket) }
            repeat(4) { router.handle(ClientMessage.Login("victim", "guess-$it"), socket) }
            socket.last<ServerMessage.AuthRejected>()?.let { lastRejection = it }
        }
        assertEquals(
            "20 guesses from one address across five sockets must hit the per-IP auth budget",
            AuthRejection.RATE_LIMITED,
            lastRejection?.reason,
        )

        // A different address is unaffected.
        val elsewhere = FakeConnection(id = "elsewhere", remoteIp = "198.51.100.9")
        router.handle(ClientMessage.Identify(null), elsewhere)
        elsewhere.clearSent()
        router.handle(ClientMessage.Login("victim", "guess"), elsewhere)
        assertEquals(
            "a bystander's address must not be locked out by someone else's guessing",
            AuthRejection.NO_SUCH_USER,
            elsewhere.last<ServerMessage.AuthRejected>()?.reason,
        )
    }

    /**
     * The lobby draws one empty "waiting for player" row per free seat, from `maxPlayers`.
     * The router builds the *first* `LobbyUpdate` itself (the room has not broadcast yet) and
     * left the field at its default of 0, so a freshly hosted room looked full to its own host
     * until something else triggered a broadcast.
     */
    @Test
    fun theFirstLobbyUpdateCarriesTheSeatCount() = runBlocking {
        val host = identified()
        router.handle(hostMessage().copy(maxPlayers = 4), host)

        assertEquals(4, host.last<ServerMessage.LobbyUpdate>()?.maxPlayers)
    }

    @Test
    fun theFirstTournamentLobbyUpdateCarriesTheSeatCountToo() = runBlocking {
        val host = identified()
        router.handle(
            ClientMessage.HostTournament(
                tracks = listOf(Tracks.OVAL, Tracks.SQUARE),
                laps = 1,
                maxPlayers = 3,
                hostName = "Host",
            ),
            host,
        )

        assertEquals(3, host.last<ServerMessage.LobbyUpdate>()?.maxPlayers)
    }

    // -----------------------------------------------------------------------------------
    // Room lifecycle
    // -----------------------------------------------------------------------------------

    /**
     * Matchmaking used to orphan the room a seated player was already in: being matched
     * overwrote `connection.room`, and cleanup only ever follows one room per connection, so
     * the abandoned room was never finished, never reaped, and stalled on that player's turn.
     * The same hole `canOpenRoom` closes on Host, reached through a different message.
     */
    @Test
    fun matchmakingFromInsideARoomIsRefused() = runBlocking {
        val conn = identified()
        router.handle(hostMessage(), conn)
        val hostedRoom = conn.room
        assertNotNull(hostedRoom)
        conn.clearSent()

        router.handle(ClientMessage.EnterMatchmaking(desiredPlayers = 2), conn)

        assertEquals(ErrorCode.ALREADY_IN_ROOM, conn.last<ServerMessage.Error>()?.code)
        assertEquals("the hosted room must still be the connection's room", hostedRoom, conn.room)
        assertFalse("the hosted room must not have been abandoned", hostedRoom!!.isFinished())
    }

    /**
     * Queueing twice used to add two entries for one socket, which could then be "matched"
     * with itself - every seat in the group resolving to the same connection.
     */
    @Test
    fun queueingTwiceDoesNotMatchAConnectionWithItself() = runBlocking {
        val conn = identified()
        router.handle(ClientMessage.EnterMatchmaking(desiredPlayers = 2), conn)
        router.handle(ClientMessage.EnterMatchmaking(desiredPlayers = 2), conn)

        assertNull("a lone player must not be seated in a room", conn.room)
        assertNull(conn.last<ServerMessage.RoomJoined>())
    }

    /**
     * ChangeTrack and ChangeTournamentTracks were added after the Host path was hardened and
     * skipped its size check - so a host could swap in the oversized track that
     * Validation.checkTrack exists to refuse, and the server would rebroadcast it to every
     * player and spectator on every lobby update.
     */
    @Test
    fun changingTheTrackMidLobbyGoesThroughTheSameSizeCheckAsHosting() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        val originalTrack = host.room!!.track
        host.clearSent()

        router.handle(ClientMessage.ChangeTrack(oversizedTrack()), host)

        assertEquals(ErrorCode.TRACK_INVALID, host.last<ServerMessage.Error>()?.code)
        assertEquals("the room must still hold the track it was created with", originalTrack, host.room!!.track)
    }

    @Test
    fun changingTournamentTracksMidLobbyIsAlsoChecked() = runBlocking {
        val host = identified()
        router.handle(
            ClientMessage.HostTournament(
                tracks = listOf(Tracks.OVAL, Tracks.SQUARE),
                laps = 1,
                maxPlayers = 2,
                hostName = "Host",
            ),
            host,
        )
        host.clearSent()

        // Two tracks, so the itinerary length passes and the size check is what refuses it.
        router.handle(ClientMessage.ChangeTournamentTracks(listOf(oversizedTrack(), Tracks.SQUARE)), host)

        assertEquals(ErrorCode.TRACK_INVALID, host.last<ServerMessage.Error>()?.code)
    }

    @Test
    fun aValidMidLobbyTrackChangeStillWorks() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        host.clearSent()

        router.handle(ClientMessage.ChangeTrack(Tracks.SQUARE), host)

        assertNull(host.last<ServerMessage.Error>())
        assertEquals(Tracks.SQUARE.name, host.last<ServerMessage.LobbyUpdate>()?.track?.name)
    }

    /** A track past MAX_WALLS - the shape Validation.checkTrack exists to refuse. */
    private fun oversizedTrack(): info.piepgras.gridracer.engine.Track {
        val base = Tracks.OVAL
        val filler = info.piepgras.gridracer.engine.Wall(
            info.piepgras.gridracer.engine.Point(0, 0),
            info.piepgras.gridracer.engine.Point(0, 1),
        )
        return base.copy(walls = base.walls + List(Limits.MAX_WALLS + 1) { filler })
    }

    @Test
    fun theRoomPasswordNeverReachesTheWire() = runBlocking {
        val secret = "correct-horse-battery"
        val host = identified()
        router.handle(privateHost(secret), host)

        val watcher = identified()
        router.handle(ClientMessage.RequestLeaderboard(), watcher)
        val listing = serializer.encodeRoomSummaries(roomManager.listRooms())

        // Serialise everything anyone could have received and look for the plaintext, so a
        // password leaked through any field is caught, not just the ones we thought of.
        val everything = (host.sent + watcher.sent).joinToString("\n") {
            serializer.encodeServerMessageToJson(it)
        } + listing
        assertFalse("the room password reached a client", secret in everything)
    }

    @Test
    fun aNamedRoomShowsItsNameInTheListing() = runBlocking {
        val host = identified()
        router.handle(hostMessage().copy(roomName = "Friday night"), host)

        assertEquals("Friday night", roomManager.listRooms().single().roomName)
    }

    // -----------------------------------------------------------------------------------
    // Seat-scoped messages
    // -----------------------------------------------------------------------------------

    @Test
    fun inGameMessagesRequireASeat() = runBlocking {
        val conn = identified()
        router.handle(ClientMessage.SetReady(true), conn)

        assertEquals(ErrorCode.NOT_IN_ROOM, conn.last<ServerMessage.Error>()?.code)
    }

    @Test
    fun spectatorsCannotPlay() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        val code = host.room!!.code

        val watcher = identified()
        router.handle(ClientMessage.Spectate(code), watcher)
        watcher.clearSent()

        router.handle(ClientMessage.StartRace, watcher)

        assertEquals(ErrorCode.NOT_IN_ROOM, watcher.last<ServerMessage.Error>()?.code)
    }

    @Test
    fun onlyTheHostCanStartTheRace() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        val code = host.room!!.code

        val guest = identified()
        router.handle(ClientMessage.Join(code, "Guest"), guest)
        guest.clearSent()

        router.handle(ClientMessage.StartRace, guest)

        assertEquals("Only the host can start the race", guest.last<ServerMessage.Error>()?.message)
    }

    // -----------------------------------------------------------------------------------
    // Matchmaking
    // -----------------------------------------------------------------------------------

    @Test
    fun matchmakingRejectsAnImpossibleFieldSize() = runBlocking {
        val conn = identified()
        router.handle(ClientMessage.EnterMatchmaking(desiredPlayers = 99, track = null, laps = null), conn)

        assertEquals(ErrorCode.INVALID_INPUT, conn.last<ServerMessage.Error>()?.code)
    }

    @Test
    fun disconnectingLeavesTheMatchmakingQueue() = runBlocking {
        // Regression test: a dropped connection used to stay queued forever, and worse - it
        // would eventually "match", fail to seat, and take the room down with it, breaking
        // every subsequent match in that bucket.
        val conn = identified()
        router.handle(ClientMessage.EnterMatchmaking(desiredPlayers = 2, track = null, laps = null), conn)
        assertNotNull(conn.last<ServerMessage.MatchmakingJoined>())

        router.onDisconnected(conn)

        val other = identified()
        router.handle(ClientMessage.EnterMatchmaking(desiredPlayers = 2, track = null, laps = null), other)

        // Queued alone, not matched against a ghost.
        assertNotNull(other.last<ServerMessage.MatchmakingJoined>())
        assertNull(other.last<ServerMessage.RoomJoined>())
        assertNull(other.last<ServerMessage.RoomClosed>())
    }

    // -----------------------------------------------------------------------------------
    // Disconnect handling
    // -----------------------------------------------------------------------------------

    @Test
    fun disconnectingClearsRoomState() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)

        router.onDisconnected(host)

        assertNull(host.room)
        assertNull(host.playerId)
    }

    @Test
    fun disconnectingASpectatorRemovesItFromTheRoom() = runBlocking {
        val host = identified()
        router.handle(hostMessage(), host)
        val room = host.room!!

        val watcher = identified()
        router.handle(ClientMessage.Spectate(room.code), watcher)
        router.onDisconnected(watcher)

        watcher.clearSent()
        room.broadcast(ServerMessage.Error("ping"))
        assertTrue("a removed spectator should receive nothing", watcher.sent.isEmpty())
    }

    // -----------------------------------------------------------------------------------
    // Accounts (Phase 4)
    // -----------------------------------------------------------------------------------

    @Test
    fun registeringAnAccountRepliesWithTheAccountAndASessionToken() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Register("racer1", "password1", null), conn)

        val authed = conn.last<ServerMessage.Authenticated>()
        assertNotNull(authed)
        assertEquals("racer1", authed!!.account.username)
        assertEquals("racer1", authed.account.displayName)
        assertTrue(authed.sessionToken.isNotBlank())
        assertEquals(authed.sessionToken, conn.token)
        assertNotNull(conn.accountId)
    }

    @Test
    fun loginRepliesWithTheAccountAndASessionToken() = runBlocking {
        val register = FakeConnection()
        router.handle(ClientMessage.Register("racer2", "password2", null), register)
        val registered = register.last<ServerMessage.Authenticated>()!!

        val conn = FakeConnection()
        router.handle(ClientMessage.Login("racer2", "password2"), conn)
        val authed = conn.last<ServerMessage.Authenticated>()
        assertNotNull(authed)
        assertEquals(registered.account.publicId, authed!!.account.publicId)
    }

    @Test
    fun aSessionTokenPresentedToIdentifyResolvesToTheAccount() = runBlocking {
        val register = FakeConnection()
        router.handle(ClientMessage.Register("racer3", "password3", null), register)
        val session = register.last<ServerMessage.Authenticated>()!!.sessionToken

        val conn = FakeConnection()
        router.handle(ClientMessage.Identify(session), conn)
        val identified = conn.last<ServerMessage.Identified>()
        assertNotNull(identified)
        assertEquals(session, identified!!.token)
        assertNotNull(identified.account)
        assertEquals("racer3", identified.account!!.username)
    }

    @Test
    fun anAnonymousIdentifyCarriesNoAccount() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Identify(null), conn)
        val identified = conn.last<ServerMessage.Identified>()
        assertNotNull(identified)
        assertNull(identified!!.account)
    }

    @Test
    fun logoutInvalidatesTheSessionAndIssuesAnAnonymousToken() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Register("racer4", "password4", null), conn)
        val session = conn.last<ServerMessage.Authenticated>()!!.sessionToken
        conn.clearSent()

        router.handle(ClientMessage.Logout, conn)
        val identified = conn.last<ServerMessage.Identified>()
        assertNotNull(identified)
        assertNotEquals(session, identified!!.token)

        // The old session no longer resolves to an account.
        val reconn = FakeConnection()
        router.handle(ClientMessage.Identify(session), reconn)
        assertNull(reconn.last<ServerMessage.Identified>()?.account)
    }

    // -----------------------------------------------------------------------------------
    // Account deletion (Google Play requires an in-app path to it)
    // -----------------------------------------------------------------------------------

    @Test
    fun deletingAnAccountClearsTheConnectionAndInvalidatesTheSession() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Register("racer7", "password7", null), conn)
        val session = conn.last<ServerMessage.Authenticated>()!!.sessionToken
        conn.clearSent()

        router.handle(ClientMessage.DeleteAccount("password7"), conn)

        val deleted = conn.last<ServerMessage.AccountDeleted>()
        assertNotNull(deleted)
        assertNotEquals("the deleted session token must not be handed back", session, deleted!!.token)
        assertNull(conn.accountId)
        assertNull(conn.accountPublicId)
        assertNull(conn.displayName)

        // The session is gone with the account, and the username is free again.
        val reconn = FakeConnection()
        router.handle(ClientMessage.Identify(session), reconn)
        assertNull(reconn.last<ServerMessage.Identified>()?.account)
        val reused = FakeConnection()
        router.handle(ClientMessage.Register("racer7", "password7", null), reused)
        assertNotNull(reused.last<ServerMessage.Authenticated>())
    }

    @Test
    fun deletingAnAccountWithTheWrongPasswordIsRejected() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Register("racer8", "password8", null), conn)
        conn.clearSent()

        router.handle(ClientMessage.DeleteAccount("not-the-password"), conn)

        assertEquals(AuthRejection.WRONG_PASSWORD, conn.last<ServerMessage.AuthRejected>()?.reason)
        assertNull(conn.last<ServerMessage.AccountDeleted>())
        // Still logged in, and the account still resolves.
        assertNotNull(conn.accountId)
    }

    @Test
    fun deletingAnAccountRequiresBeingLoggedIn() = runBlocking {
        val conn = identified()

        router.handle(ClientMessage.DeleteAccount("password"), conn)

        assertEquals(ErrorCode.NOT_AUTHENTICATED, conn.last<ServerMessage.Error>()?.code)
        assertNull(conn.last<ServerMessage.AccountDeleted>())
    }

    /**
     * A seat caches the account id it was taken with, so deleting the account mid-race would
     * leave a seat naming a row that no longer exists - which the leaderboard and the race log
     * both read at the finish. The player leaves the room first.
     */
    @Test
    fun deletingAnAccountIsRefusedWhileSeatedInARoom() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Register("racer9", "password9", null), conn)
        router.handle(hostMessage("Racer"), conn)
        conn.clearSent()

        router.handle(ClientMessage.DeleteAccount("password9"), conn)

        assertEquals(ErrorCode.ALREADY_IN_ROOM, conn.last<ServerMessage.Error>()?.code)
        assertNotNull("the account must survive a refused deletion", auth.accountById(conn.accountId!!))
    }

    /**
     * The account may be seated through another connection on another device. That seat holds
     * the account id and public handle from the moment it was taken, and nothing else would
     * ever clear them.
     */
    @Test
    fun deletingAnAccountClearsSeatsHeldByItsOtherConnections() = runBlocking {
        val seated = FakeConnection()
        router.handle(ClientMessage.Register("racer10", "password10", null), seated)
        val session = seated.last<ServerMessage.Authenticated>()!!.sessionToken
        router.handle(hostMessage("Racer"), seated)

        // A second device, logged in as the same account and not in a room.
        val other = FakeConnection()
        router.handle(ClientMessage.Identify(session), other)
        assertNotNull(other.last<ServerMessage.Identified>()?.account)

        router.handle(ClientMessage.DeleteAccount("password10"), other)
        assertNotNull(other.last<ServerMessage.AccountDeleted>())

        val room = roomManager.getRoom(seated.room!!.code)!!
        // Nothing left in that room points at the deleted account.
        assertEquals(0, room.forgetAccount(seated.accountId ?: -1L))
        assertEquals(0, room.forgetAccount(1L))
    }

    @Test
    fun registerWithATakenUsernameIsRejected() = runBlocking {
        router.handle(ClientMessage.Register("racer5", "password5", null), FakeConnection())
        val conn = FakeConnection()
        router.handle(ClientMessage.Register("racer5", "different", null), conn)
        val rejected = conn.last<ServerMessage.AuthRejected>()
        assertNotNull(rejected)
        assertEquals(AuthRejection.USERNAME_TAKEN, rejected!!.reason)
    }

    @Test
    fun loginWithAWrongPasswordIsRejected() = runBlocking {
        router.handle(ClientMessage.Register("racer6", "right-pw", null), FakeConnection())
        val conn = FakeConnection()
        router.handle(ClientMessage.Login("racer6", "wrong-pw"), conn)
        assertEquals(AuthRejection.WRONG_PASSWORD, conn.last<ServerMessage.AuthRejected>()!!.reason)
    }

    @Test
    fun theServerAdvertisesAccountsInItsFeatures() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Hello(), conn)
        val welcome = conn.last<ServerMessage.Welcome>()!!
        assertTrue(welcome.features.contains(ServerFeature.ACCOUNTS))
    }

    // ---- Track sharing (Phase 7) ------------------------------------------------------

    private fun validDraft() = info.piepgras.gridracer.engine.TrackDraft(
        outerBoundary = listOf(info.piepgras.gridracer.engine.Point(0, 0), info.piepgras.gridracer.engine.Point(20, 0), info.piepgras.gridracer.engine.Point(20, 12), info.piepgras.gridracer.engine.Point(0, 12)),
        innerBoundary = listOf(info.piepgras.gridracer.engine.Point(7, 4), info.piepgras.gridracer.engine.Point(13, 4), info.piepgras.gridracer.engine.Point(13, 7), info.piepgras.gridracer.engine.Point(7, 7)),
        finishLineFrom = info.piepgras.gridracer.engine.Point(10, 7),
        finishLineTo = info.piepgras.gridracer.engine.Point(10, 12),
        finishDirection = info.piepgras.gridracer.engine.Point(-1, 0),
    )

    private fun authenticatedConnection(username: String = "sharer", displayName: String = "Sharer"): FakeConnection {
        val conn = FakeConnection()
        runBlocking {
            router.handle(ClientMessage.Register(username, "password1", displayName), FakeConnection())
            router.handle(ClientMessage.Login(username, "password1"), conn)
        }
        return conn
    }

    @Test
    fun theServerAdvertisesTrackSharingInItsFeatures() = runBlocking {
        val conn = FakeConnection()
        router.handle(ClientMessage.Hello(), conn)
        val welcome = conn.last<ServerMessage.Welcome>()!!
        assertTrue(welcome.features.contains(ServerFeature.TRACK_SHARING))
    }

    @Test
    fun uploadRequiresAuthentication() = runBlocking {
        val conn = identified() // anonymous, no account
        router.handle(ClientMessage.UploadTrack("My Track", validDraft()), conn)
        val error = conn.last<ServerMessage.Error>()
        assertNotNull(error)
        assertEquals(ErrorCode.NOT_AUTHENTICATED, error!!.code)
    }

    @Test
    fun uploadSucceedsForAuthenticatedUser() = runBlocking {
        val conn = authenticatedConnection()
        conn.clearSent()
        router.handle(ClientMessage.UploadTrack("Shared Track", validDraft()), conn)
        val uploaded = conn.last<ServerMessage.TrackUploaded>()
        assertNotNull(uploaded)
        assertEquals("Shared Track", uploaded!!.name)
        assertNotNull(uploaded.track)
    }

    @Test
    fun listReturnsUploadedTracks() = runBlocking {
        val uploader = authenticatedConnection()
        uploader.clearSent()
        router.handle(ClientMessage.UploadTrack("Listed Track", validDraft()), uploader)
        val uploaded = uploader.last<ServerMessage.TrackUploaded>()!!

        val reader = identified()
        router.handle(ClientMessage.ListTracks(), reader)
        val list = reader.last<ServerMessage.TrackList>()!!
        assertTrue(list.tracks.any { it.trackId == uploaded.trackId && it.name == "Listed Track" })
    }

    @Test
    fun downloadReturnsStoredTrack() = runBlocking {
        val uploader = authenticatedConnection()
        uploader.clearSent()
        router.handle(ClientMessage.UploadTrack("Download Me", validDraft()), uploader)
        val trackId = uploader.last<ServerMessage.TrackUploaded>()!!.trackId

        val reader = identified()
        router.handle(ClientMessage.DownloadTrack(trackId), reader)
        val downloaded = reader.last<ServerMessage.TrackDownloaded>()
        assertNotNull(downloaded)
        assertEquals("Download Me", downloaded!!.summary.name)
        assertNotNull(downloaded.track)
        assertNotNull(downloaded.draft)
    }

    @Test
    fun downloadReturnsErrorForUnknownTrack() = runBlocking {
        val reader = identified()
        router.handle(ClientMessage.DownloadTrack("no-such-track"), reader)
        val error = reader.last<ServerMessage.Error>()
        assertNotNull(error)
        assertEquals(ErrorCode.ROOM_NOT_FOUND, error!!.code)
    }

    @Test
    fun reportRequiresAuthentication() = runBlocking {
        val conn = identified()
        router.handle(ClientMessage.ReportTrack("some-id", "offensive"), conn)
        val error = conn.last<ServerMessage.Error>()
        assertNotNull(error)
        assertEquals(ErrorCode.NOT_AUTHENTICATED, error!!.code)
    }

    @Test
    fun reportSucceedsForExistingTrack() = runBlocking {
        val uploader = authenticatedConnection()
        uploader.clearSent()
        router.handle(ClientMessage.UploadTrack("Reportable", validDraft()), uploader)
        val trackId = uploader.last<ServerMessage.TrackUploaded>()!!.trackId

        val reporter = authenticatedConnection(username = "reporter", displayName = "Reporter")
        reporter.clearSent()
        router.handle(ClientMessage.ReportTrack(trackId, "offensive content"), reporter)
        val reported = reporter.last<ServerMessage.TrackReported>()
        assertNotNull(reported)
        assertEquals(trackId, reported!!.trackId)
    }

    @Test
    fun welcomeAdvertisesTheServerIdAndName() = runBlocking {
        val conn = FakeConnection()
        routerWithServerId.handle(ClientMessage.Hello(), conn)
        val welcome = conn.last<ServerMessage.Welcome>()!!
        assertEquals("11111111-1111-1111-1111-111111111111", welcome.serverId)
        assertEquals("TestServer", welcome.serverName)
    }

    @Test
    fun rateRequiresAuthentication() = runBlocking {
        val conn = identified()
        router.handle(ClientMessage.RateTrack("some-id", 4), conn)
        val error = conn.last<ServerMessage.Error>()
        assertNotNull(error)
        assertEquals(ErrorCode.NOT_AUTHENTICATED, error!!.code)
    }

    @Test
    fun rateSucceedsAndReportsTheAggregate() = runBlocking {
        val uploader = authenticatedConnection()
        uploader.clearSent()
        router.handle(ClientMessage.UploadTrack("Rate Me", validDraft()), uploader)
        val trackId = uploader.last<ServerMessage.TrackUploaded>()!!.trackId

        val rater = authenticatedConnection(username = "rater", displayName = "Rater")
        rater.clearSent()
        router.handle(ClientMessage.RateTrack(trackId, 4), rater)
        val rated = rater.last<ServerMessage.TrackRated>()
        assertNotNull(rated)
        assertEquals(trackId, rated!!.trackId)
        assertEquals(4, rated.yourRating)
        assertEquals(4.0, rated.averageRating, 0.001)
        assertEquals(1, rated.ratingCount)
    }

    @Test
    fun rateRejectsAnOutOfRangeRating() = runBlocking {
        val uploader = authenticatedConnection()
        uploader.clearSent()
        router.handle(ClientMessage.UploadTrack("Rate Me", validDraft()), uploader)
        val trackId = uploader.last<ServerMessage.TrackUploaded>()!!.trackId

        val rater = authenticatedConnection(username = "rater", displayName = "Rater")
        rater.clearSent()
        router.handle(ClientMessage.RateTrack(trackId, 9), rater)
        val error = rater.last<ServerMessage.Error>()
        assertNotNull(error)
        assertEquals(ErrorCode.INVALID_INPUT, error!!.code)
    }

    @Test
    fun rateReturnsNotFoundForUnknownTrack() = runBlocking {
        val rater = authenticatedConnection(username = "rater", displayName = "Rater")
        rater.clearSent()
        router.handle(ClientMessage.RateTrack("no-such-track", 3), rater)
        val error = rater.last<ServerMessage.Error>()
        assertNotNull(error)
        assertEquals(ErrorCode.ROOM_NOT_FOUND, error!!.code)
    }

    @Test
    fun uploadedSummaryCarriesAuthorUsernameAndServer() = runBlocking {
        // The router's trackShare service has no serverId configured, so the summary's
        // serverId/serverName are empty here; the authorUsername still resolves from the
        // account the uploader logged in as.
        val uploader = authenticatedConnection(username = "alice", displayName = "Alice")
        uploader.clearSent()
        router.handle(ClientMessage.UploadTrack("Alice's Track", validDraft()), uploader)
        val uploaded = uploader.last<ServerMessage.TrackUploaded>()!!

        val reader = identified()
        router.handle(ClientMessage.ListTracks(), reader)
        val list = reader.last<ServerMessage.TrackList>()!!
        val summary = list.tracks.first { it.trackId == uploaded.trackId }
        assertEquals("alice", summary.authorUsername)
        assertEquals("Alice", summary.authorName)
    }
}
