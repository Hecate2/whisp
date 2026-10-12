# 工程质量

## 检查入口

Kotlin 和 Gradle 脚本使用 Spotless 8.10.4 与 ktfmt 0.65 的 Kotlin 官方风格，Python 工具使用 Black 26.10.1。编辑器约定见 `.editorconfig`。

```sh
sudo apt-get install python3-venv
python3 -m venv .venv
.venv/bin/python -m pip install black==26.10.1
. .venv/bin/activate
export WHISP_BLACK="$PWD/.venv/bin/black"
./gradlew fmt
./gradlew qualityCheck
./gradlew :app:assembleRelease :app:assembleDebugAndroidTest
```

`fmt` 统一排版，`qualityCheck` 执行格式检查、公开内容检查、单元测试及 Debug / Release Lint。GitHub Actions 对推送和 Pull Request 执行同一套检查，并编译 Release 和设备测试 APK。

## 依赖版本

2026-10-12 按 [Google Maven](https://maven.google.com/) 和 [Maven Central](https://central.sonatype.com/) 发布信息升级直接依赖，版本在构建脚本中固定。常规组件采用最新稳定版；Material 3 使用当前 Beta 的动效接口。

| 组件 | 版本 |
| --- | --- |
| Gradle / AGP | 9.8.1 / 9.4.1 |
| Kotlin / KSP | 2.4.21 / 2.3.12 |
| Compose BOM / Material 3 | 2026.09.00 / 1.5.0-beta01 |
| Activity / Core | 1.13.0 / 1.19.1 |
| Lifecycle / Navigation 3 | 2.11.0 / 1.2.0 |
| Media3 / SMBJ | 1.11.1 / 0.15.0 |
| Room / DataStore / WorkManager | 2.8.5 / 1.2.1 / 2.12.0 |
| Coil / OkHttp / Okio | 3.6.3 / 5.5.0 / 3.18.2 |
| Coroutines / kotlinx.serialization / Robolectric | 1.11.0 / 1.11.0 / 4.17 |
| AndroidX Test Runner / Rules / JUnit 扩展 | 1.7.0 / 1.7.0 / 1.3.0 |
| UI Automator / JUnit 4 / MockWebServer 3 | 2.4.0 / 4.13.2 / 5.5.0 |

编译 SDK 为 API 37，目标 SDK 为 API 36，最低系统为 API 26。构建使用 JDK 21 与 Build Tools 36.0.0，对应 [AGP 9.4 的兼容范围](https://developer.android.com/build/releases/agp-9-4-0-release-notes)。

Room 与 WorkManager 的扩展 API 随主模块提供。测试服务器使用 MockWebServer 3；播放器滑块和面板使用新版 Material 3 状态接口。

## 实现分工

| 能力 | 实现 |
| --- | --- |
| WebVTT 时间、标签、实体、注释及重叠字幕 | Media3 `WebvttParser` |
| 字幕缓存 | Android `LruCache` |
| SHA-256 与十六进制编码 | Okio `ByteString`，保留原 128 位截断格式 |
| 时间定位查找 | Kotlin 标准库 `binarySearch` |
| ViewModel 创建 | AndroidX `viewModelFactory` |
| 文件原子替换 | Java NIO `Files.move` |
| HTTP / SMB 传输 | OkHttp / SMBJ |
| 播放、下载与音频缓存 | Media3 |
| 索引、设置与任务 | Room / DataStore / WorkManager |

应用负责作品目录识别、录音分组、字幕阅读锚点、精细定位手势和按作品合并的进度写入。LRC 解析服务于多时间标签与偏移量；MP3 扫描通过少量分段请求读取时长索引，音频解码由 Media3 负责。

在线播放缓存的淘汰器与图片文件缓存负责运行时容量调整、永久资料分区及文件变化通知。图片优化使用 Android Bitmap API，编码参数与容量限制由应用定义。

## API 与兼容范围

数据库字段和内部 ID 格式保持兼容。新测试覆盖既有 ID 格式、WebVTT 注释与样式、重叠字幕、重复时间点和极大时间位置。

Material 3 固定为 `1.5.0-beta01`，当前动效配置使用该版本公开的 `MotionScheme` 接口；共享元素转场使用 Compose 的实验 API。对应功能通过界面回归验证。系统版本分支承担 Android 8–16 的兼容处理，`smoke` 构建承担独立设备测试。

目标 API 36 对应当前系统行为范围，Lint 提示 API 37 的目标版本适配。ARMv8 构建范围对应架构提示，导出的媒体服务通过 `MediaLibrarySession.onConnect` 验证控制器身份。

Kotlin 编译器将警告视为错误。Gradle 9.8 的弃用报告中，`Configuration.setVisible` 来自 AGP 9.4.1 内部配置；后续 AGP 升级继续追踪该上游兼容提示。
