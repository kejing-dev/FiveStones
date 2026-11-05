package io.embodit.fivestones.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.fivestones.R
import io.embodit.fivestones.domain.AlertKind
import io.embodit.fivestones.domain.Board
import io.embodit.fivestones.domain.Forbidden
import androidx.compose.foundation.layout.Column
import io.embodit.fivestones.domain.BoardGeometry
import io.embodit.fivestones.domain.Phase
import io.embodit.fivestones.domain.PlayState
import io.embodit.fivestones.platform.Graph
import io.embodit.fivestones.platform.TAG
import io.embodit.fivestones.ui.Containers
import io.embodit.fivestones.ui.theme.FiveColors
import io.embodit.fivestones.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
}

private const val PLATE = "plate"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the
 * board and routes pointer hover (ghost stone) and pinch / trigger (tap) on the 225 pads to [GameFlow].
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(
            open = { navigator.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
            close = { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) },
        )
        graph.window.closeBoard = { navigator.closeWindowContainer() }
        graph.window.request("start")
        onDispose {
            graph.window.detach()
            runCatching { navigator.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
        }
    }

    // Leaving for the background stops the engine and saves; coming back resumes (design container.lifecycle).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(Unit) {
                detectSpatialTapGesture(context = context) { tap ->
                    val name = tap.targetEntity?.getName()
                    val p = scene.pointOf(name)
                    Log.i(TAG, "PINCH target=$name kind=${tap.interactionKind}")
                    if (p != null) graph.flow.tap(p)
                }
            }
            .pointerInput(Unit) {
                detectSpatialPointerEvent(context = context) { infos ->
                    val info = infos.firstOrNull()
                    if (info != null && !info.pressed) {
                        val p = scene.pointOf(info.targetedEntity?.getName())
                        graph.flow.hover(p)
                    }
                    false
                }
            },
        attachments = {
            // The attachment is its own composition: hand it the localised context explicitly.
            AttachmentPanel(PLATE) { PlateRoot(graph) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            // S00: a plain slab and two cylinders first (3 resources), then the real board.
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle: "首帧兜底 素色方板 + 两个圆柱")
                    val slab = MeshResource.createBox(Vector3(BoardGeometry.BOARD, BoardGeometry.THICK, BoardGeometry.BOARD), cornerRadius = 0.006f)
                    // gate:allow geometry S00 loading stand-in bowls
                    val cyl = MeshResource.createCylinder(radius = BoardGeometry.BOWL_R, height = BoardGeometry.BOWL_H)
                    val mat = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.80f, 0.70f, 0.52f, 1f)); setRoughness(0.8f) }
                    Triple(slab, cyl, mat)
                }.getOrNull()
            }
            quick?.let { (s, c, m) -> scene.showFallback(s, c, m) }
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            // QA only (debug broadcast `s00hold on`): keep the S00 stand-in for 10 s on the next launch so it can be captured.
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            scene.install(resources, attachments.entity(PLATE))
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** The plate is its own composition: it reads state + language itself so a language switch always reaches it (critic 要改 6). */
@Composable
private fun PlateRoot(graph: Graph) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(s.language) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContext provides base.localized(s.language)) { Plate(st, graph) }
    }
}

/** The move plate above the far edge (100 x 35 mm): move number + whose turn; reads the same state as the window (D38). */
@Composable
private fun Plate(state: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        modifier = Modifier
            .size(width = 280.dp, height = 72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xF2FBF6EC)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> Text(stringResource(R.string.level_hint), color = FiveColors.Ink, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("chip") }) {
                // design-style: inherited-content-color Button
                Text(stringResource(R.string.open_panel), fontSize = 16.sp)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlateText(state)
                // D45: the reason for a refused tap also shows here, next to the board, for 1.5 s (same source as the window).
                state.alert?.let { al ->
                    val reason = when (al.kind) {
                        AlertKind.OCCUPIED -> stringResource(R.string.plate_taken)
                        AlertKind.ENDED -> stringResource(R.string.plate_over_short)
                        AlertKind.FORBIDDEN -> stringResource(R.string.plate_forbidden, stringResource(when (al.reason) {
                            Forbidden.DOUBLE_FOUR -> R.string.forbidden_double_four
                            Forbidden.OVERLINE -> R.string.forbidden_overline
                            else -> R.string.forbidden_double_three
                        }))
                    }
                    Text(reason, color = FiveColors.ErrorText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW plate/reason \"$reason\"") })
                }
            }
        }
    }
}

@Composable
private fun PlateText(state: PlayState) {
    val n = state.moves.size
    val color = state.toMove
    val text = when {
        state.config == null || state.phase in setOf(Phase.LOADING, Phase.WELCOME, Phase.SETUP) -> stringResource(R.string.plate_ready)
        state.result != null -> stringResource(R.string.plate_over, n)
        else -> stringResource(R.string.plate_turn, n + 1, stringResource(if (color == Board.BLACK) R.string.color_black else R.string.color_white))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
        if (state.config != null && state.result == null && state.phase !in setOf(Phase.SETUP, Phase.WELCOME)) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(if (color == Board.BLACK) FiveColors.StoneBlack else Color(0xFFFFFFFF)).border(2.dp, FiveColors.Ink, CircleShape))
        }
        Text(text, color = FiveColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
            onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW plate/turn \"$text\"") })
    }
}
