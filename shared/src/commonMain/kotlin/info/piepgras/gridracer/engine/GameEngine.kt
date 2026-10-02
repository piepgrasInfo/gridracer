package info.piepgras.gridracer.engine

import kotlin.random.Random

/**
 * Pure, deterministic rules engine for the vector-race grid game. No platform
 * dependencies: safe to run on a server or any client, and to unit test on the JVM.
 */
object GameEngine {

    /**
     * Hard cap on how many rounds a race may run. A round is one move per still-racing
     * player (see [GameState.round]); once it would tick past this, the race ends with
     * [GamePhase.RACE_OVER] and every player still [RACING][PlayerStatus.RACING] keeps a
     * null [finishRank], which [Scoring.pointsForRank] maps to 0 points. Stops a stuck or
     * looping race from running forever.
     */
    const val MAX_ROUNDS = 200

    /** Convenience overload: players start at track.startPositions[0..playerCount-1] in order. */
    fun newGame(
        track: Track,
        playerCount: Int,
        totalLaps: Int = 1,
        rules: RaceRules = RaceRules.current(gravelTrap = false),
    ): GameState {
        require(playerCount in 1..track.startPositions.size) {
            "playerCount must be between 1 and ${track.startPositions.size}"
        }
        return newGame(track, track.startPositions.take(playerCount), totalLaps, rules)
    }

    /**
     * Players start at the given positions (e.g. chosen during a placement phase), in player-id order.
     *
     * @param rules The rules the race is played under. A new race gets the newest core rules by
     *     default; only the gravel trap is the caller's choice.
     */
    fun newGame(
        track: Track,
        startAssignments: List<Point>,
        totalLaps: Int = 1,
        rules: RaceRules = RaceRules.current(gravelTrap = false),
    ): GameState {
        require(startAssignments.isNotEmpty()) { "startAssignments must not be empty" }
        require(totalLaps >= 1) { "totalLaps must be at least 1" }
        val players = startAssignments.mapIndexed { i, position ->
            PlayerState(id = i, position = position)
        }
        return startTurn(
            GameState(track = track, players = players, currentPlayerIndex = 0, totalLaps = totalLaps, rules = rules),
        )
    }

    /**
     * The 9 grid points around the player's unbraked coast point, ignoring walls, other cars and
     * the gravel trap's brake - the paper-race rule on its own. [legalMoves] is what a race uses.
     */
    fun candidateMoves(player: PlayerState): Set<Point> = MoveRules.candidates(player.position, player.lastVelocity)

    /**
     * Where [player] coasts to this turn if they neither speed up nor slow down: their position
     * plus their velocity, braked while the gravel trap has them (see [PlayerState.gravelBrakeTurns]).
     * The centre of the nine points [legalMoves] chooses from.
     */
    fun coastPoint(state: GameState, player: PlayerState): Point =
        player.position + MoveRules.effectiveVelocity(state.track, state.rules, player)

    /**
     * Candidate moves filtered down to ones that don't cross a wall or another car. For a
     * player's very first move the only legal choices are those that actually cross the
     * start/finish line in [Track.finishDirection], so each player is forced to leave the
     * starting grid by crossing the line. The very first crossing does not count as a lap
     * (see [applyMove]), but the move must still be a forward one.
     *
     * Hazards narrow the choice for one move after a car drives through them:
     * - An oil spill takes the steering away: the car carries on to the coast point a
     *   zero-acceleration move would reach, give or take the two grid points [oilSlipTargets]
     *   adds. A cell occupied by another car is only offered as a last resort, and taking it
     *   crashes both cars.
     * - Nails limit the car to a slow-down: at speed > 1 (the faster axis) or standing still,
     *   the 3 of the 9 candidates with the smallest resulting velocity; at speed 1 the stop
     *   point, the coast-forward point and the two perpendicular strafes, plus the two
     *   axis-aligned points for a diagonal heading.
     * - Both at once: the oil targets the nails allow, else the nails slowdown on its own.
     * A hazard that would leave no move at all is dropped, so a hazard never crashes a car by
     * itself.
     *
     * With the gravel trap on ([RaceRules.gravelTrap]) the outer boundary may be crossed into the
     * gravel band beyond it, but not past the barrier at the band's far edge; and while a car is
     * braked by the gravel ([PlayerState.gravelBrakeTurns]) the nine candidates are centred on its
     * braked coast point ([coastPoint]) rather than the usual one, with any hazard acting on that.
     */
    fun legalMoves(state: GameState, playerId: Int): Set<Point> {
        // No such player is "no moves", not an exception: legalMoves is called from a restored
        // snapshot and from the AI's hypothetical states, and a throw here would take the
        // process with it rather than ending the turn.
        val player = state.players.firstOrNull { it.id == playerId } ?: return emptySet()
        if (player.status != PlayerStatus.RACING) return emptySet()
        val occupied = state.players
            .filter { it.id != playerId && it.status == PlayerStatus.RACING }
            .mapTo(mutableSetOf()) { it.position }
        return MoveRules.legalTargets(
            track = state.track,
            position = player.position,
            velocity = MoveRules.effectiveVelocity(state.track, state.rules, player),
            pending = player.pendingModifiers,
            oilSlips = player.oilSlips,
            starting = MoveRules.isStartingMove(state.track, player),
            occupied = occupied,
            clear = { from, to -> MoveRules.segmentClear(state.track, state.rules, from, to) },
        )
    }

    /**
     * Whether the straight segment from [from] to [to] crosses any wall of [track], ignoring
     * other cars, modifiers and the gravel trap. Public so [SimpleAi] can build its safety set
     * from track geometry alone, without constructing a full [GameState] for every
     * (position, velocity) it probes.
     */
    fun pathClearOfWalls(track: Track, from: Point, to: Point): Boolean =
        !track.geometry.touchesAnyWall(from * track.subdivision, to * track.subdivision)

    /**
     * Whether moving from [from] to [to] crosses [track]'s finish line against
     * [Track.finishDirection] by the original rules - the one way a move that doesn't touch a
     * wall still crashes (see [applyMove]). A forward crossing (which ticks a lap or finishes the
     * race) is not a crash and returns false here.
     */
    fun crossesFinishLineBackward(track: Track, from: Point, to: Point): Boolean =
        MoveRules.legacyCrossing(track, from, to) == MoveRules.Crossing.BACKWARD

    /**
     * Whether moving from [from] to [to] is the backward crossing that crashes a car under
     * [rules]; see [applyMove].
     */
    fun crossesFinishLineBackward(track: Track, rules: RaceRules, from: Point, to: Point): Boolean =
        MoveRules.lapCrossing(track, rules, from, to) == MoveRules.Crossing.BACKWARD

    /**
     * Where a car on oil may end up: the coast point, plus up to two neighbouring grid points
     * [PlayerState.oilSlips] picked out when the spill was hit. Three points rather than one
     * so a spill is a loss of control rather than a fully scripted move - there is still a
     * decision to make, just not the usual one.
     *
     * An empty [PlayerState.oilSlips] narrows this to the coast point alone. That is the
     * conservative reading, and the right one for a planner looking at a spill it has not
     * driven into yet: the coast point is the only target it can count on being offered.
     */
    fun oilSlipTargets(player: PlayerState): Set<Point> =
        MoveRules.oilTargets(player.position, player.lastVelocity, player.oilSlips)

    /** The 8 one-step offsets a car can be nudged by; (0,0) is excluded so a spill always slips. */
    private val SLIP_OFFSETS: List<Point> =
        (-1..1).flatMap { dx -> (-1..1).map { dy -> Point(dx, dy) } }.filterNot { it == Point(0, 0) }

    /**
     * Which hazard zones a move from [from] to [to] runs through - the path, not just where
     * it stops. A car at racing speed covers several grid units per turn and would otherwise
     * sail straight over a spill without touching a single grid point inside it.
     */
    fun modifiersOn(track: Track, from: Point, to: Point): Set<ModifierType> {
        if (track.modifiers.isEmpty()) return emptySet()
        val fineFrom = from * track.subdivision
        val fineTo = to * track.subdivision
        val geometry = track.geometry
        var touched: MutableSet<ModifierType>? = null
        for ((i, modifier) in track.modifiers.withIndex()) {
            if (touched != null && modifier.type in touched) continue
            if (!geometry.mayTouchZone(i, fineFrom, fineTo)) continue
            if (touchesZone(fineFrom, fineTo, modifier.area)) {
                if (touched == null) touched = mutableSetOf()
                touched += modifier.type
            }
        }
        return touched ?: emptySet()
    }

    private fun touchesZone(fineFrom: Point, fineTo: Point, area: List<Point>): Boolean {
        if (area.size < 3) return false
        if (Geometry.pointInPolygon(fineTo, area) || Geometry.pointInPolygon(fineFrom, area)) return true
        for (i in area.indices) {
            if (Geometry.segmentsIntersect(fineFrom, fineTo, area[i], area[(i + 1) % area.size])) return true
        }
        return false
    }

    /**
     * Whether moving [playerId] to [target] is allowed right now - the check the server runs on
     * every move a client sends.
     *
     * @return `true` if [target] is in [legalMoves] for that player.
     */
    fun isLegalMove(state: GameState, playerId: Int, target: Point): Boolean =
        target in legalMoves(state, playerId)

    /**
     * Applies a legal move for the given player, then advances to the next racing
     * player - auto-crashing (and skipping) any players who have no legal moves,
     * per [startTurn]. A move that crosses the finish line the right way completes
     * a lap; the player only finishes the race on the crossing that completes their
     * final lap ([GameState.totalLaps]) - earlier crossings just tick lapsCompleted.
     * A move that crosses the line in reverse crashes the player on the spot instead.
     * The very first move off the start line never counts as a crossing either way -
     * players start sitting right on top of it, so leaving it isn't a lap event. Under
     * [RaceRules.version] 2 a pass over the line counts exactly once, however a move
     * straddles it; under version 1 (races recorded before rules were) a move ending on the
     * line and the next one leaving it both counted.
     *
     * With the gravel trap on, a move that touches the gravel sets the mover's
     * [PlayerState.gravelBrakeTurns] to two; any other move counts it down.
     *
     * [random] is only drawn from when this move ends on an oil spill, to fix which way the
     * car will slide next turn (see [PlayerState.oilSlips]). It is settled here, and stored,
     * rather than rolled inside [legalMoves] - which runs several times a turn, for rendering
     * as well as for rules, and has to give the same answer every time.
     *
     * An oil-forced move onto a cell occupied by another car (only offered as a last resort,
     * see [legalMoves]) crashes both the mover and the car already there.
     */
    fun applyMove(state: GameState, playerId: Int, target: Point, random: Random = Random.Default): GameState {
        require(state.currentPlayer.id == playerId) { "It isn't player $playerId's turn" }
        require(isLegalMove(state, playerId, target)) { "Move to $target is not legal for player $playerId" }

        val player = state.currentPlayer
        val starting = MoveRules.isStartingMove(state.track, player)
        val crossing = if (starting) {
            MoveRules.Crossing.NONE
        } else {
            MoveRules.lapCrossing(state.track, state.rules, player.position, target)
        }
        val lapsCompleted = player.lapsCompleted + if (crossing == MoveRules.Crossing.FORWARD) 1 else 0
        val finished = crossing == MoveRules.Crossing.FORWARD && lapsCompleted >= state.totalLaps
        val crashed = crossing == MoveRules.Crossing.BACKWARD
        val alreadyFinished = state.players.count { it.status == PlayerStatus.FINISHED }
        val picked = modifiersOn(state.track, player.position, target)

        // An oil-forced move onto another racing car (the last-resort case) crashes both. The
        // mover is crashed regardless of lap/finish status; the occupant is crashed as a side
        // effect. Crashed cars (wrecks) don't block, so only a racing car can be crashed into.
        val occupant = state.players.firstOrNull { it.id != playerId && it.position == target && it.status == PlayerStatus.RACING }
        val crashedByCollision = occupant != null

        val updatedPlayer = player.copy(
            position = target,
            lastVelocity = target - player.position,
            path = if (starting && target == player.position) {
                // Staying in place on a starting move (the no-forward-move fallback): don't
                // grow the path so isStartingMove stays true and the forward-crossing
                // requirement still applies next turn. The player just loses this turn.
                player.path
            } else {
                player.path + target
            },
            lapsCompleted = lapsCompleted,
            status = when {
                crashed -> PlayerStatus.CRASHED
                crashedByCollision -> PlayerStatus.CRASHED
                finished -> PlayerStatus.FINISHED
                else -> PlayerStatus.RACING
            },
            finishRank = if (finished && !crashedByCollision && !crashed) alreadyFinished + 1 else null,
            pendingModifiers = picked,
            oilSlips = if (ModifierType.OIL_SPILL in picked) twoDistinctSlipOffsets(random) else emptyList(),
            // Oil-collision crashes produce a wreck at the collision point (fine grid).
            // Backward finish-line crashes produce no wreck.
            wreckPosition = if (crashedByCollision) target * state.track.subdivision else null,
            gravelBrakeTurns = MoveRules.nextGravelCounter(
                state.track,
                state.rules,
                player.gravelBrakeTurns,
                player.position,
                target,
            ),
        )
        val playersAfter = state.players.map { p ->
            when {
                p.id == playerId -> updatedPlayer
                occupant != null && p.id == occupant.id -> p.copy(
                    status = PlayerStatus.CRASHED,
                    wreckPosition = target * state.track.subdivision,
                )
                else -> p
            }
        }
        val newState = state.copy(players = playersAfter)
        return startTurn(advanceToNextPlayer(newState))
    }

    /** Two distinct one-step offsets sampled without replacement from [SLIP_OFFSETS]. */
    private fun twoDistinctSlipOffsets(random: Random): List<Point> {
        val pool = SLIP_OFFSETS.toMutableList()
        val first = pool.removeAt(random.nextInt(pool.size))
        val second = pool.removeAt(random.nextInt(pool.size))
        return listOf(first, second)
    }

    /**
     * Retires [playerId] as crashed and passes the turn on. The way out of a turn that
     * cannot be played at all - if a driver ever fails to produce a move for a player the
     * engine says has one, the race carries on a car short rather than sitting frozen on
     * a turn nobody can take. A forfeit produces no wreck (the car leaves the track
     * rather than hitting a wall).
     */
    fun forfeit(state: GameState, playerId: Int): GameState {
        if (state.players.none { it.id == playerId && it.status == PlayerStatus.RACING }) return state
        val crashed = state.copy(
            players = state.players.map {
                if (it.id == playerId) it.copy(status = PlayerStatus.CRASHED, wreckPosition = null) else it
            },
        )
        return startTurn(advanceToNextPlayer(crashed))
    }

    /**
     * Retires [playerId] from the race without passing the turn on.
     *
     * The counterpart to [forfeit], for a player who walks out of a race when it is *not*
     * their turn - only reachable in a multiplayer race, where someone can press Leave at any
     * time. Advancing the turn here would skip whoever is actually on the clock.
     *
     * It lives in the engine rather than in the server's `Room` (where it was an inline
     * `copy`) so that a replay can name the transition and reproduce it exactly.
     */
    fun retire(state: GameState, playerId: Int): GameState {
        if (state.players.none { it.id == playerId && it.status == PlayerStatus.RACING }) return state
        val crashed = state.copy(
            players = state.players.map {
                if (it.id == playerId) it.copy(status = PlayerStatus.CRASHED, wreckPosition = null) else it
            },
        )
        return startTurn(crashed)
    }

    /**
     * Ensures the current player (if any) actually has a legal move to make;
     * a player with none crashes on the spot and the turn passes on. Recurses
     * until either a movable player is found or the race is over. A wall-trap
     * crash produces a wreck at the intersection of the coast projection line
     * and the wall that trapped the car.
     */
    tailrec fun startTurn(state: GameState): GameState {
        if (state.phase == GamePhase.RACE_OVER) return state
        val player = state.currentPlayer
        if (legalMoves(state, player.id).isNotEmpty()) return state
        val wreckPos = computeWreckPosition(state, player)
        val crashed = state.copy(
            players = state.players.map {
                if (it.id == player.id) it.copy(status = PlayerStatus.CRASHED, wreckPosition = wreckPos) else it
            }
        )
        return startTurn(advanceToNextPlayer(crashed))
    }

    private fun advanceToNextPlayer(state: GameState): GameState {
        val n = state.players.size
        for (step in 1..n) {
            val idx = (state.currentPlayerIndex + step) % n
            if (state.players[idx].status == PlayerStatus.RACING) {
                val wrapped = idx <= state.currentPlayerIndex
                // Ending the round (wrapping past the last player back to an earlier index)
                // ticks the round counter; once that would exceed MAX_ROUNDS the race is
                // over rather than starting a new round. Players still RACING keep a null
                // finishRank, which scores 0.
                if (wrapped && state.round + 1 > state.maxRounds) {
                    return state.copy(phase = GamePhase.RACE_OVER)
                }
                return state.copy(currentPlayerIndex = idx, round = if (wrapped) state.round + 1 else state.round)
            }
        }
        return state.copy(phase = GamePhase.RACE_OVER)
    }

    /**
     * Computes the fine-grid point where a wall-trap wreck should be drawn: the intersection
     * of the coast projection line (position → coast point, braked if the gravel has the car)
     * with the nearest wall it crosses. With the gravel trap on, the outer boundary is not a
     * wall - the barrier at the far edge of the band is, so the wreck goes where the line
     * leaves the band, if that comes first. If the projection line doesn't cross anything (the
     * car is trapped by a combination of walls around the coast rather than by the coast
     * itself hitting one), the coast point on the fine grid is used as a fallback — the car
     * "crashed" trying to reach its coast.
     */
    private fun computeWreckPosition(state: GameState, player: PlayerState): Point {
        val track = state.track
        val subdivision = track.subdivision
        val fineFrom = player.position * subdivision
        val fineTo = coastPoint(state, player) * subdivision
        val geometry = track.geometry
        val gravel = if (MoveRules.gravelActive(track, state.rules)) geometry.gravel else null
        if (gravel == null) return geometry.nearestHit(fineFrom, fineTo) { true } ?: fineTo
        val wallHit = geometry.nearestHit(fineFrom, fineTo) { !geometry.isOuterWall(it) }
        val barrierHit = gravel.lastClearBefore(fineFrom, fineTo)
        fun distSq(p: Point): Long {
            val dx = (p.x - fineFrom.x).toLong()
            val dy = (p.y - fineFrom.y).toLong()
            return dx * dx + dy * dy
        }
        return listOfNotNull(wallHit, barrierHit).minByOrNull(::distSq) ?: fineTo
    }
}
