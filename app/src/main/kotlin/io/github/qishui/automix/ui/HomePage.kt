package io.github.qishui.automix.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qishui.automix.ModuleActions
import io.github.qishui.automix.ModuleStatus
import io.github.qishui.automix.SettingsSchema
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.text.DateFormat
import java.util.Date

private val gentle = spring<androidx.compose.ui.graphics.Color>(stiffness = Spring.StiffnessLow)

@Composable
fun HomePage(store: SettingsStore, bottomPadding: Dp, openPage: (Int) -> Unit) {
    val activity = LocalContext.current as Activity
    val status = store.status
    PageScaffold(title = "叠拍 FizzFade", bottomPadding = bottomPadding) {
        item(key = "hero") {
            StatusHero(status) { if (status.state == ModuleStatus.State.CONNECTED) openPage(1) else ModuleActions.openHost(activity) }
        }
        item(key = "counters") { Counters(status, onClick = { openPage(1) }) }
        item(key = "warning") {
            val warning = status.warnings()
            AnimatedVisibility(
                visible = warning.isNotEmpty(),
                enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) + shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)),
            ) { WarningCard(warning) { ModuleActions.openHostInfo(activity) } }
        }
        item(key = "setup") {
            AnimatedVisibility(
                visible = status.neverConnected() && status.state != ModuleStatus.State.NOT_INSTALLED,
                enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
                exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)) + shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)),
            ) { SetupCard(activity) }
        }
        item(key = "quick") {
            Section("常用") {
                QuickToggle(store, "enabled", MiuixIcons.Merge, Tint.Blue)
                QuickToggle(store, "ui_lyrics", MiuixIcons.Notes, Tint.Pink)
                QuickToggle(store, "ui_player", MiuixIcons.Music, Tint.Orange)
                QuickToggle(store, "ui_tab", MiuixIcons.All, Tint.Purple)
            }
        }
        item(key = "info") { InfoCard(status) }
    }
}

@Composable
private fun QuickToggle(store: SettingsStore, key: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    val toggle = SettingsSchema.find(key) ?: return
    ToggleRow(store, toggle) { IconTile(icon, tint) }
}

/** SukiSU's hero, full width: state colour, an oversized glyph bleeding off the corner. */
@Composable
private fun StatusHero(status: ModuleStatus, onClick: () -> Unit) {
    val colors = heroColors(status.state)
    val background by animateColorAsState(colors.background, gentle, label = "hero-bg")
    val glyph by animateColorAsState(colors.glyph, gentle, label = "hero-glyph")
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(150.dp),
        colors = CardDefaults.defaultColors(color = background),
        cornerRadius = 22.dp,
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(40.dp, 48.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Crossfade(targetState = status.state, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "hero-icon") { state ->
                    Icon(
                        modifier = Modifier.size(176.dp),
                        imageVector = heroIcon(state),
                        tint = glyph.copy(alpha = 0.9f),
                        contentDescription = null,
                    )
                }
            }
            Column(modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(text = status.title(), fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "模块 " + ModuleStatus.MODULE_VERSION + " · 汽水 " + (status.hostVersion ?: "未安装"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = heroDetail(status),
                    fontSize = 13.sp,
                    maxLines = 2,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    modifier = Modifier.fillMaxWidth(0.72f),
                )
            }
        }
    }
}

private fun heroIcon(state: ModuleStatus.State): ImageVector = when (state) {
    ModuleStatus.State.CONNECTED -> MiuixIcons.Ok
    ModuleStatus.State.NOT_INSTALLED -> MiuixIcons.Report
    ModuleStatus.State.WAITING -> MiuixIcons.Info
    else -> MiuixIcons.Close2
}

private fun heroDetail(status: ModuleStatus): String = when (status.state) {
    ModuleStatus.State.NOT_INSTALLED -> "未找到官网版汽水音乐"
    ModuleStatus.State.WAITING -> "在 LSPosed 启用模块后，强行停止并重新打开汽水"
    ModuleStatus.State.NOT_RUNNING -> "点此打开汽水 · 最近 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.lastSeen))
    ModuleStatus.State.DISABLED -> "界面功能照常；混音已暂停"
    else -> status.detail.ifEmpty { "已连接" }
}

@Composable
private fun Counters(status: ModuleStatus, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Counter("混音成功", status.completed, Modifier.weight(1f), onClick)
        Counter("对拍确认", status.beatCompleted, Modifier.weight(1f), onClick)
    }
}

@Composable
private fun Counter(label: String, value: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.fillMaxHeight(),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(verticalAlignment = Alignment.Bottom) {
            RollingNumber(value = value, fontSize = 28.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(text = " 次", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun WarningCard(text: String, onForceStop: () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp)
            .fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (dark) androidx.compose.ui.graphics.Color(0xFF3A2A14) else androidx.compose.ui.graphics.Color(0xFFFFF3E0),
        ),
        insideMargin = PaddingValues(16.dp),
        onClick = onForceStop,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Text(text = text, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
        Text(text = "点此打开汽水应用信息 → 强行停止", fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SetupCard(activity: Activity) {
    Section("首次使用") {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            listOf(
                "在 LSPosed 启用本模块，作用域只选汽水音乐",
                "在应用信息里强行停止汽水，再重新打开",
                "连续播放两首完整的歌，临近曲尾听过渡",
            ).forEachIndexed { index, step ->
                Row(modifier = Modifier.padding(bottom = 10.dp)) {
                    Text(text = "${index + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                    Text(text = step, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
        Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)) {
            TextButton(text = "应用信息", onClick = { ModuleActions.openHostInfo(activity) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            TextButton(text = "打开汽水", onClick = { ModuleActions.openHost(activity) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun InfoCard(status: ModuleStatus) {
    Section("信息") {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoLine("适配", "官网汽水 " + ModuleStatus.HOST_VERSION + " / " + ModuleStatus.HOST_VERSION_CODE)
            InfoLine("宿主已加载", status.loadedVersion.ifEmpty { "尚未连接" })
            InfoLine("设备", Build.MODEL + " · Android " + Build.VERSION.RELEASE, last = true)
        }
    }
}

@Composable
private fun InfoLine(title: String, value: String, last: Boolean = false) {
    Text(text = title, fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface)
    Text(text = value, fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = if (last) 0.dp else 18.dp))
}
