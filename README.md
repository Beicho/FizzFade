# 叠拍 FizzFade

面向汽水音乐的 Android / LSPosed 模块。上一首还在收尾，下一首已经进场。两首歌的重叠时间由你设定，默认 6 秒，可调到 2–12 秒。

**[下载 APK](https://github.com/Beicho/FizzFade/releases/latest)** · [安装与试听](#安装与试听) · [源码构建](#源码构建) · [MIT 许可](LICENSE)

![混音、界面和深色模式下的混音设置，截自 Android 模拟器](docs/images/settings.png)

## 听起来有什么变化

| 功能 | 播放时的表现 |
| --- | --- |
| 双轨过渡 | 当前曲淡出，下一首淡入。打开智能时机后，模块会根据尾奏的响度和下一首的起音调整交接点。 |
| 对拍与轻微变速 | 两首歌都有可信拍点、速度又接近时，尝试对齐节拍。变速范围为 0.92–1.08 倍，过渡结束后回到原速。条件不足时使用普通交叉淡化。 |
| 逐字歌词 | 有逐字时间的歌词随演唱点亮，只有逐行时间的歌词按行高亮。可以点一行跳到那句，也可以单独开启封面取色背景。 |
| 播放页与底栏 | 可选圆角封面、暂停缩放、短歌词和悬浮玻璃底栏。混音与界面效果分别设置。 |

## 安装与试听

1. 准备 Android 9 或更高版本、支持传统 Xposed API 82 的 LSPosed 环境，以及**官网签名的汽水音乐 21.6.0（100216030）**。模块会核对包名、版本和签名。逐字歌词页需要 Android 10 或更高版本。
2. 从 [Releases](https://github.com/Beicho/FizzFade/releases/latest) 安装 APK。在 LSPosed 启用「叠拍 FizzFade」，作用域勾选汽水音乐（`com.luna.music`）。原版汽水 AutoMix 用户可直接覆盖安装。
3. 强行停止汽水音乐并重新打开，在叠拍首页确认模块已连接。在「混音」开启自动交叉混音，以默认 6 秒听完一首歌的结尾。
4. 想试对拍时，再打开智能过渡时机、预渲染下一首和对拍与轻微变速。选两首节奏稳定、速度接近的完整歌曲，自然播放到交接处。首页可分别查看完成过渡和确认对拍的次数。

暂停、拖进度或手动切歌会取消正在进行的过渡。广告、试播、视频、单曲循环和睡眠定时沿用汽水自身行为。双轨重叠及预渲染会增加一段时间的解码负载，并可能增加流量。

**遇到问题**　首页提示模块版本过旧时，强行停止并重开汽水。反馈时附上 Android、LSPosed 和汽水版本，以及问题发生时的操作。「更多」可以导出诊断日志，公开分享前请检查其中的设备信息。

## 源码构建

需要 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0。设置 `JAVA_HOME` 和 `ANDROID_HOME` 后运行以下命令。调试包位于 `app/build/outputs/apk/debug/app-debug.apk`，使用本地调试证书。

```bash
./gradlew :app:assembleDebug
bash scripts/test.sh
bash scripts/test-host.sh
```

要生成可分发的签名 APK，另需 Python 3。通过环境变量提供 `SIGNING_KEY`（密钥库绝对路径）、`SIGNING_ALIAS`、`SIGNING_STORE_PASSWORD`；密钥口令不同时另设 `SIGNING_KEY_PASSWORD`。运行 `bash scripts/build-gradle.sh`，APK 和校验文件输出到 `dist/`。自行签名的构建与公开版使用不同证书时，Android 会拒绝覆盖安装。

### 代码入口

| 位置 | 内容 |
| --- | --- |
| [MixManager.java](app/src/main/java/io/github/qishui/automix/MixManager.java) | 协调下一首预渲染、队列交接和双轨音量 |
| [BeatMatch.java](app/src/main/java/io/github/qishui/automix/BeatMatch.java) | 判断节拍匹配条件并计算变速与对齐位置 |
| [设置界面](app/src/main/kotlin/io/github/qishui/automix/ui) | 混音、歌词与播放页开关，以及状态和诊断 |
| [测试](tests) | 核心逻辑、宿主替身、播放器与歌词模型 |

宿主适配可以用自行取得的官网 APK 核验。运行 `python3 scripts/verify_host.py /path/to/qishui.apk --output host-contract.json`，即可检查模块依赖的 102 项 DEX 声明。JVM 回归覆盖播放线程、暂停、过渡取消及音频缓冲区，但对拍听感和长时间后台稳定性仍需在目标手机上验证。

## 许可与致谢

原创代码沿用 [MIT License](LICENSE)。玻璃折射效果移植自 Kyant 的 AndroidLiquidGlass，部分图标来自 Google Material Symbols，归属及许可证见[第三方声明](docs/THIRD_PARTY_NOTICES.md)。叠拍是独立社区项目，与汽水音乐官方无隶属关系。
