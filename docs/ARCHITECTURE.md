# BA Grid Master 架构

第一阶段刻意把系统交互、UI 和算法隔离，避免 CV/求解器接入时重写 Android 外壳。

## 当前数据流

```text
Compose 底栏：首页 / 结果信息 / 设置
  ├─ SettingsRepository / Preferences DataStore
  └─ AssistantController / AssistantWorkflow
       ├─ 屏幕模式：系统授权 → CaptureSessionService 就绪 → OverlayService
       ├─ 最新截图模式：OverlayService（不启动屏幕捕获）
       └─ 自行选择模式：系统文档选择器 → Activity 内 AnalysisEngine → 首页结果

OverlayService
  └─ AnalysisEngine
       └─ CvAnalysisEngine
            ├─ CaptureFrameBroker / MediaProjection RGBA frame
            ├─ GameVisionDetector
            ├─ ML Kit inventory count recognizer
            ├─ FragmentCompletionPlanner
            │    ├─ BoardObjectRecognizer / 类型候选与已见分组
            │    ├─ LocalTemplateMatcher / 空间纹理与局部格对齐
            │    └─ LegalObjectPlacements / 形状约束延伸兜底
            └─ BoardStrategySolver / 额外探索
```

## 算法接入边界

`AssistantController` 在主线程统一协调两个服务；独立于 Activity 生命周期。每次启动带单调递增会话 ID，旧授权结果与旧服务回调不能启动或停止新会话。屏幕授权撤销、捕获失败/销毁会停止悬浮窗；停止助手或切换来源也会停止捕获。两个服务使用 `START_NOT_STICKY`，不在系统重建时自行恢复未经重新授权的窗口。捕获/悬浮窗启动阶段有10秒超时，等待用户系统授权时不计超时。

`AnalysisEngine` 是悬浮窗唯一依赖的分析接口。后续真实实现内部再拆成：

```text
FrameSource
  → FrameGeometryDetector
       ├─ BoardLocator
       └─ ItemTrayLocator
  → CellStateRecognizer
  → FragmentMatcher
  → PosteriorLayoutCounter
  → StrategySolver
  → AnalysisResult
```

`OverlayService` 读取 `AnalysisResult` 的几何、推荐、格子状态与物品可见区域；不持有原始截图或解释后验布局。
`RecognitionDebugStore` 保存最近一次采集尝试（原始帧、结果或错误），供配置页展示；不写磁盘。
用户可通过 `DebugImageExporter` 手动保存原图/标注图；预览与导出共用 `DebugAnnotationRenderer`。
`CvAnalysisEngine` 根据 `ImageInputMode` 选择屏幕捕获、`LatestGalleryFrameSource` 或 `SelectedGalleryFrameSource`。最新截图来源查询 MediaStore、检查整库权限并排除应用导出图；自行选择来源只读取系统选择器授予的单个 Content URI，不申请整库权限。三种来源共用检测、OCR、匹配与求解管线，识别结果始终保持源图坐标。
`BoardCalibrationRepository` 通过共享互斥锁协调识别、学习、保存与手动清除；使用独立 Preferences DataStore，仅保存已确认的两角坐标。`BoardCalibrationTracker` 为纯 Kotlin 策略，按输入来源及图片尺寸隔离；`calibratedDetection` 总是先调用现场检测，再按有界历史偏差调用 `GameVisionDetector.reinspect` 重建格子、碎片边缘和物品卡观测，后续 OCR/匹配/求解只使用这一份一致的结果。清除不能被同一时刻的旧保存操作覆盖，离开设置页也不会留下能恢复旧坐标的内存缓存。
`BoardObjectRecognizer` 的可见区域分组与候选类型供 `FragmentCompletionPlanner` 筛选贴图。`GameVisionDetector` 只产生格子状态和真实边界证据，不再直接产生无形状约束的建议。`LocalTemplateMatcher` 对合法矩形占格搜索旋转/尺度/平移，以前景 Dice、像素颜色差与明暗相关性评分，最佳候选与次佳不同占格的分差不足时拒绝采用。`FragmentCompletionPlanner` 另外输出本轮完整补开计划；`LitObjectExplorationGate` 仅在所有点亮物品均可完整补开时，将计划摆法及扣减后的清单传给探索求解器。唯一摆法用于规划副本的占用约束，多摆法联合采样，不写回真实格子状态或清单。

`BoardCoordinateOverlayView` 将棋盘几何绘制成 Excel 风格坐标层：列使用 `A-I`，
行使用 `1-5`，建议格使用 `F3` 形式。该层所在窗口设置 `FLAG_NOT_TOUCHABLE`，
只负责显示，不参与游戏触摸分发。
`KnownObjectAnnotations` 以纯 Kotlin 生成游戏内 a/b/c 中文物品标签、全开物品整体矩形和灰色空格框；实际调试截图保持独立的细粒度标记。坐标仍由映射后的棋盘几何生成，不更改识别结果。

## 自适应几何定位方案

绝对像素和单一屏幕比例只作为搜索先验，不作为最终坐标。首版 CV 建议：

1. 将截图缩放到约 640 px 宽，在右半屏使用 HSV 蓝色区域和亮度边缘生成棋盘候选；
2. 对候选区域做 Canny/Sobel，再用 Hough 或轮廓投影寻找近似等距的 10 条竖线、
   6 条横线；用 RANSAC 拟合 9×5 网格和四角，得到棋盘区域；
3. 左下物品区先使用宽松归一化 ROI，再检测三个重复卡片外框；必要时用标题/边框
   小模板做多尺度匹配，而不是匹配物品本身；
4. 输出棋盘、物品区、行列数和各自置信度；低置信度时不画推荐框，并提示用户重试；
5. 按“分辨率 + 方向 + 宽高比”缓存最近一次几何结果，并对连续帧做中位数平滑，
   避免 UI 动画导致推荐框抖动；
6. 保留一次手动校准作为识别失败时的降级路径。

## 当前 CV 行为

- 仅点击悬浮按钮后先隐藏本应用的悬浮层，再请求一张新 RGBA 帧；面板点击不触发识别。`OverlayTouchController` 将面板拖动与按钮点击分离，使用 `OverlayGestureTracker` 记录整段拖动；松手后的吸附/重排通过 `View.post` 延后执行，取消事件不修改视图树；
- 使用 10 条竖线、6 条横线的等距约束定位 9×5 棋盘；
- 识别左下三张重复物品卡，并采集每张卡的彩色物品贴图；
- 从卡片左下白色小面板中的深色格孔恢复物品形状矩阵；
- 数量区 OCR 读取 `×N`；中央贴图区另检 Finish 黄字字形/深色横幅，数量漏读时追加中央 OCR。两区不混用数字，完成标记与正数数量冲突时保留未知。完成卡即使形状灰化难读也可以按 0 件处理；
- 坐标覆盖层会把截图坐标转换为悬浮窗安全区坐标，且不拦截触摸；
- 联合棋盘内容覆盖率与物品形状卡校验场景；弹窗、奖励结算或无关画面返回拒识。
- 非正方形先对贴图前景做 PCA 长轴预旋转，得到水平标准贴图，再按形状归一化、切分并比较横竖/正反摆放。弱长轴素材保留多方向候选。每格采样32×32，角度细化3°，双线性重采样与有界宽高尺度微调；纹理用前景内3×3平滑亮度相关性，轮廓和颜色仍用未平滑像素。通过阈值维持0.78/0.72/0.045，不把颜色分当位置概率。
- 正方形跳过旋转贴图匹配；`SquarePlacementResolver` 根据真实格边接触推断角/边/中心，在合法摆法中确定2×2或3×3完整范围。两个轴的证据不足或与已见物品冲突时退回保守补开，不因邻格数量足够而宣称完整。
- 不可靠匹配退回真实格边窄带接触检测；真实接触优先于碎片形状主轴提示，并先排除已知空格/完成格/其他可见物品冲突。条形物品方向冲突时不强行推荐。
- 选择态需同时满足绿色边框和格内中央勾号，避免邻格框线污染已见碎片；选择态不作为碎片来源，但与未翻开格同等参与推荐和查表。
- 仅在同一个合法二维物品摆法同时容纳源格、两个邻格和对角格时补充对角格；禁止条形物品拐弯或扩张。
- `BoardStrategySolver` 使用不重叠布局的加权采样后验，提供贪婪和有限前瞻探索，并区分已点亮/未点亮物品。
- 五种清单的首步与首步为空分支使用离线表；细节和近似边界见 `STRATEGY.md`。
