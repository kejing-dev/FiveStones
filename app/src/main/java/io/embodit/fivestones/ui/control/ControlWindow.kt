package io.embodit.fivestones.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.windows.TabBar
import com.pico.spatial.ui.platform.ViewPoint
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.fivestones.BuildConfig
import io.embodit.fivestones.R
import io.embodit.fivestones.domain.AlertKind
import io.embodit.fivestones.domain.Board
import io.embodit.fivestones.domain.ConfirmKind
import io.embodit.fivestones.domain.EndReason
import io.embodit.fivestones.domain.Forbidden
import io.embodit.fivestones.domain.GameSetup
import io.embodit.fivestones.domain.Language
import io.embodit.fivestones.domain.Mode
import io.embodit.fivestones.domain.Phase
import io.embodit.fivestones.domain.PlayState
import io.embodit.fivestones.domain.Settings
import io.embodit.fivestones.domain.SidePick
import io.embodit.fivestones.platform.Graph
import io.embodit.fivestones.platform.TAG
import io.embodit.fivestones.ui.Containers
import io.embodit.fivestones.ui.Page
import io.embodit.fivestones.ui.Ui
import io.embodit.fivestones.ui.theme.FiveColors
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer beside the board): all 2D UI of the app, four pages switched by the
 * native SpatialUI TabBar (D8): Play / New game / Rules / Settings. Dragging and closing are the system window
 * controls under the window (D21); nothing here draws a handle.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // The game decides the page at its milestones: first run -> rules, setup -> new, a game starts -> play.
    LaunchedEffect(state.phase) {
        when (state.phase) {
            Phase.WELCOME -> Ui.page = Page.RULES
            Phase.SETUP -> Ui.page = Page.NEW
            // A restored game also lands on Play (critic 要改 1: it used to stay on Rules after a real force-stop).
            Phase.IDLE, Phase.THINKING, Phase.RESULT, Phase.PAUSED -> if (Ui.page == Page.NEW || (Ui.page == Page.RULES && state.phase != Phase.PAUSED)) Ui.page = Page.PLAY
            else -> Unit
        }
    }
    LaunchedEffect(Ui.page) {
        Log.i(TAG, "PAGE ${Ui.page} ${Ui.page.stateId}")
        if (Ui.page != Page.SETTINGS) Ui.exitConfirm = false
        if (Ui.page != Page.NEW) Ui.newConfirm = false
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
        when (Ui.page) {
            Page.PLAY -> PlayPage(state, settings, graph)
            Page.NEW -> NewPage(state, settings, graph)
            Page.RULES -> RulesPage(state, graph)
            Page.SETTINGS -> SettingsPage(settings, graph)
        }
    }

    // The TabBar is its own system window: its items do not see this window's localised context, so the labels are
    // resolved here and passed in as plain strings (QA q1-6: tabs stayed Chinese in English).
    val tabs = listOf(
        Triple(Page.PLAY, R.drawable.ic_tab_play, stringResource(R.string.tab_play)),
        Triple(Page.NEW, R.drawable.ic_tab_new, stringResource(R.string.tab_new)),
        Triple(Page.RULES, R.drawable.ic_tab_rules, stringResource(R.string.tab_rules)),
        Triple(Page.SETTINGS, R.drawable.ic_tab_settings, stringResource(R.string.tab_settings)),
    )
    androidx.compose.runtime.key(settings.language) { ControlTabBar(tabs) }
}

@Composable
private fun PageTitle(text: String, tag: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(text, "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun levelName(level: Int): String = stringResource(
    when (level) { 1 -> R.string.level_1; 2 -> R.string.level_2; 3 -> R.string.level_3; 4 -> R.string.level_4; else -> R.string.level_5 },
)

@Composable
private fun colorName(color: Int): String = stringResource(if (color == Board.BLACK) R.string.color_black else R.string.color_white)

@Composable
private fun forbiddenName(f: Forbidden): String = stringResource(
    when (f) { Forbidden.DOUBLE_THREE -> R.string.forbidden_double_three; Forbidden.DOUBLE_FOUR -> R.string.forbidden_double_four; Forbidden.OVERLINE -> R.string.forbidden_overline },
)

/** Who is "X" in status lines: in an AI game the human is "You / 你", the AI is "AI 中级". */
@Composable
private fun whoName(state: PlayState, color: Int): String {
    val cfg = state.config ?: return colorName(color)
    return when {
        cfg.mode == Mode.TWO_PLAYER -> colorName(color)
        color == cfg.humanColor -> stringResource(R.string.who_you) + " · " + colorName(color)
        else -> stringResource(R.string.who_ai, levelName(cfg.level))
    }
}

// ------------------------------------------------------------------ Play

@Composable
private fun PlayPage(state: PlayState, settings: Settings, graph: Graph) {
    val flow = graph.flow
    if (state.config == null || state.phase in setOf(Phase.SETUP, Phase.WELCOME, Phase.LOADING)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            PageTitle(stringResource(R.string.tab_play), "play")
            Txt(stringResource(R.string.coach_1), "play/empty", style = PicoTheme.typography.titleMedium, maxLines = 3)
            RulesDiagram()
            Txt(stringResource(R.string.coach_2), "play/empty2", style = PicoTheme.typography.bodyLarge, maxLines = 3)
            Txt(stringResource(R.string.coach_3), "play/empty3", style = PicoTheme.typography.bodyLarge, maxLines = 3)
            PrimaryButton(stringResource(R.string.btn_new_game), "play/new", onClick = { Ui.page = Page.NEW }, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    // Short page in a 640 dp window: content is centred vertically and the main actions are larger, so no big
    // empty band is left (profile D44: blank <= 25 % of the window height).
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        // Turn chip + move number (same source as the plate on the board, D38)
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.result == null) {
                StoneDot(state.toMove, 30.dp)
                Spacer(Modifier.width(12.dp))
                Txt(whoName(state, state.toMove), "play/turn", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            Txt(stringResource(R.string.move_n, state.moves.size), "play/moves", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelSecondary)
        }
        StatusLine(state)
        GameInfoCard(state)
        Box(Modifier.fillMaxWidth()) {
            when {
                state.confirm != null -> ConfirmBlock(state.confirm, graph)
                state.phase == Phase.PAUSED -> PauseBlock(graph)
                state.result != null -> ResultBlock(state, graph)
                Ui.moreOpen -> MoreBlock(graph)
                state.canRedo -> PrimaryButton(stringResource(R.string.btn_redo), "play/redo", onClick = { flow.redo() }, modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
        }
        if (state.result == null && state.phase != Phase.PAUSED && state.confirm == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.btn_undo), "play/undo", onClick = { flow.requestUndo() }, enabled = state.canUndo, modifier = Modifier.weight(1f), tall = true)
                ToggleButton(stringResource(R.string.btn_hint), "play/hint", on = state.hintOn, onClick = { flow.setHint(!state.hintOn) }, modifier = Modifier.weight(1.05f))
                ToggleButton(stringResource(R.string.btn_shapes), "play/shapes", on = state.shapesOn, onClick = { flow.setShapes(!state.shapesOn) }, modifier = Modifier.weight(1.05f))
                ToggleButton(stringResource(R.string.btn_mute), "play/mute", on = !settings.sound, onClick = { graph.settings.update { it.copy(sound = !it.sound) } }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton(stringResource(R.string.btn_more) + if (Ui.moreOpen) " ▴" else " ▾", "play/more", onClick = { Ui.moreOpen = !Ui.moreOpen }, tall = true)
            }
        }
    }
}

/** Who plays what under which rule, plus the pillar colour key (fills the play page, D44 blank <= 25 %). */
@Composable
private fun GameInfoCard(state: PlayState) {
    val cfg = state.config ?: return
    Column(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(PicoTheme.colorScheme.fillSecondary).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val who = if (cfg.mode == Mode.AI) stringResource(R.string.info_vs_ai, levelName(cfg.level), colorName(cfg.humanColor)) else stringResource(R.string.info_2p)
        val rule = stringResource(if (cfg.renju) R.string.info_rule_renju else R.string.info_rule_free)
        Txt("$who · $rule", "play/info", style = PicoTheme.typography.bodyLarge, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(14.dp, 24.dp).background(FiveColors.Primary))
            Txt(stringResource(R.string.legend_mine), "play/legend-mine", style = PicoTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(14.dp, 24.dp).background(FiveColors.Tertiary))
            Txt(stringResource(R.string.legend_theirs), "play/legend-theirs", style = PicoTheme.typography.bodyMedium)
            if (!state.shapesOn) { Spacer(Modifier.width(12.dp)); Txt(stringResource(R.string.legend_off), "play/legend-off", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary) }
        }
        Txt(stringResource(R.string.legend_heights), "play/legend-heights", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
    }
}

@Composable
private fun StatusLine(state: PlayState) {
    val alert = state.alert
    var coach by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.firstGame) { while (state.firstGame) { delay(6000); coach = (coach + 1) % 3 } }
    val text = when {
        alert != null && alert.kind == AlertKind.OCCUPIED -> stringResource(R.string.status_occupied)
        alert != null && alert.kind == AlertKind.FORBIDDEN -> stringResource(R.string.status_forbidden, forbiddenName(alert.reason!!))
        alert != null && alert.kind == AlertKind.ENDED -> stringResource(R.string.status_ended)
        state.phase == Phase.PAUSED -> stringResource(R.string.status_paused)
        state.result != null -> state.config?.let { c -> when {
            c.mode == Mode.AI && state.result.winner == c.humanColor -> stringResource(R.string.result_you_win)
            c.mode == Mode.AI && state.result.winner != 0 -> stringResource(R.string.result_you_lose)
            else -> stringResource(R.string.status_ended)
        } } ?: ""
        state.phase == Phase.THINKING -> stringResource(R.string.status_thinking, whoName(state, state.toMove))
        state.phase == Phase.PLACING && state.retractUntil != null -> stringResource(R.string.status_placing)
        state.phase == Phase.PLACING && state.flight != null -> stringResource(R.string.status_ai_placing, whoName(state, state.flight.color))
        state.ghostForbidden != null -> stringResource(R.string.status_ghost_forbidden, forbiddenName(state.ghostForbidden))
        state.phase == Phase.PREVIEW -> stringResource(R.string.status_pinch, whoName(state, state.toMove))
        state.firstGame && state.humansTurn -> stringResource(listOf(R.string.coach_1, R.string.coach_2, R.string.coach_3)[coach])
        state.restored -> stringResource(R.string.status_restored)
        else -> stringResource(R.string.status_your_move, whoName(state, state.toMove))
    }
    val color = if (alert != null && alert.kind != AlertKind.ENDED) FiveColors.ErrorText else PicoTheme.colorScheme.labelPrimary
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.phase == Phase.THINKING) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        Txt(text, "play/status", style = PicoTheme.typography.titleMedium, color = color, maxLines = 2, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MoreBlock(graph: Graph) {
    val flow = graph.flow
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(stringResource(R.string.btn_pause), "more/pause", onClick = { Ui.moreOpen = false; flow.pause() }, modifier = Modifier.weight(1f), tall = true)
        SecondaryButton(stringResource(R.string.btn_resign), "more/resign", onClick = { Ui.moreOpen = false; flow.requestConfirm(ConfirmKind.RESIGN) }, modifier = Modifier.weight(1f), tall = true)
        SecondaryButton(stringResource(R.string.btn_restart), "more/restart", onClick = { Ui.moreOpen = false; flow.requestConfirm(ConfirmKind.RESTART) }, modifier = Modifier.weight(1f), tall = true)
    }
}

@Composable
private fun PauseBlock(graph: Graph) {
    val flow = graph.flow
    // A large Resume and a 2 x 2 grid, so the pause page is not half empty (D44).
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryButton(stringResource(R.string.btn_resume), "pause/resume", onClick = { flow.resume() }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.btn_new_game), "pause/new", onClick = { flow.requestConfirm(ConfirmKind.NEW_FROM_PAUSE) }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(R.string.btn_rules), "pause/rules", onClick = { Ui.page = Page.RULES }, modifier = Modifier.weight(1f), tall = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.btn_settings), "pause/settings", onClick = { Ui.page = Page.SETTINGS }, modifier = Modifier.weight(1f), tall = true)
            SecondaryButton(stringResource(R.string.btn_leave), "pause/leave", onClick = { flow.requestConfirm(ConfirmKind.LEAVE) }, modifier = Modifier.weight(1f), tall = true)
        }
    }
}

@Composable
private fun ConfirmBlock(kind: ConfirmKind, graph: Graph) {
    val flow = graph.flow
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val (q, yes) = when (kind) {
        ConfirmKind.RESTART -> R.string.confirm_restart to R.string.confirm_yes_restart
        ConfirmKind.RESIGN -> R.string.confirm_resign to R.string.confirm_yes_resign
        ConfirmKind.EXIT -> R.string.confirm_exit to R.string.confirm_yes_exit
        ConfirmKind.UNDO_2P -> R.string.confirm_undo_2p to R.string.confirm_yes_undo
        ConfirmKind.NEW_FROM_PAUSE, ConfirmKind.LEAVE -> R.string.confirm_leave to R.string.confirm_yes_leave
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(stringResource(q), "confirm/${kind.name}", style = PicoTheme.typography.titleMedium, maxLines = 2)
        // Buttons share the row equally (Cloud Air round 5).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(stringResource(R.string.btn_cancel), "confirm/cancel", onClick = { flow.cancelConfirm() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(yes), "confirm/yes", danger = kind != ConfirmKind.UNDO_2P, onClick = {
                if (kind == ConfirmKind.EXIT) exitApp(context, navigator, graph) else flow.confirm()
            }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResultBlock(state: PlayState, graph: Graph) {
    val r = state.result ?: return
    val cfg = state.config ?: return
    val title = stringResource(
        when (r.reason) {
            EndReason.FIVE -> if (r.winner == Board.BLACK) R.string.result_black_five else R.string.result_white_five
            EndReason.FULL -> R.string.result_draw_full
            EndReason.FORBIDDEN_STUCK -> R.string.result_white_forbidden
            EndReason.RESIGN -> if (r.winner == Board.WHITE) R.string.result_black_resign else R.string.result_white_resign
        },
    )
    val secs = state.elapsedMs / 1000
    val time = "%d:%02d".format(secs / 60, secs % 60)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt(title, "result/title", style = PicoTheme.typography.headlineSmall, color = PicoTheme.colorScheme.labelPrimary)
        Txt(stringResource(R.string.result_stats, state.moves.size, time, state.hintCount), "result/stats", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary)
        if (state.shapesWasOn) Txt(stringResource(R.string.result_shapes_on), "result/shapes", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        if (cfg.renju) Txt(stringResource(R.string.result_renju_note), "result/renju", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.btn_again), "result/again", onClick = { graph.flow.again() }, modifier = Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.btn_menu), "result/menu", onClick = { graph.flow.toMenu() }, modifier = Modifier.weight(1f).height(56.dp))
        }
    }
}

// ------------------------------------------------------------------ New game

@Composable
private fun NewPage(state: PlayState, settings: Settings, graph: Graph) {
    val setup = settings.lastSetup
    fun set(change: (GameSetup) -> GameSetup) = graph.settings.update { it.copy(lastSetup = change(it.lastSetup)) }
    // Centred, taller rows and a large Start, so the page has no big empty band (D44).
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        PageTitle(stringResource(R.string.new_title), "new")
        if (state.savedAvailable && state.phase == Phase.SETUP) {
            SecondaryButton(stringResource(R.string.btn_continue), "new/continue", onClick = { graph.flow.continueSaved() }, modifier = Modifier.fillMaxWidth())
        }
        OptionRow(stringResource(R.string.new_opponent), "new/opponent", rowHeight = 68.dp) {
            Segments(listOf(Mode.AI to stringResource(R.string.new_vs_ai), Mode.TWO_PLAYER to stringResource(R.string.new_two_players)), setup.mode, "new/opponent", { m -> set { it.copy(mode = m) } }, Modifier.weight(1f))
        }
        if (setup.mode == Mode.AI) {
            OptionRow(stringResource(R.string.new_side), "new/side", rowHeight = 68.dp) {
                Segments(
                    listOf(SidePick.BLACK to stringResource(R.string.new_side_black), SidePick.WHITE to stringResource(R.string.new_side_white), SidePick.RANDOM to stringResource(R.string.new_side_random)),
                    setup.side, "new/side", { s -> set { it.copy(side = s) } }, Modifier.weight(1f),
                )
            }
            OptionRow(stringResource(R.string.new_level), "new/level", labelWidth = 92.dp, rowHeight = 68.dp) {
                Segments((1..5).map { it to levelName(it) }, setup.level, "new/level", { l -> set { it.copy(level = l) } }, Modifier.weight(1f), small = true)
            }
        } else {
            Txt(stringResource(R.string.new_two_players_note), "new/2p-note", style = PicoTheme.typography.bodyMedium, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.padding(vertical = 8.dp))
        }
        OptionRow(stringResource(R.string.new_rule), "new/rule", rowHeight = 68.dp) {
            Segments(listOf(false to stringResource(R.string.new_rule_free), true to stringResource(R.string.new_rule_renju)), setup.renju, "new/rule", { v -> set { it.copy(renju = v) } }, Modifier.weight(1f))
        }
        Txt("ⓘ " + stringResource(R.string.new_renju_info), "new/renju-info", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        if (Ui.newConfirm) {
            // Starting over drops the running game: ask first, right here (critic 要改 2).
            Txt(stringResource(R.string.confirm_new_game), "new/confirm", style = PicoTheme.typography.titleMedium, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(stringResource(R.string.btn_cancel), "new/confirm-cancel", onClick = { Ui.newConfirm = false }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.confirm_yes_restart), "new/confirm-yes", danger = true, onClick = { Ui.newConfirm = false; graph.flow.newGame(setup); Ui.page = Page.PLAY }, modifier = Modifier.weight(1f))
            }
        } else {
            PrimaryButton(stringResource(R.string.btn_start), "new/start", onClick = {
                if (state.inGame && state.moves.isNotEmpty()) { Ui.newConfirm = true; Log.i(TAG, "CONFIRM NEW_GAME S13") } else { graph.flow.newGame(setup); Ui.page = Page.PLAY }
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ Rules

@Composable
private fun RulesPage(state: PlayState, graph: Graph) {
    // Three views switched inside the page (same-page option, not page navigation), each fits without scrolling (D44).
    // Every line is its own child of a SpaceBetween column, so the content reaches from top to bottom and the gaps are
    // shared out evenly (blank above + below the content block <= 25 %, no inner band > 20 %; build.md D44 measure).
    Column(Modifier.fillMaxSize().padding(vertical = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Segments(
                listOf(0 to stringResource(R.string.rules_seg_how), 1 to stringResource(R.string.rules_mistakes), 2 to stringResource(R.string.rules_seg_renju)),
                Ui.rulesView, "rules/view", { Ui.rulesView = it }, Modifier.weight(1f),
            )
        }
        when (Ui.rulesView) {
            0 -> {
                if (state.phase == Phase.WELCOME) Txt(stringResource(R.string.welcome_title), "rules/welcome", style = PicoTheme.typography.titleMedium)
                RulesDiagram()
                listOf(R.string.rules_1, R.string.rules_2, R.string.rules_3, R.string.rules_4).forEach { Txt(stringResource(it), "rules/line", style = PicoTheme.typography.bodyMedium, maxLines = 4) }
            }
            1 -> {
                RulesDiagram()
                listOf(R.string.mistake_1, R.string.mistake_2, R.string.mistake_3, R.string.mistake_4).forEach { Txt("· " + stringResource(it), "rules/mistake", style = PicoTheme.typography.bodyLarge, maxLines = 4) }
            }
            else -> {
                ForbiddenDiagram()
                listOf(R.string.renju_1, R.string.renju_2, R.string.renju_3).forEach { Txt(stringResource(it), "rules/renju-line", style = PicoTheme.typography.titleMedium, maxLines = 4) }
            }
        }
        if (state.phase == Phase.WELCOME) {
            PrimaryButton(stringResource(R.string.welcome_start), "rules/welcome-start", onClick = { graph.flow.welcomeDone() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The double three that is forbidden for Black: two open threes meet on one point, marked with the red cross. */
@Composable
private fun ForbiddenDiagram() {
    val wood = FiveColors.Kaya
    val ink = FiveColors.BoardInk
    Canvas(Modifier.size(width = 240.dp, height = 120.dp)) {
        val cell = size.height / 7f
        drawRoundRect(wood, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f))
        for (i in 0..6) drawLine(ink, Offset(cell * 0.5f, cell * (i + 0.5f)), Offset(size.width - cell * 0.5f, cell * (i + 0.5f)), strokeWidth = 1.5f)
        var x = cell * 0.5f
        while (x < size.width) { drawLine(ink, Offset(x, cell * 0.5f), Offset(x, size.height - cell * 0.5f), strokeWidth = 1.5f); x += cell }
        fun stone(c: Int, r: Int) = drawCircle(FiveColors.StoneBlack, cell * 0.42f, Offset(cell * (c + 0.5f), cell * (r + 0.5f)))
        // row 3: two black stones, column 6: two black stones, both open; the crossing point (6, 3) would make a double three
        stone(4, 3); stone(5, 3); stone(6, 1); stone(6, 2)
        val o = Offset(cell * 6.5f, cell * 3.5f); val d = cell * 0.32f
        drawLine(FiveColors.Error, Offset(o.x - d, o.y - d), Offset(o.x + d, o.y + d), strokeWidth = cell * 0.16f)
        drawLine(FiveColors.Error, Offset(o.x - d, o.y + d), Offset(o.x + d, o.y - d), strokeWidth = cell * 0.16f)
    }
}

/** One picture: five in a row (lifted, teal band), an open three with its two amber 3 cm pillars, a four with a 5 cm pillar. */
@Composable
private fun RulesDiagram() {
    val wood = FiveColors.Kaya
    val ink = FiveColors.BoardInk
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 240.dp, height = 120.dp)) {
            val cell = size.height / 7f
            drawRoundRect(wood, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f))
            for (i in 0..6) {
                drawLine(ink, Offset(cell * 0.5f, cell * (i + 0.5f)), Offset(size.width - cell * 0.5f, cell * (i + 0.5f)), strokeWidth = 1.5f)
            }
            var x = cell * 0.5f
            while (x < size.width) { drawLine(ink, Offset(x, cell * 0.5f), Offset(x, size.height - cell * 0.5f), strokeWidth = 1.5f); x += cell }
            fun stone(c: Int, r: Int, black: Boolean) {
                val o = Offset(cell * (c + 0.5f), cell * (r + 0.5f))
                drawCircle(if (black) FiveColors.StoneBlack else Color.White, cell * 0.42f, o)
                if (!black) drawCircle(ink, cell * 0.42f, o, style = Stroke(1.5f))
            }
            fun pillar(c: Int, r: Int, h: Float, teal: Boolean) {
                val o = Offset(cell * (c + 0.5f), cell * (r + 0.5f))
                val col = if (teal) FiveColors.Primary else FiveColors.Tertiary
                drawRect(col.copy(alpha = 0.85f), topLeft = Offset(o.x - cell * 0.16f, o.y - h * cell), size = androidx.compose.ui.geometry.Size(cell * 0.32f, h * cell))
                drawCircle(col, cell * 0.2f, o)
            }
            // five in a row on row 1 with a teal band
            drawLine(FiveColors.Primary, Offset(cell * 1.5f, cell * 1.5f), Offset(cell * 5.5f, cell * 1.5f), strokeWidth = cell * 0.5f)
            for (c in 1..5) stone(c, 1, true)
            // open three (white) on row 4 with amber pillars both ends
            for (c in 2..4) stone(c, 4, false)
            pillar(1, 4, 1.0f, false); pillar(5, 4, 1.0f, false)
            // a four (black) on row 6 blocked left, pillar on the open end
            stone(7, 6, false)
            for (c in 8..11) stone(c, 6, true)
            pillar(12, 6, 1.6f, true)
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Legend(FiveColors.Primary, "5", stringResource(R.string.legend_four))
            Legend(FiveColors.Tertiary, "3", stringResource(R.string.legend_three))
            Legend(FiveColors.Primary, "7", stringResource(R.string.legend_open_four))
        }
    }
}

@Composable
private fun Legend(color: Color, cm: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(14.dp, 22.dp)) { drawRect(color) }
        Spacer(Modifier.width(8.dp))
        Txt("$label · $cm cm", "rules/legend", style = PicoTheme.typography.bodyMedium)
    }
}

// ------------------------------------------------------------------ Settings

@Composable
private fun SettingsPage(settings: Settings, graph: Graph) {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    fun upd(change: (Settings) -> Settings) { graph.settings.update(change); graph.flow.settingsChanged() }
    // 8 rows, no scrolling: [重开控制窗] and [退出应用] are always on the first screen (D44, design densityCap 504 <= 560 dp).
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        OptionRow(stringResource(R.string.settings_language), "settings/lang") {
            Segments(listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en)), settings.language, "settings/lang", { l -> upd { it.copy(language = l) } }, Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.settings_sound), "settings/sound", settings.sound) { v -> upd { it.copy(sound = v) } }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume") {
            VolumeSlider(settings.volume, onChange = { v -> upd { it.copy(volume = v) } }, modifier = Modifier.weight(1f))
        }
        SwitchRow(stringResource(R.string.settings_reduced), "settings/reduced", settings.reducedMotion) { v -> upd { it.copy(reducedMotion = v) } }
        OptionRow(stringResource(R.string.settings_tilt), "settings/tilt") {
            Segments(Settings.TILTS.map { it to "$it°" }, settings.tilt, "settings/tilt", { t -> upd { it.copy(tilt = t) } }, Modifier.weight(1f))
        }
        OptionRow(stringResource(R.string.settings_size), "settings/size") {
            Segments(Settings.SIZES.map { it to "$it%" }, settings.size, "settings/size", { s -> upd { it.copy(size = s) } }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        SecondaryButton(stringResource(R.string.settings_recall), "settings/reopen", onClick = { graph.window.recall() }, modifier = Modifier.fillMaxWidth())
        Txt(stringResource(R.string.settings_recall_hint), "settings/reopen-hint", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
        Txt(stringResource(R.string.settings_about_text, BuildConfig.VERSION_NAME), "settings/about", style = PicoTheme.typography.bodySmall, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, modifier = Modifier.padding(vertical = 6.dp))
        // D26: quit is the last row; the confirmation opens in the same row (no second panel, no scrolling).
        if (!Ui.exitConfirm) {
            SecondaryButton(stringResource(R.string.settings_exit), "settings/exit", onClick = { Ui.exitConfirm = true; Log.i(TAG, "CONFIRM EXIT S13") }, modifier = Modifier.fillMaxWidth(), danger = true)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.confirm_exit), "settings/exit-q", style = PicoTheme.typography.titleMedium, modifier = Modifier.weight(1.3f), maxLines = 2)
                SecondaryButton(stringResource(R.string.btn_cancel), "settings/exit-cancel", onClick = { Ui.exitConfirm = false }, modifier = Modifier.weight(1f))
                SecondaryButton(stringResource(R.string.confirm_yes_exit), "settings/exit-yes", onClick = { exitApp(context, navigator, graph) }, modifier = Modifier.weight(1f), danger = true)
            }
        }
    }
}

/** D26 exit: release, close the windows, remove every task, then end the process. */
internal fun exitApp(context: Context, navigator: SpatialNavigator, graph: Graph) {
    Log.i(TAG, "EXIT release + close + finishAndRemoveTask + exitProcess")
    runCatching { graph.flow.onBackground(); graph.sounds.release(); graph.window.detach() }
    // D44 / QA q1-5: close BOTH window containers (board volume and this control window) before ending the task,
    // otherwise the task stays in recents. Give them 400 ms to report ON_DESTROY, then remove the task and end.
    val closeBoard = graph.window.closeBoard
    runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
    runCatching { closeBoard?.invoke() }.onFailure { Log.w(TAG, "EXIT close board: $it") }
    Log.i(TAG, "EXIT closed control + board (board closer=${closeBoard != null})")
    val main = Handler(Looper.getMainLooper())
    main.postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        Log.i(TAG, "EXIT finishAndRemoveTask")
        main.postDelayed({ exitProcess(0) }, 300)
    }, 400)
}

/** The native TabBar with labels already resolved in the app language (kept last in the file: no string lookups below). */
@Composable
private fun ControlTabBar(tabs: List<Triple<Page, Int, String>>) {
    TabBar(followViewpoints = setOf(ViewPoint.Front, ViewPoint.Left)) {
        tabs.forEach { (page, icon, label) ->
            item(
                selected = Ui.page == page,
                onClick = { Ui.page = page },
                mainContent = { Icon(painter = painterResource(icon), contentDescription = label) },
                supportContent = { Txt(label, "tabbar/${page.name}", style = PicoTheme.typography.labelMedium) },
            )
        }
    }
}
