# 测试

所有示例名称、编号、地址和账号均用于测试。不要使用个人共享、来源凭据或真实媒体作为公开测试数据。

## 单元测试

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

默认测试覆盖索引、包装目录、跨来源身份、录音版本、字幕匹配、播放队列、精确定位、进度写入、循环调度、缓存、WebDAV 和 MP3 元数据。真实 Samba 测试未配置时跳过。

## 生成媒体样本

样本脚本不读取媒体库。它使用 FFmpeg 生成约 31 分钟的静音 MP3，并生成中性纯色封面、字幕和文本。MP3 约 71 MiB，用于长距离跳转和 40 MB 偏移读取。

```sh
sudo apt-get install ffmpeg
python3 tools/prepare-device-fixture.py --output fixtures/device
```

`fixtures/` 已由 Git 排除。使用新的输出目录，脚本不会覆盖既有样本。

SMB 偏移测试另需一个超过 4 GiB 的稀疏文件：

```sh
python3 tools/prepare-device-fixture.py --output fixtures/samba --smb
```

稀疏文件在支持稀疏存储的文件系统上仅占少量磁盘空间；复制到其他文件系统可能分配完整容量。

## 真实 Samba 合同测试

将生成的 Samba 样本放在专用只读测试共享中。配置保存在 Git 排除的本地文件，例如 `.signing/smb-test.properties`，字段为：

| 字段 | 用途 |
| --- | --- |
| `root` | 测试 SMB 来源地址 |
| `username` | 测试账号，可包含域 |
| `password` | 测试账号密码 |
| `fixture` | 同一生成样本在测试主机上的目录 |

通过环境变量 `WHISP_SMB_TEST_CONFIG` 指向该文件，然后运行 `SmbServerTest`。测试验证鉴权、目录扫描、随机读取、EOF、超过 4 GiB 的偏移、MP3 时长索引、复用会话和错误密码；不要提交配置文件或测试服务器日志。

## 隔离设备测试

为避免影响日常安装的应用，性能、系统媒体、动效和存储测试使用独立的 `app.whisp.smoketest` 包。

```sh
./gradlew -PsmokeTest :app:assembleSmoke :app:assembleSmokeAndroidTest
adb install -r app/build/outputs/apk/smoke/app-smoke.apk
adb install -r app/build/outputs/apk/androidTest/smoke/app-smoke-androidTest.apk
adb shell am instrument -w -e class app.whisp.PerformanceIntegrationTest app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

每个测试类别使用新的隔离应用数据；只在专用测试设备或模拟器上清理 `app.whisp.smoketest`，不要清理正式包。界面测试需要设备解锁并保持测试应用在前台。当前 ABI 配置适用于 arm64 设备或 arm64 模拟器。

`PerformanceIntegrationTest`、`SystemMediaIntegrationTest`、`MotionNavigationIntegrationTest` 和 `StorageIntegrationTest` 的样本由测试自行生成。需要实际 SMB 的 `SmbIntegrationTest`、`PlaybackFlowIntegrationTest` 和 `StorageAudioIntegrationTest` 分别从 instrumentation 参数读取专用测试共享；详情见各测试源码，凭据不内置。

## WebDAV 与本地目录选择器测试

这两项测试使用 Debug 包和预先推送的生成样本，只在专用测试设备运行。它们会建立测试索引，本地目录选择器测试会创建 `Download/WhispFixture`。目录选择器测试目前使用英文系统按钮文字。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 tools/prepare-device-fixture.py --output fixtures/device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push fixtures/device /data/local/tmp/whisp-fixture
adb shell run-as app.whisp mkdir -p files/fixture
adb shell run-as app.whisp cp -r /data/local/tmp/whisp-fixture/. files/fixture/
adb shell am instrument -w -e class app.whisp.PlaybackIntegrationTest app.whisp.test/androidx.test.runner.AndroidJUnitRunner
```

WebDAV 测试在设备内启动带测试鉴权和 Range 的本机服务。生产网络配置仅对 localhost 与 Android 模拟器测试地址允许 HTTP。运行 `LocalImportIntegrationTest` 可验证系统目录授权和持久读取权限；不同测试之间应使用干净的专用测试数据。

## 提交前检查

```sh
python3 tools/check-public-content.py
git diff --cached --check
git diff --cached
```

只上传项目目录。个人调试日志、外部设计稿、发布密钥、设备备份和私人媒体不属于仓库。
