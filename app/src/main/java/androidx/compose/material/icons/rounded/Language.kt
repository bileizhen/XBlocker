package androidx.compose.material.icons.rounded

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path

val Icons.Rounded.Language: ImageVector
    get() = languageIcon

private val languageIcon: ImageVector by lazy {
    materialIcon(name = "Rounded.Language") {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(21f, 12f)
            curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
            curveTo(7.03f, 21f, 3f, 16.97f, 3f, 12f)
            curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
            curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
            close()
            moveTo(12f, 3f)
            curveTo(6.67f, 8f, 6.67f, 16f, 12f, 21f)
            curveTo(17.33f, 16f, 17.33f, 8f, 12f, 3f)
            close()
            moveTo(4f, 8f)
            horizontalLineTo(20f)
            moveTo(4f, 16f)
            horizontalLineTo(20f)
        }
    }
}
