package io.embodit.fivestones.domain

import io.embodit.fivestones.domain.Board.BLACK
import io.embodit.fivestones.domain.Board.EMPTY

/** Why a Black move is forbidden (forbidden moves on). */
enum class Forbidden { DOUBLE_THREE, DOUBLE_FOUR, OVERLINE }

/**
 * The rules. Free rule (default): five or more in a row wins for either side. Forbidden moves on (simplified
 * Renju, see design-package specialRules): Black may not play a double three, a double four or an overline,
 * and only exactly five wins for Black; White is free. A point that completes an exact five is never forbidden.
 * The check is the simplified one: it does not ask whether a three could really become a straight four
 * without itself landing on a forbidden point.
 */
object Rules {
    fun exactFor(color: Int, renju: Boolean): Boolean = renju && color == BLACK

    /** Would [color] at empty [p] make a winning line? */
    fun winsAt(board: IntArray, p: Int, color: Int, renju: Boolean): Boolean {
        val exact = exactFor(color, renju)
        for (d in 0 until 4) {
            val n = Patterns.run(board, p, color, d)
            if (if (exact) n == 5 else n >= 5) return true
        }
        return false
    }

    /** The winning runs through [p] for [color] (already on the board): every direction with five (or 6+). */
    fun winningLines(board: IntArray, p: Int, color: Int, renju: Boolean): List<List<Int>> {
        val exact = exactFor(color, renju)
        val out = ArrayList<List<Int>>()
        for (d in 0 until 4) {
            val n = Patterns.run(board, p, color, d)
            if (if (exact) n == 5 else n >= 5) out += Patterns.runPoints(board, p, color, d)
        }
        return out
    }

    /** Null when Black may play [p]; otherwise the reason. Only meaningful for Black with forbidden moves on. */
    fun forbidden(board: IntArray, p: Int, renju: Boolean): Forbidden? {
        if (!renju || board[p] != EMPTY) return null
        var fours = 0
        var threes = 0
        var overline = false
        for (d in 0 until 4) {
            val n = Patterns.run(board, p, BLACK, d)
            if (n == 5) return null // an exact five always wins
            if (n >= 6) overline = true
            when (Patterns.shape(board, p, BLACK, d, exactFive = true)) {
                Patterns.FOUR, Patterns.OPEN4 -> fours++
                Patterns.OPEN3 -> threes++
            }
        }
        return when {
            overline -> Forbidden.OVERLINE
            fours >= 2 -> Forbidden.DOUBLE_FOUR
            threes >= 2 -> Forbidden.DOUBLE_THREE
            else -> null
        }
    }

    fun isLegal(board: IntArray, p: Int, color: Int, renju: Boolean): Boolean =
        p in 0 until Board.SIZE && board[p] == EMPTY && !(color == BLACK && forbidden(board, p, renju) != null)

    /** Every forbidden point for Black right now. */
    fun forbiddenPoints(board: IntArray, renju: Boolean): Map<Int, Forbidden> {
        if (!renju) return emptyMap()
        val out = LinkedHashMap<Int, Forbidden>()
        for (p in 0 until Board.SIZE) {
            if (board[p] != EMPTY || !hasNeighbour(board, p, 2)) continue
            forbidden(board, p, renju)?.let { out[p] = it }
        }
        return out
    }

    fun hasLegalMove(board: IntArray, color: Int, renju: Boolean): Boolean =
        (0 until Board.SIZE).any { isLegal(board, it, color, renju) }

    fun hasNeighbour(board: IntArray, p: Int, dist: Int): Boolean {
        val c0 = Board.col(p)
        val r0 = Board.row(p)
        for (dr in -dist..dist) for (dc in -dist..dist) {
            if (dr == 0 && dc == 0) continue
            val c = c0 + dc
            val r = r0 + dr
            if (Board.inside(c, r) && board[r * Board.N + c] != EMPTY) return true
        }
        return false
    }
}

enum class EndReason { FIVE, FULL, FORBIDDEN_STUCK, RESIGN }

/** The end of a game: [winner] is BLACK / WHITE, or 0 for a draw. [lines] are the winning runs to lift. */
data class GameResult(val winner: Int, val reason: EndReason, val lines: List<List<Int>> = emptyList())

object Outcome {
    /** The result after [moves] (the last one just played), or null if the game goes on. */
    fun after(moves: List<Int>, renju: Boolean): GameResult? {
        if (moves.isEmpty()) return null
        val board = Board.fromMoves(moves)
        val last = moves.last()
        val color = board[last]
        val lines = Rules.winningLines(board, last, color, renju)
        if (lines.isNotEmpty()) return GameResult(color, EndReason.FIVE, lines)
        if (moves.size >= Board.SIZE) return GameResult(0, EndReason.FULL)
        val next = Board.other(color)
        if (next == BLACK && renju && !Rules.hasLegalMove(board, BLACK, renju)) return GameResult(Board.WHITE, EndReason.FORBIDDEN_STUCK)
        return null
    }
}
