package io.github.qishui.automix.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.qishui.automix.SettingsSchema
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun MixPage(store: SettingsStore, bottomPadding: Dp) {
    val master = SettingsSchema.find("enabled")!!
    PageScaffold(title = "混音", bottomPadding = bottomPadding) {
        item(key = "master") {
            Section {
                ToggleRow(store, master) { IconTile(MiuixIcons.Merge, Tint.Blue) }
            }
        }
        // With the mix off, nothing below can take effect: the page folds to the master switch.
        item(key = "duration") { Folding(visible = store.isOn(master.key)) { DurationSection(store) } }
        item(key = "smart") {
            Folding(visible = store.isOn(master.key)) {
                Section("智能") {
                    for (toggle in SettingsSchema.group(SettingsSchema.Group.MIX)) {
                        if (toggle.key != master.key) ToggleRow(store, toggle)
                    }
                }
            }
        }
        item(key = "note") {
            Footnote("对拍与变速为实验功能，不等同于 Apple Music 的专有算法。广告、试播、视频、单曲循环和睡眠定时沿用原播放；暂停、拖动或切歌会立即退出过渡。")
        }
    }
}

@Composable
private fun DurationSection(store: SettingsStore) {
    Section("过渡") {
        BasicComponent(
            title = "过渡时长",
            summary = "两首歌重叠的时间，智能时机会再微调",
            startAction = { IconTile(MiuixIcons.Tune, Tint.Indigo) },
            endActions = {
                Text(
                    text = "${store.duration / 1000} 秒",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
            bottomAction = {
                Slider(
                    value = store.duration / 1000f,
                    onValueChange = { store.previewDuration((it.roundToInt()) * 1000) },
                    onValueChangeFinished = { store.commitDuration() },
                    valueRange = SettingsSchema.DURATION_MIN / 1000f..SettingsSchema.DURATION_MAX / 1000f,
                    steps = (SettingsSchema.DURATION_MAX - SettingsSchema.DURATION_MIN) / 1000 - 1,
                    showKeyPoints = true,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                )
            },
        )
    }
}
