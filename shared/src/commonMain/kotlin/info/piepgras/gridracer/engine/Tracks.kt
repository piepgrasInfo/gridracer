package info.piepgras.gridracer.engine

import kotlinx.serialization.json.Json

/**
 * Tracks are authored as plain JSON (parsed below) rather than built with code,
 * so new layouts can be added without touching engine logic. Every finish line
 * spans its track's full corridor width, so it's always at least 5 grid points
 * (4 units) long, and start positions sit spread across the line itself, side
 * by side.
 *
 * Walls and the finish line are authored on a grid 5x finer than the coarse
 * game-mechanic grid (see [Track.subdivision]) - e.g. a coarse corner at (9, 6)
 * becomes fine-grid (45, 30). That subdivision factor may change per track later;
 * width/height always stay in coarse units regardless.
 *
 * Every built-in track keeps a 1-coarse-unit margin between its outer boundary
 * and the grid edge so the full wall outline (including its stroke width) stays
 * inside the visible canvas. Start positions are derived from the finish line
 * with [TrackBuilder.startGrid] rather than hand-authored, so they're always
 * inside the start box (deepened until it seats four cars) and each has a legal opening move.
 */
object Tracks {
    private val json = Json { ignoreUnknownKeys = true }

    private fun Track.withComputedStartPositions(): Track {
        val grid = TrackBuilder.startGrid(walls, finishLine, finishDirection, subdivision)
        return copy(startPositions = grid.positions, startZoneDepth = grid.depth)
    }

    /** A 33x21 rectangular ring, centreline lap about 66 coarse units. */
    val OVAL: Track = json.decodeFromString(Track.serializer(), OVAL_JSON).withComputedStartPositions()
    /** A 27x27 near-square ring, centreline lap about 68 coarse units. */
    val SQUARE: Track = json.decodeFromString(Track.serializer(), SQUARE_JSON).withComputedStartPositions()
    /** A 37x17 long, narrow ring, five coarse units wide throughout; lap about 84 units. */
    val SPEEDWAY: Track = json.decodeFromString(Track.serializer(), SPEEDWAY_JSON).withComputedStartPositions()
    /** A triangular ring with a five-unit-wide base corridor; lap about 60 coarse units. */
    val TRIANGLE: Track = json.decodeFromString(Track.serializer(), TRIANGLE_JSON).withComputedStartPositions()

    /** Every built-in track, in the order the track picker lists them. */
    val all: List<Track> = listOf(OVAL, SQUARE, SPEEDWAY, TRIANGLE)
}

// 33x21 rectangular ring. Outer boundary inset 1 coarse unit from the grid edge
// so the full wall outline stays visible. Centerline lap length ~66 units.
// Modifiers cover ~5% of the drivable corridor, placed near the inner boundary.
private const val OVAL_JSON = """
{
  "name": "Oval",
  "width": 33,
  "height": 21,
  "subdivision": 5,
  "walls": [
    {"from": {"x": 5, "y": 5}, "to": {"x": 155, "y": 5}},
    {"from": {"x": 155, "y": 5}, "to": {"x": 155, "y": 95}},
    {"from": {"x": 155, "y": 95}, "to": {"x": 5, "y": 95}},
    {"from": {"x": 5, "y": 95}, "to": {"x": 5, "y": 5}},
    {"from": {"x": 50, "y": 35}, "to": {"x": 110, "y": 35}},
    {"from": {"x": 110, "y": 35}, "to": {"x": 110, "y": 65}},
    {"from": {"x": 110, "y": 65}, "to": {"x": 50, "y": 65}},
    {"from": {"x": 50, "y": 65}, "to": {"x": 50, "y": 35}}
  ],
  "finishLine": {"from": {"x": 80, "y": 65}, "to": {"x": 80, "y": 95}},
  "finishDirection": {"x": -1, "y": 0},
  "startPositions": [],
  "modifiers": [
    {"type": "OIL_SPILL", "area": [{"x": 55, "y": 23}, {"x": 75, "y": 23}, {"x": 75, "y": 33}, {"x": 55, "y": 33}]},
    {"type": "NAILS", "area": [{"x": 75, "y": 67}, {"x": 95, "y": 67}, {"x": 95, "y": 77}, {"x": 75, "y": 77}]},
    {"type": "OIL_SPILL", "area": [{"x": 113, "y": 40}, {"x": 127, "y": 40}, {"x": 127, "y": 55}, {"x": 113, "y": 55}]}
  ]
}
"""

// 27x27 near-square ring. Outer boundary inset 1 coarse unit from the grid edge.
// Centerline lap length ~68 units.
// Modifiers cover ~5% of the drivable corridor, placed near the inner boundary.
private const val SQUARE_JSON = """
{
  "name": "Square",
  "width": 27,
  "height": 27,
  "subdivision": 5,
  "walls": [
    {"from": {"x": 5, "y": 5}, "to": {"x": 125, "y": 5}},
    {"from": {"x": 125, "y": 5}, "to": {"x": 125, "y": 125}},
    {"from": {"x": 125, "y": 125}, "to": {"x": 5, "y": 125}},
    {"from": {"x": 5, "y": 125}, "to": {"x": 5, "y": 5}},
    {"from": {"x": 40, "y": 40}, "to": {"x": 90, "y": 40}},
    {"from": {"x": 90, "y": 40}, "to": {"x": 90, "y": 90}},
    {"from": {"x": 90, "y": 90}, "to": {"x": 40, "y": 90}},
    {"from": {"x": 40, "y": 90}, "to": {"x": 40, "y": 40}}
  ],
  "finishLine": {"from": {"x": 65, "y": 90}, "to": {"x": 65, "y": 125}},
  "finishDirection": {"x": -1, "y": 0},
  "startPositions": [],
  "modifiers": [
    {"type": "NAILS", "area": [{"x": 50, "y": 28}, {"x": 70, "y": 28}, {"x": 70, "y": 38}, {"x": 50, "y": 38}]},
    {"type": "OIL_SPILL", "area": [{"x": 55, "y": 92}, {"x": 75, "y": 92}, {"x": 75, "y": 102}, {"x": 55, "y": 102}]},
    {"type": "NAILS", "area": [{"x": 92, "y": 55}, {"x": 106, "y": 55}, {"x": 106, "y": 70}, {"x": 92, "y": 70}]}
  ]
}
"""

// 37x17 long, narrow ring. Corridor is 5 coarse units wide everywhere (including
// the gate), so it fits the usual 4 cars side by side. Centerline lap length ~84 units.
// Modifiers cover ~5% of the drivable corridor, placed near the inner boundary.
private const val SPEEDWAY_JSON = """
{
  "name": "Speedway",
  "width": 37,
  "height": 17,
  "subdivision": 5,
  "walls": [
    {"from": {"x": 5, "y": 5}, "to": {"x": 175, "y": 5}},
    {"from": {"x": 175, "y": 5}, "to": {"x": 175, "y": 75}},
    {"from": {"x": 175, "y": 75}, "to": {"x": 5, "y": 75}},
    {"from": {"x": 5, "y": 75}, "to": {"x": 5, "y": 5}},
    {"from": {"x": 30, "y": 30}, "to": {"x": 150, "y": 30}},
    {"from": {"x": 150, "y": 30}, "to": {"x": 150, "y": 50}},
    {"from": {"x": 150, "y": 50}, "to": {"x": 30, "y": 50}},
    {"from": {"x": 30, "y": 50}, "to": {"x": 30, "y": 30}}
  ],
  "finishLine": {"from": {"x": 90, "y": 50}, "to": {"x": 90, "y": 75}},
  "finishDirection": {"x": -1, "y": 0},
  "startPositions": [],
  "modifiers": [
    {"type": "OIL_SPILL", "area": [{"x": 50, "y": 20}, {"x": 70, "y": 20}, {"x": 70, "y": 29}, {"x": 50, "y": 29}]},
    {"type": "NAILS", "area": [{"x": 100, "y": 20}, {"x": 120, "y": 20}, {"x": 120, "y": 29}, {"x": 100, "y": 29}]},
    {"type": "OIL_SPILL", "area": [{"x": 120, "y": 51}, {"x": 140, "y": 51}, {"x": 140, "y": 60}, {"x": 120, "y": 60}]}
  ]
}
"""

// Triangular ring. Outer boundary inset 1 coarse unit from the grid edge so the
// base wall stays fully visible. The bottom corridor is 5 coarse units wide so it
// fits 4 cars side by side at the gate. Centerline lap length ~60 units.
// Modifiers cover ~5% of the drivable corridor, placed near the inner boundary.
private const val TRIANGLE_JSON = """
{
  "name": "Triangle",
  "width": 37,
  "height": 21,
  "subdivision": 5,
  "walls": [
    {"from": {"x": 5, "y": 95}, "to": {"x": 175, "y": 95}},
    {"from": {"x": 175, "y": 95}, "to": {"x": 90, "y": 5}},
    {"from": {"x": 90, "y": 5}, "to": {"x": 5, "y": 95}},
    {"from": {"x": 60, "y": 70}, "to": {"x": 120, "y": 70}},
    {"from": {"x": 120, "y": 70}, "to": {"x": 90, "y": 35}},
    {"from": {"x": 90, "y": 35}, "to": {"x": 60, "y": 70}}
  ],
  "finishLine": {"from": {"x": 90, "y": 70}, "to": {"x": 90, "y": 95}},
  "finishDirection": {"x": -1, "y": 0},
  "startPositions": [],
  "modifiers": [
    {"type": "NAILS", "area": [{"x": 70, "y": 73}, {"x": 85, "y": 73}, {"x": 85, "y": 88}, {"x": 70, "y": 88}]},
    {"type": "OIL_SPILL", "area": [{"x": 95, "y": 73}, {"x": 110, "y": 73}, {"x": 110, "y": 88}, {"x": 95, "y": 88}]}
  ]
}
"""
