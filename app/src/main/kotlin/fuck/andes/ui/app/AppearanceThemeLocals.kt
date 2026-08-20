package fuck.andes.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import fuck.andes.data.model.AppearanceSettings
import fuck.andes.data.model.AppearanceTopBarBlurStyle

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
internal val LocalBlurEnabled = staticCompositionLocalOf { true }
internal val LocalTopBarBlurStyle = staticCompositionLocalOf { AppearanceTopBarBlurStyle.GAUSSIAN }
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }
