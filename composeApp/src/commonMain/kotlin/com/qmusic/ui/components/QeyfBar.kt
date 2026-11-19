package com.qmusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qmusic.ui.theme.BorderSubtle
import com.qmusic.ui.theme.Ink
import com.qmusic.ui.theme.TextPrimary
import com.qmusic.ui.theme.TextSecondary

/** The green of unread counts. */
val QeyfBadgeGreen = Color(0xFF1DAA61)

/**
 * Qeyf's bottom bar, the same in the messages, the music and the videos: a hairline on top, the selected tab's icon on
 * a pill (like the system's own bars), counts and dots on the icons.
 */
@Composable
fun QeyfBar(background: Color, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(background)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(BorderSubtle))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp), content = content)
    }
}

/** One tab of [QeyfBar] with an icon. */
@Composable
fun RowScope.QeyfBarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    count: Int = 0,
    dot: Boolean = false,
    badgeColor: Color = QeyfBadgeGreen,
    onClick: () -> Unit,
) = QeyfBarItem(label, selected, count, dot, badgeColor, icon = { tint -> Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp)) }, onClick = onClick)

/** One tab of [QeyfBar], drawing its own icon in the colour given. */
@Composable
fun RowScope.QeyfBarItem(
    label: String,
    selected: Boolean,
    count: Int = 0,
    dot: Boolean = false,
    badgeColor: Color = QeyfBadgeGreen,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.widthIn(max = 60.dp).fillMaxWidth(0.86f).height(32.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) Ink.copy(alpha = 0.14f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { icon(if (selected) Ink else TextSecondary) }
            if (count > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = (-2).dp).height(18.dp).widthIn(min = 18.dp).clip(CircleShape).background(badgeColor).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (count > 99) "99+" else count.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            } else if (dot) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = (-10).dp, y = 2.dp).size(9.dp).clip(CircleShape).background(QeyfBadgeGreen))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
