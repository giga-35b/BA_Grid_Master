# BA_Grid_Master 1.0.0 发布版基线

- 基线日期：2026-08-31。
- 应用显示名称：BA Grid Master。
- applicationId：`com.bagridmaster.app`。
- versionName：`1.0.0`；versionCode：`2`（此前为 `1`）。
- 预定仓库：`https://github.com/giga-35b/BA_Grid_Master`。此操作不创建或发布仓库。
- 冻结范围：当前识别、探索、屏幕捕获修复、相册备用识别、三页 UI、权限及文案调整。

## 备份内容

`backups/BA_Grid_Master-1.0.0-baseline-<时间>/` 是独立目录，后续构建不会覆盖。

- `BA_Grid_Master-1.0.0-source.zip`：应用源码、单元/UI 测试与测试资源、Gradle Wrapper、构建配置、
  文档、许可、数据集及原始素材。解压后保留原来的项目相对路径。
- `artifacts/`：Debug APK、未签名 Release APK、instrumentation 测试 APK、各自产物元数据。
- `verification/`：单元测试 XML、Lint 报告及构建配置/版本信息。
- `source-manifest.json`：每个源码归档文件的相对路径、长度与 SHA-256；校验压缩包所有条目后生成备份结果。
- `backup-manifest.json`：归档和产物的 SHA-256、大小，以及此次测试汇总和时间信息。

不打包 Gradle/IDE 缓存、中间构建目录、本机 SDK 路径 `local.properties`、任何签名私钥或已有备份。
数据集与素材仅作为本地可复现基线备份，不代表获得了公开发布其中游戏图片的授权。
工作区尚未初始化 Git，因此此备份不依赖 commit/tag，也不创建 Git 仓库。

## 验证与恢复

构建使用 JBR 21、Gradle 9.5.0、AGP 9.3.0、compileSdk 37.1 / targetSdk 37，Java 目标为 17。
执行单元测试、Debug/Release Lint、Debug APK、Release APK 及 instrumentation 测试 APK 构建。
具体测试数量和校验信息以备份目录内 `backup-manifest.json` 为准；UI 测试 APK 的编译不等于真机执行。

恢复时解压源码到新目录，重新配置本机 Android SDK 路径或环境变量；首次构建可能需要下载依赖。
用 Gradle Wrapper 重建应用。Gradle/SDK 缓存不属于源码备份，因而不承诺完全离线构建。

## 签名与发布边界

尚未配置正式发布签名；基线内 Debug APK 使用本机开发签名，仅用于验证。
Release APK 未签名，不能直接安装或作为最终发布包分发。正式分发前应由作者决定并妥善备份长期发布签名。
若发布签名不同于当前开发签名，则不能直接覆盖安装已有 Debug 版。
此次只更新版本、构建验证并备份，不安装手机、不上传 GitHub、不创建签名密钥。
