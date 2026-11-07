package io.embodit.fivestones.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design-package states[] that live in the game (S11 / S12 / S14 are window pages). */
enum class Phase(val id: String) {
    LOADING("S00"), WELCOME("S01"), SETUP("S02"), IDLE("S03"), PREVIEW("S04"), PLACING("S05"),
    THINKING("S06"), ALERT("S08"), RESULT("S09"), PAUSED("S10"),
}

enum class Sound { PLACE, RETRACT, ILLEGAL, FORBIDDEN, HINT, WIN, LOSE, DRAW }

enum class AlertKind { OCCUPIED, FORBIDDEN, ENDED }

data class Alert(val kind: AlertKind, val point: Int, val reason: Forbidden? = null, val at: Long)

/** A stone in the air: from its colour's bowl to [point] (or back into the bowl when [back]). */
data class Flight(val point: Int, val color: Int, val startedAt: Long, val back: Boolean = false)

enum class ConfirmKind { RESTART, RESIGN, EXIT, UNDO_2P, NEW_FROM_PAUSE, LEAVE }

/** The one state every surface reads (board scene, control window, plate, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val moves: List<Int> = emptyList(),
    val redo: List<Int> = emptyList(),
    val ghost: Int? = null,
    val ghostForbidden: Forbidden? = null,
    val flight: Flight? = null,
    val retractUntil: Long? = null,
    val alert: Alert? = null,
    val result: GameResult? = null,
    val forbidden: Map<Int, Forbidden> = emptyMap(),
    val pillars: List<Pillar> = emptyList(),
    val hintOn: Boolean = false,
    val hint: Int? = null,
    val shapesOn: Boolean = true,
    val shapesWasOn: Boolean = false,
    val hintCount: Int = 0,
    val elapsedMs: Long = 0,
    val resumedAt: Long = 0,
    val confirm: ConfirmKind? = null,
    val pausedFrom: Phase? = null,
    val savedAvailable: Boolean = false,
    /** Debug freeze: the stone in flight / retract window stops this many ms after its start. */
    val freezeMs: Long? = null,
    val restored: Boolean = false,
    val firstGame: Boolean = false,
) {
    val toMove: Int get() = Board.colorToMove(moves.size)
    val lastMove: Int? get() = moves.lastOrNull()
    val board: IntArray get() = Board.fromMoves(moves)
    val humansTurn: Boolean get() = config?.isHuman(toMove) == true
    val canUndo: Boolean get() = config != null && when (config.mode) {
        Mode.TWO_PLAYER -> moves.isNotEmpty()
        Mode.AI -> moves.withIndex().any { (i, _) -> Board.colorToMove(i) == config.humanColor }
    }
    val canRedo: Boolean get() = redo.isNotEmpty() && phase in setOf(Phase.IDLE, Phase.PREVIEW)
    val inGame: Boolean get() = phase in setOf(Phase.IDLE, Phase.PREVIEW, Phase.PLACING, Phase.THINKING, Phase.ALERT, Phase.PAUSED)
}

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Turn sequencing (rules - state - render: this is the middle layer). Main-thread confined: every public call
 * and every coroutine it launches runs on [scope]'s dispatcher; only the engine runs on [engineDispatcher].
 * Debug commands call the same functions the board and the window call.
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
    seed: Int = 20261105,
) {
    companion object {
        const val FLIGHT_MS = 220L
        const val RETRACT_MS = 500L
        const val BACK_MS = 200L
        const val MIN_THINK_MS = 400L
        const val HOVER_DWELL_MS = 80L
        const val HOVER_LEAVE_MS = 150L
        const val ALERT_MS = 1500L
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private val random = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    /** Debug: keep the current alert (status line + plate reason) on screen until released, for screenshots. */
    var holdAlert: Boolean = false

    private var settleJob: Job? = null
    private var thinkJob: Job? = null
    private var hintJob: Job? = null
    private var hoverJob: Job? = null
    private var alertJob: Job? = null
    private var hoverTarget: Int? = null

    private fun set(next: PlayState) { _state.value = next }

    // ---- lifecycle ----

    fun start() {
        val saved = store.load()
        val st = settings()
        if (!st.firstRunDone) { set(s.copy(phase = Phase.WELCOME, savedAvailable = saved != null)); log("STATE S01"); return }
        if (saved != null && Outcome.after(saved.moves, saved.config.renju) == null) restore(saved)
        else { set(s.copy(phase = Phase.SETUP, savedAvailable = false)); log("STATE S02") }
    }

    fun welcomeDone() {
        onSettings(settings().copy(firstRunDone = true))
        set(s.copy(phase = Phase.SETUP, savedAvailable = store.load() != null))
        log("STATE S02")
    }

    fun continueSaved(): Boolean {
        val saved = store.load() ?: return false
        if (Outcome.after(saved.moves, saved.config.renju) != null) return false
        restore(saved)
        return true
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        set(PlayState(
            phase = Phase.IDLE, config = saved.config, moves = saved.moves, redo = saved.redo,
            hintCount = saved.hintCount, shapesWasOn = saved.shapesWasOn, elapsedMs = saved.elapsedMs, resumedAt = clock(),
            shapesOn = settings().shapesFor(saved.config.level, saved.config.mode), restored = true, savedAvailable = true,
        ))
        log("GAME restored moves=${saved.moves.size} S16")
        afterBoardChange()
        nextTurn()
    }

    fun newGame(setup: GameSetup) {
        val human = when (setup.side) { SidePick.BLACK -> Board.BLACK; SidePick.WHITE -> Board.WHITE; SidePick.RANDOM -> if (random.nextBoolean()) Board.BLACK else Board.WHITE }
        onSettings(settings().copy(lastSetup = setup))
        newGame(GameConfig(setup.mode, human, setup.level.coerceIn(1, 5), setup.renju))
    }

    fun newGame(config: GameConfig) {
        cancelJobs()
        val shapes = settings().shapesFor(config.level, config.mode)
        set(PlayState(
            phase = Phase.IDLE, config = config, shapesOn = shapes, shapesWasOn = shapes, resumedAt = clock(),
            firstGame = !settings().coachDone, hintOn = s.hintOn,
        ))
        log("GAME new mode=${config.mode} human=${config.humanColor} level=${config.level} renju=${config.renju}")
        persist()
        afterBoardChange()
        nextTurn()
    }

    fun again() { s.config?.let { newGame(it) } }

    fun toMenu() {
        cancelJobs()
        set(s.copy(phase = Phase.SETUP, confirm = null, savedAvailable = store.load() != null, ghost = null, flight = null))
        log("STATE S02")
    }

    /** Leaving the app or the process going to the background: stop the engine, keep the game. */
    fun onBackground() {
        thinkJob?.cancel(); hintJob?.cancel()
        if (s.flight != null && !s.flight!!.back) settleNow()
        persist()
    }

    fun onForeground() {
        if (s.phase == Phase.THINKING && thinkJob?.isActive != true) think()
    }

    // ---- input (board pads, debug) ----

    fun hover(p: Int?) {
        if (p == hoverTarget) return
        hoverTarget = p
        hoverJob?.cancel()
        hoverJob = scope.launch {
            delay(if (p == null) HOVER_LEAVE_MS else HOVER_DWELL_MS)
            showGhost(p)
        }
    }

    /** Debug: the pointer has rested on [p] (skips the dwell). */
    fun hoverNow(p: Int?) { hoverTarget = p; hoverJob?.cancel(); showGhost(p) }

    private fun showGhost(p: Int?) {
        val st = s
        if (st.phase !in setOf(Phase.IDLE, Phase.PREVIEW, Phase.ALERT) || !st.humansTurn) {
            if (st.ghost != null) set(st.copy(ghost = null, ghostForbidden = null))
            return
        }
        val board = st.board
        val q = p?.takeIf { board[it] == Board.EMPTY }
        val forb = if (q != null && st.toMove == Board.BLACK) Rules.forbidden(board, q, st.config!!.renju) else null
        val phase = if (st.phase == Phase.ALERT) Phase.ALERT else if (q != null) Phase.PREVIEW else Phase.IDLE
        set(st.copy(ghost = q, ghostForbidden = forb, phase = phase))
        if (q != null) log("HOVER ${Board.name(q)}${forb?.let { " forbidden=$it" } ?: ""}")
    }

    /** A pinch / trigger on the board at [p] (the pad under the pointer). */
    fun tap(p: Int) {
        val st = s
        log("TAP ${Board.name(p)} phase=${st.phase.id} ghost=${st.ghost?.let(Board::name)}")
        when (st.phase) {
            Phase.PLACING -> {
                val until = st.retractUntil
                if (until != null && clock() < until && p == st.lastMove && st.flight?.back != true) retract()
                else log("TAP ignored ${Board.name(p)} (stone in flight / retract window)")
            }
            Phase.RESULT -> alert(AlertKind.ENDED, p, null)
            Phase.IDLE, Phase.PREVIEW, Phase.ALERT -> {
                if (!st.humansTurn) return
                val target = st.ghost ?: p
                val board = st.board
                if (board[target] != Board.EMPTY) { alert(AlertKind.OCCUPIED, target, null); return }
                if (st.toMove == Board.BLACK) Rules.forbidden(board, target, st.config!!.renju)?.let { alert(AlertKind.FORBIDDEN, target, it); return }
                commit(target, human = true)
            }
            else -> Unit
        }
    }

    private fun alert(kind: AlertKind, p: Int, reason: Forbidden?) {
        val back = if (s.phase == Phase.ALERT) Phase.IDLE else s.phase
        val at = clock()
        set(s.copy(phase = if (back == Phase.RESULT) Phase.RESULT else Phase.ALERT, alert = Alert(kind, p, reason, at)))
        onSound(if (kind == AlertKind.FORBIDDEN) Sound.FORBIDDEN else Sound.ILLEGAL)
        log("ALERT $kind ${Board.name(p)}${reason?.let { " $it" } ?: ""} S08")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            while (holdAlert) delay(100)
            val now = s
            if (now.alert?.at == at) set(now.copy(alert = null, phase = if (now.phase == Phase.ALERT) (if (now.ghost != null) Phase.PREVIEW else Phase.IDLE) else now.phase))
        }
    }

    // ---- moves ----

    private fun commit(p: Int, human: Boolean) {
        val st = s
        val color = st.toMove
        val redo = if (st.redo.firstOrNull() == p) st.redo.drop(1) else emptyList()
        val now = clock()
        set(st.copy(
            phase = Phase.PLACING, moves = st.moves + p, redo = redo, ghost = null, ghostForbidden = null, hint = null,
            flight = Flight(p, color, now), retractUntil = if (human) now + FLIGHT_MS + RETRACT_MS else null, alert = null,
            restored = false,
        ))
        hoverTarget = null
        log("MOVE ${Board.name(p)} color=$color n=${s.moves.size} by=${if (human) "human" else "ai"} S05")
        persist()
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(FLIGHT_MS)
            onSound(Sound.PLACE)
            if (human) delay(RETRACT_MS)
            awaitUnfrozen()
            settle()
        }
    }

    private suspend fun awaitUnfrozen() {
        while (coroutineContext.isActive && s.freezeMs != null) delay(50)
    }

    private fun retract() {
        val st = s
        val p = st.lastMove ?: return
        settleJob?.cancel()
        val color = st.toMove.let(Board::other)
        set(st.copy(moves = st.moves.dropLast(1), flight = Flight(p, color, clock(), back = true), retractUntil = null))
        onSound(Sound.RETRACT)
        log("RETRACT ${Board.name(p)}")
        persist()
        settleJob = scope.launch {
            delay(BACK_MS)
            set(s.copy(flight = null, phase = Phase.IDLE))
            afterBoardChange()
            nextTurn()
        }
    }

    private fun settleNow() { settleJob?.cancel(); settle() }

    private fun settle() {
        val st = s
        set(st.copy(flight = null, retractUntil = null))
        val result = Outcome.after(s.moves, s.config!!.renju)
        if (result != null) { finish(result); return }
        afterBoardChange()
        nextTurn()
    }

    private fun finish(result: GameResult) {
        cancelJobs()
        val st = s
        set(st.copy(phase = Phase.RESULT, result = result, elapsedMs = st.elapsedMs + (clock() - st.resumedAt), ghost = null, flight = null, retractUntil = null, hint = null, confirm = null))
        // The opponent's five is not a fanfare (critic 要改 9c): an AI win plays the low "lose" note.
        val cfg = st.config
        val aiWon = cfg != null && cfg.mode == Mode.AI && result.winner == cfg.aiColor
        onSound(when { result.winner == 0 -> Sound.DRAW; aiWon -> Sound.LOSE; result.reason == EndReason.FIVE -> Sound.WIN; else -> Sound.DRAW })
        log("RESULT winner=${result.winner} reason=${result.reason} moves=${st.moves.size} S09")
        if (st.firstGame) onSettings(settings().copy(coachDone = true))
        store.clear()
        afterBoardChange()
    }

    private fun nextTurn() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.PAUSED) return
        if (cfg.isHuman(st.toMove)) {
            set(st.copy(phase = if (st.ghost != null) Phase.PREVIEW else Phase.IDLE))
            log("STATE S03 toMove=${st.toMove}")
            refreshHint()
        } else think()
    }

    private fun think() {
        val st = s
        val cfg = st.config ?: return
        set(st.copy(phase = Phase.THINKING, ghost = null, ghostForbidden = null, hint = null))
        log("THINK level=${cfg.level} S06")
        thinkJob?.cancel()
        val moves = st.moves
        val color = st.toMove
        thinkJob = scope.launch {
            val t0 = clock()
            val seed = aiSeed + moves.size
            val move = withContext(engineDispatcher) {
                val job = coroutineContext[Job]
                Engine(Random(seed)).bestMove(moves, color, cfg.renju, cfg.level) { job?.isActive == false }
            }
            val waited = clock() - t0
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking) delay(100)
            if (s.phase != Phase.THINKING || s.moves != moves) return@launch
            if (move < 0) { finish(GameResult(Board.other(color), EndReason.FORBIDDEN_STUCK)); return@launch }
            log("AI ${Board.name(move)} after ${clock() - t0} ms")
            commit(move, human = false)
        }
    }

    // ---- derived: forbidden points, pillars, hint ----

    private fun afterBoardChange() {
        val st = s
        val cfg = st.config ?: return
        val board = st.board
        val blackToMove = st.toMove == Board.BLACK && st.phase != Phase.RESULT
        val forbidden = if (cfg.renju && blackToMove && cfg.isHuman(Board.BLACK)) Rules.forbiddenPoints(board, true) else emptyMap()
        val own = if (cfg.mode == Mode.AI) cfg.humanColor else st.toMove
        val pillars = if (st.shapesOn && st.phase != Phase.RESULT) Shapes.pillars(board, cfg.renju, preferColor = own) else emptyList()
        set(st.copy(forbidden = forbidden, pillars = pillars))
    }

    private fun refreshHint() {
        hintJob?.cancel()
        val st = s
        val cfg = st.config ?: return
        if (!st.hintOn || !st.humansTurn || st.phase !in setOf(Phase.IDLE, Phase.PREVIEW)) { if (st.hint != null) set(st.copy(hint = null)); return }
        val moves = st.moves
        val color = st.toMove
        hintJob = scope.launch {
            val p = withContext(engineDispatcher) {
                val job = coroutineContext[Job]
                Engine(Random(aiSeed)).bestMove(moves, color, cfg.renju, 3) { job?.isActive == false }
            }
            if (s.moves != moves || !s.hintOn || p < 0) return@launch
            set(s.copy(hint = p, hintCount = s.hintCount + 1))
            // hint is silent (D51: selection / pre-selection / hover / hints make no sound)
            log("HINT ${Board.name(p)}")
            persist()
        }
    }

    fun setHint(on: Boolean) {
        set(s.copy(hintOn = on, hint = if (on) s.hint else null))
        log("HINT ${if (on) "on" else "off"}")
        refreshHint()
    }

    fun setShapes(on: Boolean) {
        onSettings(settings().copy(shapesOverride = on))
        set(s.copy(shapesOn = on, shapesWasOn = s.shapesWasOn || on))
        log("SHAPES ${if (on) "on" else "off"}")
        afterBoardChange()
        persist()
    }

    // ---- undo / redo ----

    fun requestUndo() {
        val cfg = s.config ?: return
        if (!s.canUndo) return
        if (cfg.mode == Mode.TWO_PLAYER) { set(s.copy(confirm = ConfirmKind.UNDO_2P)); log("CONFIRM UNDO_2P S13") } else undo()
    }

    private fun undo() {
        val cfg = s.config ?: return
        settleJob?.cancel(); thinkJob?.cancel(); hintJob?.cancel()
        val moves = s.moves.toMutableList()
        val popped = ArrayList<Int>()
        if (cfg.mode == Mode.TWO_PLAYER) {
            if (moves.isNotEmpty()) popped.add(0, moves.removeAt(moves.lastIndex))
        } else {
            var removedHuman = false
            while (moves.isNotEmpty()) {
                val color = Board.colorToMove(moves.size - 1)
                popped.add(0, moves.removeAt(moves.lastIndex))
                if (color == cfg.humanColor) removedHuman = true
                if (removedHuman && Board.colorToMove(moves.size) == cfg.humanColor) break
            }
        }
        val st = s
        set(st.copy(moves = moves, redo = popped + st.redo, flight = null, retractUntil = null, result = null, phase = Phase.IDLE, ghost = null, confirm = null, hint = null, resumedAt = if (st.phase == Phase.RESULT) clock() else st.resumedAt))
        log("UNDO -> n=${moves.size} redo=${s.redo.size}")
        persist()
        afterBoardChange()
        nextTurn()
    }

    fun redo() {
        val st = s
        val cfg = st.config ?: return
        if (!st.canRedo) return
        val moves = st.moves.toMutableList()
        val redo = st.redo.toMutableList()
        do {
            moves += redo.removeAt(0)
        } while (redo.isNotEmpty() && cfg.mode == Mode.AI && !cfg.isHuman(Board.colorToMove(moves.size)))
        set(st.copy(moves = moves, redo = redo, ghost = null, hint = null))
        log("REDO -> n=${moves.size}")
        persist()
        val result = Outcome.after(moves, cfg.renju)
        if (result != null) { finish(result); return }
        afterBoardChange()
        nextTurn()
    }

    // ---- pause, confirm dialogs ----

    fun pause() {
        val st = s
        if (!st.inGame || st.phase == Phase.PAUSED) return
        if (st.flight != null && !st.flight.back) settleNow()
        thinkJob?.cancel(); hintJob?.cancel()
        val from = if (s.phase == Phase.THINKING) Phase.THINKING else Phase.IDLE
        set(s.copy(phase = Phase.PAUSED, pausedFrom = from, ghost = null, elapsedMs = s.elapsedMs + (clock() - s.resumedAt)))
        log("PAUSE S10")
        persist()
    }

    fun resume() {
        if (s.phase != Phase.PAUSED) return
        set(s.copy(phase = Phase.IDLE, pausedFrom = null, resumedAt = clock()))
        log("RESUME")
        nextTurn()
    }

    fun requestConfirm(kind: ConfirmKind) { set(s.copy(confirm = kind)); log("CONFIRM $kind S13") }

    fun cancelConfirm() { set(s.copy(confirm = null)) }

    /** Confirms the open dialog. EXIT is handled by the window (it owns the navigator); it only closes here. */
    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null))
        when (kind) {
            ConfirmKind.UNDO_2P -> undo()
            ConfirmKind.RESIGN -> {
                val cfg = s.config ?: return
                val loser = if (cfg.mode == Mode.AI) cfg.humanColor else s.toMove
                if (s.flight != null && !s.flight!!.back) settleNow()
                finish(GameResult(Board.other(loser), EndReason.RESIGN))
            }
            ConfirmKind.RESTART, ConfirmKind.NEW_FROM_PAUSE, ConfirmKind.LEAVE -> { cancelJobs(); store.clear(); set(PlayState(phase = Phase.SETUP, hintOn = s.hintOn)); log("STATE S02") }
            ConfirmKind.EXIT -> { onBackground() }
        }
    }

    // ---- debug ----

    /** Debug: stop the clock of the stone in flight / the retract window at [ms] after the next (or current) placement. */
    fun freeze(ms: Long?) {
        set(s.copy(freezeMs = ms))
        log("FREEZE ${ms ?: "off"}")
    }

    /** Debug: replace the game with [moves] (validated), keeping the config. */
    fun load(moves: List<Int>, config: GameConfig = s.config ?: GameConfig()) {
        cancelJobs()
        val b = Board.empty()
        val ok = ArrayList<Int>()
        for ((i, p) in moves.withIndex()) {
            if (b[p] != Board.EMPTY) break
            b[p] = if (i % 2 == 0) Board.BLACK else Board.WHITE
            ok += p
        }
        set(PlayState(phase = Phase.IDLE, config = config, moves = ok, shapesOn = s.shapesOn, hintOn = s.hintOn, resumedAt = clock(), shapesWasOn = s.shapesOn))
        log("LOAD n=${ok.size}")
        persist()
        val result = Outcome.after(ok, config.renju)
        if (result != null) { finish(result); return }
        afterBoardChange()
        nextTurn()
    }

    /** Debug: show a named alert / set a frozen flight for screenshots without changing the moves. */
    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun settingsChanged() { afterBoardChange() }

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} cfg=${st.config} n=${st.moves.size} moves=${st.moves.joinToString(" ") { Board.name(it) }} " +
            "toMove=${st.toMove} ghost=${st.ghost?.let(Board::name)} hint=${st.hint?.let(Board::name)} pillars=${st.pillars.joinToString(",") { "${Board.name(it.point)}:${it.color}:${it.level}" }} " +
            "forbidden=${st.forbidden.keys.joinToString(",") { Board.name(it) }} result=${st.result} redo=${st.redo.size} confirm=${st.confirm} freeze=${st.freezeMs}"
    }

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT) return
        store.save(SavedGame(cfg, st.moves, st.redo, st.elapsedMs, st.hintCount, st.shapesWasOn))
    }

    private fun cancelJobs() {
        settleJob?.cancel(); thinkJob?.cancel(); hintJob?.cancel(); hoverJob?.cancel(); alertJob?.cancel()
        hoverTarget = null
    }
}
