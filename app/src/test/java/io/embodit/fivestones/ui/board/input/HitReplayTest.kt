package io.embodit.fivestones.ui.board.input

import io.embodit.fivestones.domain.Board
import io.embodit.fivestones.domain.GameConfig
import io.embodit.fivestones.domain.GameFlow
import io.embodit.fivestones.domain.GameStore
import io.embodit.fivestones.domain.Mode
import io.embodit.fivestones.domain.Phase
import io.embodit.fivestones.domain.SavedGame
import io.embodit.fivestones.domain.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * build.md「命中目标 → 格子必须有回放单测」: replay what the system actually reports as the hit entity (the visible marker
 * mesh, its pad holder, a placed stone, the board face, a bowl, the flying stone …) through the same resolver the
 * board-input template uses, and on into GameFlow.tap — every hit on a point must resolve to that point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private val parents = listOf("boardRoot", "tiltRoot", "sceneRoot", "root")

    @Test fun everyMarkerPadAndStoneResolvesToItsPoint() {
        for (p in 0 until Board.SIZE) {
            assertEquals(p, FiveTargets.resolvePath(listOf("marker:$p", "pad:$p") + parents))   // gaze / ray on the marker dot
            assertEquals(p, FiveTargets.resolvePath(listOf("pad:$p") + parents))                // on the pad collider rim
            assertEquals(p, FiveTargets.resolvePath(listOf("stone:$p") + parents))              // on a placed stone
        }
    }

    @Test fun nonTargetsResolveToNothing() {
        for (path in listOf(listOf("face") + parents, listOf("slab") + parents, listOf("bowlBody:1", "bowl:1", "sceneRoot"),
                listOf("flyer") + parents, listOf("ghost") + parents, listOf("pillar:0") + parents, listOf("plate", "sceneRoot"))) {
            assertNull(path.toString(), FiveTargets.resolvePath(path))
        }
        assertNull(FiveTargets.pointOf("pad:225"))
        assertNull(FiveTargets.pointOf("pad:x"))
    }

    private class Mem : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    private fun flow(scope: TestScope) = GameFlow(
        scope.backgroundScope, StandardTestDispatcher(scope.testScheduler), { scope.testScheduler.currentTime },
        Mem(), { Settings(firstRunDone = true, coachDone = true) },
    )

    @Test fun replayedHitsPlaceStonesOnTheHitPoint() = runTest {
        val f = flow(this)
        f.newGame(GameConfig(Mode.TWO_PLAYER, Board.BLACK, 1, false)); runCurrent()
        val points = listOf(0, 14, 112, 210, 224, 7, 105, 119, 217, 31)
        for (p in points) {
            val hit = FiveTargets.resolvePath(listOf("marker:$p", "pad:$p") + parents)
            assertEquals(p, hit)
            f.tap(hit!!)
            advanceTimeBy(GameFlow.FLIGHT_MS + GameFlow.RETRACT_MS + 100); runCurrent()
        }
        assertEquals(points, f.state.value.moves)
        // A hit on a placed stone inside the retract window takes it back; after the window it is "taken".
        f.tap(FiveTargets.resolvePath(listOf("marker:50", "pad:50") + parents)!!)
        advanceTimeBy(300)
        f.tap(FiveTargets.resolvePath(listOf("stone:50") + parents)!!)
        advanceTimeBy(GameFlow.BACK_MS + 50); runCurrent()
        assertTrue(50 !in f.state.value.moves)
        f.tap(FiveTargets.resolvePath(listOf("stone:112") + parents)!!)
        assertEquals(Phase.ALERT, f.state.value.phase)
    }
}
