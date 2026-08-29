package com.voiceconfig.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 全 App 统一的间距令牌。所有新页面应优先引用这里，避免散落魔法数字。 */
object Spacing {
    val screen: Dp = 16.dp
    val screenCompact: Dp = 12.dp
    val section: Dp = 12.dp
    val card: Dp = 16.dp
    val item: Dp = 8.dp
    val control: Dp = 48.dp
    val floating: Dp = 60.dp
    val small: Dp = 4.dp
    val medium: Dp = 8.dp
    val large: Dp = 16.dp
}

/** 全 App 统一的圆角令牌。 */
object Radius {
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    val extraLarge: Dp = 24.dp
    val circle: Dp = 999.dp
}

/** 语义色令牌，避免页面直接写硬编码色值。 */
object SemanticColors {
    val success = Color(0xFF2E7D32)
    val warning = Color(0xFFF57C00)
    val danger = Color(0xFFB3261E)
    val primary = Color(0xFF4F46E5)
}
