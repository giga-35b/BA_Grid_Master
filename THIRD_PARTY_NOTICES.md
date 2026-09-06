# 第三方依赖与素材

根目录 `LICENSE` 的 MIT 授权适用于本项目自有源代码，不覆盖第三方依赖和游戏素材。本项目是非官方辅助工具，不代表游戏开发商或发行商。

## 游戏图片

`dataset/`、`recordings/` 中的静态样本、`texture/` 及应用中使用的游戏相关图像（包括 `kei_app_icon`）涉及游戏画面或角色素材，其权利归各自权利人所有。它们用于本项目识别测试、问题复现或界面展示；本仓库不对这些素材另行授予 MIT 权利。维护者尚未在仓库内提供单独的素材授权证明。

如需转载素材或另行分发，请自行核实来源及许可；如有权利或隐私异议，请在 [项目 Issues](https://github.com/giga-35b/BA_Grid_Master/issues) 联系维护者（仓库公开后可用），或通过 [作者主页](https://space.bilibili.com/15097920) 联系。

## 构建与运行依赖

依赖及版本以 `app/build.gradle.kts`、`build.gradle.kts` 和 Gradle Wrapper 配置为准。它们由各自上游许可管理，不因本项目 MIT 授权而改变。

- AndroidX / Jetpack Compose：[AndroidX 项目](https://android.googlesource.com/platform/frameworks/support/)。
- Kotlin 与 kotlinx.coroutines：[Kotlin](https://github.com/JetBrains/kotlin)、[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines)。
- Google ML Kit 文字识别：[官方说明](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)。
- Gradle Wrapper：[Gradle](https://github.com/gradle/gradle)。
- 测试工具包括 JUnit、AndroidX Test：依各自上游许可。

正式发布 APK 前应根据最终依赖清单补充所需的许可与通知文件。这里是依赖来源索引，不替代上游许可证全文。

## 可选本机工具

[scrcpy](https://github.com/Genymobile/scrcpy) 仅用于人工录屏/调试，不随本仓库复制其可执行文件，也不是应用运行依赖。`tools/vendor/` 被忽略，请从上游安装工具。
