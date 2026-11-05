package io.embodit.fivestones.domain

/**
 * What one stone makes along one line. The stone sits in the middle of a 9-cell window (4 cells each way);
 * the other 8 cells are empty, own, or blocked (opponent or off the board). Every window is classified once
 * into a lookup table, so the rules, the shape pillars and the AI all read the same answer.
 *
 * "Exact" tables are for Black with forbidden moves on: only exactly five wins and six or more is an
 * overline. "Free" tables count five or more as a win.
 */
object Patterns {
    const val NONE = 0
    const val TWO = 1        // closed two: one move from a closed three
    const val OPEN2 = 2      // one move from an open three
    const val THREE = 3      // closed three: one move from a four
    const val OPEN3 = 4      // one move from an open (straight) four
    const val FOUR = 5       // one move from five, one way
    const val OPEN4 = 6      // one move from five, two ways
    const val FIVE = 7
    const val OVERLINE = 8   // six or more (exact tables only)

    private const val CELLS = 8
    private const val CODES = 6561 // 3^8

    private val free: ByteArray by lazy { build(exact = false) }
    private val exact: ByteArray by lazy { build(exact = true) }

    /** Builds both tables now (call off the main thread at startup). */
    fun warmUp() { free.size; exact.size }

    fun table(exactFive: Boolean): ByteArray = if (exactFive) exact else free

    /**
     * The shape [color] makes at [p] along direction [dir] (index into Board.DIRS), as if a [color] stone
     * stands on [p] (whatever is there now).
     */
    fun shape(board: IntArray, p: Int, color: Int, dir: Int, exactFive: Boolean): Int =
        table(exactFive)[code(board, p, color, dir)].toInt()

    fun code(board: IntArray, p: Int, color: Int, dir: Int): Int {
        val d = Board.DIRS[dir]
        val c0 = Board.col(p)
        val r0 = Board.row(p)
        var code = 0
        var mul = 1
        for (k in -4..4) {
            if (k == 0) continue
            val c = c0 + d[0] * k
            val r = r0 + d[1] * k
            val v = if (!Board.inside(c, r)) 2 else {
                val s = board[r * Board.N + c]
                if (s == color) 1 else if (s == Board.EMPTY) 0 else 2
            }
            code += v * mul
            mul *= 3
        }
        return code
    }

    /** Length of the run of [color] stones through [p] along [dir], counting [p] as [color]. */
    fun run(board: IntArray, p: Int, color: Int, dir: Int): Int {
        val d = Board.DIRS[dir]
        var n = 1
        for (sign in intArrayOf(1, -1)) {
            var c = Board.col(p) + d[0] * sign
            var r = Board.row(p) + d[1] * sign
            while (Board.inside(c, r) && board[r * Board.N + c] == color) { n++; c += d[0] * sign; r += d[1] * sign }
        }
        return n
    }

    /** The points of that run, in order along [dir]. */
    fun runPoints(board: IntArray, p: Int, color: Int, dir: Int): List<Int> {
        val d = Board.DIRS[dir]
        var c = Board.col(p)
        var r = Board.row(p)
        while (Board.inside(c - d[0], r - d[1]) && board[(r - d[1]) * Board.N + (c - d[0])] == color) { c -= d[0]; r -= d[1] }
        val out = ArrayList<Int>()
        while (Board.inside(c, r) && (board[r * Board.N + c] == color || (r * Board.N + c) == p)) { out += r * Board.N + c; c += d[0]; r += d[1] }
        return out
    }

    // ---- table construction (pure, exhaustive) ----

    private fun build(exact: Boolean): ByteArray {
        val out = ByteArray(CODES)
        val line = IntArray(9)
        for (code in 0 until CODES) {
            var x = code
            var j = 0
            for (k in 0 until 9) {
                if (k == 4) { line[k] = 1; continue }
                line[k] = x % 3
                x /= 3
                j++
            }
            out[code] = classify(line, exact).toByte()
        }
        return out
    }

    private fun runAt(l: IntArray, i: Int): Int {
        var n = 1
        var k = i - 1
        while (k >= 0 && l[k] == 1) { n++; k-- }
        k = i + 1
        while (k < l.size && l[k] == 1) { n++; k++ }
        return n
    }

    private fun isFive(l: IntArray, exact: Boolean): Boolean {
        val n = runAt(l, 4)
        return if (exact) n == 5 else n >= 5
    }

    /** Empty cells that would make five through the centre. */
    private fun completions(l: IntArray, exact: Boolean): Int {
        var n = 0
        for (e in l.indices) {
            if (l[e] != 0) continue
            l[e] = 1
            if (isFive(l, exact)) n++
            l[e] = 0
        }
        return n
    }

    /** OPEN3 / THREE / NONE for a line with no four yet. */
    private fun threeLevel(l: IntArray, exact: Boolean): Int {
        var open = false
        var closed = false
        for (e in l.indices) {
            if (l[e] != 0) continue
            l[e] = 1
            if (!(exact && runAt(l, 4) >= 6) && !isFive(l, exact)) {
                val c = completions(l, exact)
                if (c >= 2) open = true else if (c == 1) closed = true
            }
            l[e] = 0
            if (open) break
        }
        return if (open) OPEN3 else if (closed) THREE else NONE
    }

    private fun classify(l: IntArray, exact: Boolean): Int {
        if (exact && runAt(l, 4) >= 6) return OVERLINE
        if (isFive(l, exact)) return FIVE
        val c = completions(l, exact)
        if (c >= 2) return OPEN4
        if (c == 1) return FOUR
        val t = threeLevel(l, exact)
        if (t != NONE) return t
        var open2 = false
        var two = false
        for (e in l.indices) {
            if (l[e] != 0) continue
            l[e] = 1
            when (threeLevel(l, exact)) {
                OPEN3 -> open2 = true
                THREE -> two = true
            }
            l[e] = 0
            if (open2) break
        }
        return if (open2) OPEN2 else if (two) TWO else NONE
    }
}
