package io.embodit.fivestones.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import io.embodit.fivestones.domain.Board
import kotlin.random.Random

/**
 * The board face drawn on a Canvas (Xiangqi BoardTextures pattern): kaya grain, 15 lines, 5 star points and the
 * A-O / 1-15 coordinates. The bitmap's top edge is the board's far edge (row 15), its left edge column A; the
 * face plane is laid flat with pitch -90°, which maps bitmap-up to -Z (away from the player).
 */
object BoardTextures {
    /** Fraction of the board side taken by one grid pitch: 4.5 cm of 70 cm. */
    const val PITCH_FRACTION = 0.045f / 0.70f

    fun boardFace(size: Int = 2048): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(0xE2, 0xB5, 0x5F))
        // Fine straight grain, a few darker streaks; deterministic.
        val rnd = Random(15)
        val grain = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 220) {
            val x = rnd.nextFloat() * size
            val w = 1f + rnd.nextFloat() * 5f
            val dark = rnd.nextFloat()
            grain.color = Color.argb((10 + dark * 26).toInt(), 0x8C, 0x62, 0x30)
            grain.strokeWidth = w
            val wobble = rnd.nextFloat() * 18f - 9f
            c.drawLine(x, 0f, x + wobble, size.toFloat(), grain)
        }
        val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(18, 255, 240, 200); strokeWidth = 3f }
        for (i in 0 until 60) { val x = rnd.nextFloat() * size; c.drawLine(x, 0f, x, size.toFloat(), light) }

        val pitch = size * PITCH_FRACTION
        val origin = size / 2f - 7 * pitch
        fun px(col: Int) = origin + col * pitch
        fun py(row: Int) = origin + (14 - row) * pitch // row 0 (= 1) at the bottom / near edge

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x3A, 0x2A, 0x16); strokeWidth = size / 680f; strokeCap = Paint.Cap.SQUARE }
        for (i in 0 until Board.N) {
            c.drawLine(px(0), py(i), px(14), py(i), ink)
            c.drawLine(px(i), py(0), px(i), py(14), ink)
        }
        // Heavier border line.
        val border = Paint(ink).apply { strokeWidth = size / 340f; style = Paint.Style.STROKE }
        c.drawRect(px(0), py(14), px(14), py(0), border)
        val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.color }
        for (p in Board.STARS) c.drawCircle(px(Board.col(p)), py(Board.row(p)), size / 150f, star)

        // Coordinates: letters along the near edge, numbers along the left edge, 1.2 cm tall.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x4A, 0x34, 0x18)
            textSize = size * (0.012f / 0.70f) * 1.35f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val margin = (origin) // distance from the texture edge to the outer line
        for (col in 0 until Board.N) {
            c.drawText("${'A' + col}", px(col), py(0) + margin * 0.62f + text.textSize * 0.35f, text)
        }
        for (row in 0 until Board.N) {
            c.drawText("${row + 1}", px(0) - margin * 0.55f, py(row) + text.textSize * 0.36f, text)
        }
        return bmp
    }

    /** A disc of loose stones seen in a bowl mouth (black or white), with transparent corners. */
    fun bowlStones(black: Boolean, size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (black) Color.rgb(0x16, 0x18, 0x1B) else Color.rgb(0xC9, 0xC4, 0xB8) }
        c.drawCircle(size / 2f, size / 2f, size / 2f, base)
        val rnd = Random(if (black) 3 else 4)
        val stone = Paint(Paint.ANTI_ALIAS_FLAG)
        val hi = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(42) {
            val a = rnd.nextFloat() * 6.283f
            val r = rnd.nextFloat() * size * 0.38f
            val x = size / 2f + r * kotlin.math.cos(a)
            val y = size / 2f + r * kotlin.math.sin(a)
            stone.color = if (black) Color.rgb(0x20 + rnd.nextInt(10), 0x23 + rnd.nextInt(10), 0x27 + rnd.nextInt(10)) else Color.rgb(0xEC - rnd.nextInt(14), 0xE9 - rnd.nextInt(14), 0xE0 - rnd.nextInt(14))
            c.drawCircle(x, y, size * 0.075f, stone)
            hi.color = if (black) Color.argb(70, 120, 170, 165) else Color.argb(160, 255, 255, 255)
            c.drawCircle(x - size * 0.02f, y - size * 0.02f, size * 0.022f, hi)
        }
        return bmp
    }
}
