package io.embodit.fivestones.domain

/**
 * The "看棋形 / Shapes" teaching layer: key empty points of every open three, four and open four on the board,
 * for both colours. Level 1 = open three (3 cm pillar), 2 = four (5 cm), 3 = open four (7 cm).
 *
 * A point is a key point of a [color] four when [color] there makes five; of an open four when that four has
 * two ways to five; of an open three when [color] there makes a four with two ways to five (a straight four).
 */
data class Pillar(val point: Int, val color: Int, val level: Int)

object Shapes {
    const val MAX = 12
    const val THREE = 1
    const val FOUR = 2
    const val OPEN_FOUR = 3

    fun pillars(board: IntArray, renju: Boolean, max: Int = MAX, preferColor: Int = Board.BLACK): List<Pillar> {
        val out = ArrayList<Pillar>()
        for (p in 0 until Board.SIZE) {
            if (board[p] != Board.EMPTY || !Rules.hasNeighbour(board, p, 2)) continue
            for (color in intArrayOf(Board.BLACK, Board.WHITE)) {
                val level = levelAt(board, p, color, renju)
                if (level > 0) out += Pillar(p, color, level)
            }
        }
        // Most urgent first; at equal urgency the opponent's threats before the player's chances.
        return out.sortedWith(compareByDescending<Pillar> { it.level }.thenBy { if (it.color == preferColor) 1 else 0 }.thenBy { it.point }).take(max)
    }

    fun levelAt(board: IntArray, p: Int, color: Int, renju: Boolean): Int {
        val exact = Rules.exactFor(color, renju)
        var level = 0
        for (d in 0 until 4) {
            val n = Patterns.run(board, p, color, d)
            if (if (exact) n == 5 else n >= 5) {
                // p completes a four: open four if the line has a second completion besides p.
                level = maxOf(level, if (secondCompletion(board, p, color, d, exact)) OPEN_FOUR else FOUR)
                continue
            }
            if (Patterns.shape(board, p, color, d, exact) == Patterns.OPEN4) level = maxOf(level, THREE)
        }
        if (color == Board.BLACK && level > 0 && Rules.forbidden(board, p, renju) != null) return 0
        return level
    }

    /** Is there another empty point on this line (other than [p]) that also completes five for [color]? */
    private fun secondCompletion(board: IntArray, p: Int, color: Int, dir: Int, exact: Boolean): Boolean {
        val d = Board.DIRS[dir]
        for (k in -5..5) {
            if (k == 0) continue
            val c = Board.col(p) + d[0] * k
            val r = Board.row(p) + d[1] * k
            if (!Board.inside(c, r)) continue
            val q = r * Board.N + c
            if (board[q] != Board.EMPTY) continue
            val n = Patterns.run(board, q, color, dir)
            if (if (exact) n == 5 else n >= 5) return true
        }
        return false
    }
}
