package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.SimpleAi
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.RaceStanding
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.multiplayer.applyDelta
import info.piepgras.gridracer.server.db.AuthService
import info.piepgras.gridracer.server.db.Database
import info.piepgras.gridracer.server.db.LeaderboardService
import info.piepgras.gridracer.server.security.Limits
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTest {

    @After
    fun cleanup() {
        File("gridracer.sqlite").delete()
    }

    private fun latestPlacement(messages: List<ServerMessage>): PlacementState {
        val startedIndex = messages.indexOfLast { it is ServerMessage.PlacementStarted }
        if (startedIndex < 0) error("No placement started")
        var placement = (messages[startedIndex] as ServerMessage.PlacementStarted).placement
        for (i in startedIndex + 1 until messages.size) {
            placement = when (val message = messages[i]) {
                is ServerMessage.PlacementUpdate -> message.placement
                // Not `?: placement`: a delta the server sent that does not apply to the state
                // its own previous messages produced is the bug these tests exist to catch.
                is ServerMessage.PlacementDelta -> placement.applyDelta(message.delta)
                    ?: error("Placement delta ${message.delta.revision} does not apply to revision ${placement.revision}")
                else -> placement
            }
        }
        return placement
    }

    private fun latestRaceState(messages: List<ServerMessage>): GameState {
        val startedIndex = messages.indexOfLast { it is ServerMessage.RaceStarted }
        if (startedIndex < 0) error("No race started")
        var state = (messages[startedIndex] as ServerMessage.RaceStarted).state
        for (i in startedIndex + 1 until messages.size) {
            state = when (val message = messages[i]) {
                is ServerMessage.StateSync -> message.state
                is ServerMessage.RaceDelta -> state.applyDelta(message.delta)
                    ?: error("Race delta ${message.delta.revision} does not apply to revision ${state.revision}")
                else -> state
            }
        }
        return state
    }

    private suspend fun runRaceToCompletion(room: Room, vararg connections: FakeConnection) {
        var state = latestRaceState(connections[0].sent + connections[1].sent)
        var iterations = 0
        while (state.phase != GamePhase.RACE_OVER && iterations < 500) {
            val playerId = state.currentPlayer.id
            val move = SimpleAi.chooseMove(state, playerId)
            if (move == null) break
            room.chooseMove(playerId, move)
            state = latestRaceState(connections[0].sent + connections[1].sent)
            iterations++
        }
    }

    private suspend fun completePlacement(room: Room, host: FakeConnection, guest: FakeConnection) {
        var placement = latestPlacement(host.sent)
        while (!placement.isComplete) {
            val current = placement.currentPlayerIndex
            val slot = placement.availableSlots.first()
            room.choosePlacement(current, slot)
            placement = latestPlacement(host.sent)
        }
    }

    @Test
    fun fullTwoPlayerRaceStartsAfterRemotePlacement() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(
            code = "TEST",
            track = track,
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        val guestId = room.join("Guest", guest)
        assertEquals(1, guestId)

        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        // With two humans, the remote placement phase begins.
        val placementStarted = host.sent.filterIsInstance<ServerMessage.PlacementStarted>().firstOrNull()
        assertNotNull(placementStarted)

        // First placement slot is chosen by whichever player the server assigned first.
        val placement1 = placementStarted!!.placement
        val firstPlayer = placement1.currentPlayerIndex
        val slot1 = placement1.availableSlots.first()
        room.choosePlacement(firstPlayer, slot1)

        // The second player is now prompted; pick the next available slot.
        val placement2 = latestPlacement(host.sent)
        val secondPlayer = placement2.currentPlayerIndex
        val slot2 = placement2.availableSlots.first()
        room.choosePlacement(secondPlayer, slot2)

        // Race should now be running.
        val raceStarted = host.sent.filterIsInstance<ServerMessage.RaceStarted>().firstOrNull()
        assertNotNull(raceStarted)
        assertEquals(2, raceStarted!!.state.players.size)
        assertEquals(2, raceStarted.configs.size)
    }

    /**
     * The inversion of `aiFillsEmptySlotsWhenHostStartsAlone`, which asserted the behaviour
     * this replaces: the server used to top a room up to `maxPlayers` with AI drivers at the
     * instant a race began, so the host committed to a grid they had never seen.
     */
    @Test
    fun startingDoesNotInventDrivers() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "AI01",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 3,
            hostName = "Host",
            hostConnection = host,
        )
        val guest = FakeConnection()
        room.join("Guest", guest)
        room.setReady(0, true)
        room.setReady(1, true)
        host.clearSent()

        room.startRace(0)

        val placement = host.sent.filterIsInstance<ServerMessage.PlacementStarted>().last()
        assertEquals("two seats taken means two cars, not three", 2, placement.placement.configs.size)
        assertTrue("no AI may appear that nobody asked for", placement.placement.configs.none { it.isAi })
    }

    @Test
    fun aSoloHostCannotStartAndIsToldWhy() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "AI02",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        room.setReady(0, true)
        host.clearSent()

        room.startRace(0)

        assertNull("a one-car race must not start", host.last<ServerMessage.PlacementStarted>())
        val error = host.last<ServerMessage.Error>()
        assertNotNull(error)
        assertTrue(
            "the error has to say what to do about it, not just refuse: ${error!!.message}",
            error.message.contains("AI", ignoreCase = true),
        )
    }

    // -----------------------------------------------------------------------------------
    // Host-seated AI drivers
    // -----------------------------------------------------------------------------------

    @Test
    fun theHostCanSeatAnAiDriver() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "AI03",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        host.clearSent()

        room.addAiPlayer(0)

        val lobby = host.last<ServerMessage.LobbyUpdate>()!!
        assertEquals(2, lobby.players.size)
        assertTrue(lobby.players[1].isAi)
        assertTrue("an AI has nothing to decide, so it is ready at once", lobby.ready[1])
        assertFalse("an AI holds no connection", lobby.connected[1])
    }

    @Test
    fun aSeatedAiLetsASoloHostStart() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "AI04",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        room.setReady(0, true)
        room.addAiPlayer(0)
        host.clearSent()

        room.startRace(0)

        val placement = host.sent.filterIsInstance<ServerMessage.PlacementStarted>().last()
        assertEquals("exactly what the lobby showed: one human, one AI", 2, placement.placement.configs.size)
        assertEquals(1, placement.placement.configs.count { it.isAi })
    }

    @Test
    fun onlyTheHostCanSeatAnAi() = runBlocking {
        val (room, conns) = roomOf("AI05", "Guest")
        conns[1].clearSent()

        room.addAiPlayer(1)

        assertEquals(ErrorCode.NOT_HOST, conns[1].last<ServerMessage.Error>()?.code)
        assertEquals("the roster must be unchanged", 2, room.playerCount())
    }

    @Test
    fun aFullRoomRefusesAnotherAi() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "AI06",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )
        room.addAiPlayer(0)
        host.clearSent()

        room.addAiPlayer(0)

        assertEquals(ErrorCode.ROOM_FULL, host.last<ServerMessage.Error>()?.code)
        assertEquals(2, room.playerCount())
    }

    @Test
    fun anAiCannotBeSeatedOnceTheRaceHasStarted() = runBlocking {
        val (room, conns) = roomOf("AI07", "Guest")
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)
        conns[0].clearSent()

        room.addAiPlayer(0)

        assertEquals(ErrorCode.ROOM_IN_PROGRESS, conns[0].last<ServerMessage.Error>()?.code)
    }

    /**
     * The host puts an AI in a seat, so the host has to be able to take it back out - otherwise
     * one stray tap holds a seat, and locks a human out of it, for the life of the room. The
     * server used to refuse this outright ("Cannot kick AI players").
     */
    @Test
    fun aSeatedAiCanBeRemovedAgain() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "AI08",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        room.addAiPlayer(0)
        assertEquals(2, room.playerCount())
        host.clearSent()

        room.kickPlayer(hostId = 0, targetId = 1)

        assertEquals(1, room.playerCount())
        assertEquals(1, host.last<ServerMessage.LobbyUpdate>()?.players?.size)
    }

    @Test
    fun removingAnAiRenumbersTheSeatsBelowIt() = runBlocking {
        val (room, conns) = roomOf("AI09", "Guest")
        // Seats: 0 host, 1 guest. Add an AI at 2, then remove the guest at 1.
        room.addAiPlayer(0)
        conns.forEach { it.clearSent() }

        room.kickPlayer(hostId = 0, targetId = 1)

        // The AI moved from seat 2 to seat 1; ids must stay equal to list indices.
        val lobby = conns[0].last<ServerMessage.LobbyUpdate>()!!
        assertEquals(2, lobby.players.size)
        assertTrue("the AI is now seat 1", lobby.players[1].isAi)
        assertEquals(0, lobby.hostPlayerId)
    }

    @Test
    fun twoSeatedAiDriversGetDistinctNames() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "AI10",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        room.addAiPlayer(0)
        room.addAiPlayer(0)

        val names = host.last<ServerMessage.LobbyUpdate>()!!.players.map { it.name }
        assertEquals("no two seats may share a name", names.size, names.toSet().size)
    }

    @Test
    fun hostLeavePromotesAnotherPlayer() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "PROM",
            track = track,
            laps = 1,
            maxPlayers = 3,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        val guestId = room.join("Guest", guest)
        assertEquals(1, guestId)

        room.leave(0)

        assertTrue(host.closed)
        assertFalse(room.isFinished())
        assertEquals("Guest", room.toSummary().hostName)

        val third = FakeConnection()
        val thirdId = room.join("Third", third)
        assertNotNull(thirdId)
        assertEquals("Guest", room.toSummary().hostName)
    }

    @Test
    fun playerDisconnectsAndReconnects() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "RECO",
            track = track,
            laps = 1,
            maxPlayers = 3,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        val guestId = room.join("Guest", guest)
        assertEquals(1, guestId)

        room.disconnect(guestId!!)

        val newGuest = FakeConnection()
        newGuest.token = "guest-token"
        val reconnected = room.reconnect("guest-token", newGuest)
        assertTrue(reconnected)

        val reconnectedMsg = newGuest.sent.filterIsInstance<ServerMessage.Reconnected>().firstOrNull()
        assertNotNull(reconnectedMsg)
        assertEquals(guestId, reconnectedMsg!!.playerId)
        assertFalse(reconnectedMsg.isHost)

        val lobbyUpdate = newGuest.sent.filterIsInstance<ServerMessage.LobbyUpdate>().firstOrNull()
        assertNotNull(lobbyUpdate)
    }

    @Test
    fun staleMoveIsRejectedWithResync() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "STALE",
            track = track,
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        room.join("Guest", guest)

        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        // Complete remote placement so the race starts.
        val placementStarted = host.sent.filterIsInstance<ServerMessage.PlacementStarted>().firstOrNull()
        assertNotNull(placementStarted)
        val placement1 = placementStarted!!.placement
        val firstPlayer = placement1.currentPlayerIndex
        val slot1 = placement1.availableSlots.first()
        room.choosePlacement(firstPlayer, slot1)

        val placement2 = latestPlacement(host.sent)
        val secondPlayer = placement2.currentPlayerIndex
        val slot2 = placement2.availableSlots.first()
        room.choosePlacement(secondPlayer, slot2)

        val raceStarted = host.sent.filterIsInstance<ServerMessage.RaceStarted>().firstOrNull()
        assertNotNull(raceStarted)
        val initialState = raceStarted!!.state
        val currentPlayer = initialState.currentPlayer.id
        val legalMove = GameEngine.legalMoves(initialState, currentPlayer).firstOrNull()
        assertNotNull(legalMove)

        val stateSyncsBefore = host.sent.filterIsInstance<ServerMessage.StateSync>().size

        // Submit a move with a stale revision; it must be rejected and resynced.
        room.chooseMove(currentPlayer, initialState.revision - 1, legalMove!!)

        val stateSyncsAfterStale = host.sent.filterIsInstance<ServerMessage.StateSync>().size
        assertEquals(stateSyncsBefore + 1, stateSyncsAfterStale)

        // A resynced StateSync should have the same, unchanged revision.
        val resyncState = latestRaceState(host.sent)
        assertEquals(initialState.revision, resyncState.revision)

        // Now submit the same move with the current revision; it must be applied.
        room.chooseMove(currentPlayer, initialState.revision, legalMove)

        val latestState = latestRaceState(host.sent)
        assertEquals(initialState.revision + 1, latestState.revision)
        assertTrue(latestState.currentPlayer.id != currentPlayer || latestState.phase == GamePhase.RACE_OVER)
    }

    @Test
    fun matchmakingCreatesRoomWhenQueueFull() = runBlocking {
        val manager = RoomManager()
        val host = FakeConnection().apply { token = "host-token" }
        val guest = FakeConnection().apply { token = "guest-token" }

        val track = Tracks.all.first()
        val enteredHost = manager.enterMatchmaking(host, 2, track, 1)
        assertTrue(enteredHost)

        val matchmakingHost = host.sent.filterIsInstance<ServerMessage.MatchmakingJoined>().firstOrNull()
        assertNotNull(matchmakingHost)
        val update = host.sent.filterIsInstance<ServerMessage.MatchmakingUpdate>().lastOrNull()
        assertNotNull(update)
        assertEquals(1, update!!.playersInQueue)

        val enteredGuest = manager.enterMatchmaking(guest, 2, track, 1)
        assertTrue(enteredGuest)

        val joinedHost = host.sent.filterIsInstance<ServerMessage.RoomJoined>().firstOrNull()
        val joinedGuest = guest.sent.filterIsInstance<ServerMessage.RoomJoined>().firstOrNull()
        assertNotNull(joinedHost)
        assertNotNull(joinedGuest)
        assertEquals(0, joinedHost!!.playerId)
        assertEquals(1, joinedGuest!!.playerId)

        val lobby = (host.sent + guest.sent).filterIsInstance<ServerMessage.LobbyUpdate>().last()
        assertEquals(2, lobby.players.size)
        assertEquals(track, lobby.track)
        assertEquals(1, lobby.laps)
    }

    @Test
    fun tournamentRunsTwoTracksAndFinishes() = runBlocking {
        val track1 = Tracks.all[0]
        val track2 = Tracks.all[1]
        val host = FakeConnection()
        val guest = FakeConnection()

        val room = Room(
            code = "T00",
            track = track1,
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
            isTournament = true,
            additionalTracks = listOf(track2),
        )

        room.join("Guest", guest)
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        completePlacement(room, host, guest)
        runRaceToCompletion(room, host, guest)

        assertEquals(1, host.sent.filterIsInstance<ServerMessage.TournamentUpdate>().size)
        val update1 = host.sent.filterIsInstance<ServerMessage.TournamentUpdate>().first()
        assertEquals(1, update1.currentTrackIndex)
        assertTrue(update1.standings.isNotEmpty())

        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        completePlacement(room, host, guest)
        runRaceToCompletion(room, host, guest)

        assertTrue(room.isFinished())
        val finished = (host.sent + guest.sent).filterIsInstance<ServerMessage.TournamentFinished>().firstOrNull()
        assertNotNull(finished)
        assertTrue(finished!!.standings.isNotEmpty())
    }

    @Test
    fun ratingsAreUpdatedAfterMatchmadeRace() = runBlocking {
        // Phase 5: only matchmade rooms are rated, and only account-holding finishers count.
        // This test registers two accounts, seats them in a matchmade room, runs a race, and
        // checks the leaderboard reflects the result after the channel drains.
        val db = Database(":memory:")
        val leaderboard = LeaderboardService(db, consumerScope = CoroutineScope(Dispatchers.Default))
        val auth = AuthService(db)
        val hostAccount = (auth.register("hostuser", "password1", "RacerA") as AuthService.Result.Ok).success.account
        val guestAccount = (auth.register("guestuser", "password1", "RacerB") as AuthService.Result.Ok).success.account

        val host = FakeConnection().apply { token = "host-token"; accountId = hostAccount.id; displayName = "RacerA" }
        val guest = FakeConnection().apply { token = "guest-token"; accountId = guestAccount.id; displayName = "RacerB" }

        val room = Room(
            code = "RATT",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "RacerA",
            hostConnection = host,
            leaderboard = leaderboard,
            isMatchmade = true,
        )

        room.join("RacerB", guest)
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        completePlacement(room, host, guest)
        runRaceToCompletion(room, host, guest)

        // The rating write is asynchronous (channel + consumer). Give it time to drain.
        delay(500)

        val page = leaderboard.leaderboardPage(offset = 0, limit = 50, forAccountId = null)
        assertEquals(2, page.entries.size)
        assertEquals(2, page.totalEntries)
        assertTrue(page.entries.all { it.matches > 0 })
        // The wire type carries no token - see MessageRouterTest.theLeaderboardCarriesNoTokens.
        assertTrue(page.entries.all { it.publicId.isNotBlank() })

        leaderboard.close()
        db.close()
    }

    @Test
    fun nonMatchmadeRoomsAreNotRated() = runBlocking {
        // A private (non-matchmade) room must not affect ratings even with account-holding players.
        val db = Database(":memory:")
        val leaderboard = LeaderboardService(db, consumerScope = CoroutineScope(Dispatchers.Default))
        val auth = AuthService(db)
        val hostAccount = (auth.register("hostuser2", "password1", "RacerC") as AuthService.Result.Ok).success.account
        val guestAccount = (auth.register("guestuser2", "password1", "RacerD") as AuthService.Result.Ok).success.account

        val host = FakeConnection().apply { token = "host-token2"; accountId = hostAccount.id; displayName = "RacerC" }
        val guest = FakeConnection().apply { token = "guest-token2"; accountId = guestAccount.id; displayName = "RacerD" }

        val room = Room(
            code = "PRIV",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "RacerC",
            hostConnection = host,
            leaderboard = leaderboard,
            isMatchmade = false,
        )

        room.join("RacerD", guest)
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        completePlacement(room, host, guest)
        runRaceToCompletion(room, host, guest)

        delay(500)

        val page = leaderboard.leaderboardPage(offset = 0, limit = 50, forAccountId = null)
        // Accounts exist but no race was rated, so matches should still be 0.
        assertTrue(page.entries.all { it.matches == 0 })

        leaderboard.close()
        db.close()
    }

    @Test
    fun nonTournamentRaceSendsRaceFinishedAndReturnsToLobby() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "RFIN",
            track = track,
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        room.join("Guest", guest)

        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        completePlacement(room, host, guest)
        runRaceToCompletion(room, host, guest)

        // The room should NOT be finished — it transitions back to lobby for a rematch.
        assertFalse(room.isFinished())

        // Both clients should receive RaceFinished with standings.
        val hostResult = host.sent.filterIsInstance<ServerMessage.RaceFinished>().lastOrNull()
        val guestResult = guest.sent.filterIsInstance<ServerMessage.RaceFinished>().lastOrNull()
        assertNotNull(hostResult)
        assertNotNull(guestResult)
        assertEquals(2, hostResult!!.standings.size)
        assertEquals(2, guestResult!!.standings.size)

        // No RoomClosed should be sent for a non-tournament race.
        assertFalse(host.sent.any { it is ServerMessage.RoomClosed })
        assertFalse(guest.sent.any { it is ServerMessage.RoomClosed })

        // After RaceFinished, no LobbyUpdate is sent automatically — the host must call
        // returnToLobby to bring everyone back to the lobby screen. Check that the last
        // LobbyUpdate came before the RaceFinished message.
        val lastLobbyIndex = host.sent.indexOfLast { it is ServerMessage.LobbyUpdate }
        val raceFinishedIndex = host.sent.indexOfLast { it is ServerMessage.RaceFinished }
        assertTrue(raceFinishedIndex > lastLobbyIndex)

        // The host returns everyone to the lobby; now a LobbyUpdate is broadcast.
        room.returnToLobby(0)
        val lobbyUpdate = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobbyUpdate)
        val guestLobby = guest.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(guestLobby)

        // The host should be able to start a new race (rematch).
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0) // should not throw
    }

    @Test
    fun hostPromotedSendsHostPromotedMessage() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "HPRO",
            track = track,
            laps = 1,
            maxPlayers = 3,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        room.join("Guest", guest)

        // Host leaves — guest should be promoted and receive HostPromoted.
        room.leave(0)

        assertTrue(host.closed)
        assertFalse(room.isFinished())
        assertEquals("Guest", room.toSummary().hostName)

        val promoted = guest.sent.filterIsInstance<ServerMessage.HostPromoted>().lastOrNull()
        assertNotNull(promoted)
        assertEquals("HPRO", promoted!!.roomCode)
    }

    @Test
    fun kickSendsKickedMessageBeforeClosing() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "KICK",
            track = track,
            laps = 1,
            maxPlayers = 3,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        room.join("Guest", guest)

        room.kickPlayer(0, 1)

        // The kicked guest should have received a Kicked message.
        val kicked = guest.sent.filterIsInstance<ServerMessage.Kicked>().lastOrNull()
        assertNotNull(kicked)
        assertTrue(kicked!!.reason.isNotBlank())
    }

    @Test
    fun hostCanChangeTrackInLobby() = runBlocking {
        val original = Tracks.all.first()
        val replacement = Tracks.all.drop(1).first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "CHTR",
            track = original,
            laps = 3,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        room.join("Guest", guest)

        room.changeTrack(0, replacement)

        // Both clients should receive a LobbyUpdate with the new track.
        val hostLobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        val guestLobby = guest.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(hostLobby)
        assertNotNull(guestLobby)
        assertEquals(replacement.name, hostLobby!!.track.name)
        assertEquals(replacement.name, guestLobby!!.track.name)
    }

    @Test
    fun nonHostCannotChangeTrack() = runBlocking {
        val original = Tracks.all.first()
        val replacement = Tracks.all.drop(1).first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "NHCT",
            track = original,
            laps = 3,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        room.join("Guest", guest)

        room.changeTrack(1, replacement)

        // The guest should get an error, and the track should not have changed.
        val error = guest.sent.filterIsInstance<ServerMessage.Error>().lastOrNull()
        assertNotNull(error)
        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(original.name, lobby!!.track.name)
    }

    @Test
    fun hostCanChangeLapsInLobby() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(code = "CHLP", track = track, laps = 1, maxPlayers = 2, hostName = "Host", hostConnection = host)
        val guest = FakeConnection()
        room.join("Guest", guest)

        room.changeLaps(0, 5)

        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(5, lobby!!.laps)

        // The new lap count is what an actual race starts with, not just what the lobby shows.
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)
        var placement = latestPlacement(host.sent)
        room.choosePlacement(placement.currentPlayerIndex, placement.availableSlots.first())
        placement = latestPlacement(host.sent)
        room.choosePlacement(placement.currentPlayerIndex, placement.availableSlots.first())

        val raceStarted = host.sent.filterIsInstance<ServerMessage.RaceStarted>().firstOrNull()
        assertNotNull(raceStarted)
        assertEquals(5, raceStarted!!.state.totalLaps)
    }

    @Test
    fun changeLapsIsClamped() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(code = "CLCL", track = track, laps = 1, maxPlayers = 2, hostName = "Host", hostConnection = host)

        room.changeLaps(0, 9999)

        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(Limits.MAX_LAPS, lobby!!.laps)
    }

    @Test
    fun hostCanChangeMaxPlayersInLobby() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(code = "CHMP", track = track, laps = 1, maxPlayers = 2, hostName = "Host", hostConnection = host)

        room.changeMaxPlayers(0, 4)

        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(4, lobby!!.maxPlayers)

        // The new seat count is enforced, not just displayed: the room now holds 4 (host plus
        // three guests) instead of 2, and a fifth join is refused.
        assertNotNull(room.join("Guest2", FakeConnection()))
        assertNotNull(room.join("Guest3", FakeConnection()))
        assertNotNull(room.join("Guest4", FakeConnection()))
        assertNull(room.join("Guest5", FakeConnection()))
    }

    @Test
    fun changeMaxPlayersRefusesToShrinkBelowOccupiedSeats() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(code = "CMSH", track = track, laps = 1, maxPlayers = 4, hostName = "Host", hostConnection = host)
        room.join("Guest1", FakeConnection())
        room.join("Guest2", FakeConnection())

        room.changeMaxPlayers(0, 2)

        val error = host.sent.filterIsInstance<ServerMessage.Error>().lastOrNull()
        assertNotNull(error)
        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(4, lobby!!.maxPlayers)
    }

    @Test
    fun hostCanChangeTurnTimeoutInLobby() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(code = "CHTO", track = track, laps = 1, maxPlayers = 2, hostName = "Host", hostConnection = host)

        room.changeTurnTimeoutSeconds(0, 20)

        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(20, lobby!!.turnTimeoutSeconds)
    }

    @Test
    fun changeTurnTimeoutSecondsIsClamped() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(code = "CTCL", track = track, laps = 1, maxPlayers = 2, hostName = "Host", hostConnection = host)

        room.changeTurnTimeoutSeconds(0, 9999)

        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(Limits.MAX_TURN_TIMEOUT_SECONDS, lobby!!.turnTimeoutSeconds)
    }

    @Test
    fun nonHostCannotChangeLobbySettings() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        val room = Room(code = "NHCS", track = track, laps = 1, maxPlayers = 2, hostName = "Host", hostConnection = host)
        val guest = FakeConnection()
        room.join("Guest", guest)

        room.changeLaps(1, 5)
        room.changeMaxPlayers(1, 4)
        room.changeTurnTimeoutSeconds(1, 20)

        assertEquals(3, guest.sent.filterIsInstance<ServerMessage.Error>().size)
        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobby)
        assertEquals(1, lobby!!.laps)
        assertEquals(2, lobby.maxPlayers)
        assertEquals(20, lobby.turnTimeoutSeconds)
    }

    @Test
    fun returnToLobbyBroadcastsLobbyUpdate() = runBlocking {
        val track = Tracks.all.first()
        val host = FakeConnection()
        host.token = "host-token"
        val room = Room(
            code = "RTLO",
            track = track,
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )

        val guest = FakeConnection()
        guest.token = "guest-token"
        room.join("Guest", guest)

        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        completePlacement(room, host, guest)
        runRaceToCompletion(room, host, guest)

        // After the race, RaceFinished is sent but no LobbyUpdate.
        val raceFinished = host.sent.filterIsInstance<ServerMessage.RaceFinished>().lastOrNull()
        assertNotNull(raceFinished)
        val lobbyAfterRace = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>()
            // The last LobbyUpdate before RaceFinished was the initial lobby state.
            .toList()
        // No LobbyUpdate after RaceFinished until returnToLobby is called.
        val lastLobbyIndex = host.sent.indexOfLast { it is ServerMessage.LobbyUpdate }
        val raceFinishedIndex = host.sent.indexOfLast { it is ServerMessage.RaceFinished }
        assertTrue(raceFinishedIndex > lastLobbyIndex)

        // Host calls returnToLobby — now a LobbyUpdate is broadcast.
        room.returnToLobby(0)
        val lobbyAfterReturn = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(lobbyAfterReturn)
        val guestLobbyAfterReturn = guest.sent.filterIsInstance<ServerMessage.LobbyUpdate>().lastOrNull()
        assertNotNull(guestLobbyAfterReturn)
    }

    // -----------------------------------------------------------------------------------
    // The lobby roster
    //
    // The lobby draws one row per *seat*, not per occupant, so it has to be told how many
    // seats there are, who holds the room, and which seats are actually attached.
    // -----------------------------------------------------------------------------------

    @Test
    fun theLobbyReportsEverySeatNotJustTheOccupiedOnes() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "SEAT1",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        room.join("Guest", FakeConnection())

        val lobby = host.sent.filterIsInstance<ServerMessage.LobbyUpdate>().last()
        assertEquals("the room's size, so the lobby can draw the empty seats", 4, lobby.maxPlayers)
        assertEquals(2, lobby.players.size)
    }

    @Test
    fun theLobbyNamesTheHostAndSaysWhoIsAttached() = runBlocking {
        val (room, conns) = roomOf("SEAT2", "Guest")
        val guest = conns[1]

        room.disconnect(1)
        val lobby = conns[0].sent.filterIsInstance<ServerMessage.LobbyUpdate>().last()

        assertEquals(0, lobby.hostPlayerId)
        assertEquals(
            "a dropped player still holds their seat, and the lobby has to say so",
            listOf(true, false),
            lobby.connected,
        )
        assertEquals(2, lobby.players.size)
        assertNotNull("the dropped player still holds a token to reconnect with", guest.token)
    }

    @Test
    fun theLobbyFollowsTheHostWhenItMoves() = runBlocking {
        val (room, conns) = roomOf("SEAT3", "Guest")

        room.leave(0)

        // The remaining player is renumbered to seat 0 and is now the host; a client that
        // missed HostPromoted still learns both from this one message.
        val lobby = conns[1].sent.filterIsInstance<ServerMessage.LobbyUpdate>().last()
        assertEquals(0, lobby.hostPlayerId)
        assertEquals(1, lobby.players.size)
        assertEquals(listOf(true), lobby.connected)
    }

    /**
     * The lobby promises the host that empty seats are simply not raced. This is that promise.
     */
    @Test
    fun emptySeatsAreSimplyNotRaced() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "SEAT4",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        room.setReady(0, true)
        room.addAiPlayer(0)
        host.clearSent()

        room.startRace(0)

        val placement = host.sent.filterIsInstance<ServerMessage.PlacementStarted>().last()
        assertEquals("two of four seats taken races two cars", 2, placement.placement.configs.size)
    }

    // -----------------------------------------------------------------------------------
    // Host promotion
    //
    // The invariant: after a room is opened, it always has exactly one host, or it is closed.
    // A room with players but no host cannot be started, cannot change track, and cannot be
    // returned to the lobby - nobody can manage it and it sits until the idle sweep.
    // -----------------------------------------------------------------------------------

    private fun roomOf(code: String, vararg guestNames: String): Pair<Room, List<FakeConnection>> {
        val host = FakeConnection()
        val room = Room(
            code = code,
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        val guests = guestNames.map { name -> FakeConnection().also { runBlocking { room.join(name, it) } } }
        return room to listOf(host) + guests
    }

    @Test
    fun theHostLeavingPromotesTheNextPlayer() = runBlocking {
        val (room, conns) = roomOf("PROM1", "Guest")
        val guest = conns[1]
        guest.clearSent()

        room.leave(0)

        assertNotNull("the remaining player must be told they are now the host", guest.last<ServerMessage.HostPromoted>())
        assertFalse("the room must stay open", room.isFinished())
        // ...and the promotion has to be real, not just announced: they can now start a race.
        guest.clearSent()
        room.startRace(0)
        assertNull(
            "the promoted host must not be told only the host can start",
            guest.sent.filterIsInstance<ServerMessage.Error>()
                .lastOrNull { it.message.contains("host", ignoreCase = true) },
        )
    }

    @Test
    fun theHostDroppingPromotesTheNextPlayer() = runBlocking {
        val (room, conns) = roomOf("PROM2", "Guest")
        val guest = conns[1]
        guest.clearSent()

        room.disconnect(0)

        assertNotNull(guest.last<ServerMessage.HostPromoted>())
        assertFalse(room.isFinished())
    }

    @Test
    fun theLastPlayerLeavingClosesTheRoom() = runBlocking {
        val (room, _) = roomOf("PROM3")

        room.leave(0)

        assertTrue("a room with nobody in it must not linger", room.isFinished())
    }

    /**
     * The case that has no good answer without one: the host leaves while the only other
     * player is *dropped but not gone* - mid-reconnect. Promoting them is right; closing the
     * room would take away the seat they are about to come back to.
     */
    @Test
    fun aDroppedPlayerIsStillPromotedRatherThanLosingTheRoom() = runBlocking {
        val (room, conns) = roomOf("PROM4", "Guest")
        val guestToken = conns[1].token!!
        room.disconnect(1)

        room.leave(0)

        assertFalse("the room must be held for the dropped player", room.isFinished())
        val returning = FakeConnection().also { it.token = guestToken }
        assertTrue(room.reconnect(guestToken, returning))
        assertEquals(
            "the player who comes back must be the host",
            true,
            returning.last<ServerMessage.Reconnected>()?.isHost,
        )
    }

    @Test
    fun promotionSkipsAiSeats() = runBlocking {
        val (room, conns) = roomOf("PROM5", "Guest")
        room.setReady(0, true)
        room.setReady(1, true)
        // Two humans; the race starts with exactly the seats that are taken.
        room.startRace(0)
        conns[1].clearSent()

        room.leave(0)

        assertNotNull("the human must be promoted, never an AI", conns[1].last<ServerMessage.HostPromoted>())
        assertFalse(room.isFinished())
    }

    @Test
    fun everyRoomAlwaysHasExactlyOneHostOrIsClosed() = runBlocking {
        // Walks a room down to nothing, checking the invariant after every departure.
        val (room, conns) = roomOf("PROM6", "B", "C")
        for (departing in listOf(0, 0, 0)) {
            room.leave(departing)
            if (room.isFinished()) break
            assertEquals(
                "a live room must have exactly one host",
                1,
                room.hostCountForTest(),
            )
        }
        assertTrue("the room closes once the last player has gone", room.isFinished())
        assertEquals(3, conns.size)
    }

    // -----------------------------------------------------------------------------------
    // Seat renumbering
    //
    // The server has always renumbered seats when the roster shrinks - GameState.isConsistent
    // requires ids to be exactly the list indices - and has never told the clients. A client
    // kept the id it was handed at join, so after a mid-lobby departure it highlighted the
    // wrong row as "you" and could not recognise its own turn.
    // -----------------------------------------------------------------------------------

    private fun lobbyOfFour(): Triple<Room, List<FakeConnection>, Unit> {
        val host = FakeConnection()
        val room = Room(
            code = "SEATS",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 4,
            hostName = "Host",
            hostConnection = host,
        )
        val guests = List(3) { FakeConnection() }
        runBlocking { guests.forEach { room.join("Guest", it) } }
        return Triple(room, listOf(host) + guests, Unit)
    }

    @Test
    fun kickingAMiddleSeatTellsTheSeatsBelowItThatTheyMoved() = runBlocking {
        val (room, conns) = lobbyOfFour().let { it.first to it.second }
        // Seats are 0 (host), 1, 2, 3. Kicking seat 1 shifts 2 -> 1 and 3 -> 2.
        conns.forEach { it.clearSent() }

        room.kickPlayer(hostId = 0, targetId = 1)

        assertEquals(
            "the player who was seat 2 must be told they are now seat 1",
            1,
            conns[2].last<ServerMessage.SeatAssigned>()?.playerId,
        )
        assertEquals(
            "the player who was seat 3 must be told they are now seat 2",
            2,
            conns[3].last<ServerMessage.SeatAssigned>()?.playerId,
        )
        // The connection's own view must agree with what the server thinks.
        assertEquals(1, conns[2].playerId)
        assertEquals(2, conns[3].playerId)
    }

    @Test
    fun aSeatThatDidNotMoveIsNotToldItDid() = runBlocking {
        val (room, conns) = lobbyOfFour().let { it.first to it.second }
        conns.forEach { it.clearSent() }

        room.kickPlayer(hostId = 0, targetId = 3)

        assertNull("seat 0 did not move", conns[0].last<ServerMessage.SeatAssigned>())
        assertNull("seat 1 did not move", conns[1].last<ServerMessage.SeatAssigned>())
        assertNull("seat 2 did not move", conns[2].last<ServerMessage.SeatAssigned>())
    }

    @Test
    fun aPlayerLeavingTheLobbyAlsoRenumbersTheSeatsBelowThem() = runBlocking {
        val (room, conns) = lobbyOfFour().let { it.first to it.second }
        conns.forEach { it.clearSent() }

        room.leave(1)

        assertEquals(1, conns[2].last<ServerMessage.SeatAssigned>()?.playerId)
        assertEquals(2, conns[3].last<ServerMessage.SeatAssigned>()?.playerId)
    }

    // -----------------------------------------------------------------------------------
    // Resync
    // -----------------------------------------------------------------------------------

    @Test
    fun aResyncRequestIsAnsweredWithTheFullStateForThePhase() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "RESYNC",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )
        val guest = FakeConnection()
        room.join("Guest", guest)

        // In the lobby, the full state is the lobby.
        guest.clearSent()
        room.resync(guest)
        assertNotNull(guest.last<ServerMessage.LobbyUpdate>())

        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        // In placement, it is a full PlacementUpdate - never a delta, which is the whole point.
        guest.clearSent()
        room.resync(guest)
        assertNotNull(guest.last<ServerMessage.PlacementUpdate>())
        assertNull(guest.last<ServerMessage.PlacementDelta>())
    }

    /**
     * An illegal move from a well-behaved client is the symptom of divergence: it picked a
     * target that is legal on the board it thinks it has. Answering with only an error left it
     * stuck on that wrong board.
     */
    @Test
    fun anIllegalMoveIsAnsweredWithAFullStateNotJustAnError() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "ILLEG",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
            placementTimeoutMs = 50L,
        )
        val guest = FakeConnection()
        room.join("Guest", guest)
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)
        delay(600) // let placement auto-complete

        val state = latestRaceState(host.sent)
        val mover = state.currentPlayer.id
        val moverConn = if (mover == 0) host else guest
        moverConn.clearSent()

        // Nowhere near any legal move.
        room.chooseMove(mover, state.revision, info.piepgras.gridracer.engine.Point(-99, -99))

        assertNotNull(moverConn.last<ServerMessage.Error>())
        assertNotNull(
            "the client must be handed the authoritative state, not just told it was wrong",
            moverConn.last<ServerMessage.StateSync>(),
        )
    }

    // -----------------------------------------------------------------------------------
    // Idle reaping
    //
    // Limits.EMPTY_LOBBY_GRACE_MILLIS / IDLE_LOBBY_MILLIS / IDLE_RACE_MILLIS existed since
    // the limits file was written and were enforced nowhere. These pin down that they are.
    // -----------------------------------------------------------------------------------

    /** A room with a clock the test controls, so idleness is asserted rather than waited for. */
    private fun idleTestRoom(host: FakeConnection, now: () -> Long) = Room(
        code = "IDLE1",
        track = Tracks.all.first(),
        laps = 1,
        maxPlayers = 2,
        hostName = "Host",
        hostConnection = host,
        clock = now,
    )

    @Test
    fun aFreshLobbyIsNotIdle() = runBlocking {
        var now = 1_000_000L
        val room = idleTestRoom(FakeConnection()) { now }

        assertNull(room.idleReason(now))
        now += Limits.IDLE_LOBBY_MILLIS - 1
        assertNull(room.idleReason(now))
    }

    @Test
    fun aLobbyNobodyHasTouchedForHalfAnHourIsIdle() = runBlocking {
        var now = 1_000_000L
        val host = FakeConnection()
        val room = idleTestRoom(host) { now }
        room.setReady(0, true) // activity

        now += Limits.IDLE_LOBBY_MILLIS + 1
        assertEquals("Lobby idle", room.idleReason(now))
    }

    @Test
    fun activityResetsTheIdleClock() = runBlocking {
        var now = 1_000_000L
        val host = FakeConnection()
        val room = idleTestRoom(host) { now }

        now += Limits.IDLE_LOBBY_MILLIS - 1
        room.setReady(0, true)
        now += 2
        assertNull("a room somebody just used is not idle", room.idleReason(now))
    }

    @Test
    fun aRaceNobodyHasMovedInIsIdleOnTheShorterRaceTimeout() = runBlocking {
        var now = 1_000_000L
        val host = FakeConnection()
        val room = idleTestRoom(host) { now }
        val guest = FakeConnection()
        room.join("Guest", guest)
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        // A race is idle sooner than a lobby: the per-turn forfeit should already have moved
        // things along, so reaching this timeout means the room is stuck in a way the turn
        // timer cannot see.
        now += Limits.IDLE_RACE_MILLIS - 1
        assertNull(room.idleReason(now))
        now += 2
        assertEquals("Race idle", room.idleReason(now))
    }

    /**
     * A transient drop no longer closes the room, even for a solo host. Closing immediately
     * made the advertised reconnect unreachable: `Reconnect` is refused once a room is
     * FINISHED, so a host whose phone changed networks lost the room and everyone in it. The
     * room is held for the grace period and reaped only if nobody comes back.
     */
    @Test
    fun aRoomEveryoneDroppedOutOfIsHeldForTheGracePeriodThenReaped() = runBlocking {
        var now = 1_000_000L
        val host = FakeConnection()
        val room = idleTestRoom(host) { now }

        room.disconnect(0)
        assertFalse("a transient drop must not close the room", room.isFinished())

        now += Limits.EMPTY_LOBBY_GRACE_MILLIS - 1
        assertNull("the player may still be reconnecting", room.idleReason(now))

        now += 2
        assertEquals("Everyone left", room.idleReason(now))
    }

    @Test
    fun aReconnectClearsTheEmptyRoomClock() = runBlocking {
        var now = 1_000_000L
        val host = FakeConnection()
        val room = idleTestRoom(host) { now }
        val token = host.token!!

        room.disconnect(0)
        now += Limits.EMPTY_LOBBY_GRACE_MILLIS - 1

        val returning = FakeConnection().also { it.token = token }
        assertTrue("the dropped host must be able to come back", room.reconnect(token, returning))

        now += 2
        assertNull("someone came back, so the room is not empty", room.idleReason(now))
        assertNotNull(returning.last<ServerMessage.Reconnected>())
    }

    /** An explicit Leave is different from a drop: the player said goodbye, so the room closes. */
    @Test
    fun theLastPlayerLeavingExplicitlyStillClosesTheRoom() = runBlocking {
        val host = FakeConnection()
        val room = idleTestRoom(host) { 1_000_000L }

        room.leave(0)

        assertTrue(room.isFinished())
    }

    @Test
    fun aFinishedRoomIsNeverReportedIdle() = runBlocking {
        var now = 1_000_000L
        val room = idleTestRoom(FakeConnection()) { now }
        room.closeRoom("done")

        now += Limits.IDLE_LOBBY_MILLIS * 10
        // removeFinished already reaps these; reporting them idle too would double-close.
        assertNull(room.idleReason(now))
    }

    /**
     * A player who drops without pressing Leave keeps their seat for a reconnect - but they
     * are not there to press Ready, and counting them as un-ready left the host unable to
     * start the race at all, with no way to clear it short of leaving the room themselves.
     */
    @Test
    fun aDisconnectedLobbyPlayerDoesNotBlockTheStart() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "DROP1",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )
        val guest = FakeConnection()
        room.join("Guest", guest)
        room.setReady(0, true)
        // The guest never presses Ready and then drops off the network.
        room.disconnect(1)
        host.clearSent()

        room.startRace(0)

        assertNull(
            "a dropped player must not veto the start",
            host.sent.filterIsInstance<ServerMessage.Error>()
                .lastOrNull { it.message.contains("ready", ignoreCase = true) },
        )
        assertNotNull(host.sent.filterIsInstance<ServerMessage.PlacementStarted>().lastOrNull())
    }

    /**
     * A connected player who has not pressed Ready still does block it - the fix above must
     * not have turned the readiness check off altogether.
     */
    @Test
    fun aConnectedUnreadyPlayerStillBlocksTheStart() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "DROP2",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
        )
        room.join("Guest", FakeConnection())
        room.setReady(0, true)
        host.clearSent()

        room.startRace(0)

        assertNotNull(
            host.sent.filterIsInstance<ServerMessage.Error>()
                .lastOrNull { it.message.contains("ready", ignoreCase = true) },
        )
    }

    /**
     * The placement phase had no timeout at all, so a client that hung on the placement screen
     * held the room - and everyone in it - forever. A slot is auto-chosen instead.
     */
    @Test
    fun anUnansweredPlacementTurnIsChosenAutomatically() = runBlocking {
        val host = FakeConnection()
        val room = Room(
            code = "PLACE1",
            track = Tracks.all.first(),
            laps = 1,
            maxPlayers = 2,
            hostName = "Host",
            hostConnection = host,
            placementTimeoutMs = 50L,
        )
        val guest = FakeConnection()
        room.join("Guest", guest)
        room.setReady(0, true)
        room.setReady(1, true)
        room.startRace(0)

        // Nobody picks. Two auto-choices (one per seat) plus scheduling slack.
        delay(600)

        assertNotNull(
            "the race must start even though no human ever picked a slot",
            host.sent.filterIsInstance<ServerMessage.RaceStarted>().lastOrNull(),
        )
    }
}
