package app.indoorbike.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CadenceColorScheme = darkColorScheme(
    primary = CadenceColors.Brand500,
    onPrimary = CadenceColors.OnBrand,
    primaryContainer = CadenceColors.Brand700,
    onPrimaryContainer = CadenceColors.Fg1,
    secondary = CadenceColors.Brand400,
    onSecondary = CadenceColors.OnBrand,
    background = CadenceColors.Bg0,
    onBackground = CadenceColors.Fg1,
    surface = CadenceColors.Bg1,
    onSurface = CadenceColors.Fg1,
    surfaceVariant = CadenceColors.Bg2,
    onSurfaceVariant = CadenceColors.Fg2,
    outline = CadenceColors.Line2,
    outlineVariant = CadenceColors.Line1,
    error = CadenceColors.Danger,
    onError = CadenceColors.Fg1
)

@Composable
fun CadenceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CadenceColorScheme,
        typography = CadenceTypography,
        content = content
    )
}
