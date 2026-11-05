package io.embodit.fivestones.ui.control

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import io.embodit.fivestones.domain.Board
import io.embodit.fivestones.platform.TAG
import io.embodit.fivestones.ui.theme.FiveColors

/** Text that logs "OVERFLOW <panel>/<control>" whenever it is cut (profile D13; ui-audit runs read the log). */
@Composable
internal fun Txt(
    text: String,
    tag: String,
    style: TextStyle = PicoTheme.typography.bodyLarge,
    color: Color = PicoTheme.colorScheme.labelPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, softWrap = maxLines > 1, textAlign = align,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") },
    )
}

@Composable
internal fun PrimaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 56.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = PicoTheme.colorScheme.labelPrimaryLight),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/primary", style = PicoTheme.typography.titleMedium, color = PicoTheme.colorScheme.labelPrimaryLight)
    }
}

@Composable
internal fun SecondaryButton(text: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) FiveColors.Error else PicoTheme.colorScheme.fillSecondary,
            contentColor = if (danger) FiveColors.OnError else PicoTheme.colorScheme.labelPrimary,
        ),
    ) {
        // design-style: inherited-content-color Button
        Txt(text, "$tag/button", style = PicoTheme.typography.labelLarge, color = if (danger) FiveColors.OnError else PicoTheme.colorScheme.labelPrimary)
    }
}

/** A switch-like button (D19): its on / off state is the fill, and a small lamp on the left. */
@Composable
internal fun ToggleButton(text: String, tag: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fg = if (on) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = 52.dp, minWidth = 96.dp),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillSecondary, contentColor = fg),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) FiveColors.PrimaryContainer else Color.Transparent).border(1.5.dp, fg, CircleShape))
        Box(Modifier.width(8.dp))
        // design-style: inherited-content-color Button
        Txt(text, "$tag/toggle", style = PicoTheme.typography.labelLarge, color = fg)
    }
}

/** A labelled row holding a same-page option switch (SegmentControl is never used to change pages). */
@Composable
internal fun OptionRow(label: String, tag: String, labelWidth: Dp = 112.dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, "$tag/label", style = PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(labelWidth))
        content()
    }
}

@Composable
internal fun <T> Segments(options: List<Pair<T, String>>, selected: T, tag: String, onSelect: (T) -> Unit, modifier: Modifier = Modifier, small: Boolean = false) {
    SegmentControl(modifier = modifier) {
        options.forEach { (value, label) ->
            SegmentItem(
                selected = value == selected,
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                title = {
                    // design-style: inherited-content-color SegmentItem
                    Txt(label, "$tag/segment", style = if (small) PicoTheme.typography.labelMedium else PicoTheme.typography.labelLarge, color = PicoTheme.colorScheme.labelPrimary)
                },
            )
        }
    }
}

/** A stone-coloured dot (white gets an ink rim so it shows on light glass). */
@Composable
internal fun StoneDot(color: Int, size: Dp = 18.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (color == Board.BLACK) FiveColors.StoneBlack else Color(0xFFFFFFFF))
            .border(1.5.dp, FiveColors.Ink, CircleShape),
    )
}
