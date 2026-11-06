package io.embodit.fivestones.domain

import io.embodit.fivestones.domain.Board.BLACK
import io.embodit.fivestones.domain.Board.EMPTY
import kotlin.random.Random

/**
 * Local gomoku AI: negamax alpha-beta with iterative deepening and a time limit, over candidate points within
 * two of a stone, scored by line shapes (design-package gameSpec.ai). Five levels:
 *
 *  1 入门 depth 1, random among the top 5; blocks only fours (never an open three), so beginners can win.
 *  2 初级 depth 2, random among the top 3; blocks open threes.
 *  3 中级 depth 4 / 300 ms.   4 高级 depth 6 / 800 ms.   5 大师 depth 8 / 2 s, plus a VCF (continuous fours) search.
 *
 * Pure Kotlin. [shouldStop] is polled so a coroutine can cancel a search; [now] is the clock in ms.
 */
class Engine(
    private val random: Random = Random(7),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Level(val depth: Int, val timeMs: Long, val randomTop: Int, val blocksThrees: Boolean, val width: Int, val vcf: Int, val defend: Int = 0, val margin: Int = 1500)

    companion object {
        const val WIN = 10_000_000
        const val FIVE_SCORE = 1_000_000

        fun level(n: Int): Level = when (n.coerceIn(1, 5)) {
            1 -> Level(depth = 1, timeMs = 200, randomTop = 5, blocksThrees = false, width = 40, vcf = 0)
            // Each level adds one capability on top of the one below, so adjacent levels are clearly ordered
            // (mustPass: adjacent pairs >= 70 %): 2 = shallow + random, 3 = deeper + own VCF, 4 = + defends against the
            // opponent's VCF, 5 = deepest search, longest VCF / defence.
            2 -> Level(depth = 2, timeMs = 300, randomTop = 3, blocksThrees = true, width = 12, vcf = 0, margin = 4000)
            3 -> Level(depth = 4, timeMs = 300, randomTop = 1, blocksThrees = true, width = 10, vcf = 6)
            4 -> Level(depth = 6, timeMs = 800, randomTop = 1, blocksThrees = true, width = 10, vcf = 10, defend = 6)
            else -> Level(depth = 8, timeMs = 2000, randomTop = 1, blocksThrees = true, width = 12, vcf = 14, defend = 12)
        }
    }

    private class Stop : RuntimeException() { override fun fillInStackTrace(): Throwable = this }

    private val board = IntArray(Board.SIZE)
    private val near = IntArray(Board.SIZE)
    private var renju = false
    private var deadline = Long.MAX_VALUE
    private var stop: () -> Boolean = { false }
    private var nodes = 0L
    var lastNodes = 0L; private set
    var lastDepth = 0; private set

    /** The move [color] should play after [moves]. Never returns an occupied or forbidden point while a legal one exists. */
    fun bestMove(moves: List<Int>, color: Int, renju: Boolean, levelNumber: Int, shouldStop: () -> Boolean = { false }): Int {
        load(moves, renju)
        stop = shouldStop
        val lv = level(levelNumber)
        deadline = now() + lv.timeMs
        nodes = 0
        if (moves.isEmpty()) return Board.CENTER
        val cands = candidates(color)
        if (cands.isEmpty()) return (0 until Board.SIZE).firstOrNull { Rules.isLegal(board, it, color, renju) } ?: -1

        // Immediate five, then a forced block of the opponent's five.
        cands.firstOrNull { it.mine >= FIVE_SCORE }?.let { return it.p }
        val mustBlock = cands.filter { it.theirs >= FIVE_SCORE }
        if (mustBlock.isNotEmpty()) return mustBlock.maxBy { it.mine }.p

        if (levelNumber <= 1) return beginner(cands)

        if (lv.vcf > 0) {
            val deadlineBefore = deadline
            deadline = now() + lv.timeMs / 3
            val win = try { vcf(color, lv.vcf) } catch (_: Stop) { null }
            deadline = deadlineBefore
            if (win != null) { lastDepth = -1; return win }
        }

        var best = cands.first().p
        var ranked: List<Pair<Int, Int>> = cands.map { it.p to 0 }
        for (depth in 1..lv.depth) {
            try {
                ranked = root(color, depth, lv, ranked.map { it.first })
                best = ranked.first().first
                lastDepth = depth
                if (ranked.first().second >= WIN / 2) break
            } catch (_: Stop) {
                break
            }
        }
        lastNodes = nodes
        if (lv.defend > 0 && ranked.first().second < WIN / 2) {
            // Defence: skip a move after which the opponent has a winning chain of fours (VCF).
            val opp = Board.other(color)
            deadline = now() + lv.timeMs
            try {
                for ((p, _) in ranked.take(6)) {
                    place(p, color)
                    val threat = try { vcf(opp, lv.defend) } finally { unplace(p) }
                    if (threat == null) { best = p; break }
                }
            } catch (_: Stop) { }
        }
        if (lv.randomTop > 1) {
            val top = ranked.first().second
            if (top < WIN / 2 && top > -WIN / 2) {
                val pool = ranked.take(lv.randomTop).filter { it.second >= top - lv.margin }
                if (pool.size > 1) return pool[random.nextInt(pool.size)].first
            }
        }
        return best
    }

    /** Level 1: own five first, then the opponent's five; otherwise a random pick among the top 5 by own shape. */
    private fun beginner(cands: List<Cand>): Int {
        // Own shapes count only up to an open three and some noise is mixed in, so it builds lines but does not
        // hunt for double threats; it never blocks an open three (fives are handled before this).
        val ranked = cands.map { it to minOf(it.mine, 2_200) + random.nextInt(600) }.sortedByDescending { it.second }.map { it.first }
        val pool = ranked.take(5)
        return pool[random.nextInt(pool.size)].p
    }

    private fun root(color: Int, depth: Int, lv: Level, order: List<Int>): List<Pair<Int, Int>> {
        val cands = restrict(candidates(color), lv)
        val byPrev = order.withIndex().associate { it.value to it.index }
        val sorted = cands.sortedWith(compareBy<Cand> { byPrev[it.p] ?: Int.MAX_VALUE }.thenByDescending { it.order })
            .take(lv.width + 4)
        val out = ArrayList<Pair<Int, Int>>()
        var alpha = -WIN - 1
        for (c in sorted) {
            place(c.p, color)
            val v = try { -negamax(depth - 1, -WIN - 1, -alpha, Board.other(color), 1, lv) } finally { unplace(c.p) }
            out += c.p to v
            if (v > alpha) alpha = v
        }
        return out.sortedByDescending { it.second }
    }

    private fun negamax(depth: Int, alphaIn: Int, beta: Int, color: Int, ply: Int, lv: Level): Int {
        if ((++nodes and 255L) == 0L && (now() > deadline || stop())) throw Stop()
        var alpha = alphaIn
        val all = candidates(color)
        if (all.isEmpty()) return 0
        if (all.any { it.mine >= FIVE_SCORE }) return WIN - ply
        val cands = restrict(all, lv)
        if (cands.isEmpty()) return -(WIN - ply - 1) // only forbidden points left to block with
        if (depth <= 0) return evaluate(all)
        var best = -WIN - 1
        for (c in cands.sortedByDescending { it.order }.take(lv.width)) {
            place(c.p, color)
            val v = try { -negamax(depth - 1, -beta, -alpha, Board.other(color), ply + 1, lv) } finally { unplace(c.p) }
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /** Tactical narrowing: block a five; otherwise answer an open three with a block or a four of our own. */
    private fun restrict(all: List<Cand>, lv: Level): List<Cand> {
        val blocks = all.filter { it.theirs >= FIVE_SCORE }
        if (blocks.isNotEmpty()) return blocks
        if (all.any { it.mineOpenFour }) return all.filter { it.mineOpenFour || it.mine >= FIVE_SCORE }
        if (lv.blocksThrees && all.any { it.theirsOpenFour }) {
            return all.filter { it.theirsOpenFour || it.mineFour || it.theirs >= 8_000 }
        }
        return all
    }

    private fun evaluate(all: List<Cand>): Int {
        var mine = 0L
        var theirs = 0L
        for (c in all) { mine += c.mine; theirs += c.theirs }
        return (mine - theirs * 11 / 10).coerceIn(-(WIN / 4).toLong(), (WIN / 4).toLong()).toInt()
    }

    // ---- VCF: a chain of fours the opponent must keep blocking, ending in five or an open four ----

    private fun vcf(color: Int, depth: Int): Int? {
        if (depth <= 0) return null
        if ((++nodes and 255L) == 0L && (now() > deadline || stop())) throw Stop()
        val opp = Board.other(color)
        val cands = candidates(color)
        cands.firstOrNull { it.mine >= FIVE_SCORE }?.let { return it.p }
        if (cands.any { it.theirs >= FIVE_SCORE }) return null
        for (c in cands.filter { it.mineFour }.sortedByDescending { it.mine }) {
            place(c.p, color)
            try {
                val completions = fivePoints(color)
                if (completions.size >= 2) return c.p
                if (completions.size == 1) {
                    val block = completions[0]
                    if (!(opp == BLACK && Rules.forbidden(board, block, renju) != null)) {
                        if (Rules.winsAt(board, block, opp, renju)) continue
                        place(block, opp)
                        try {
                            if (fivePoints(opp).isEmpty() && vcf(color, depth - 1) != null) return c.p
                        } finally { unplace(block) }
                    } else {
                        return c.p // the only block is forbidden for Black
                    }
                }
            } finally { unplace(c.p) }
        }
        return null
    }

    private fun fivePoints(color: Int): List<Int> {
        val out = ArrayList<Int>(2)
        for (p in 0 until Board.SIZE) if (board[p] == EMPTY && near[p] > 0 && Rules.winsAt(board, p, color, renju)) out += p
        return out
    }

    // ---- board and candidates ----

    private class Cand(val p: Int, val mine: Int, val theirs: Int, val mineFour: Boolean, val mineOpenFour: Boolean, val theirsOpenFour: Boolean) {
        val order: Int get() = mine + theirs * 9 / 10
    }

    private fun load(moves: List<Int>, renju: Boolean) {
        this.renju = renju
        board.fill(EMPTY)
        near.fill(0)
        moves.forEachIndexed { i, p -> place(p, if (i % 2 == 0) BLACK else Board.WHITE) }
    }

    private fun place(p: Int, color: Int) {
        board[p] = color
        bump(p, 1)
    }

    private fun unplace(p: Int) {
        board[p] = EMPTY
        bump(p, -1)
    }

    private fun bump(p: Int, by: Int) {
        val c0 = Board.col(p)
        val r0 = Board.row(p)
        for (dr in -2..2) for (dc in -2..2) {
            val c = c0 + dc
            val r = r0 + dr
            if (Board.inside(c, r)) near[r * Board.N + c] += by
        }
    }

    private fun candidates(color: Int): List<Cand> {
        val opp = Board.other(color)
        val out = ArrayList<Cand>(48)
        for (p in 0 until Board.SIZE) {
            if (board[p] != EMPTY || near[p] == 0) continue
            val m = score(p, color)
            if (m.score < 0) continue // forbidden for Black
            val t = score(p, opp)
            out += Cand(p, m.score, maxOf(t.score, 0), m.four, m.openFour, t.openFour)
        }
        return out
    }

    private class PointScore(val score: Int, val four: Boolean, val openFour: Boolean)

    /** Shape score of [color] at empty [p]; -1 if it is a forbidden point for Black. */
    private fun score(p: Int, color: Int): PointScore {
        val exact = Rules.exactFor(color, renju)
        val table = Patterns.table(exact)
        var s = 0
        var fours = 0
        var open3 = 0
        var openFour = false
        var overline = false
        for (d in 0 until 4) {
            when (table[Patterns.code(board, p, color, d)].toInt()) {
                Patterns.FIVE -> return PointScore(FIVE_SCORE, true, true)
                Patterns.OVERLINE -> overline = true
                Patterns.OPEN4 -> { s += 10_000; fours++; openFour = true }
                Patterns.FOUR -> { s += 1_000; fours++ }
                Patterns.OPEN3 -> { s += 1_000; open3++ }
                Patterns.THREE -> s += 100
                Patterns.OPEN2 -> s += 100
                Patterns.TWO -> s += 10
            }
        }
        if (exact && (overline || fours >= 2 || open3 >= 2)) return PointScore(-1, false, false)
        if (overline) s += 0
        if (fours >= 2 || (fours >= 1 && open3 >= 1)) { s += 20_000; openFour = true }
        else if (open3 >= 2) s += 8_000
        return PointScore(s + centrality(p), fours > 0, openFour)
    }

    private fun centrality(p: Int): Int {
        val dc = Board.col(p) - 7
        val dr = Board.row(p) - 7
        return 7 - maxOf(kotlin.math.abs(dc), kotlin.math.abs(dr)) / 2
    }
}
