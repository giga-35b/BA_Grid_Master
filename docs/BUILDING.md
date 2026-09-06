# 从源码构建

当前版本为 1.1.6（versionCode 9）。以下命令在仓库根目录执行，不需要连接手机。

## 环境

- 本次验证使用 JBR/JDK 25.0.2（Java/Kotlin 编译目标为 17），设置 `JAVA_HOME`。
- Android SDK：设置 `ANDROID_HOME`，或通过 Android Studio 生成本机 `local.properties`。不要提交该文件。
- 安装 Android SDK Platform 37.1（`platforms;android-37.1`）及 Build Tools 37.0.0，并接受 SDK 许可。
- 使用项目自带 Gradle Wrapper 9.5.0；AGP 为 9.3.0。不需要全局安装 Gradle。
- 首次构建需要联网下载 Gradle、Google Maven / Maven Central 依赖；建议保留至少 3 GB 的 Gradle JVM 内存余量。

PowerShell 示例（路径按实际安装位置填写）：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin --no-daemon
```

macOS / Linux 使用相同环境变量，运行 `bash ./gradlew` 加上相同任务参数。

- Debug：`app/build/outputs/apk/debug/app-debug.apk`，由本机调试密钥签名。
- Release：`app/build/outputs/apk/release/app-release-unsigned.apk`，目前**未配置正式签名**，不能直接作为正式安装包分发。
- 单元测试：`app/build/reports/tests/testDebugUnitTest/index.html`。
- Lint：`app/build/reports/lint-results-debug.html`。
- `compileDebugAndroidTestKotlin` 只检查设备测试能否编译，不代表已在手机上通过。

## 非 debug 测试包与正式发布

`tools/Package-NonDebugTest.ps1` 仅供本机性能对比：把 unsigned release 的副本用调试密钥签名，并校验 non-debug 标志、签名、版本和对齐。先构建 Debug 和 Release，再在 PowerShell 7 中运行：

```powershell
.\tools\Package-NonDebugTest.ps1 -Variant nondebug-local
```

可显式传入 `-JavaPath`、`-BuildTools`、`-DebugKeyStore`。脚本默认从环境变量、PATH 和用户 `.android` 目录解析，不携带开发者本机路径。标准调试密钥密码 `android` 不是生产秘密，也不能用于正式发布。

正式发布前需另行确定签名方案，私钥和密码在仓库外保存并备份；本仓库不包含密钥、APK 或 AAB。将来正式签名后的 APK 放 GitHub Releases，不放源码提交。使用不同签名的版本通常不能直接覆盖安装；迁移时先备份需要的数据。

## 数据集与辅助工具

截图回归使用 `dataset/` 内的静态图片；视频不在公开源码内。详见 [数据集说明](../dataset/README.md)。

`tools/Record-PhoneScreen.ps1` 需另行安装官方 scrcpy 并提供 `-ScrcpyPath`（或加入 PATH）；adb 从 Android SDK 或 `-AdbPath` 读取。其他手机诊断脚本也需要显式设备参数。它们**不参与普通构建**，不要在没有用户授权的情况下运行；`Backup-PhonePerformanceState.ps1` 是针对历史性能调查的专项脚本，不是通用手机备份工具。

`tools/Backup-CurrentVersion.ps1` 是本地完整版本备份，依赖已经校验的非 debug 测试 APK 和测试报告；不是公开源码导出工具。`Backup-ReleaseBaseline.ps1` 仅用于历史 1.0.0 基线。

`tools/Prepare-PublicSource.ps1` 创建一份遵守项目 `.gitignore` 的独立源码快照及 SHA-256 清单，保存在忽略的 `build/` 下。不会初始化当前工程的 Git 仓库，不会提交或上传。可在快照目录重复上述构建，检查是否依赖未提交的本机文件。
