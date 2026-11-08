package dev.robinsiegl.sheno

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

val Indigo = Color(0xFF5B50D6)
val MedicationColors = listOf("Purple" to 0xFF6750A4, "Blue" to 0xFF2463B8, "Rose" to 0xFFB54065,
    "Orange" to 0xFFB45D20, "Green" to 0xFF347857, "Teal" to 0xFF007E87, "White" to 0xFFF0EDF3, "Charcoal" to 0xFF49454F)
@Composable fun ShenoTheme(preferences: Preferences, content: @Composable () -> Unit) {
    val dark = when (preferences.theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val context = LocalContext.current
    val colors = if (preferences.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme(primary = Color(0xFFC7BFFF), primaryContainer = Color(0xFF39306F), secondaryContainer = Color(0xFF343044))
    else lightColorScheme(primary = Indigo, onPrimary = Color.White, primaryContainer = Color(0xFFE6DFFF), onPrimaryContainer = Color(0xFF20144D), secondaryContainer = Color(0xFFE9E1F7))
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
@Composable fun MedicationIcon(med: Medication, modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp).semantics { contentDescription = med.form.label }) {
        val color = Color(med.color)
        val line = if (med.color == 0xFFF0EDF3) Color(0xFF6D6575) else Color.White.copy(alpha = 0.8f)
        val w = size.width
        val h = size.height
        when (med.form) {
            Form.ROUND -> {
                drawCircle(color, radius = w * .39f)
                drawCircle(Color(0xFF79747E), radius = w * .39f, style = Stroke(w * .025f))
                drawLine(line, Offset(w * .26f, h * .26f), Offset(w * .74f, h * .74f), strokeWidth = w * .035f)
            }
            Form.OVAL, Form.OBLONG, Form.CAPSULE -> {
                val rect = androidx.compose.ui.geometry.RoundRect(w * .12f, h * .29f, w * .88f, h * .71f, CornerRadius(h * .21f))
                val path = Path().apply { addRoundRect(rect) }
                clipPath(path) {
                    drawRect(color)
                    if (med.form == Form.CAPSULE) drawRect(Color(med.secondColor), Offset(w * .5f, 0f))
                    drawLine(line, Offset(w * .5f, h * .28f), Offset(w * .5f, h * .72f), strokeWidth = w * .03f)
                }
                drawPath(path, Color(0xFF79747E), style = Stroke(w * .025f))
            }
            Form.LIQUID, Form.DROPS -> {
                drawRoundRect(color, Offset(w * .26f, h * .3f), Size(w * .48f, h * .58f), CornerRadius(w * .09f))
                drawRoundRect(color, Offset(w * .35f, h * .12f), Size(w * .3f, h * .23f), CornerRadius(w * .04f))
                drawLine(line, Offset(w * .34f, h * .58f), Offset(w * .66f, h * .58f), strokeWidth = w * .04f)
            }
            Form.INJECTION -> {
                drawLine(color, Offset(w * .65f, h * .25f), Offset(w * .29f, h * .65f), strokeWidth = w * .22f)
                drawLine(color, Offset(w * .27f, h * .66f), Offset(w * .13f, h * .85f), strokeWidth = w * .035f)
                drawLine(color, Offset(w * .53f, h * .12f), Offset(w * .85f, h * .42f), strokeWidth = w * .07f)
            }
            Form.INHALER -> {
                drawRoundRect(color, Offset(w * .25f, h * .15f), Size(w * .35f, h * .65f), CornerRadius(w * .08f))
                drawRoundRect(color, Offset(w * .4f, h * .6f), Size(w * .4f, h * .22f), CornerRadius(w * .06f))
            }
            Form.PATCH, Form.OTHER -> {
                drawRoundRect(color, Offset(w * .15f, h * .22f), Size(w * .7f, h * .56f), CornerRadius(w * .13f))
                drawRoundRect(line, Offset(w * .36f, h * .35f), Size(w * .28f, h * .3f), CornerRadius(w * .04f), style = Stroke(w * .03f))
            }
        }
    }
}
