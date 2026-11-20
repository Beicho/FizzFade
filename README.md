# 叠拍 FizzFade

叠拍原来叫「汽水 AutoMix」，是给汽水音乐接歌用的 LSPosed 模块。上一首慢慢淡出时，下一首的前奏就进来了。默认重叠 6 秒，在「混音」里可以调到 2–12 秒。

目前只适配 **汽水音乐官网版 21.6.0（100216030）**。手机需要 Android 9 或更高版本，以及支持传统 Xposed API 82 的 LSPosed。旧版汽水 AutoMix 可以直接覆盖安装。[下载 APK](https://github.com/Beicho/FizzFade/releases/latest)

![叠拍的混音设置、界面设置和深色模式](docs/images/settings.png)

## 装好以后

1. 在 LSPosed 里启用「叠拍 FizzFade」，作用域勾选「汽水音乐」。
2. 到系统设置里强行停止汽水音乐，再打开它。回到叠拍首页看连接状态。
3. 在「混音」里打开「自动交叉混音」，放一个歌单，让第一首自然播到结尾。手动按下一首或拖进度条会取消这次过渡。

想听对拍效果，可以挑两首鼓点清楚、速度接近的歌，把「智能过渡时机」「预渲染下一首」和「对拍与轻微变速」都打开。接上时，下一首会稍微加速或放慢，过渡结束后回到原速。有些歌找不到稳定的拍点，这时只做淡入淡出。想知道刚才那次有没有对上，可以看首页的对拍次数。

试播、广告、视频、单曲循环和睡眠定时都按汽水原来的方式播放。预渲染会提前准备下一首，可能多用一些流量。

## 歌词和播放页

这些开关都在「界面」，可以和混音分开用。逐字歌词页需要 Android 10 或更高版本。有逐字时间的歌会跟着人声一个字一个字亮起来，只有逐行时间的就整行高亮。打开「点歌词跳转」后，想重听哪句就点哪句。

播放页可以换圆角封面、用封面颜色做背景，也可以单独开封面下方的短歌词或底部的悬浮玻璃栏。改完设置如果看到重启提示，强行停止并重开汽水就行。

遇到问题可以[开个 issue](https://github.com/Beicho/FizzFade/issues)。写上 Android、LSPosed 和汽水的版本，再说说是哪一步出了问题。「更多」里可以导出日志，上传前记得看一眼里面的设备信息。

<details>
<summary>自己编译</summary>

装好 JDK 17、Android SDK Platform 36 和 Build Tools 36.0.0，设好 `JAVA_HOME` 和 `ANDROID_HOME`，在项目根目录运行：

```bash
./gradlew :app:assembleDebug
```

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。调试包用本机证书签名，无法覆盖安装 Releases 里的版本。

跑测试用下面两条命令。手头有适配版本的官网 APK，也可以用 Python 3 检查播放器接口。

```bash
bash scripts/test.sh
bash scripts/test-host.sh
python3 scripts/verify_host.py /path/to/qishui.apk --output host-contract.json
```

要用自己的证书打包，设置 `SIGNING_KEY`（密钥库绝对路径）、`SIGNING_ALIAS` 和 `SIGNING_STORE_PASSWORD`。如果密钥和密钥库的密码不同，再设 `SIGNING_KEY_PASSWORD`。运行 `bash scripts/build-gradle.sh`，签名 APK 和校验文件会放在 `dist/` 下。

</details>

叠拍与汽水音乐官方无关。原创代码采用 [MIT 许可](LICENSE)。玻璃效果来自 Kyant 的 AndroidLiquidGlass，部分图标来自 Google Material Symbols，详见[第三方声明](docs/THIRD_PARTY_NOTICES.md)。
