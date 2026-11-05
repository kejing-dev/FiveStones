package io.embodit.fivestones.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private fun p(n: String) = Board.parse(n)!!

    private class MemStore : GameStore {
        var saved: SavedGame? = null
        var writes = 0
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game; writes++ }
        override fun clear() { saved = null }
    }

    private class Harness(scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true, coachDone = true)) {
        val phases = ArrayList<Phase>()
        val flow = GameFlow(
            scope = scope.backgroundScope,
            engineDispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            store = store,
            settings = { settings },
            onSettings = { settings = it },
        )
    }

    @Test fun retractWithinWindowTakesTheStoneBackAndNeverStartsTheAi() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 3, false))
        runCurrent()
        h.flow.hoverNow(p("H8"))
        h.flow.tap(p("H8"))
        assertEquals(Phase.PLACING, h.flow.state.value.phase)
        assertEquals(1, h.store.saved!!.moves.size) // written at once, before the window closes
        advanceTimeBy(300)
        h.flow.tap(p("H8"))
        advanceTimeBy(GameFlow.BACK_MS + 10)
        val st = h.flow.state.value
        assertEquals(Phase.IDLE, st.phase)
        assertTrue(st.moves.isEmpty())
        assertTrue(h.store.saved!!.moves.isEmpty())
        advanceTimeBy(2000)
        assertEquals(Phase.IDLE, h.flow.state.value.phase) // the AI never moved
    }

    @Test fun aiSearchStartsOnlyAfterTheWindow() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 1, false))
        runCurrent()
        h.flow.tap(p("H8"))
        advanceTimeBy(GameFlow.FLIGHT_MS + GameFlow.RETRACT_MS - 20)
        assertEquals(Phase.PLACING, h.flow.state.value.phase)
        advanceTimeBy(40)
        runCurrent()
        assertEquals(Phase.THINKING, h.flow.state.value.phase)
        advanceTimeBy(5000); runCurrent()
        // AI moved (min think 400 ms), its stone flew, and it is the human's turn again.
        val st = h.flow.state.value
        assertEquals(2, st.moves.size)
        assertEquals(Phase.IDLE, st.phase)
    }

    @Test fun tapOutsideTheWindowDoesNotRetract() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Board.BLACK, 1, false))
        runCurrent()
        h.flow.tap(p("H8"))
        advanceTimeBy(GameFlow.FLIGHT_MS + GameFlow.RETRACT_MS + 50)
        h.flow.tap(p("H8"))
        assertEquals(Phase.ALERT, h.flow.state.value.phase)
        assertEquals(AlertKind.OCCUPIED, h.flow.state.value.alert!!.kind)
        assertEquals(1, h.flow.state.value.moves.size)
        advanceTimeBy(GameFlow.ALERT_MS + 10)
        assertEquals(Phase.IDLE, h.flow.state.value.phase)
    }

    @Test fun ghostIsWhatGetsPlaced() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Board.BLACK, 1, false))
        runCurrent()
        h.flow.hover(p("H8"))
        advanceTimeBy(GameFlow.HOVER_DWELL_MS + 5)
        assertEquals(p("H8"), h.flow.state.value.ghost)
        assertEquals(Phase.PREVIEW, h.flow.state.value.phase)
        // The gaze jitters to a neighbour and the pinch lands there before the dwell: the ghost's point is placed.
        h.flow.hover(p("I8"))
        advanceTimeBy(30)
        h.flow.tap(p("I8"))
        assertEquals(p("H8"), h.flow.state.value.moves.single())
    }

    @Test fun forbiddenPointIsRefused() = runTest {
        val h = Harness(this)
        val cfg = GameConfig(Mode.TWO_PLAYER, Board.BLACK, 1, true)
        h.flow.load(listOf("G8", "A1", "H8", "A3", "I9", "A5", "I10", "A7").map(::p), cfg)
        runCurrent()
        assertTrue(p("I8") in h.flow.state.value.forbidden)
        h.flow.tap(p("I8"))
        val st = h.flow.state.value
        assertEquals(Phase.ALERT, st.phase)
        assertEquals(Forbidden.DOUBLE_THREE, st.alert!!.reason)
        assertEquals(8, st.moves.size)
    }

    @Test fun undoInAiModeTakesBackAWholeRoundAndRedoReplaysIt() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 1, false))
        runCurrent()
        h.flow.tap(p("H8"))
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, h.flow.state.value.moves.size)
        h.flow.requestUndo()
        runCurrent()
        assertEquals(0, h.flow.state.value.moves.size)
        assertEquals(2, h.flow.state.value.redo.size)
        h.flow.redo()
        runCurrent()
        assertEquals(2, h.flow.state.value.moves.size)
        assertEquals(Phase.IDLE, h.flow.state.value.phase)
    }

    @Test fun twoPlayerUndoAsksFirst() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Board.BLACK, 1, false))
        runCurrent()
        h.flow.tap(p("H8")); advanceTimeBy(5000); runCurrent()
        h.flow.tap(p("I9")); advanceTimeBy(5000); runCurrent()
        h.flow.requestUndo()
        assertEquals(ConfirmKind.UNDO_2P, h.flow.state.value.confirm)
        assertEquals(2, h.flow.state.value.moves.size)
        h.flow.confirm(); runCurrent()
        assertEquals(1, h.flow.state.value.moves.size)
    }

    @Test fun fiveEndsTheGameAndClearsTheSave() = runTest {
        val h = Harness(this)
        val cfg = GameConfig(Mode.TWO_PLAYER, Board.BLACK, 1, false)
        h.flow.load(listOf("D8", "A1", "E8", "A3", "F8", "A5", "G8", "A7").map(::p), cfg)
        runCurrent()
        h.flow.tap(p("H8"))
        advanceTimeBy(5000); runCurrent()
        val st = h.flow.state.value
        assertEquals(Phase.RESULT, st.phase)
        assertEquals(Board.BLACK, st.result!!.winner)
        assertEquals(5, st.result!!.lines.single().size)
        assertNull(h.store.saved)
    }

    @Test fun restoreAfterKillInsideTheWindowCountsTheMoveAndLetsTheAiThink() = runTest {
        val store = MemStore()
        val h = Harness(this, store)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 1, false))
        runCurrent()
        h.flow.tap(p("H8"))
        advanceTimeBy(200) // killed inside the retract window
        val h2 = Harness(this, store)
        h2.flow.start()
        runCurrent()
        val st = h2.flow.state.value
        assertEquals(1, st.moves.size)
        assertTrue(st.restored)
        assertEquals(Phase.THINKING, st.phase)
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, h2.flow.state.value.moves.size)
    }

    @Test fun shapesDefaultsFollowTheLevelUntilTheUserChoosesAndSettingsSurviveAGame() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 2, false)); runCurrent()
        assertTrue(h.flow.state.value.shapesOn)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 5, false)); runCurrent()
        assertFalse(h.flow.state.value.shapesOn)
        h.flow.setShapes(true)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 5, false)); runCurrent()
        assertTrue(h.flow.state.value.shapesOn)
        // Playing a game never changes tilt / size / sound (D29-D31: game-only settings stay in the game).
        val before = h.settings.copy(shapesOverride = null, lastSetup = GameSetup())
        h.flow.tap(p("H8")); advanceTimeBy(5000); runCurrent()
        assertEquals(before, h.settings.copy(shapesOverride = null, lastSetup = GameSetup()))
    }

    @Test fun firstRunShowsWelcome() = runTest {
        val h = Harness(this, settings = Settings(firstRunDone = false))
        h.flow.start(); runCurrent()
        assertEquals(Phase.WELCOME, h.flow.state.value.phase)
        h.flow.welcomeDone()
        assertEquals(Phase.SETUP, h.flow.state.value.phase)
        assertTrue(h.settings.firstRunDone)
    }

    @Test fun freezeHoldsThePlacingState() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.TWO_PLAYER, Board.BLACK, 1, false)); runCurrent()
        h.flow.freeze(300)
        h.flow.tap(p("H8"))
        advanceTimeBy(5000)
        assertEquals(Phase.PLACING, h.flow.state.value.phase)
        assertNotNull(h.flow.state.value.freezeMs)
        h.flow.freeze(null)
        advanceTimeBy(200)
        assertEquals(Phase.IDLE, h.flow.state.value.phase)
    }

    @Test fun resignAsks() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Board.BLACK, 1, false)); runCurrent()
        h.flow.requestConfirm(ConfirmKind.RESIGN)
        h.flow.confirm(); runCurrent()
        val st = h.flow.state.value
        assertEquals(Phase.RESULT, st.phase)
        assertEquals(EndReason.RESIGN, st.result!!.reason)
        assertEquals(Board.WHITE, st.result!!.winner)
    }
}
