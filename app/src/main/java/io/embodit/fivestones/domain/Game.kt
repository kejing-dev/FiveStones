package io.embodit.fivestones.domain

enum class Mode { AI, TWO_PLAYER }

enum class SidePick { BLACK, WHITE, RANDOM }

enum class Language { SYSTEM, ZH, EN }

/** One game's setup. [humanColor] is resolved (never random here); in two-player mode it is unused. */
data class GameConfig(
    val mode: Mode = Mode.AI,
    val humanColor: Int = Board.BLACK,
    val level: Int = 2,
    val renju: Boolean = false,
) {
    fun isHuman(color: Int): Boolean = mode == Mode.TWO_PLAYER || color == humanColor
    val aiColor: Int get() = Board.other(humanColor)
}

/** What a game looks like on disk (files/game.txt): enough to rebuild everything else. */
data class SavedGame(
    val config: GameConfig,
    val moves: List<Int>,
    val redo: List<Int> = emptyList(),
    val elapsedMs: Long = 0,
    val hintCount: Int = 0,
    val shapesWasOn: Boolean = false,
)

/** App settings (SharedPreferences). [shapesOverride] null = the level's default (on for levels 1-3). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val tilt: Int = 20,
    val size: Int = 100,
    val shapesOverride: Boolean? = null,
    val firstRunDone: Boolean = false,
    val coachDone: Boolean = false,
    val lastSetup: GameSetup = GameSetup(),
) {
    fun shapesFor(level: Int, mode: Mode): Boolean = shapesOverride ?: (mode == Mode.TWO_PLAYER || level <= 3)

    companion object {
        /** User 2026-11-06 (headset): 0 / 30 / 45 / 60 / 90 — plus the design default 20 kept as the default. */
        val TILTS = listOf(0, 20, 30, 45, 60, 90)
        val SIZES = listOf(100, 110)
    }
}

/** The new-game page's choices (remembered between games). */
data class GameSetup(
    val mode: Mode = Mode.AI,
    val side: SidePick = SidePick.BLACK,
    val level: Int = 2,
    val renju: Boolean = false,
)

/** Line codec for [SavedGame]: `v1|mode|human|level|renju|moves|redo|elapsed|hints|shapes`, moves as "H8 I9 ...". */
object SaveCodec {
    fun encode(g: SavedGame): String = listOf(
        "v1",
        g.config.mode.name,
        g.config.humanColor.toString(),
        g.config.level.toString(),
        if (g.config.renju) "1" else "0",
        g.moves.joinToString(" ") { Board.name(it) },
        g.redo.joinToString(" ") { Board.name(it) },
        g.elapsedMs.toString(),
        g.hintCount.toString(),
        if (g.shapesWasOn) "1" else "0",
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 10 || f[0] != "v1") return null
        fun pts(s: String) = if (s.isBlank()) emptyList() else s.split(" ").map { Board.parse(it)!! }
        val moves = pts(f[5])
        if (moves.toSet().size != moves.size) return null
        SavedGame(
            config = GameConfig(Mode.valueOf(f[1]), f[2].toInt().coerceIn(1, 2), f[3].toInt().coerceIn(1, 5), f[4] == "1"),
            moves = moves,
            redo = pts(f[6]),
            elapsedMs = f[7].toLong(),
            hintCount = f[8].toInt(),
            shapesWasOn = f[9] == "1",
        )
    }.getOrNull()
}
