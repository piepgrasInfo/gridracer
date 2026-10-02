package info.piepgras.gridracer.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlacementTest {

    private val configs = listOf(
        PlayerConfig("P1", isAi = false),
        PlayerConfig("P2", isAi = false),
        PlayerConfig("P3", isAi = true),
    )

    @Test
    fun start_sequentialOrderMatchesConfigOrder() {
        val state = Placement.start(Tracks.OVAL, configs, randomized = false)
        assertEquals(listOf(0, 1, 2), state.order)
        assertEquals(0, state.currentPlayerIndex)
    }

    @Test
    fun start_randomizedOrderIsAPermutationOfConfigIndices() {
        val state = Placement.start(Tracks.OVAL, configs, randomized = true, random = Random(42))
        assertEquals(setOf(0, 1, 2), state.order.toSet())
        assertEquals(3, state.order.size)
    }

    @Test
    fun start_randomizedOrderIsDeterministicForASeededRandom() {
        val a = Placement.start(Tracks.OVAL, configs, randomized = true, random = Random(7))
        val b = Placement.start(Tracks.OVAL, configs, randomized = true, random = Random(7))
        assertEquals(a.order, b.order)
    }

    @Test
    fun choose_assignsTheSlotAndAdvancesToTheNextPlayer() {
        var state = Placement.start(Tracks.OVAL, configs, randomized = false)
        val firstSlot = state.availableSlots.first()
        state = Placement.choose(state, firstSlot)
        assertEquals(mapOf(0 to firstSlot), state.assignments)
        assertEquals(1, state.currentPlayerIndex)
        assertFalse(firstSlot in state.availableSlots)
    }

    @Test
    fun choose_rejectsASlotThatIsNotAvailable() {
        var state = Placement.start(Tracks.OVAL, configs, randomized = false)
        val slot = state.availableSlots.first()
        state = Placement.choose(state, slot)
        assertFalse(slot in state.availableSlots)
        val error = runCatching { Placement.choose(state, slot) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun autoChoose_onlyPicksFromAvailableSlots() {
        var state = Placement.start(Tracks.OVAL, configs, randomized = false)
        repeat(configs.size) {
            val before = state.availableSlots.toSet()
            state = Placement.autoChoose(state, Random(it))
            assertTrue(state.assignments.values.last() in before)
        }
        assertTrue(state.isComplete)
    }

    @Test
    fun toStartAssignments_returnsPositionsInPlayerIdOrder() {
        var state = Placement.start(Tracks.OVAL, configs, randomized = true, random = Random(3))
        while (!state.isComplete) {
            state = Placement.autoChoose(state, Random(state.currentOrderIndex))
        }
        val assignments = Placement.toStartAssignments(state)
        assertEquals(3, assignments.size)
        assignments.forEachIndexed { playerId, position ->
            assertEquals(state.assignments.getValue(playerId), position)
        }
    }
}
