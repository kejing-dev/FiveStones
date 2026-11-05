package io.embodit.fivestones.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.fivestones.domain.Language
import io.embodit.fivestones.platform.Graph
import java.util.Locale

/** design-package theme.colorScheme: kaya wood, cloud-stone teal (own chances), amber (their threats). */
object FiveColors {
    val Primary = Color(0xFF2FA69A)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryDeep = Color(0xFF1E7E74)
    val PrimaryContainer = Color(0xFFC4EDE6)
    val OnPrimaryContainer = Color(0xFF063B36)
    val Secondary = Color(0xFF8C6230)
    val SecondaryContainer = Color(0xFFF1DDB8)
    val OnSecondaryContainer = Color(0xFF3F2A0A)
    val Tertiary = Color(0xFFD99A2B)
    val OnTertiary = Color(0xFF2B1D05)
    val TertiaryContainer = Color(0xFFFFE2A8)
    val OnTertiaryContainer = Color(0xFF3E2A00)
    val Error = Color(0xFFC8423F)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass; the brighter Error stays for 3D marks and button fills. */
    val ErrorText = Color(0xFF7A1A18)

    // 3D
    val Kaya = Color(0xFFE2B55F)
    val KayaEdge = Color(0xFFC7934A)
    val StoneBlack = Color(0xFF1C1F22)
    val StoneWhite = Color(0xFFF1EEE6)
    val Glaze = Color(0xFF3B3430)
    val GlazeInner = Color(0xFF231E1B)
    val BoardInk = Color(0xFF3A2A16)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). [language] localises resources below this point
 * through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun FiveTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = FiveColors.OnPrimary,
        labelPrimary = FiveColors.Ink,
        labelSecondary = FiveColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = FiveColors.Error,
        alert = FiveColors.Tertiary,
        passable = FiveColors.Primary,
        interaction = FiveColors.PrimaryDeep,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
