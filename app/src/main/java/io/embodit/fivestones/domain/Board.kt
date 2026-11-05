package io.embodit.fivestones.domain

/**
 * The 15 x 15 board. A point is an index 0..224 = row * 15 + col; col 0..14 is A..O (left to right as the
 * player sits), row 0..14 is 1..15 (near edge to far edge). Cells hold [EMPTY], [BLACK] or [WHITE].
 */
object Board {
    const val N = 15
    const val SIZE = N * N
    const val EMPTY = 0
    const val BLACK = 1
    const val WHITE = 2

    /** Star points: H8 (tengen) and D4 / D12 / L4 / L12. */
    val STARS: List<Int> = listOf(at(7, 7), at(3, 3), at(3, 11), at(11, 3), at(11, 11))
    val CENTER: Int = at(7, 7)

    /** The four line directions as (dCol, dRow): horizontal, vertical, diagonal, anti-diagonal. */
    val DIRS: Array<IntArray> = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))

    fun at(col: Int, row: Int): Int = row * N + col
    fun col(p: Int): Int = p % N
    fun row(p: Int): Int = p / N
    fun inside(col: Int, row: Int): Boolean = col in 0 until N && row in 0 until N
    fun other(color: Int): Int = 3 - color

    /** "H8" style name: column letter A..O, row number 1..15. */
    fun name(p: Int): String = "${'A' + col(p)}${row(p) + 1}"

    fun parse(text: String): Int? {
        val t = text.trim().uppercase()
        if (t.length < 2) return null
        val c = t[0] - 'A'
        val r = t.substring(1).toIntOrNull()?.minus(1) ?: return null
        return if (inside(c, r)) at(c, r) else null
    }

    fun empty(): IntArray = IntArray(SIZE)

    /** Rebuilds a board from a move list (black first, alternating). */
    fun fromMoves(moves: List<Int>): IntArray {
        val b = empty()
        moves.forEachIndexed { i, p -> b[p] = if (i % 2 == 0) BLACK else WHITE }
        return b
    }

    fun colorToMove(moveCount: Int): Int = if (moveCount % 2 == 0) BLACK else WHITE
}
