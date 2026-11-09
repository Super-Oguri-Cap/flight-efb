package com.oguricap.efb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 配色直接取自航路生成器页面（`Walker'sHTML/航路生成器_v8.6_无数据版.html` 的 :root，
// v8.4 → v8.6 深色变量未变，只多了一套 html[data-theme="light"]）：
//   --bg #0f141a  --panel #1a212b  --panel2 #141a22  --line #2a3441
//   --txt #d0dae6 --dim #7b8b9e    --accent #4a9eff
//   --ok #3fd08a  --bad #e05c5c    --warn #e8b64a
//
// 为什么固定深色、不跟随系统：
// 工具页里嵌的是两个硬编码深色的单文件网页，App 若在浅色模式下给白底，
// 切进工具页会像换了个应用。整套界面统一到同一套深色底，边界才不突兀。

private val EfbColors = darkColorScheme(
    primary = Color(0xFF4A9EFF),
    onPrimary = Color(0xFF06121F),
    primaryContainer = Color(0xFF1B3A5C),
    onPrimaryContainer = Color(0xFFCFE6FF),

    secondary = Color(0xFF7B8B9E),
    onSecondary = Color(0xFF0F141A),
    secondaryContainer = Color(0xFF243040),
    onSecondaryContainer = Color(0xFFD0DAE6),

    tertiary = Color(0xFF3FD08A),
    onTertiary = Color(0xFF04140D),
    tertiaryContainer = Color(0xFF11301F),
    onTertiaryContainer = Color(0xFFB7F3D6),

    background = Color(0xFF0F141A),
    onBackground = Color(0xFFD0DAE6),
    surface = Color(0xFF1A212B),
    onSurface = Color(0xFFD0DAE6),
    surfaceVariant = Color(0xFF141A22),
    onSurfaceVariant = Color(0xFF7B8B9E),
    surfaceContainer = Color(0xFF161D26),
    surfaceContainerHigh = Color(0xFF1E2733),
    surfaceContainerHighest = Color(0xFF243040),

    outline = Color(0xFF2A3441),
    outlineVariant = Color(0xFF222B36),

    error = Color(0xFFE05C5C),
    onError = Color(0xFF1A0C0C),
    errorContainer = Color(0xFF3A1A1A),
    onErrorContainer = Color(0xFFFFD9D9),

    // 警告色页面里是 #e8b64a，Material 没有独立槽位，挂在 inversePrimary 上备用
    inversePrimary = Color(0xFFE8B64A),
)

@Composable
fun EfbTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EfbColors, content = content)
}
