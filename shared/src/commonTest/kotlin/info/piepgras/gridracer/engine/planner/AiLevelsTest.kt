package info.piepgras.gridracer.engine.planner

import info.piepgras.gridracer.engine.AiLevel
import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GamePhase
import info.piepgras.gridracer.engine.Placement
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.PlayerStatus
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import info.piepgras.gridracer.engine.Tracks
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The three levels against each other: the stronger one must be the faster one. */
class AiLevelsTest {

    private val rules = RaceRules.current(gravelTrap = true)
    private val tables = Tracks.all.associateWith { track ->
        mapOf(
            AiLevel.EASY to null,
            AiLevel.NORMAL to RacePlanner.build(track, rules, AiTuning.NORMAL_PROFILE),
            AiLevel.BEST to RacePlanner.build(track, rules, AiTuning.BEST_PROFILE),
        )
    }

    /** Moves a lone car of [level] takes for three laps, or null if it never finishes. */
    private fun solo(track: Track, level: AiLevel, seed: Int): Int? {
        val random = Random(seed)
        val table = tables.getValue(track)[level]
        var state = GameEngine.newGame(track, listOf(track.startPositions.random(random)), 3, rules).copy(maxRounds = 400)
        var moves = 0
        while (state.phase == GamePhase.IN_PROGRESS) {
            val move = AiDriver.chooseMove(state, 0, level, table, random) ?: break
            state = GameEngine.applyMove(state, 0, move, Random(random.nextLong()))
            moves++
        }
        return if (state.players[0].status == PlayerStatus.FINISHED) moves else null
    }

    @Test
    fun alone_bestIsFastest_thenNormal_thenEasy() {
        val totals = AiLevel.entries.associateWith { level ->
            Tracks.all.sumOf { track -> (0 until 12).sumOf { seed -> solo(track, level, seed) ?: 400 } }
        }
        assertTrue(totals.getValue(AiLevel.BEST) < totals.getValue(AiLevel.NORMAL), "$totals")
        assertTrue(totals.getValue(AiLevel.NORMAL) < totals.getValue(AiLevel.EASY), "$totals")
        for (track in Tracks.all) {
            val best = (0 until 12).map { solo(track, AiLevel.BEST, it) }
            val normal = (0 until 12).map { solo(track, AiLevel.NORMAL, it) }
            assertTrue(best.all { it != null } && normal.all { it != null }, "${track.name}: a planned driver failed to finish")
            assertTrue(best.sumOf { it!! } <= normal.sumOf { it!! }, track.name)
        }
    }

    @Test
    fun inAMixedField_bestWinsMostRaces() {
        var bestWins = 0
        var races = 0
        for (track in Tracks.all) {
            for (seed in 0 until 6) {
                val random = Random(seed)
                val configs = listOf(
                    PlayerConfig("Easy", true, AiLevel.EASY),
                    PlayerConfig("Normal", true, AiLevel.NORMAL),
                    PlayerConfig("Best", true, AiLevel.BEST),
                )
                var placement = Placement.start(track, configs, randomized = true, random = random)
                while (!placement.isComplete) {
                    val level = configs[placement.currentPlayerIndex].aiLevel
                    val slot = AiDriver.chooseStartSlot(placement, 3, level, tables.getValue(track)[level], random)
                    placement = Placement.choose(placement, slot)
                }
                var state = GameEngine.newGame(track, Placement.toStartAssignments(placement), 3, rules)
                while (state.phase == GamePhase.IN_PROGRESS) {
                    val id = state.currentPlayer.id
                    val level = configs[id].aiLevel
                    val move = AiDriver.chooseMove(state, id, level, tables.getValue(track)[level], random)
                    state = if (move == null) GameEngine.forfeit(state, id) else GameEngine.applyMove(state, id, move, Random(random.nextLong()))
                }
                races++
                if (state.players[2].finishRank == 1) bestWins++
            }
        }
        assertTrue(bestWins * 2 > races, "Best won $bestWins of $races")
    }

    @Test
    fun aiLevel_travelsByName_andAnUnknownLevelReadsAsEasy() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals("""{"name":"Bot","isAi":true,"aiLevel":"BEST"}""", json.encodeToString(PlayerConfig.serializer(), PlayerConfig("Bot", true, AiLevel.BEST)))
        val old = json.encodeToString(PlayerConfig.serializer(), PlayerConfig("Bot", true))
        assertFalse("aiLevel" in old, "the default stays off the wire: $old")
        assertEquals(AiLevel.EASY, json.decodeFromString(PlayerConfig.serializer(), old).aiLevel)
        val future = """{"name":"Bot","isAi":true,"aiLevel":"GRANDMASTER"}"""
        assertEquals(AiLevel.EASY, json.decodeFromString(PlayerConfig.serializer(), future).aiLevel)
    }
}
