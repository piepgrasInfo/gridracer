package info.piepgras.gridracer.engine

import kotlin.math.abs

/**
 * The movement rules in their primitive form: which targets a car may move to, given only where it
 * is, how it moves, what it picked up on its last move and which cells are taken.
 *
 * [GameEngine.legalMoves] is a thin wrapper around [legalTargets]; the planner behind the stronger
 * AI levels calls the same function with its own inputs instead of building a [GameState] for each
 * of the hundreds of thousands of states it examines. One implementation, so the AI can never plan
 * by rules the race does not play by.
 */
internal object MoveRules {

    /** How many coarse units a car in the gravel loses from its coast; see [brake]. */
    const val GRAVEL_BRAKE = 2

    /**
     * How many of a car's moves are braked after the last one that touched the gravel, that move
     * excluded: the next move, and with it every move made from inside the band, plus [GRAVEL_PERSISTS]
     * more after the car is back on the track - so leaving the gravel still costs two braked moves.
     */
    const val GRAVEL_PERSISTS = 2

    /** Whether the gravel trap is in play for this race: switched on, and the track has a band. */
    fun gravelActive(track: Track, rules: RaceRules): Boolean = rules.gravelTrap && track.geometry.gravel != null

    /**
     * The velocity a braked car coasts with: [velocity] shortened by [GRAVEL_BRAKE] along its own
     * heading, in the grid's own measure of speed (the faster axis).
     *
     * The faster axis loses exactly [GRAVEL_BRAKE]; the other axis is scaled by the same ratio and
     * rounded half away from zero, so the heading is kept as nearly as the grid allows. A car slower
     * than that comes to a standstill coast, which still leaves it the eight one-step moves round
     * where it stands - braking never pins a car in place.
     *
     * (5,0) -> (3,0), (4,1) -> (2,1), (3,1) -> (1,0), (5,2) -> (3,1), (2,1) -> (0,0).
     */
    fun brake(velocity: Point): Point {
        val speed = maxOf(abs(velocity.x), abs(velocity.y))
        if (speed <= GRAVEL_BRAKE) return Point(0, 0)
        val braked = speed - GRAVEL_BRAKE
        fun axis(component: Int): Int {
            val scaled = (2L * abs(component) * braked + speed) / (2L * speed)
            return if (component < 0) -scaled.toInt() else scaled.toInt()
        }
        return Point(axis(velocity.x), axis(velocity.y))
    }

    /** The velocity [player] coasts with this turn: [PlayerState.lastVelocity], braked while in or just out of the gravel. */
    fun effectiveVelocity(track: Track, rules: RaceRules, player: PlayerState): Point =
        if (player.gravelBrakeTurns > 0 && gravelActive(track, rules)) brake(player.lastVelocity) else player.lastVelocity

    /**
     * Whether the move from [from] to [to] (coarse grid) is clear of every wall the race treats as
     * solid. Other cars are not considered here.
     *
     * Without the gravel trap that is every wall. With it, the outer boundary may be crossed: what
     * stops a car is any other wall, or reaching past the band - the barrier.
     */
    fun segmentClear(track: Track, rules: RaceRules, from: Point, to: Point): Boolean {
        val sub = track.subdivision
        val fineFrom = from * sub
        val fineTo = to * sub
        val geometry = track.geometry
        val gravel = if (rules.gravelTrap) geometry.gravel else null
        if (gravel == null) return !geometry.touchesAnyWall(fineFrom, fineTo)
        if (geometry.touchesNonOuterWall(fineFrom, fineTo)) return false
        return gravel.walkClear(fineFrom, fineTo)
    }

    /**
     * Whether the move from [from] to [to] touches the gravel: starts or ends in the band, or
     * crosses the outer boundary on the way (a corner cut, or a graze of the boundary itself).
     * Only meaningful while [gravelActive].
     */
    fun touchesGravel(track: Track, from: Point, to: Point): Boolean {
        val geometry = track.geometry
        val gravel = geometry.gravel ?: return false
        if (gravel.classOf(from) != GravelGeometry.INSIDE || gravel.classOf(to) != GravelGeometry.INSIDE) return true
        val sub = track.subdivision
        return geometry.touchesOuterWall(from * sub, to * sub)
    }

    /**
     * The braking counter a car carries after moving from [from] to [to], given the one it had
     * before: reset to [GRAVEL_PERSISTS] by any move that touches the gravel, otherwise counted down.
     */
    fun nextGravelCounter(track: Track, rules: RaceRules, previous: Int, from: Point, to: Point): Int {
        if (!gravelActive(track, rules)) return 0
        return if (touchesGravel(track, from, to)) GRAVEL_PERSISTS else maxOf(0, previous - 1)
    }

    /** The 9 grid points around the coast point [position] + [velocity]. */
    fun candidates(position: Point, velocity: Point): Set<Point> {
        val center = position + velocity
        return buildSet {
            for (dx in -1..1) for (dy in -1..1) add(center + Point(dx, dy))
        }
    }

    /**
     * Every target a car may move to - the whole of the movement rules, minus the decision of who
     * moves when.
     *
     * @param position Where the car is.
     * @param velocity The velocity it coasts with this turn - [effectiveVelocity], so already braked
     *     if it is in or just out of the gravel.
     * @param pending Hazards picked up by its last move, in force for this one.
     * @param oilSlips The slip offsets drawn when it hit oil; see [PlayerState.oilSlips].
     * @param starting Whether this is the car's opening move off the start line.
     * @param occupied Cells held by other racing cars.
     * @param clear Whether a move between two points is clear of walls; normally [segmentClear] for
     *     the race's track and rules, but a caller that tests the same moves again and again may
     *     answer from a cache.
     */
    fun legalTargets(
        track: Track,
        position: Point,
        velocity: Point,
        pending: Set<ModifierType>,
        oilSlips: List<Point>,
        starting: Boolean,
        occupied: Set<Point>,
        clear: (Point, Point) -> Boolean,
    ): Set<Point> {
        val car = Car(track, position, velocity, starting, occupied, clear)
        val effects = ModifierType.entries.filter { it in pending }
        if (effects.size == 2 && ModifierType.OIL_SPILL in effects && ModifierType.NAILS in effects) {
            // Oil first, filtered with the occupied-cell last-resort rule, then narrowed by the
            // nails; if that leaves nothing, the nails slowdown is offered on its own so the car
            // escapes the oil but is still nail-limited.
            val legalOil = car.filterLegalForOil(oilTargets(position, velocity, oilSlips))
            val nailAllowed = nailAllowed(position, velocity)
            val intersection = legalOil intersect nailAllowed
            if (intersection.isNotEmpty()) return intersection
            return car.filterLegal(nailAllowed)
        }

        // A single hazard (or none): narrow the 9 candidates, then drop wall-blocked ones. If the
        // narrowed set comes back empty while the unrestricted one would not, the effect is
        // dropped, so a hazard never crashes the car by itself - hitting a wall has to be the
        // driver's decision. The brake is not a hazard and is never dropped.
        var remaining = effects
        while (true) {
            var restricted = candidates(position, velocity)
            for (effect in remaining) {
                restricted = when (effect) {
                    ModifierType.OIL_SPILL -> restricted intersect oilTargets(position, velocity, oilSlips)
                    ModifierType.NAILS -> restricted intersect nailAllowed(position, velocity)
                }
            }
            val moves = if (ModifierType.OIL_SPILL in remaining) car.filterLegalForOil(restricted) else car.filterLegal(restricted)
            if (moves.isNotEmpty() || remaining.isEmpty()) return moves
            remaining = remaining.dropLast(1)
        }
    }

    /**
     * Where a car on oil may end up: the coast point, plus the slips drawn when it hit the spill.
     * No slips narrows it to the coast point alone.
     */
    fun oilTargets(position: Point, velocity: Point, oilSlips: List<Point>): Set<Point> {
        val coast = position + velocity
        if (oilSlips.isEmpty()) return setOf(coast)
        return setOf(coast) + oilSlips.map { coast + it }
    }

    /**
     * The candidates nails leave: a slow-down. See [GameEngine.legalMoves] for the rule in words;
     * computed around [velocity], so a braked car is nail-limited around its braked coast.
     */
    fun nailAllowed(position: Point, velocity: Point): Set<Point> {
        val speed = maxOf(abs(velocity.x), abs(velocity.y))
        if (speed > 1 || velocity == Point(0, 0)) {
            return candidates(position, velocity).sortedBy { candidate ->
                val v = candidate - position
                v.x * v.x + v.y * v.y
            }.take(3).toSet()
        }
        // Speed 1 with a real heading: stop, front and the two perpendicular strafes; a diagonal
        // heading also gets its two axis-aligned decompositions.
        val base = mutableSetOf(
            position,
            position + velocity,
            position + Point(-velocity.y, velocity.x),
            position + Point(velocity.y, -velocity.x),
        )
        if (velocity.x != 0 && velocity.y != 0) {
            base.add(position + Point(velocity.x, 0))
            base.add(position + Point(0, velocity.y))
        }
        return base
    }

    /** One car's view of the board for a single legal-move computation. */
    private class Car(
        val track: Track,
        val position: Point,
        val velocity: Point,
        val starting: Boolean,
        val occupied: Set<Point>,
        val clear: (Point, Point) -> Boolean,
    ) {
        /** Candidates clear of walls and of other cars; on an opening move, only forward crossings. */
        fun filterLegal(candidates: Set<Point>): Set<Point> {
            val legal = candidates.filterTo(mutableSetOf()) { target ->
                clear(position, target) && (occupied.isEmpty() || Geometry.latticePointsOn(position, target).none { it in occupied })
            }
            if (!starting) return legal
            val forward = legal.filterTo(mutableSetOf()) { legacyCrossing(track, position, it) == Crossing.FORWARD }
            if (forward.isNotEmpty()) return forward
            // No forward-crossing move from the start line: stay put and try again next turn rather
            // than crash. A zero-length move crosses nothing, so it is always clear.
            return setOf(position)
        }

        /**
         * Like [filterLegal] for oil-forced targets: a cell occupied by another car is offered only
         * when every target is occupied; walls still block unconditionally.
         */
        fun filterLegalForOil(candidates: Set<Point>): Set<Point> {
            val wallClear = candidates.filterTo(mutableSetOf()) { clear(position, it) }
            if (starting) {
                val forward = wallClear.filterTo(mutableSetOf()) { legacyCrossing(track, position, it) == Crossing.FORWARD }
                return splitFreeAndOccupied(if (forward.isNotEmpty()) forward else setOf(position))
            }
            return splitFreeAndOccupied(wallClear)
        }

        private fun splitFreeAndOccupied(candidates: Set<Point>): Set<Point> {
            val free = candidates.filterTo(mutableSetOf()) { it !in occupied }
            return if (free.isNotEmpty()) free else candidates
        }
    }

    /** How a move relates to the finish line. */
    enum class Crossing { NONE, FORWARD, BACKWARD }

    /**
     * The original crossing test: a move touching the line counts, and its direction against
     * [Track.finishDirection] decides which way. Still what decides whether an opening move leaves
     * the grid the right way - start slots may sit exactly on the line.
     */
    fun legacyCrossing(track: Track, from: Point, to: Point): Crossing {
        val sub = track.subdivision
        if (!Geometry.segmentsIntersect(from * sub, to * sub, track.finishLine.from, track.finishLine.to)) return Crossing.NONE
        val movement = to - from
        val alignment = movement.x * track.finishDirection.x + movement.y * track.finishDirection.y
        return when {
            alignment > 0 -> Crossing.FORWARD
            alignment < 0 -> Crossing.BACKWARD
            else -> Crossing.NONE
        }
    }

    /**
     * How a race move crosses the finish line under [rules]: what counts a lap and what is the
     * backward crash.
     *
     * Version 1 is [legacyCrossing], under which a move ending on the line and the next move
     * leaving it forward both counted. Version 2 uses which side of the line each end is on,
     * exactly, in integers: forward when the car goes from strictly behind the line to on or past
     * it, backward when it goes from on or past it to strictly behind. Every pass then counts
     * once, however the car straddles the line.
     */
    fun lapCrossing(track: Track, rules: RaceRules, from: Point, to: Point): Crossing {
        if (!rules.countsEachPassOnce) return legacyCrossing(track, from, to)
        val sub = track.subdivision
        val line = track.finishLine
        if (!Geometry.segmentsIntersect(from * sub, to * sub, line.from, line.to)) return Crossing.NONE
        val dirX = (line.to.x - line.from.x).toLong()
        val dirY = (line.to.y - line.from.y).toLong()
        val orientation = dirX * track.finishDirection.y - dirY * track.finishDirection.x
        if (orientation == 0L) return legacyCrossing(track, from, to)
        val sign = if (orientation > 0) 1L else -1L
        fun side(p: Point): Long {
            val px = (p.x * sub - line.from.x).toLong()
            val py = (p.y * sub - line.from.y).toLong()
            return sign * (dirX * py - dirY * px)
        }
        val before = side(from)
        val after = side(to)
        return when {
            before < 0 && after >= 0 -> Crossing.FORWARD
            before >= 0 && after < 0 -> Crossing.BACKWARD
            else -> Crossing.NONE
        }
    }

    /** Guards against a start slot exactly [Track.startZoneDepth] away being lost to rounding. */
    private const val START_LINE_TOLERANCE_EPSILON = 1e-9

    /**
     * Whether [p] is inside the track's starting box - the band [Track.startZoneDepth] deep that
     * start positions are drawn from and the placement screen shades.
     */
    fun isNearFinishLine(track: Track, p: Point): Boolean {
        val scaled = p * track.subdivision
        val toleranceFine = track.startZoneDepth * track.subdivision + START_LINE_TOLERANCE_EPSILON
        val distSq = Geometry.distancePointToSegmentSq(scaled, track.finishLine.from, track.finishLine.to)
        return distSq <= toleranceFine * toleranceFine
    }

    /** True for a player's very first move, made from a start position in the starting box. */
    fun isStartingMove(track: Track, player: PlayerState): Boolean =
        player.path.size == 1 && isNearFinishLine(track, player.position)
}
