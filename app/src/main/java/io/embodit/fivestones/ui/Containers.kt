package io.embodit.fivestones.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The board is the default container (manifest). */
object Containers {
    const val BOARD = "FiveBoard"
    const val CONTROL = "FiveControl"
    const val CONTROL_TAG = "control"
}

enum class Page(val stateId: String) { PLAY("S03"), NEW("S02"), RULES("S12"), SETTINGS("S11") }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.RULES)
    var moreOpen by mutableStateOf(false)
    var renjuOpen by mutableStateOf(false)
    var exitConfirm by mutableStateOf(false)
    var newConfirm by mutableStateOf(false)
    var rulesView by mutableStateOf(0)
}
