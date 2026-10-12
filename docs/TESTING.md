# 测试

公开测试数据使用示例名称、编号、地址和账号，媒体样本由脚本生成，网络测试使用专用共享。

## 单元测试

格式、测试与 Lint 的统一入口见 [工程质量](QUALITY.md)。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

默认测试覆盖索引、包装目录、跨来源身份、录音版本、字幕匹配、播放队列、精确定位、进度写入、循环调度、缓存、WebDAV 和 MP3 元数据。配置专用共享后，可通过 `WHISP_SMB_TEST_CONFIG` 启用真实 Samba 合同测试。

## 生成媒体样本

样本脚本使用 FFmpeg 生成约 31 分钟的静音 MP3，并生成纯色封面、字幕和文本。MP3 约 71 MiB，用于长距离跳转和 40 MB 偏移读取。

```sh
sudo apt-get install ffmpeg
python3 tools/prepare-device-fixture.py --output fixtures/device
```

每次生成使用新的输出目录，已有样本保留在原目录。`fixtures/` 由 Git 排除。

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

通过环境变量 `WHISP_SMB_TEST_CONFIG` 指向该文件，然后运行 `SmbServerTest`。测试覆盖鉴权、目录扫描、随机读取、EOF、超过 4 GiB 的偏移、MP3 时长索引、复用会话和错误密码处理。配置与服务器日志作为本机测试资料保存。

## 隔离设备测试

性能、系统媒体、动效和存储测试使用独立的 `app.whisp.smoketest` 包，测试数据由该包管理。

```sh
./gradlew -PsmokeTest :app:assembleSmoke :app:assembleSmokeAndroidTest
adb install -r app/build/outputs/apk/smoke/app-smoke.apk
adb install -r app/build/outputs/apk/androidTest/smoke/app-smoke-androidTest.apk
adb shell am instrument -w -e class app.whisp.PerformanceIntegrationTest app.whisp.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

每个测试类别使用新的隔离应用数据，清理目标为专用测试设备或模拟器上的 `app.whisp.smoketest`。界面测试在设备解锁、测试应用处于前台时运行。常规构建的通用安装包覆盖四种 ABI，可安装到对应设备或模拟器。

`PerformanceIntegrationTest`、`SystemMediaIntegrationTest`、`MotionNavigationIntegrationTest` 和 `StorageIntegrationTest` 的样本由测试自行生成。`SmbIntegrationTest`、`PlaybackFlowIntegrationTest` 和 `StorageAudioIntegrationTest` 使用专用 SMB 共享，地址与测试凭据通过 instrumentation 参数传入；参数定义见各测试源码。

## WebDAV 与本地目录选择器测试

这两项测试在专用测试设备上运行，使用 Debug 包和预先推送的生成样本。测试会建立媒体索引，本地目录选择器测试会创建 `Download/WhispFixture`。目录选择器测试使用英文系统按钮文字。

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

WebDAV 测试在设备内启动带测试鉴权和 Range 的本机服务。网络配置为远程 WebDAV 使用 HTTPS，为 localhost 与 Android 模拟器测试地址提供 HTTP。运行 `LocalImportIntegrationTest` 可验证系统目录授权和持久读取权限；各测试使用独立的专用测试数据。

## 提交前检查

```sh
python3 tools/check-public-content.py
git diff --cached --check
git diff --cached
```

提交范围为项目源码、构建配置、文档和合成测试数据。调试日志、设计资料、发布密钥、设备备份及媒体由本机管理。
