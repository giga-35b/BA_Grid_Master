# 非 Debug 性能对照包（2026-08-31）

产物：`app/build/outputs/apk/nondebug-test/BA_Grid_Master-1.0.0-nondebug-test.apk`

- 当前同代码 Release 构建；没有修改识别算法、阈值、UI、默认设置或冻结发布基线。
- `BuildConfig.DEBUG=false`，二进制清单未启用 `debuggable` / `testOnly`。
- 包名 `com.bagridmaster.app`，版本仍为1.0.0 / versionCode 2。
- 使用现有 Debug APK 的同一测试证书签名，可覆盖原测试版，不需要卸载或清除数据。此为**非 Debug 构建 + 测试签名**，不是正式发布签名包。
- 本轮只生成文件，未安装到手机。正式 Release 的 Gradle 签名配置仍为空，未将测试密钥写入生产配置。

## 验证

- Gradle：`:app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:signingReport` 成功，复用未变化源码的构建/测试缓存。
- 单元测试结果180项，0失败、0错误、0跳过；Release lint为0错误、3项原有警告。
- 工程没有 `testReleaseUnitTest` 任务，未宣称运行过 Release 专属单元测试。
- 签名工具校验通过，APK Signature Scheme v2/v3有效；证书与此前完整Debug包一致。
- APK 16KiB native-library / 4字节 ZIP 对齐检查通过。
- 构建产物目录内保存签名、二进制清单、badging、对齐报告及 `verification.json`。

大小：53568234字节（约51.1MiB）。

SHA256：

```text
D36F663955B48E0DEDC1C7486045B152F46D190A53A09FB6164C9C9996ED2CD7
```

证书SHA256：

```text
01baa55d5d51b9d5d9cf73396f141a41fe7d0644a110e2ffeaff4884c47dcd39
```

签名前Release输入仍为 `DF25B561E42F2FB50B82CAC2DFB15556A52B51996BFAF182875609C40C8C1A6B`；原Debug APK仍为 `9847494D4CDFF3BEB6B38078A3A151B21479724E8177143D013194858BAD0A87`，未被覆盖。

## 用户对照流程

1. 覆盖安装本APK，不卸载。重新打开应用；如悬浮助手停止，按原方式启动并授权需要的捕获会话。
2. 保持与之前相同的识别来源、算法、游戏画面、充电/电源设置；不要翻新格，也不要通过Android Studio的Run/Debug/Apply Changes启动。
3. 对同一画面做2–3次“清屏→手动截图→识别”，分别记录首轮和后续的识别、推荐耗时；提供结果截图即可。

本包保留 `BAGridAnalysis` 分段日志，可以通过正常ADB logcat读取。没有额外打开profileable权限；因为它不可调试，上一轮依赖run-as的内部目录/线程采样脚本不能直接用于它。如果仍需更深的Release调用栈采样，应另行准备允许shell profiling的专用构建，不能因此又开启debuggable。

可重复打包脚本：`tools/Package-NonDebugTest.ps1`。它只签署独立副本，拒绝覆盖已有测试APK；`-VerifyOnly`只重新校验现有文件。
