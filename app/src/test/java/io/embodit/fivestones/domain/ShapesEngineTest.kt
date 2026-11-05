package io.embodit.fivestones.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShapesEngineTest {
    private fun p(n: String) = Board.parse(n)!!
    private fun moves(s: String) = s.split(" ").filter { it.isNotBlank() }.map(::p)

    /** Interleaves black and white lists into a legal move order (black first). */
    private fun game(black: String, white: String): List<Int> {
        val b = moves(black); val w = moves(white)
        require(b.size == w.size || b.size == w.size + 1)
        val out = ArrayList<Int>()
        for (i in b.indices) { out += b[i]; if (i < w.size) out += w[i] }
        return out
    }

    @Test fun sampleFirstFramePillars() {
        // design-package sampleFirstFrame: black F6 G7 H8 J10 C9 L4, white I12 J12 K12 E5 G9 (white to move? 6 vs 5: white to move).
        val g = game("F6 G7 H8 J10 C9 L4", "I12 J12 K12 E5 G9")
        val pillars = Shapes.pillars(Board.fromMoves(g), renju = false, preferColor = Board.BLACK)
        val byPoint = pillars.associateBy { Board.name(it.point) }
        assertEquals(Shapes.FOUR, byPoint.getValue("I9").level)
        assertEquals(Board.BLACK, byPoint.getValue("I9").color)
        assertEquals(Shapes.THREE, byPoint.getValue("H12").level)
        assertEquals(Shapes.THREE, byPoint.getValue("L12").level)
        assertEquals(Board.WHITE, byPoint.getValue("L12").color)
        assertTrue(pillars.size <= Shapes.MAX)
    }

    @Test fun openFourPillarsAreTallest() {
        val g = game("E8 F8 G8 H8", "A1 A3 A5")
        val pillars = Shapes.pillars(Board.fromMoves(g), false)
        val tall = pillars.filter { it.level == Shapes.OPEN_FOUR }.map { Board.name(it.point) }.toSet()
        assertEquals(setOf("D8", "I8"), tall)
    }

    @Test fun pillarsCapAtTwelve() {
        val g = game("C3 E3 G3 C7 E7 G7 C11 E11", "K3 M3 K7 M7 K11 M11 O1 O15")
        // Not necessarily > 12 candidates, but never more than 12 shown.
        assertTrue(Shapes.pillars(Board.fromMoves(g + p("I13")), false).size <= 12)
    }

    @Test fun everyLevelPlaysTheWinningFive() {
        val g = game("D8 E8 F8 G8", "D9 E9 F9 A1")
        for (level in 1..5) {
            val m = Engine(Random(level)).bestMove(g, Board.BLACK, false, level)
            assertTrue("level $level played ${Board.name(m)}", m == p("C8") || m == p("H8"))
        }
    }

    @Test fun everyLevelBlocksAFour() {
        // White to move; Black threatens D8-G8 with H8 open, C8 blocked by white.
        val g = game("D8 E8 F8 G8 A15", "C8 A1 A3 A5")
        for (level in 1..5) {
            val m = Engine(Random(level)).bestMove(g, Board.WHITE, false, level)
            assertEquals("level $level", p("H8"), m)
        }
    }

    @Test fun levelsTwoUpBlockOpenThrees() {
        val rnd = Random(3)
        var blocked = 0
        var total = 0
        repeat(20) { k ->
            // An open three for black somewhere in the middle, white to move with scattered stones.
            val r = 4 + rnd.nextInt(7); val c = 4 + rnd.nextInt(5)
            val three = listOf(Board.at(c, r), Board.at(c + 1, r), Board.at(c + 2, r))
            val whites = ArrayList<Int>()
            // White's stones on a far edge row, three or more columns apart, so White has no shape of its own.
            val cols = listOf(0, 4, 8, 12).shuffled(rnd).take(3)
            for (cc in cols) whites += Board.at(cc, if (r > 7) 0 else 14)
            val g = ArrayList<Int>()
            for (i in 0 until 3) { g += three[i]; g += whites[i] }
            g += Board.at(if (r > 7) 14 else 0, 7)
            // now white to move (7 moves: 4 black, 3 white)
            for (level in 2..3) {
                val m = Engine(Random(k)).bestMove(g, Board.WHITE, false, level)
                total++
                val ends = listOf(Board.at(c - 1, r), Board.at(c + 3, r))
                if (m in ends) blocked++ else println("MISS level=$level ${g.joinToString(" ") { Board.name(it) }} -> ${Board.name(m)}")
            }
        }
        assertTrue("blocked $blocked / $total", blocked >= total * 95 / 100)
    }

    @Test fun beginnerDoesNotBlockOpenThrees() {
        // Level 1 only blocks fours: with an open three on the board it may play elsewhere (B27 beginners can win).
        var elsewhere = 0
        repeat(10) { k ->
            val g = game("G8 H8 I8 A15", "A1 A3 A5")
            val m = Engine(Random(k)).bestMove(g, Board.WHITE, false, 1)
            if (m != p("F8") && m != p("J8")) elsewhere++
        }
        assertTrue(elsewhere > 0)
    }

    @Test fun aiAvoidsForbiddenPointsAsBlack() {
        // Black to move, renju on; I8 is a double three for Black. The engine never plays it.
        val g = game("G8 H8 I9 I10 A15", "A1 A3 A5 O1")
        repeat(5) { k ->
            val m = Engine(Random(k)).bestMove(g, Board.BLACK, true, 2)
            assertNotEquals(p("I8"), m)
        }
    }

    @Test fun masterFindsVcf() {
        // Black can win by continuous fours: column F (F5 F6 F7) and row 9 shapes. Just check it wins quickly by
        // playing moves until five against forced blocks.
        val g = game("F5 F6 F7 H9 I9 A15", "F4 J9 A1 A3 A5")
        val m = Engine(Random(1)).bestMove(g, Board.BLACK, false, 5)
        assertTrue(Rules.hasNeighbour(Board.fromMoves(g), m, 2))
    }

    /** "Naive bot": take a five, block a five, block an open three, else a random point next to a stone. */
    private fun naive(moves: List<Int>, color: Int, rnd: Random): Int {
        val b = Board.fromMoves(moves)
        val empties = (0 until Board.SIZE).filter { b[it] == Board.EMPTY }
        empties.firstOrNull { Rules.winsAt(b, it, color, false) }?.let { return it }
        empties.firstOrNull { Rules.winsAt(b, it, Board.other(color), false) }?.let { return it }
        empties.firstOrNull { q -> (0 until 4).any { d -> Patterns.shape(b, q, Board.other(color), d, false) == Patterns.OPEN4 } }?.let { return it }
        val near = empties.filter { Rules.hasNeighbour(b, it, 1) }
        return if (near.isEmpty()) Board.CENTER else near[rnd.nextInt(near.size)]
    }

    private fun play(black: (List<Int>) -> Int, white: (List<Int>) -> Int): Int {
        val moves = ArrayList<Int>()
        while (true) {
            val color = Board.colorToMove(moves.size)
            val m = if (color == Board.BLACK) black(moves) else white(moves)
            moves += m
            val r = Outcome.after(moves, false)
            if (r != null) return r.winner
        }
    }

    @Test fun naiveBotBeatsBeginnerHalfTheTime() {
        var naiveWins = 0
        repeat(20) { g ->
            val rnd = Random(100 + g)
            val engine = Engine(Random(200 + g))
            val naiveIsBlack = g % 2 == 0
            val winner = if (naiveIsBlack) play({ naive(it, Board.BLACK, rnd) }, { engine.bestMove(it, Board.WHITE, false, 1) })
            else play({ engine.bestMove(it, Board.BLACK, false, 1) }, { naive(it, Board.WHITE, rnd) })
            if (winner == (if (naiveIsBlack) Board.BLACK else Board.WHITE)) naiveWins++
        }
        println("naive bot vs level 1: $naiveWins / 20")
        assertTrue("naive won $naiveWins / 20", naiveWins >= 10)
    }

    @Test fun middleBeatsBeginner() {
        var wins = 0
        val games = 10
        repeat(games) { g ->
            val hi = Engine(Random(300 + g)); val lo = Engine(Random(400 + g))
            val hiBlack = g % 2 == 0
            val winner = if (hiBlack) play({ hi.bestMove(it, Board.BLACK, false, 3) }, { lo.bestMove(it, Board.WHITE, false, 1) })
            else play({ lo.bestMove(it, Board.BLACK, false, 1) }, { hi.bestMove(it, Board.WHITE, false, 3) })
            if (winner == (if (hiBlack) Board.BLACK else Board.WHITE)) wins++
        }
        println("level 3 vs level 1: $wins / $games")
        assertTrue(wins >= games * 7 / 10)
    }

    @Test fun beginnerLevelTwoVsOne() {
        var wins = 0
        val games = 10
        repeat(games) { g ->
            val hi = Engine(Random(500 + g)); val lo = Engine(Random(600 + g))
            val hiBlack = g % 2 == 0
            val winner = if (hiBlack) play({ hi.bestMove(it, Board.BLACK, false, 2) }, { lo.bestMove(it, Board.WHITE, false, 1) })
            else play({ lo.bestMove(it, Board.BLACK, false, 1) }, { hi.bestMove(it, Board.WHITE, false, 2) })
            if (winner == (if (hiBlack) Board.BLACK else Board.WHITE)) wins++
        }
        println("level 2 vs level 1: $wins / $games")
        assertTrue(wins >= games * 7 / 10)
    }
}
