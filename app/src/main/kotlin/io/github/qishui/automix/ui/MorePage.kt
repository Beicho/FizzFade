package io.github.qishui.automix.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.qishui.automix.ModuleActions
import io.github.qishui.automix.ModuleStatus
import io.github.qishui.automix.SettingsActivity
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MorePage(store: SettingsStore, bottomPadding: Dp) {
    val activity = LocalContext.current as Activity
    var confirmClear by remember { mutableStateOf(false) }
    var clearHeld by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) ModuleActions.toast(activity, if (ModuleActions.writeExport(activity, uri)) "日志已导出" else "导出失败，请改用分享")
    }
    PageScaffold(title = "更多", bottomPadding = bottomPadding) {
        item(key = "logs") {
            Section("诊断日志") {
                val kb = store.logChars / 1024
                BasicComponent(
                    title = if (store.logChars == 0) "暂无日志" else "已保存约 ${maxOf(1, kb)} KB",
                    summary = "上限 ${ModuleActions.logCapacityKb()} KB，也写入 LSPosed 日志",
                    startAction = { IconTile(MiuixIcons.ListView, Tint.Teal) },
                )
                SuperArrow(
                    title = "导出文件",
                    summary = "含设备、版本、设置和完整日志",
                    startAction = { IconTile(MiuixIcons.Download, Tint.Blue) },
                    onClick = {
                        export.launch("FizzFade-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")
                    },
                )
                SuperArrow(
                    title = "分享",
                    startAction = { IconTile(MiuixIcons.Share, Tint.Green) },
                    onClick = { ModuleActions.share(activity) },
                )
                SuperArrow(
                    title = "清空日志",
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                    startAction = { IconTile(MiuixIcons.Delete, Tint.Red) },
                    holdDownState = clearHeld,
                    onClick = {
                        confirmClear = true
                        clearHeld = true
                    },
                )
            }
            SuperDialog(
                show = confirmClear,
                title = "清空诊断日志？",
                summary = "只清空模块保存的日志，LSPosed 日志不受影响。",
                onDismissRequest = { confirmClear = false },
                onDismissFinished = { clearHeld = false },
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(text = "取消", onClick = { confirmClear = false }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = "清空",
                        onClick = {
                            ModuleActions.clearLog(activity)
                            store.refresh()
                            confirmClear = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
        item(key = "host") {
            Section("汽水") {
                SuperArrow(
                    title = "打开汽水",
                    startAction = { IconTile(MiuixIcons.Music, Tint.Orange) },
                    onClick = { ModuleActions.openHost(activity) },
                )
                SuperArrow(
                    title = "应用信息",
                    summary = "强行停止后重新打开，让修改生效",
                    startAction = { IconTile(MiuixIcons.Settings, Tint.Gray) },
                    onClick = { ModuleActions.openHostInfo(activity) },
                )
            }
        }
        item(key = "look") {
            Section("外观") {
                SuperSwitch(
                    title = "壁纸取色",
                    summary = "用系统壁纸的颜色代替默认蓝色",
                    startAction = { IconTile(MiuixIcons.Background, Tint.Purple) },
                    checked = store.monet,
                    onCheckedChange = { store.updateMonet(it) },
                )
                SuperSwitch(
                    title = "毛玻璃",
                    summary = if (android.os.Build.VERSION.SDK_INT >= 31) "顶栏和底栏透出页面，关闭省电" else "需要 Android 12 及以上",
                    startAction = { IconTile(MiuixIcons.Layers, Tint.Indigo) },
                    checked = store.blur,
                    onCheckedChange = { store.updateBlur(it) },
                    enabled = android.os.Build.VERSION.SDK_INT >= 31,
                )
                SuperArrow(
                    title = "简易界面",
                    summary = "只用系统控件的备用设置页",
                    startAction = { IconTile(MiuixIcons.Forward, Tint.Gray) },
                    onClick = { activity.startActivity(Intent(activity, SettingsActivity::class.java)) },
                )
            }
        }
        item(key = "about") {
            Section("关于") {
                BasicComponent(
                    title = "叠拍 FizzFade " + ModuleStatus.MODULE_VERSION,
                    summary = "仅适配官网汽水 " + ModuleStatus.HOST_VERSION + "；没有网络、存储或无障碍权限",
                    startAction = { IconTile(MiuixIcons.Info, Tint.Blue) },
                )
                BasicComponent(
                    title = "开源许可",
                    summary = "本模块 MIT；界面使用 miuix、Haze（Apache-2.0）；底栏玻璃移植自 Kyant AndroidLiquidGlass（Apache-2.0）；图标含 Material Symbols（Apache-2.0）",
                )
            }
        }
        item(key = "note") {
            Footnote("汽水音乐与 Apple Music 相关商标归各权利人。本模块不修改会员、试听和广告权益逻辑。")
        }
    }
}
