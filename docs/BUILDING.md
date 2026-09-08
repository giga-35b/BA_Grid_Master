# 从源码构建

当前版本为 1.2.1（versionCode 11）。以下命令在仓库根目录执行，不需要连接手机。

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
- Release：未提供签名环境变量时输出 `app/build/outputs/apk/release/app-release-unsigned.apk`；正式发布请使用下述脚本。
- 单元测试：`app/build/reports/tests/testDebugUnitTest/index.html`。
- Lint：`app/build/reports/lint-results-debug.html`。
- `compileDebugAndroidTestKotlin` 只检查设备测试能否编译，不代表已在手机上通过。

## 非 debug 测试包与正式发布

`tools/Package-NonDebugTest.ps1` 仅供本机性能对比：把 unsigned release 的副本用调试密钥签名，并校验 non-debug 标志、签名、版本和对齐。先构建 Debug 和 Release，再在 PowerShell 7 中运行：

```powershell
.\tools\Package-NonDebugTest.ps1 -Variant nondebug-local
```

可显式传入 `-JavaPath`、`-BuildTools`、`-DebugKeyStore`。脚本默认从环境变量、PATH 和用户 `.android` 目录解析，不携带开发者本机路径。标准调试密钥密码 `android` 不是生产秘密，也不能用于正式发布。

正式发布使用 `tools/Build-SignedRelease.ps1`。脚本交互式隐藏输入密钥库密码和密钥密码，只在当前构建进程中临时设置以下变量，并在结束时恢复；密码不会写入项目文件：

- `BA_GRID_MASTER_KEYSTORE_FILE`
- `BA_GRID_MASTER_KEYSTORE_PASSWORD`
- `BA_GRID_MASTER_KEY_ALIAS`
- `BA_GRID_MASTER_KEY_PASSWORD`

密钥库必须放在仓库外。PowerShell 示例（路径按实际位置填写）：

```powershell
.\tools\Build-SignedRelease.ps1 `
  -KeyStore 'C:\secure\ba-grid-master-release.jks' `
  -Alias 'ba_grid_master_release' `
  -JavaHome 'C:\path\to\jdk-25'
```

脚本会依次运行单元测试、Lint 和 Release 构建，再校验包名、版本、non-debug/testOnly 状态、ZIP 对齐，以及正式证书 SHA-256。成品和验证记录写入忽略的 `app/build/outputs/apk/production-release/`。本仓库不包含密钥、APK 或 AAB；正式 APK 放 GitHub Releases，不放源码提交。使用不同签名的版本通常不能直接覆盖安装，迁移时先备份需要的数据。

## 数据集与辅助工具

截图回归使用 `dataset/` 内的静态图片；视频不在公开源码内。详见 [数据集说明](../dataset/README.md)。

`tools/Record-PhoneScreen.ps1` 需另行安装官方 scrcpy 并提供 `-ScrcpyPath`（或加入 PATH）；adb 从 Android SDK 或 `-AdbPath` 读取。其他手机诊断脚本也需要显式设备参数。它们**不参与普通构建**，不要在没有用户授权的情况下运行；`Backup-PhonePerformanceState.ps1` 是针对历史性能调查的专项脚本，不是通用手机备份工具。

`tools/Backup-CurrentVersion.ps1` 是本地完整版本备份，依赖已经校验的非 debug 测试 APK 和测试报告；传入 `-Purpose release-baseline` 可标记正式发布候选基线。它不是公开源码导出工具。`Backup-ReleaseBaseline.ps1` 仅用于历史 1.0.0 基线。

`tools/Prepare-PublicSource.ps1` 创建一份遵守项目 `.gitignore` 的独立源码快照及 SHA-256 清单，保存在忽略的 `build/` 下。不会初始化当前工程的 Git 仓库，不会提交或上传。可在快照目录重复上述构建，检查是否依赖未提交的本机文件。
