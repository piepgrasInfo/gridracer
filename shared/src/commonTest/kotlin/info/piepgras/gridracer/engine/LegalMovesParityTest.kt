package info.piepgras.gridracer.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the legal-move rules to what they were before they moved into [MoveRules] and gained the
 * gravel trap: with the gravel trap off, [GameEngine.legalMoves] must give exactly what the
 * original implementation, kept below verbatim, gives - on every built-in track and a hand-drawn
 * one, from random positions, speeds, hazards, slips and traffic.
 */
class LegalMovesParityTest {

    @Test
    fun legalMoves_withTheGravelTrapOff_matchTheOriginalImplementation() {
        val tracks = Tracks.all + TrackBuilder.build("Drawn", UserDrawnTrackFixture.draft)
        val random = Random(42)
        for (track in tracks) {
            repeat(3_000) {
                val state = randomState(track, random)
                for (rules in listOf(RaceRules.LEGACY, RaceRules.current(gravelTrap = false))) {
                    val withRules = state.copy(rules = rules)
                    assertEquals(
                        Reference.legalMoves(withRules, 0),
                        GameEngine.legalMoves(withRules, 0),
                        "${track.name}: ${withRules.players}",
                    )
                }
            }
        }
    }

    private fun randomState(track: Track, random: Random): GameState {
        val position = Point(random.nextInt(0, track.width + 1), random.nextInt(0, track.height + 1))
        val velocity = Point(random.nextInt(-4, 5), random.nextInt(-4, 5))
        val starting = random.nextInt(4) == 0
        val position0 = if (starting) track.startPositions.random(random) else position
        val pending = buildSet {
            if (random.nextBoolean()) add(ModifierType.OIL_SPILL)
            if (random.nextInt(3) == 0) add(ModifierType.NAILS)
        }
        val slips = if (ModifierType.OIL_SPILL in pending && random.nextBoolean()) {
            val offsets = (-1..1).flatMap { dx -> (-1..1).map { dy -> Point(dx, dy) } }.filterNot { it == Point(0, 0) }.shuffled(random)
            offsets.take(2)
        } else {
            emptyList()
        }
        val me = PlayerState(
            id = 0,
            position = position0,
            lastVelocity = if (starting) Point(0, 0) else velocity,
            path = if (starting) listOf(position0) else listOf(position0 - velocity, position0),
            pendingModifiers = pending,
            oilSlips = slips,
        )
        val others = (1..random.nextInt(0, 4)).map { id ->
            val near = position0 + velocity + Point(random.nextInt(-2, 3), random.nextInt(-2, 3))
            PlayerState(
                id = id,
                position = near,
                status = if (random.nextInt(5) == 0) PlayerStatus.CRASHED else PlayerStatus.RACING,
            )
        }
        return GameState(track = track, players = listOf(me) + others, currentPlayerIndex = 0)
    }

    /** The legal-move rules exactly as they were written before this change. */
    private object Reference {
        fun legalMoves(state: GameState, playerId: Int): Set<Point> {
            val player = state.players.firstOrNull { it.id == playerId } ?: return emptySet()
            if (player.status != PlayerStatus.RACING) return emptySet()
            val effects = ModifierType.entries.filter { it in player.pendingModifiers }
            if (effects.size == 2 && ModifierType.OIL_SPILL in effects && ModifierType.NAILS in effects) {
                val legalOil = filterLegalForOil(state, player, playerId, oilSlipTargets(player))
                val nailAllowed = nailAllowedSet(player)
                val intersection = legalOil intersect nailAllowed
                if (intersection.isNotEmpty()) return intersection
                return filterLegal(state, player, playerId, nailAllowed)
            }
            var remaining = effects
            while (true) {
                var candidates = candidateMoves(player)
                for (effect in remaining) {
                    candidates = when (effect) {
                        ModifierType.OIL_SPILL -> candidates intersect oilSlipTargets(player)
                        ModifierType.NAILS -> candidates intersect nailAllowedSet(player)
                    }
                }
                val moves = if (ModifierType.OIL_SPILL in remaining) {
                    filterLegalForOil(state, player, playerId, candidates)
                } else {
                    filterLegal(state, player, playerId, candidates)
                }
                if (moves.isNotEmpty() || remaining.isEmpty()) return moves
                remaining = remaining.dropLast(1)
            }
        }

        private fun candidateMoves(player: PlayerState): Set<Point> {
            val center = player.position + player.lastVelocity
            return buildSet { for (dx in -1..1) for (dy in -1..1) add(center + Point(dx, dy)) }
        }

        private fun oilSlipTargets(player: PlayerState): Set<Point> {
            val coast = player.position + player.lastVelocity
            if (player.oilSlips.isEmpty()) return setOf(coast)
            return setOf(coast) + player.oilSlips.map { coast + it }
        }

        private fun nailAllowedSet(player: PlayerState): Set<Point> {
            val speed = maxOf(kotlin.math.abs(player.lastVelocity.x), kotlin.math.abs(player.lastVelocity.y))
            if (speed > 1 || player.lastVelocity == Point(0, 0)) {
                return candidateMoves(player).sortedBy { c -> val v = c - player.position; v.x * v.x + v.y * v.y }.take(3).toSet()
            }
            val v = player.lastVelocity
            val base = mutableSetOf(
                player.position,
                player.position + v,
                player.position + Point(-v.y, v.x),
                player.position + Point(v.y, -v.x),
            )
            if (v.x != 0 && v.y != 0) {
                base.add(player.position + Point(v.x, 0))
                base.add(player.position + Point(0, v.y))
            }
            return base
        }

        private fun filterLegal(state: GameState, player: PlayerState, playerId: Int, candidates: Set<Point>): Set<Point> {
            val clear = candidates.filterTo(mutableSetOf()) { isPathClear(state, player.position, it, playerId) }
            if (!isStartingMove(state.track, player)) return clear
            val forward = clear.filterTo(mutableSetOf()) { crossesForward(state.track, player.position, it) }
            if (forward.isNotEmpty()) return forward
            return setOf(player.position)
        }

        private fun filterLegalForOil(state: GameState, player: PlayerState, playerId: Int, candidates: Set<Point>): Set<Point> {
            val wallClear = candidates.filterTo(mutableSetOf()) { wallsClear(state.track, player.position, it) }
            if (isStartingMove(state.track, player)) {
                val forward = wallClear.filterTo(mutableSetOf()) { crossesForward(state.track, player.position, it) }
                if (forward.isNotEmpty()) return splitFreeAndOccupied(state, playerId, forward)
                return splitFreeAndOccupied(state, playerId, setOf(player.position))
            }
            return splitFreeAndOccupied(state, playerId, wallClear)
        }

        private fun splitFreeAndOccupied(state: GameState, selfId: Int, candidates: Set<Point>): Set<Point> {
            val occupied = state.players.filter { it.id != selfId && it.status == PlayerStatus.RACING }.mapTo(mutableSetOf()) { it.position }
            val free = candidates.filterTo(mutableSetOf()) { it !in occupied }
            return if (free.isNotEmpty()) free else candidates
        }

        private fun wallsClear(track: Track, from: Point, to: Point): Boolean =
            track.walls.none { Geometry.segmentsIntersect(from * track.subdivision, to * track.subdivision, it.from, it.to) }

        private fun isPathClear(state: GameState, from: Point, to: Point, selfId: Int): Boolean {
            if (!wallsClear(state.track, from, to)) return false
            val occupied = state.players.filter { it.id != selfId && it.status == PlayerStatus.RACING }.mapTo(mutableSetOf()) { it.position }
            return Geometry.latticePointsOn(from, to).none { it in occupied }
        }

        private fun crossesForward(track: Track, from: Point, to: Point): Boolean {
            if (!Geometry.segmentsIntersect(from * track.subdivision, to * track.subdivision, track.finishLine.from, track.finishLine.to)) {
                return false
            }
            val movement = to - from
            return movement.x * track.finishDirection.x + movement.y * track.finishDirection.y > 0
        }

        private fun isStartingMove(track: Track, player: PlayerState): Boolean {
            if (player.path.size != 1) return false
            val scaled = player.position * track.subdivision
            val tolerance = track.startZoneDepth * track.subdivision + 1e-9
            return Geometry.distancePointToSegmentSq(scaled, track.finishLine.from, track.finishLine.to) <= tolerance * tolerance
        }
    }
}
