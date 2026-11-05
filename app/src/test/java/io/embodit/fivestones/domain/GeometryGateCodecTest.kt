package io.embodit.fivestones.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * design-package spatialLayout.panelGeometryCheck.unitTest (profile D35):
 * 3 tilts x 2 sizes x 4 view yaws x 3 eye heights:
 *  1. eye -> each of the 225 points does not pass through either bowl or the move plate;
 *  2. the plate's corners stay >= 2 cm from the board slab;
 *  3. every flight (bowl -> any point, arc 8 cm) stays inside the 0.84 x 0.54 x 0.96 m volume.
 */
class GeometryGateCodecTest {
    private val tilts = listOf(0, 20, 28)
    private val sizes = listOf(100, 110)
    private val yaws = listOf(-30f, 0f, 30f, 45f)
    /** (height above the volume floor, distance behind the board's near edge). */
    private val eyes = listOf(0.35f to 0.45f, 0.50f to 0.55f, 0.90f to 0.50f)

    private fun eyeAt(g: BoardGeometry, height: Float, back: Float, yawDeg: Float): V3 {
        val near = g.tiltOrigin.z
        val a = Math.toRadians(yawDeg.toDouble()).toFloat()
        // Rotate the viewer around the board centre (scene x = 0, z = board centre) by the yaw.
        val cz = BoardGeometry.CONTENT_Z
        val r = near - cz + back
        return V3(r * sin(a), height, cz + r * cos(a))
    }

    private fun insideBowl(g: BoardGeometry, v: V3): Boolean {
        for (c in listOf(Board.BLACK, Board.WHITE)) {
            val b = g.bowlBase(c)
            if (hypot(v.x - b.x, v.z - b.z) < BoardGeometry.BOWL_R && v.y in 0f..BoardGeometry.BOWL_H) return true
        }
        return false
    }

    private fun insidePlate(g: BoardGeometry, v: V3, eye: V3): Boolean {
        // The plate turns to face the viewer about the vertical axis: a 10 x 3.5 cm rectangle, ~1 cm thick.
        val c = g.plateCentre()
        val yaw = kotlin.math.atan2(eye.x - c.x, eye.z - c.z)
        val dx = v.x - c.x; val dz = v.z - c.z
        val along = dx * cos(yaw) - dz * sin(yaw)
        val depth = dx * sin(yaw) + dz * cos(yaw)
        return abs(along) <= BoardGeometry.PLATE_W / 2 && abs(v.y - c.y) <= BoardGeometry.PLATE_H / 2 && abs(depth) <= 0.005f
    }

    @Test fun sightLinesToEveryPointMissBowlsAndPlate() {
        var checked = 0
        for (t in tilts) for (s in sizes) for (y in yaws) for ((h, back) in eyes) {
            val g = BoardGeometry(t, s)
            val eye = eyeAt(g, h, back, y)
            for (p in 0 until Board.SIZE) {
                val target = g.pointScene(p, 0.0045f)
                for (k in 1 until 60) {
                    val v = eye.lerp(target, k / 60f)
                    assertTrue("tilt=$t size=$s yaw=$y eye=$h point ${Board.name(p)} hits a bowl at $v", !insideBowl(g, v))
                    assertTrue("tilt=$t size=$s yaw=$y eye=$h point ${Board.name(p)} hits the plate", !insidePlate(g, v, eye))
                }
                checked++
            }
        }
        assertEquals(3 * 2 * 4 * 3 * 225, checked)
    }

    @Test fun plateCornersClearTheBoard() {
        for (t in tilts) for (s in sizes) {
            val g = BoardGeometry(t, s)
            val c = g.plateCentre()
            val bottom = c.y - BoardGeometry.PLATE_H / 2
            // The board's highest point is its far top edge.
            assertTrue("tilt=$t size=$s plate bottom ${bottom} vs far top ${g.farTop().y}", bottom - g.farTop().y >= 0.02f - 1e-4f)
            // Top of the plate inside the volume.
            assertTrue(c.y + BoardGeometry.PLATE_H / 2 <= BoardGeometry.VOLUME.y)
        }
    }

    @Test fun flightsStayInsideTheVolume() {
        val half = BoardGeometry.VOLUME * 0.5f
        for (t in tilts) for (s in sizes) {
            val g = BoardGeometry(t, s)
            for (color in listOf(Board.BLACK, Board.WHITE)) for (p in 0 until Board.SIZE) for (k in 0..20) {
                val v = g.flight(color, p, k / 20f)
                assertTrue("tilt=$t size=$s ${Board.name(p)} x=${v.x}", abs(v.x) <= half.x)
                assertTrue("tilt=$t size=$s ${Board.name(p)} y=${v.y}", v.y in 0f..BoardGeometry.VOLUME.y)
                assertTrue("tilt=$t size=$s ${Board.name(p)} z=${v.z}", abs(v.z) <= half.z)
            }
            // Board corners and bowls inside too.
            for (p in listOf(0, 14, 210, 224)) assertTrue(abs(g.pointScene(p).z) <= half.z)
            assertTrue(g.bowlBase(Board.WHITE).z + BoardGeometry.BOWL_R <= half.z)
        }
    }

    @Test fun localSceneRoundTrip() {
        val g = BoardGeometry(28, 110)
        for (p in listOf(0, 112, 224)) {
            val l = g.local(p, 0.01f)
            val back = g.toLocal(g.toScene(l))
            assertTrue(abs(back.x - l.x) < 1e-5f && abs(back.y - l.y) < 1e-5f && abs(back.z - l.z) < 1e-5f)
        }
        // Near-bottom edge rests on the floor for every tilt.
        for (t in tilts) assertEquals(0f, BoardGeometry(t, 100).toScene(V3(0f, -0.03f, 0.35f)).y, 1e-4f)
    }

    @Test fun levelGateWaitsForALevelHeadAndFallsBack() {
        val g = LevelGate()
        g.ask("start", 0)
        assertNull(g.step(10, -30f))
        assertTrue(g.hintWanted(-30f))
        assertNull(g.step(100, 2f))
        assertEquals("start", g.step(450, 1f))
        g.ask("recall", 1000)
        assertNull(g.step(1000, null))
        assertEquals("recall/nohead", g.step(1000 + g.noHeadMs, null))
    }

    @Test fun saveCodecRoundTrip() {
        val g = SavedGame(GameConfig(Mode.AI, Board.WHITE, 4, true), listOf(112, 113, 0), listOf(5), 12345, 2, true)
        assertEquals(g, SaveCodec.decode(SaveCodec.encode(g)))
        assertNull(SaveCodec.decode("garbage"))
        assertNull(SaveCodec.decode("v1|AI|1|2|0|H8 H8||0|0|0"))
    }
}
