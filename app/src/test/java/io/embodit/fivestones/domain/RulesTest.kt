package io.embodit.fivestones.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun p(n: String) = Board.parse(n)!!

    /** Board from "B:H8 I9 J10;W:A1 B2" style lists. */
    private fun board(black: String, white: String = ""): IntArray {
        val b = Board.empty()
        black.split(" ").filter { it.isNotBlank() }.forEach { b[p(it)] = Board.BLACK }
        white.split(" ").filter { it.isNotBlank() }.forEach { b[p(it)] = Board.WHITE }
        return b
    }

    @Test fun namesRoundTrip() {
        for (i in 0 until Board.SIZE) assertEquals(i, Board.parse(Board.name(i)))
        assertEquals("H8", Board.name(Board.CENTER))
        assertEquals(0, p("A1"))
        assertEquals(224, p("O15"))
    }

    @Test fun fiveInEveryDirection() {
        assertTrue(Rules.winsAt(board("A1 B1 C1 D1"), p("E1"), Board.BLACK, false))          // horizontal, edge
        assertTrue(Rules.winsAt(board("O11 O12 O13 O14"), p("O15"), Board.BLACK, false))     // vertical, corner
        assertTrue(Rules.winsAt(board("D4 E5 F6 G7"), p("H8"), Board.BLACK, false))          // diagonal
        assertTrue(Rules.winsAt(board("", "A15 B14 C13 D12"), p("E11"), Board.WHITE, false)) // anti-diagonal, corner
        assertFalse(Rules.winsAt(board("A1 B1 C1"), p("E1"), Board.BLACK, false))
    }

    @Test fun overlineWinsInFreeRuleOnly() {
        val b = board("A1 B1 C1 E1 F1")
        assertTrue(Rules.winsAt(b, p("D1"), Board.BLACK, false))
        assertFalse(Rules.winsAt(b, p("D1"), Board.BLACK, true))
        assertEquals(Forbidden.OVERLINE, Rules.forbidden(b, p("D1"), true))
        // White is never bound by forbidden moves: six wins for White with renju on.
        val w = board("", "A1 B1 C1 E1 F1")
        assertTrue(Rules.winsAt(w, p("D1"), Board.WHITE, true))
    }

    @Test fun overlineCases() {
        assertEquals(Forbidden.OVERLINE, Rules.forbidden(board("H2 H3 H4 H5 H7"), p("H6"), true))
        assertEquals(Forbidden.OVERLINE, Rules.forbidden(board("B2 C3 D4 F6 G7"), p("E5"), true))
        assertEquals(Forbidden.OVERLINE, Rules.forbidden(board("K1 K2 K3 K5 K6 K7"), p("K4"), true))
    }

    @Test fun doubleThree() {
        // Open three on row 8 (G8 H8 + I8) and on column I (I9 I10 + I8).
        assertEquals(Forbidden.DOUBLE_THREE, Rules.forbidden(board("G8 H8 I9 I10"), p("I8"), true))
        // Diagonal + horizontal, centre.
        assertEquals(Forbidden.DOUBLE_THREE, Rules.forbidden(board("F6 G7 J8 K8"), p("H8"), true))
        // Split three counts (G8 _ I8 + J8 makes G8 . I8 J8 is not an open three until H8; use H8 split): E5 F5 _ H5 with H... edge case near border
        assertEquals(Forbidden.DOUBLE_THREE, Rules.forbidden(board("C3 D3 E4 E5"), p("E3"), true))
        assertNull(Rules.forbidden(board("G8 H8 I9 I10"), p("I8"), false))
    }

    @Test fun doubleFour() {
        // Four on row 8 (E8 F8 G8 + H8) and on column H (H9 H10 H11 + H8), both closed by White.
        assertEquals(Forbidden.DOUBLE_FOUR, Rules.forbidden(board("E8 F8 G8 H9 H10 H11", "D8 H12"), p("H8"), true))
        assertEquals(Forbidden.DOUBLE_FOUR, Rules.forbidden(board("A1 A2 A3 B4 C4 D4", "O15"), p("A4"), true))
        // Corner: row 1 (L1 M1 N1 + O1) and column O (O2 O3 O4 + O1), each one way to five.
        assertEquals(Forbidden.DOUBLE_FOUR, Rules.forbidden(board("L1 M1 N1 O2 O3 O4"), p("O1"), true))
        // Diagonal + anti-diagonal through G7 (D4 E5 F6 + G7, J4 I5 H6 + G7).
        assertEquals(Forbidden.DOUBLE_FOUR, Rules.forbidden(board("D4 E5 F6 J4 I5 H6"), p("G7"), true))
        // Row + diagonal through H8, each closed on one end by White (E8 F8 G8 | D8 white; I9 J10 K11 | L12 white).
        assertEquals(Forbidden.DOUBLE_FOUR, Rules.forbidden(board("E8 F8 G8 I9 J10 K11", "D8 L12"), p("H8"), true))
        // A dead four (both ends blocked) is not a four.
        assertNull(Rules.forbidden(board("A1 A2 A3 B4 C4 D4", "E4 A5"), p("A4"), true))
    }

    @Test fun fivePlusThreeIsAWinNotForbidden() {
        val b = board("D8 E8 F8 G8 I9 I10")
        assertNull(Rules.forbidden(b, p("H8"), true))
        assertTrue(Rules.winsAt(b, p("H8"), Board.BLACK, true))
    }

    @Test fun whiteHasNoForbiddenMoves() {
        val b = board("", "G8 H8 I9 I10")
        assertTrue(Rules.isLegal(b, p("I8"), Board.WHITE, true))
    }

    @Test fun outcomes() {
        val win = Outcome.after(listOf("H8", "A1", "I8", "A2", "J8", "A3", "K8", "A4", "L8").map(::p), false)
        assertNotNull(win)
        assertEquals(Board.BLACK, win!!.winner)
        assertEquals(EndReason.FIVE, win.reason)
        assertEquals(5, win.lines.single().size)
        assertNull(Outcome.after(listOf("H8", "A1").map(::p), false))
    }

    @Test fun sixLinesLiftAllStones() {
        val moves = listOf("A8", "A1", "B8", "A2", "C8", "A3", "E8", "A4", "F8", "B1", "D8").map(::p)
        val r = Outcome.after(moves, false)!!
        assertEquals(6, r.lines.single().size)
    }

    @Test fun fullBoardIsADraw() {
        // colour((c + 2r) / 2 mod 2) never makes more than two in a row and has 113 black / 112 white.
        val blacks = ArrayList<Int>(); val whites = ArrayList<Int>()
        for (i in 0 until Board.SIZE) if (((Board.col(i) + 2 * Board.row(i)) / 2) % 2 == 0) blacks += i else whites += i
        assertEquals(113, blacks.size)
        val moves = ArrayList<Int>()
        for (k in 0 until 113) { moves += blacks[k]; if (k < 112) moves += whites[k] }
        val r = Outcome.after(moves, false)!!
        assertEquals(EndReason.FULL, r.reason)
        assertEquals(0, r.winner)
    }
}
