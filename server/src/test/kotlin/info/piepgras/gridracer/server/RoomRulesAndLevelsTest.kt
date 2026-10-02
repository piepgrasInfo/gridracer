package info.piepgras.gridracer.server

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.PlacementState
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.SimpleAi
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.multiplayer.ErrorCode
import info.piepgras.gridracer.multiplayer.ServerMessage
import info.piepgras.gridracer.multiplayer.applyDelta
import info.piepgras.gridracer.replay.ReplayData
import info.piepgras.gridracer.server.ai.AiPlannerService
import info.piepgras.gridracer.server.log.RaceLogSummary
import info.piepgras.gridracer.server.log.RaceLogger
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol 9 in a room: the gravel trap, who may race it, and computer levels. */
class RoomRulesAndLevelsTest {

    private fun oldClient() = FakeConnection().apply { protocolVersion = 8 }

    private fun room(
        host: FakeConnection,
        gravelTrap: Boolean = false,
        planners: AiPlannerService? = null,
        logger: RaceLogger = RaceLogger.Disabled,
        planWaitMs: Long = 3_000,
    ) = Room(
        code = "RULE",
        track = Tracks.OVAL,
        laps = 1,
        maxPlayers = 4,
        hostName = "Host",
        hostConnection = host,
        random = Random(5),
        gravelTrap = gravelTrap,
        aiPlanners = planners,
        raceLogger = logger,
        aiPlanWaitMs = planWaitMs,
    )

    private fun lobby(connection: FakeConnection): ServerMessage.LobbyUpdate =
        connection.sent.filterIsInstance<ServerMessage.LobbyUpdate>().last()

    private fun errors(connection: FakeConnection): List<ServerMessage.Error> = connection.sent.filterIsInstance<ServerMessage.Error>()

    private fun latestPlacement(messages: List<ServerMessage>): PlacementState? {
        val startedIndex = messages.indexOfLast { it is ServerMessage.PlacementStarted }
        if (startedIndex < 0) return null
        var placement = (messages[startedIndex] as ServerMessage.PlacementStarted).placement
        for (i in startedIndex + 1 until messages.size) {
            placement = when (val message = messages[i]) {
                is ServerMessage.PlacementUpdate -> message.placement
                is ServerMessage.PlacementDelta -> placement.applyDelta(message.delta) ?: error("stale placement delta")
                else -> placement
            }
        }
        return placement
    }

    private fun latestRace(messages: List<ServerMessage>): GameState? {
        val startedIndex = messages.indexOfLast { it is ServerMessage.RaceStarted }
        if (startedIndex < 0) return null
        var state = (messages[startedIndex] as ServerMessage.RaceStarted).state
        for (i in startedIndex + 1 until messages.size) {
            state = when (val message = messages[i]) {
                is ServerMessage.StateSync -> message.state
                is ServerMessage.RaceDelta -> state.applyDelta(message.delta) ?: error("stale race delta")
                else -> state
            }
        }
        return state
    }

    /** Places and races the host's car (seat 0) with [SimpleAi] until the race is over; the computer seats drive themselves. */
    private suspend fun raceHostToTheEnd(room: Room, host: FakeConnection) {
        withTimeout(30_000) {
            while (true) {
                val race = latestRace(host.sent)
                if (race == null) {
                    val placement = latestPlacement(host.sent)
                    if (placement != null && !placement.isComplete && placement.currentPlayerIndex == 0) {
                        room.choosePlacement(0, placement.availableSlots.first())
                    } else {
                        delay(20)
                    }
                    continue
                }
                if (race.phase == GamePhase.RACE_OVER) return@withTimeout
                if (race.currentPlayer.id == 0) {
                    val move = SimpleAi.chooseMove(race, 0) ?: return@withTimeout
                    room.chooseMove(0, move)
                } else {
                    delay(20)
                }
            }
        }
    }

    @Test
    fun aGravelRoom_keepsOutClientsTooOldToPlayIt() = runBlocking {
        val room = room(FakeConnection(), gravelTrap = true)
        val joiner = oldClient()
        assertNull(room.join("Old", joiner))
        assertEquals(ErrorCode.UNSUPPORTED, errors(joiner).single().code)
        val watcher = oldClient()
        assertFalse(room.spectate(watcher))
        assertEquals(ErrorCode.UNSUPPORTED, errors(watcher).single().code)
        assertNotNull(room.join("New", FakeConnection()))
    }

    @Test
    fun theGravelTrap_cannotBeSwitchedOnWhileAnOldClientIsInTheRoom() = runBlocking {
        val host = FakeConnection()
        val room = room(host)
        val oldId = room.join("Old", oldClient())!!
        room.changeGravelTrap(0, true)
        assertEquals(ErrorCode.UNSUPPORTED, errors(host).last().code)
        assertFalse(lobby(host).gravelTrap)

        room.kickPlayer(0, oldId)
        room.changeGravelTrap(0, true)
        assertTrue(lobby(host).gravelTrap)
        assertTrue(room.toSummary().gravelTrap)
    }

    @Test
    fun aRace_startsOnTheCurrentRules_withTheRoomsGravelSetting() = runBlocking {
        val host = FakeConnection()
        val room = room(host, gravelTrap = true)
        room.addAiPlayer(0)
        room.setReady(0, true)
        room.startRace(0)
        raceHostToTheEnd(room, host)
        val started = host.sent.filterIsInstance<ServerMessage.RaceStarted>().single()
        assertEquals(RaceRules.current(gravelTrap = true), started.state.rules)
    }

    @Test
    fun computerSeats_carryTheirLevel_throughChangesAndRenumbering() = runBlocking {
        val host = FakeConnection()
        val room = room(host)
        room.addAiPlayer(0, AiLevel.EASY)
        room.addAiPlayer(0, AiLevel.BEST)
        assertEquals(listOf(AiLevel.EASY, AiLevel.EASY, AiLevel.BEST), lobby(host).players.map { it.aiLevel })

        room.changeAiLevel(0, 1, AiLevel.NORMAL)
        assertEquals(AiLevel.NORMAL, lobby(host).players[1].aiLevel)

        room.changeAiLevel(0, 0, AiLevel.BEST)
        assertEquals(ErrorCode.INVALID_INPUT, errors(host).last().code)

        // Removing seat 1 moves the BEST driver from seat 2 to seat 1; its level goes with it.
        room.kickPlayer(0, 1)
        assertEquals(listOf(false, true), lobby(host).players.map { it.isAi })
        assertEquals(AiLevel.BEST, lobby(host).players[1].aiLevel)
    }

    @Test
    fun aReplay_isNotSentToAClientThatWouldReplayItByTheOldRules() = runBlocking {
        val replays = mutableListOf<ReplayData>()
        val logger = object : RaceLogger {
            override val enabled = true
            override val serverId = "test"
            override val serverName = "Test"
            override suspend fun enqueue(replay: ReplayData, summary: RaceLogSummary) {
                replays += replay
            }
            override suspend fun sweep() = 0
            override suspend fun purgeAccount(publicId: String) = 0
        }
        val host = FakeConnection()
        val room = room(host, logger = logger)
        room.addAiPlayer(0)
        val watcher = oldClient()
        assertTrue(room.spectate(watcher))
        room.setReady(0, true)
        room.startRace(0)
        raceHostToTheEnd(room, host)

        assertEquals(1, replays.size)
        assertEquals(1, host.sent.filterIsInstance<ServerMessage.RaceReplay>().size)
        assertTrue(watcher.sent.filterIsInstance<ServerMessage.RaceReplay>().isEmpty())
        assertTrue("the old client still sees the race end", watcher.sent.any { it is ServerMessage.RaceFinished })
    }

    @Test
    fun aBestComputerSeat_racesByItsTableToTheEnd() = runBlocking {
        val planners = AiPlannerService()
        try {
            val host = FakeConnection()
            val room = room(host, gravelTrap = true, planners = planners)
            room.addAiPlayer(0, AiLevel.BEST)
            room.setReady(0, true)
            room.startRace(0)
            raceHostToTheEnd(room, host)
            val final = latestRace(host.sent)!!
            assertEquals(GamePhase.RACE_OVER, final.phase)
            assertEquals(PlayerStatus.FINISHED, final.players[1].status)
        } finally {
            planners.close()
        }
    }

    @Test
    fun whileATableIsStillBuilding_theRoomKeepsAnswering_andThenFallsBackToEasy() = runBlocking {
        val release = CountDownLatch(1)
        val planners = AiPlannerService(build = { _, _, _, _ ->
            release.await()
            null
        })
        try {
            val host = FakeConnection()
            val room = room(host, planners = planners, planWaitMs = 300)
            room.addAiPlayer(0, AiLevel.NORMAL)
            room.setReady(0, true)
            room.startRace(0)
            // The computer seat's turns wait for a table that never comes; the room must not
            // hold its lock meanwhile.
            withTimeout(200) { room.toSummary() }
            raceHostToTheEnd(room, host)
            assertEquals(GamePhase.RACE_OVER, latestRace(host.sent)!!.phase)
        } finally {
            release.countDown()
            planners.close()
        }
    }
}
