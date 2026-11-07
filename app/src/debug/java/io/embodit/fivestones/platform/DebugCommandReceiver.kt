package io.embodit.fivestones.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.fivestones.domain.Board
import io.embodit.fivestones.domain.ConfirmKind
import io.embodit.fivestones.domain.GameConfig
import io.embodit.fivestones.domain.GameSetup
import io.embodit.fivestones.domain.Language
import io.embodit.fivestones.domain.Mode
import io.embodit.fivestones.domain.Phase
import io.embodit.fivestones.domain.SidePick
import io.embodit.fivestones.ui.Page
import io.embodit.fivestones.ui.Ui
import io.embodit.fivestones.ui.board.SceneHolder

/**
 * Debug builds only. Every command goes through the same GameFlow calls the board and the window use.
 *
 *   adb shell am broadcast -a io.embodit.fivestones.DEBUG -p io.embodit.fivestones --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S01..S16          reach a named state (see AGENTS.md for what each one sets up)
 *   hover H8 | hover off   pointer resting on a point (ghost stone, skips the 80 ms dwell)
 *   tap H8                 a pinch / trigger on the pad at H8 (the ghost's point is what gets placed)
 *   freeze 300 | freeze off   stop the stone in flight / the retract window at 300 ms
 *   new "ai black 3 free"  mode ai|2p, side black|white|random, level 1-5, rule free|renju
 *   load "H8 I9 ..."       replace the moves (config kept)   sample   design sampleFirstFrame position, AI to move
 *   undo · redo · hint on|off · shapes on|off · pause · resume · resign · restart · confirm · cancel
 *   level 1-5 · lang zh|en|system · tilt 0|20|28 · size 100|110 · sound on|off · volume 0-2 · reduced on|off
 *   page play|new|rules|settings · more on|off · renju on|off · exitask · hold on|off (AI waits) · seed N
 *   alerthold on|off (keep an S08 alert for screenshots) · s00hold on|off (next launch keeps the S00 stand-in 10 s) · welcome (finish S01) · recall · dump · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim()
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun pt(s: String) = Board.parse(s)
        fun settings(change: (io.embodit.fivestones.domain.Settings) -> io.embodit.fivestones.domain.Settings) { graph.settings.update(change); flow.settingsChanged() }
        fun ensureGame() { if (flow.state.value.config == null || flow.state.value.phase in setOf(Phase.SETUP, Phase.WELCOME, Phase.RESULT)) flow.newGame(GameConfig(Mode.AI, Board.BLACK, 2, false)) }
        when (cmd) {
            "goto" -> when (a.uppercase()) {
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: force-stop and start, then capture at once")
                "S01" -> { settings { it.copy(firstRunDone = false) }; flow.debugState { it.copy(phase = Phase.WELCOME) }; Ui.page = Page.RULES }
                "S02" -> { flow.toMenu(); Ui.page = Page.NEW }
                "S03" -> { ensureGame(); flow.hoverNow(null); Ui.page = Page.PLAY }
                "S04" -> { ensureGame(); Ui.page = Page.PLAY; val b = flow.state.value.board; flow.hoverNow(listOf(Board.CENTER, Board.at(8, 8), Board.at(6, 6)).first { b[it] == Board.EMPTY }) }
                "S05" -> { ensureGame(); Ui.page = Page.PLAY; flow.freeze(300); val b = flow.state.value.board; flow.tap(listOf(Board.CENTER, Board.at(8, 8), Board.at(6, 6), Board.at(8, 6)).first { b[it] == Board.EMPTY }) }
                "S06" -> { flow.holdThinking = true; sample(flow); Ui.page = Page.PLAY }
                "S08" -> { ensureGame(); Ui.page = Page.PLAY; flow.state.value.lastMove?.let { flow.hoverNow(null); flow.tap(it) } }
                "S09" -> {
                    Ui.page = Page.PLAY
                    flow.load("D8 A1 E8 A3 F8 A5 G8 A7".split(" ").mapNotNull(::pt), GameConfig(Mode.AI, Board.BLACK, 2, false))
                    flow.hoverNow(Board.parse("H8")); flow.tap(Board.parse("H8")!!)
                }
                "S10" -> { ensureGame(); Ui.page = Page.PLAY; flow.pause() }
                "S11", "S14" -> Ui.page = Page.SETTINGS
                "S12" -> Ui.page = Page.RULES
                "S13" -> { ensureGame(); Ui.page = Page.PLAY; flow.requestConfirm(ConfirmKind.RESTART) }
                "S16" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                else -> Log.w(TAG, "unknown state $a")
            }
            "welcome" -> flow.welcomeDone()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "hover" -> flow.hoverNow(if (a == "off") null else pt(a))
            "tap" -> pt(a)?.let { p -> if (flow.state.value.phase != Phase.PLACING) flow.hoverNow(p); flow.tap(p) }
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "new" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                val setup = GameSetup(
                    mode = if (f.getOrNull(0) == "2p") Mode.TWO_PLAYER else Mode.AI,
                    side = when (f.getOrNull(1)) { "white" -> SidePick.WHITE; "random" -> SidePick.RANDOM; else -> SidePick.BLACK },
                    level = f.getOrNull(2)?.toIntOrNull() ?: 2,
                    renju = f.getOrNull(3) == "renju",
                )
                flow.newGame(setup); Ui.page = Page.PLAY
            }
            "load" -> flow.load(a.split(" ").mapNotNull(::pt))
            "sample" -> sample(flow)
            "undo" -> flow.requestUndo()
            "redo" -> flow.redo()
            "hint" -> flow.setHint(on)
            "shapes" -> flow.setShapes(on)
            "pause" -> flow.pause()
            "resume" -> flow.resume()
            "resign" -> flow.requestConfirm(ConfirmKind.RESIGN)
            "restart" -> flow.requestConfirm(ConfirmKind.RESTART)
            "confirm" -> flow.confirm()
            "cancel" -> flow.cancelConfirm()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "level" -> a.toIntOrNull()?.let { l -> flow.state.value.config?.let { flow.load(flow.state.value.moves, it.copy(level = l)) } }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "tilt" -> a.toIntOrNull()?.takeIf { it in io.embodit.fivestones.domain.Settings.TILTS }?.let { t -> settings { it.copy(tilt = t) } }
            "size" -> a.toIntOrNull()?.takeIf { it in listOf(100, 110) }?.let { s -> settings { it.copy(size = s) } }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "page" -> Ui.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "more" -> Ui.moreOpen = on
            "renju" -> Ui.renjuOpen = on
            "hold" -> flow.holdThinking = on
            "alerthold" -> flow.holdAlert = on
            "seed" -> a.toIntOrNull()?.let { flow.aiSeed = it }
            "recall" -> graph.window.recall()
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }

    /** design-package sampleFirstFrame: you are Black vs White AI (medium); White to play its 12th move. */
    private fun sample(flow: io.embodit.fivestones.domain.GameFlow) {
        val moves = "F6 I12 G7 J12 H8 K12 J10 E5 C9 G9 L4".split(" ").mapNotNull { Board.parse(it) }
        flow.load(moves, GameConfig(Mode.AI, Board.BLACK, 3, false))
    }
}
