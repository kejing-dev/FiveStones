package io.embodit.fivestones

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import androidx.compose.foundation.layout.Box
import io.embodit.fivestones.ui.Containers
import io.embodit.fivestones.ui.board.BoardScreen
import io.embodit.fivestones.ui.control.ControlWindow
import io.embodit.fivestones.ui.theme.FiveTheme

/**
 * Two surfaces in Shared Space (design-package windowModel volume_plus_control_window): the board volume
 * (default container, 0.84 x 0.54 x 0.96 m from the manifest) and the control window (Planar 560 x 480 dp)
 * placed to its right by the system.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            FiveTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 480.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            FiveTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 360.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
