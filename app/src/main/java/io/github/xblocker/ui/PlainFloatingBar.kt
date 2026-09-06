package io.github.xblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Same 64/56 dp geometry without creating API 33 RuntimeShader objects. */
@Composable
internal fun PlainFloatingBar(page: Int, labels: List<String>, icons: List<ImageVector>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(CircleShape)
        .background(MiuixTheme.colorScheme.surfaceContainer).padding(4.dp).selectableGroup()) {
        icons.forEachIndexed { index, icon ->
            val selected = page == index
            val tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
            Column(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                .background(if (selected) MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f) else androidx.compose.ui.graphics.Color.Transparent)
                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
                Icon(icon, null, tint = tint)
                Text(labels[index], color = tint, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
            }
        }
    }
}
