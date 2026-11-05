package io.embodit.fivestones.domain

import kotlin.math.cos
import kotlin.math.sin

/** A small vector for layout maths (metres). Kept free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/**
 * Where everything sits in the volume (design-package spatialLayout). Scene space: origin on the volume floor,
 * centred in x, +Z toward the player, +Y up. Hierarchy:
 *
 *   sceneRoot (volume floor)
 *   ├ tiltRoot  at the board's near-bottom edge, tipped [tiltDeg] about X (far edge up)
 *   │ └ boardRoot  board top centre, scaled by [sizePct]; boardRoot y = 0 is the playing surface
 *   ├ bowls (never tilt), the move plate (upright, above the far edge)
 *
 * Board-local units are the 100 % board: pitch 4.5 cm, board 0.70 x 0.03 x 0.70 m. Row 1 is the near edge.
 */
class BoardGeometry(val tiltDeg: Int = 20, val sizePct: Int = 100) {
    companion object {
        const val PITCH = 0.045f
        const val BOARD = 0.70f
        const val THICK = 0.03f
        // v0.1.1 (critic block 3): the bowls were 6 cm tall and hid the near corners from the emulator's default
        // eye (about 5-8° above the board). Lower, a little narrower and further forward they clear every point.
        const val BOWL_R = 0.05f
        const val BOWL_H = 0.035f
        const val BOWL_X = 0.29f
        const val BOWL_GAP = 0.05f
        /** Content is shifted toward the far side so board + bowls are centred in the 0.96 m depth. */
        const val CONTENT_Z = -0.075f
        const val ARC = 0.08f
        /** Measured (v0.1.1): attachment panels render at ~0.0008 m per dp; the plate is 280 x 72 dp. */
        const val PLATE_DP_M = 0.0008f
        const val PLATE_W = 280 * PLATE_DP_M
        const val PLATE_H = 72 * PLATE_DP_M
        const val PLATE_GAP = 0.02f
        val VOLUME = V3(0.84f, 0.54f, 0.96f)
    }

    val scale: Float = sizePct / 100f
    val side: Float = BOARD * scale
    val thick: Float = THICK * scale
    val theta: Float = Math.toRadians(tiltDeg.toDouble()).toFloat()

    /** tiltRoot position in scene space: the near-bottom edge of the board. */
    val tiltOrigin: V3 = V3(0f, 0f, CONTENT_Z + side / 2)
    /** boardRoot relative to tiltRoot (before rotation): up by the thickness, back by half the board. */
    val boardOffset: V3 = V3(0f, thick, -side / 2)

    /** Board-local (100 % units, top surface y = 0) position of a point. */
    fun local(p: Int, lift: Float = 0f): V3 = V3((Board.col(p) - 7) * PITCH, lift, -(Board.row(p) - 7) * PITCH)

    private fun rotX(v: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    /** Scene position of a board-local position. */
    fun toScene(local: V3): V3 = tiltOrigin + rotX(boardOffset + local * scale, theta)

    /** Board-local position of a scene position (inverse of [toScene]). */
    fun toLocal(scene: V3): V3 = (rotX(scene - tiltOrigin, -theta) - boardOffset) * (1f / scale)

    fun pointScene(p: Int, lift: Float = 0f): V3 = toScene(local(p, lift))

    /** Bowl centre (on the floor) for a colour: Black's bowl on the left, White's on the right. */
    fun bowlBase(color: Int): V3 = V3(if (color == Board.BLACK) -BOWL_X else BOWL_X, 0f, CONTENT_Z + side / 2 + BOWL_GAP + BOWL_R)

    /** Where a stone leaves its bowl: the middle of the bowl mouth. */
    fun bowlMouth(color: Int): V3 = bowlBase(color) + V3(0f, BOWL_H, 0f)

    /** The flight from the bowl to [p] at time fraction [t] (0..1), in scene space: a parabola peaking [ARC] above the chord. */
    fun flight(color: Int, p: Int, t: Float, stoneHalf: Float = 0.0045f): V3 {
        val a = bowlMouth(color)
        val b = pointScene(p, stoneHalf)
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + V3(0f, 4f * ARC * k * (1f - k), 0f)
    }

    /** Centre of the move plate: upright, above the far edge, never over a point. */
    fun plateCentre(): V3 = toScene(V3(0f, 0f, -BOARD / 2)) + V3(0f, PLATE_GAP + PLATE_H / 2, 0f)

    /** The far-edge top line in scene space (y of the board's highest edge). */
    fun farTop(): V3 = toScene(V3(0f, 0f, -BOARD / 2))
}
