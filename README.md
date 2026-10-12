# Whisp

Whisp 是面向本地文件和 NAS 的 Android 音频播放器，适合音声、ASMR 和其他带字幕的长音频。媒体保留在用户选择的位置，导入只建立索引；离线下载由用户主动触发。

## 功能

- 本地目录授权导入、SMB 2/3 和 HTTPS WebDAV 来源。
- 作品库、系列、收藏、搜索，以及音轨、台本和附赠资料。
- 后台播放、断点续听、书签、A–B 循环、睡眠定时和精确跳转。
- VTT / LRC 字幕、同名 TXT 台本；固定阅读锚点和手动翻阅后的恢复跟随。
- 音轨按目录及正篇／附赠分组，不自动混合不同录音版本。
- 离线下载、可调节的在线播放缓存上限和独立图片缓存。
- 系统媒体控制、系统续播、通知封面隐私和最近任务预览隐私。
- 共享封面转场、系统返回手势和减少动态效果设置。

需要 Android 8.0 或更新系统。当前构建只包含 `arm64-v8a`（ARMv8 64 位）。

## 文件组织

文件识别支持普通作品目录和带编号的作品目录；以下编号与名称均为合成示例。

```text
示例系列/
  RJ00000001 测试作品/
    音频/
      01_示例音轨.mp3
      01_示例音轨.mp3.vtt
    台本/
      01_示例音轨.txt
    特典/
      封面.png
```

支持 MP3、M4A、FLAC、WAV、Ogg、Opus 和 AAC，实际解码能力取决于设备。同编号的重复包装目录折叠为一个作品，不同编号的嵌套作品分别识别。字幕支持双扩展名或同名文件。中文、日文及其他 Unicode 文件名均可使用。

SMB 地址示例为 `smb://nas.example/media/Audio/`，可包含自定义端口。账号支持 `用户名` 和 `域\用户名`。WebDAV 使用 HTTPS。服务器、共享、目录与账号由用户在应用中填写，没有内置来源。

## 构建

项目使用 Kotlin、Jetpack Compose、Navigation 3、Media3、Room、DataStore、WorkManager、Coil、OkHttp 和 SMBJ。依赖版本固定在 Gradle 配置中；Material 3 使用 `1.5.0-alpha06` 的公开 MotionScheme API。

需要 JDK 21、Android SDK Platform 36、Build Tools 36.0.0。Gradle Wrapper 固定为 9.1.0。在 Android Studio 配置 SDK，或通过 `ANDROID_HOME` 指定 SDK；机器上的 `local.properties` 不纳入仓库。

Debian 上可通过 apt 安装基础工具：

```sh
sudo apt-get install openjdk-21-jdk-headless adb
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Android SDK 需自行安装相应平台。Debug APK 位于 `app/build/outputs/apk/debug/`。

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

Release 启用 R8 与资源裁剪。未配置签名时生成未签名 APK。可在本机的 `.signing/signing.properties` 中提供 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，其中 `storeFile` 相对 `.signing/`。该目录及密钥文件均由 Git 排除，不提供公共签名或示例密码。

## 测试与公开内容检查

单元测试使用合成目录、虚构名称、生成的字节和本机模拟服务，不依赖个人媒体库。真实 Samba 合同测试需主动配置测试共享；设备集成测试按类别准备生成样本或使用隔离测试包。具体步骤见 [测试说明](docs/TESTING.md)。

```sh
python3 tools/check-public-content.py
```

检查工具扫描 Git 候选文件和暂存内容，拒绝个人机器路径、局域网地址、常见密钥格式和媒体等不适合提交的文件。检查不能替代人工审阅；提交前仍应检查 `git diff --cached`。仓库不包含来源账号、媒体、截图、设备日志、签名或本地构建缓存。

## 行为与限制

- 来源凭据通过 Android Keystore 加密保存在设备上，不写入音轨 URL。应用关闭系统备份。
- SMB 使用只读操作，支持 SMB2/3；不支持 SMB1、DFS 转介或 NFS。SMB3 加密取决于服务端协商结果。
- 保留原左右声道，不默认混音。A–B 循环通过播放器跳转实现，不保证采样级无缝。
- 台本与字幕按 UTF-8 读取。图片可预览，PDF 阅读器尚未实现。
- 睡眠定时使用单调时钟，暂停仍计时；服务被终止后取消，不在设备重启后自动播放。
- 播放进度通常每 15 秒保存，暂停、切曲与手动跳转及时提交；突然终止可能丢失最近约 15 秒。
- 只有前台播放页更新播放时钟；下载进度与存储统计按需更新。尚未量化这些调度优化的实际耗电降幅。
- Android 14 及更新系统提供原生音频输出选择器；旧系统使用兼容入口。通知和系统历史媒体卡片的显示也受系统行为影响。

## 许可证

Whisp 项目代码采用 [GNU LGPL v3 或更新版本](LICENSE)，SPDX 标识为 `LGPL-3.0-or-later`。版权与授权声明见 [NOTICE](NOTICE)；LGPL 引用的 GPL v3 正文见 [COPYING](COPYING)。Gradle Wrapper 和第三方依赖保留各自许可证。
