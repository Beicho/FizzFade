package io.github.qishui.automix.ui

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import io.github.qishui.automix.ModuleActions
import io.github.qishui.automix.SettingsSchema
import top.yukonga.miuix.kmp.extra.SuperArrow

private val groups = listOf(
    SettingsSchema.Group.LYRICS to "歌词",
    SettingsSchema.Group.PLAYER to "播放页",
    SettingsSchema.Group.TAB to "底栏",
    SettingsSchema.Group.TRIM to "精简",
    SettingsSchema.Group.PERF to "流畅度",
)

@Composable
fun InterfacePage(store: SettingsStore, bottomPadding: Dp) {
    val activity = LocalContext.current as Activity
    val previews = Build.VERSION.SDK_INT >= 29
    PageScaffold(title = "界面", bottomPadding = bottomPadding) {
        for ((group, title) in groups) {
            item(key = group.name) {
                Section(title) {
                    for (toggle in SettingsSchema.group(group)) ToggleRow(store, toggle)
                    if (previews && group == SettingsSchema.Group.LYRICS) {
                        SuperArrow(title = "预览歌词效果", summary = "原创演示歌词，不需要打开汽水",
                            onClick = { ModuleActions.previewLyrics(activity) })
                    }
                    if (previews && group == SettingsSchema.Group.PLAYER) {
                        SuperArrow(title = "预览播放页效果", summary = "点封面切换播放与暂停",
                            onClick = { ModuleActions.previewPlayer(activity) })
                    }
                }
            }
        }
        item(key = "note") {
            Footnote("部分选项要重启汽水才生效，改动后底栏上方会提示。界面功能出错时自动退回汽水原生界面。")
        }
    }
}
