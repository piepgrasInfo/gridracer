package info.piepgras.gridracer.engine.planner

import info.piepgras.gridracer.engine.GameEngine
import info.piepgras.gridracer.engine.GameState
import info.piepgras.gridracer.engine.ModifierType
import info.piepgras.gridracer.engine.MoveRules
import info.piepgras.gridracer.engine.PlayerState
import info.piepgras.gridracer.engine.Point
import info.piepgras.gridracer.engine.RaceRules
import info.piepgras.gridracer.engine.Track
import kotlin.math.abs
import kotlin.math.max

/**
 * Works out, for every state a lone car can reach on a track, the fewest moves it needs to finish -
 * the table behind the NORMAL and BEST drivers.
 *
 * A car's future depends on where it is, how fast it is going, and - with the gravel trap on -
 * whether the move before last touched the gravel (which decides how much longer it is braked).
 * Everything else the rules care about follows from those: a hazard picked up and a gravel touch
 * are properties of the move that brought the car here, which is its position minus its velocity.
 * So a state is (position, velocity, gravel memory), and a race is a shortest path through them.
 *
 * Building one table:
 * 1. **Enumerate** every state reachable from the start slots, breadth first, with the same legal
 *    moves the race uses ([MoveRules.legalTargets]) for a car alone on the track. Oil is planned by
 *    its average effect - the slips average out to the coast point, so the planned car coasts on -
 *    but every point a real spill could offer is enumerated too, so a car that does slide still
 *    lands on a state with a value.
 * 2. **Value** the states one lap at a time, backwards: V1 is the fewest moves to finish from a
 *    state with one lap to go, V2 with two, and so on. A move over the finish line hands a car from
 *    layer k to layer k-1; within a layer every move costs one. Each layer is a breadth-first search
 *    over the reversed moves, started from the finish-line crossings with the next layer's values.
 *
 * The moves themselves are tested once per (position, velocity) and cached, since thousands of
 * states lead into the same one. The result is immutable and safe to share across threads.
 */
object RacePlanner {

    /** The value of a state from which the planned car cannot finish. */
    const val DOOMED = Int.MAX_VALUE

    private const val INF: Short = Short.MAX_VALUE
    private const val COORD_BITS = 10
    private const val COORD_LIMIT = 1 shl COORD_BITS
    private const val VELOCITY_BIAS = 15

    /**
     * Builds the table for [track] raced under [rules] by a driver with [profile], or returns null
     * when the track is too large for it - more than [maxNodes] states, or too wide to index.
     */
    fun build(
        track: Track,
        rules: RaceRules,
        profile: PlannerProfile,
        maxNodes: Int = AiTuning.MAX_NODES,
    ): PlannerTable? {
        if (profile.maxSpeed !in 1..(VELOCITY_BIAS - 2)) return null
        if (track.startPositions.isEmpty()) return null
        val gravelOn = MoveRules.gravelActive(track, rules)
        val view = if (gravelOn) track.gravelView else null
        val minX = minOf(0, view?.minCoarse?.x ?: 0)
        val minY = minOf(0, view?.minCoarse?.y ?: 0)
        val maxX = maxOf(track.width, view?.maxCoarse?.x ?: 0)
        val maxY = maxOf(track.height, view?.maxCoarse?.y ?: 0)
        if (maxX - minX >= COORD_LIMIT || maxY - minY >= COORD_LIMIT) return null
        return Builder(track, rules, gravelOn, profile, minX, minY, maxX, maxY, maxNodes).build()
    }

    private class Builder(
        val track: Track,
        val rules: RaceRules,
        val gravelOn: Boolean,
        val profile: PlannerProfile,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val maxNodes: Int,
    ) {
        private val facts = IntIntMap()
        private val nodes = IntIntMap()
        private var nodeKeys = IntArray(1024)
        private var nodeCount = 0
        private var edgeStart = IntArray(1024)
        private var edges = IntArray(4096)
        private var edgeCount = 0
        private val starts = track.startPositions
        private var overBudget = false

        fun build(): PlannerTable? {
            // The start slots are the first nodes. They are not in the hash: a car on its opening
            // move plays by the opening-move rule, unlike a car parked on the same point mid-race.
            for (i in starts.indices) newNode(ROOT_KEY)
            var u = 0
            while (u < nodeCount) {
                if (overBudget) return null
                expand(u)
                u++
            }
            if (overBudget) return null
            ensureEdgeStart(nodeCount + 1)
            edgeStart[nodeCount] = edgeCount
            return PlannerTable(
                track = track,
                rules = rules,
                profile = profile,
                gravelOn = gravelOn,
                minX = minX,
                minY = minY,
                maxX = maxX,
                maxY = maxY,
                index = nodes,
                rootCount = starts.size,
                layers = valueLayers(),
            )
        }

        // ---- states -----------------------------------------------------------------------

        private fun inBounds(p: Point): Boolean = p.x in minX..maxX && p.y in minY..maxY

        fun factsKey(q: Point, w: Point): Int =
            ((q.x - minX) shl (COORD_BITS + 10)) or ((q.y - minY) shl 10) or ((w.x + VELOCITY_BIAS) shl 5) or (w.y + VELOCITY_BIAS)

        private fun nodeKey(q: Point, w: Point, memory: Boolean): Int = (factsKey(q, w) shl 1) or (if (memory) 1 else 0)

        private fun newNode(key: Int): Int {
            if (nodeCount >= maxNodes) {
                overBudget = true
                return -1
            }
            if (nodeCount == nodeKeys.size) nodeKeys = nodeKeys.copyOf(nodeKeys.size * 2)
            nodeKeys[nodeCount] = key
            return nodeCount++
        }

        private fun nodeFor(q: Point, w: Point, memory: Boolean): Int {
            val key = nodeKey(q, w, memory)
            val existing = nodes.get(key)
            if (existing != IntIntMap.MISSING) return existing
            val created = newNode(key)
            if (created >= 0) nodes.put(key, created)
            return created
        }

        /**
         * Everything about the move from q - w to q that the rules need, worked out once: whether
         * it is clear of walls and the barrier, how it crosses the finish line, which hazards it
         * picks up and whether it touches the gravel.
         */
        private fun factsOf(q: Point, w: Point): Int {
            val key = factsKey(q, w)
            val cached = facts.get(key)
            if (cached != IntIntMap.MISSING) return cached
            val from = q - w
            var f = 0
            if (MoveRules.segmentClear(track, rules, from, q)) f = f or LEGAL
            when (MoveRules.lapCrossing(track, rules, from, q)) {
                MoveRules.Crossing.FORWARD -> f = f or FORWARD
                MoveRules.Crossing.BACKWARD -> f = f or BACKWARD
                MoveRules.Crossing.NONE -> Unit
            }
            val picked = GameEngine.modifiersOn(track, from, q)
            if (ModifierType.OIL_SPILL in picked) f = f or OIL
            if (ModifierType.NAILS in picked) f = f or NAILS
            if (gravelOn && MoveRules.touchesGravel(track, from, q)) f = f or TOUCHED
            facts.put(key, f)
            return f
        }

        private fun clear(from: Point, to: Point): Boolean {
            val w = to - from
            if (!inBounds(to) || max(abs(w.x), abs(w.y)) > profile.maxSpeed + 1) return false
            return factsOf(to, w) and LEGAL != 0
        }

        private fun expand(u: Int) {
            ensureEdgeStart(u + 1)
            edgeStart[u] = edgeCount
            val root = u < starts.size
            val q: Point
            val w: Point
            val pending: Set<ModifierType>
            val counter: Int
            val memory: Boolean
            if (root) {
                q = starts[u]
                w = Point(0, 0)
                pending = emptySet()
                counter = 0
                memory = false
            } else {
                val key = nodeKeys[u]
                val fk = key ushr 1
                q = Point(((fk ushr (COORD_BITS + 10)) and (COORD_LIMIT - 1)) + minX, ((fk ushr 10) and (COORD_LIMIT - 1)) + minY)
                w = Point(((fk ushr 5) and 31) - VELOCITY_BIAS, (fk and 31) - VELOCITY_BIAS)
                val f = factsOf(q, w)
                pending = buildSet {
                    if (f and OIL != 0) add(ModifierType.OIL_SPILL)
                    if (f and NAILS != 0) add(ModifierType.NAILS)
                }
                val touched = f and TOUCHED != 0
                counter = when {
                    !gravelOn -> 0
                    touched -> MoveRules.GRAVEL_PERSISTS
                    key and 1 != 0 -> 1
                    else -> 0
                }
                memory = gravelOn && touched
            }
            val velocity = if (counter > 0) MoveRules.brake(w) else w
            val clearFn = { from: Point, to: Point -> clear(from, to) }
            val model = MoveRules.legalTargets(
                track = track,
                position = q,
                velocity = velocity,
                pending = pending,
                oilSlips = emptyList(),
                starting = root,
                occupied = emptySet(),
                clear = clearFn,
            )
            val reachable = if (root) model else MoveRules.candidates(q, velocity).filterTo(mutableSetOf()) { clear(q, it) }
            for (t in reachable) {
                val d = t - q
                if (max(abs(d.x), abs(d.y)) > profile.maxSpeed) continue
                if (root && t == q) continue // standing still on the grid: no state to plan from
                val s = nodeFor(t, d, memory)
                if (s < 0) return
                if (t !in model) continue
                if (root) {
                    addEdge(s, lap = false)
                } else {
                    val f = factsOf(t, d)
                    if (f and BACKWARD != 0) continue // a crash, not a move worth planning
                    addEdge(s, lap = f and FORWARD != 0)
                }
            }
        }

        private fun addEdge(target: Int, lap: Boolean) {
            if (edgeCount == edges.size) edges = edges.copyOf(edges.size * 2)
            edges[edgeCount++] = if (lap) -(target + 1) else target
        }

        private fun ensureEdgeStart(size: Int) {
            if (edgeStart.size < size) edgeStart = edgeStart.copyOf(max(size, edgeStart.size * 2))
        }

        // ---- values -----------------------------------------------------------------------

        private fun valueLayers(): Array<ShortArray> {
            val n = nodeCount
            // Reverse the ordinary moves; keep the finish-line crossings apart as seeds.
            val inDegree = IntArray(n + 1)
            var lapEdges = 0
            for (k in 0 until edgeCount) {
                val e = edges[k]
                if (e >= 0) inDegree[e + 1]++ else lapEdges++
            }
            for (i in 1..n) inDegree[i] += inDegree[i - 1]
            val revStart = inDegree
            val revFrom = IntArray(revStart[n])
            val fill = revStart.copyOf(n)
            val lapFrom = IntArray(lapEdges)
            val lapTo = IntArray(lapEdges)
            var l = 0
            for (u in 0 until n) {
                for (k in edgeStart[u] until edgeStart[u + 1]) {
                    val e = edges[k]
                    if (e >= 0) {
                        revFrom[fill[e]++] = u
                    } else {
                        lapFrom[l] = u
                        lapTo[l] = -e - 1
                        l++
                    }
                }
            }
            edges = IntArray(0)

            val layers = Array(AiTuning.LAP_LAYERS) { ShortArray(n) { INF } }
            val queue = IntArray(n + lapEdges + 1)
            val pushed = ShortArray(n + lapEdges + 1)
            for (layer in 0 until AiTuning.LAP_LAYERS) {
                val dist = layers[layer]
                val next = if (layer == 0) null else layers[layer - 1]
                // Seeds: the cheapest crossing out of each state into the next layer down.
                for (k in 0 until lapEdges) {
                    val seed = if (next == null) 1 else if (next[lapTo[k]] == INF) INF.toInt() else next[lapTo[k]] + 1
                    if (seed < dist[lapFrom[k]]) dist[lapFrom[k]] = seed.coerceAtMost(INF - 1).toShort()
                }
                val seeds = (0 until n).filter { dist[it] != INF }.sortedBy { dist[it] }
                var seedAt = 0
                var head = 0
                var tail = 0
                while (true) {
                    // Two streams, both in non-decreasing order: seeds, and states reached from
                    // already-settled ones. Take the smaller head; skip entries that were improved.
                    val fromSeed = seedAt < seeds.size &&
                        (head == tail || dist[seeds[seedAt]] <= pushed[head])
                    val x: Int
                    if (fromSeed) {
                        x = seeds[seedAt++]
                    } else if (head < tail) {
                        val candidate = queue[head]
                        val value = pushed[head]
                        head++
                        if (dist[candidate] != value) continue
                        x = candidate
                    } else {
                        break
                    }
                    val reached = dist[x] + 1
                    if (reached >= INF) continue
                    for (k in revStart[x] until revStart[x + 1]) {
                        val p = revFrom[k]
                        if (reached < dist[p]) {
                            dist[p] = reached.toShort()
                            queue[tail] = p
                            pushed[tail] = reached.toShort()
                            tail++
                        }
                    }
                }
            }
            return layers
        }

        companion object {
            const val ROOT_KEY = -1
            const val LEGAL = 1
            const val FORWARD = 2
            const val BACKWARD = 4
            const val OIL = 8
            const val NAILS = 16
            const val TOUCHED = 32
        }
    }
}

/**
 * The finished table: how many moves the rest of the race takes from any state, read off by the
 * driver for each of its legal moves. Immutable; one table serves every car on the track.
 */
class PlannerTable internal constructor(
    private val track: Track,
    private val rules: RaceRules,
    /** What the table was built for. */
    val profile: PlannerProfile,
    private val gravelOn: Boolean,
    private val minX: Int,
    private val minY: Int,
    private val maxX: Int,
    private val maxY: Int,
    private val index: IntIntMap,
    private val rootCount: Int,
    private val layers: Array<ShortArray>,
) {
    /** How many states the table covers. */
    val size: Int get() = layers[0].size

    /**
     * How long a lap takes once the racing line repeats: the typical difference between having one
     * more lap to go and one fewer, which is what extends the table past [AiTuning.LAP_LAYERS].
     */
    val lapLength: Int = run {
        val deepest = layers[layers.size - 1]
        val previous = layers[layers.size - 2]
        val diffs = deepest.indices
            .filter { deepest[it] != Short.MAX_VALUE && previous[it] != Short.MAX_VALUE }
            .map { deepest[it] - previous[it] }
            .sorted()
        if (diffs.isEmpty()) 0 else diffs[diffs.size / 2]
    }

    /**
     * Moves still needed after [player] moves to [target] in [state], with [RacePlanner.DOOMED]
     * when that move can only end in a crash and null when the table has no opinion (a move faster
     * than the table's top speed, or off the planned ground).
     */
    fun valueOfMove(state: GameState, player: PlayerState, target: Point): Int? {
        val position = player.position
        val starting = MoveRules.isStartingMove(track, player)
        val crossing = if (starting) MoveRules.Crossing.NONE else MoveRules.lapCrossing(track, rules, position, target)
        if (crossing == MoveRules.Crossing.BACKWARD) return RacePlanner.DOOMED
        val lapsLeft = state.totalLaps - player.lapsCompleted
        if (crossing == MoveRules.Crossing.FORWARD && lapsLeft <= 1) return 0
        val velocity = target - position
        if (max(abs(velocity.x), abs(velocity.y)) > profile.maxSpeed) return null
        if (target.x !in minX..maxX || target.y !in minY..maxY) return null
        val memory = !starting && gravelOn && player.gravelBrakeTurns == MoveRules.GRAVEL_PERSISTS
        val key = (factsKey(target, velocity) shl 1) or (if (memory) 1 else 0)
        val node = index.get(key)
        if (node == IntIntMap.MISSING) return null
        val laps = if (crossing == MoveRules.Crossing.FORWARD) lapsLeft - 1 else lapsLeft
        return valueAt(node, laps)
    }

    /**
     * Moves the whole race of [laps] laps takes from start slot [slot], opening move included, or
     * null if [slot] is not one of the track's start slots.
     */
    fun startValue(slot: Point, laps: Int): Int? {
        val root = track.startPositions.indexOf(slot)
        if (root !in 0 until rootCount) return null
        return valueAt(root, laps)
    }

    private fun valueAt(node: Int, laps: Int): Int {
        if (laps <= 0) return 0
        val layer = minOf(laps, layers.size)
        val value = layers[layer - 1][node]
        if (value == Short.MAX_VALUE) return RacePlanner.DOOMED
        return value + (laps - layer) * lapLength
    }

    private fun factsKey(q: Point, w: Point): Int =
        ((q.x - minX) shl 20) or ((q.y - minY) shl 10) or ((w.x + 15) shl 5) or (w.y + 15)
}

/**
 * An open-addressing map from non-negative int keys to int values, without boxing - the planner
 * keeps hundreds of thousands of entries. Not thread-safe while being filled; read-only afterwards.
 */
internal class IntIntMap {
    private var keys = IntArray(1 shl 12) { EMPTY }
    private var values = IntArray(1 shl 12)
    private var size = 0

    /** The value stored for [key], or [MISSING]. */
    fun get(key: Int): Int {
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (true) {
            val k = keys[slot]
            if (k == key) return values[slot]
            if (k == EMPTY) return MISSING
            slot = (slot + 1) and mask
        }
    }

    /** Stores [value] for [key], replacing any earlier one. */
    fun put(key: Int, value: Int) {
        if ((size + 1) * 2 > keys.size) grow()
        val mask = keys.size - 1
        var slot = mix(key) and mask
        while (true) {
            val k = keys[slot]
            if (k == key) {
                values[slot] = value
                return
            }
            if (k == EMPTY) {
                keys[slot] = key
                values[slot] = value
                size++
                return
            }
            slot = (slot + 1) and mask
        }
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = IntArray(oldKeys.size * 2) { EMPTY }
        values = IntArray(oldKeys.size * 2)
        size = 0
        for (i in oldKeys.indices) if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i])
    }

    private fun mix(key: Int): Int {
        val h = key * -0x61c88647
        return h xor (h ushr 16)
    }

    companion object {
        /** Marks a free slot; keys are never negative. */
        private const val EMPTY = -1

        /** What [get] returns for a key that is not in the map. */
        const val MISSING = Int.MIN_VALUE
    }
}
