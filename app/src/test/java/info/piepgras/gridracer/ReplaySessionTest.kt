package info.piepgras.gridracer

import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.PlayerConfig
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.Tracks
import info.piepgras.gridracer.replay.MoveRecord
import info.piepgras.gridracer.replay.ReplayData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySessionTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val start = GameEngine.newGame(Tracks.OVAL, 2)
    private val configs = listOf(PlayerConfig("A", false), PlayerConfig("B", false))

    private fun firstLegalMove(playerId: Int) = GameEngine.legalMoves(start, playerId).first()

    @Test
    fun aReplayWhoseMovesAllPlay_isNotTruncated() {
        val replay = ReplayData("Oval", configs, start, listOf(MoveRecord(0, firstLegalMove(0), 1L)))
        val session = ReplaySession(replay, scope)
        assertFalse(session.isTruncated)
        assertEquals(1, session.totalSteps)
    }

    @Test
    fun aReplayWithAMoveTheRulesRefuse_stopsThereInsteadOfCrashing() {
        val impossible = MoveRecord(1, Point(1000, 1000), 2L)
        val replay = ReplayData("Oval", configs, start, listOf(MoveRecord(0, firstLegalMove(0), 1L), impossible, MoveRecord(0, Point(0, 0), 3L)))
        val session = ReplaySession(replay, scope)
        assertTrue(session.isTruncated)
        assertEquals(1, session.totalSteps)
    }
}
