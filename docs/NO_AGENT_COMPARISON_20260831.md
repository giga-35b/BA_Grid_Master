# 应用内无Studio代理对照（2026-08-31，完整安装复测完成）

## 有代理基线

来自真实应用的一次基线日志：

```text
totalMs=6509 inputMs=290 recognitionMs=5921 recommendationMs=293
boardMs=613 ocrMs=170 previewMs=314 objectMs=120 placementMs=4704
completionMs=2 explorationMs=291
```

输入为同一棋盘：B2空、H2为物品c的浅色角，库存a/b/c为3/4/1。
当前安装保留1.0.0/versionCode 2，DEBUGGABLE、TEST_ONLY标志；未安装替换APK。

旧进程中通过 `/proc/<pid>/maps` 确认加载了 `code_cache/startup_agents/b0fe613d-agent.so` 和 `libopenjdkjvmti.so`。
代理二进制包含Android Studio部署、Live Edit和解释器符号。仅凭加载不能证明全部性能差异，需真实应用内无代理复测。

## 已执行的可恢复隔离

用户授权进行下一步对照测试后，约22:08：

1. 停止Grid Master进程，未清除应用数据。
2. 创建应用自身缓存备份目录。
3. 只移动一个已确认的代理文件：
   - 原位置：`/data/user/0/com.bagridmaster.app/code_cache/startup_agents/b0fe613d-agent.so`
   - 备份：`/data/user/0/com.bagridmaster.app/code_cache/perf_agent_backup_20260831_noagent/b0fe613d-agent.so`
4. 移动前后SHA256均为 `462fdd121b4646872610a31e33c38007af6a89e1c320455988744f392e89d165`。
5. 通过系统启动入口打开现有 `.MainActivity`。
6. 新进程的 startup_agents 目录为空；maps 中启动代理、该 agent、live_edit、deploy agent 及 libopenjdkjvmti 匹配数为 0；先前 noncooperative 线程不再存在。

未改动代码、应用设置、权限、经验坐标、相册截图或冻结发布基线。代理只是隔离备份，尚未删除或恢复。测试期间请不要使用IDE的Run、Debug、Apply Changes，以免再次注入代理。

## 第一次无代理复测：对照无效，不能归因

用户按要求测试两次后，22:11–22:12读取日志未取得新的 `BAGridAnalysis` 记录。进程仍为9196，maps中的Studio代理/JVMTI匹配数仍为0，启动代理目录仍为空。

进一步核对发现：

- 基础APK SHA256为 `294AC9367BF554E03AA5F3CB2A196EC53E07BA47B10C51AAD03F7E191B1A5599`，与之前从手机读取的 `app/build/phone-before-performance.apk` 相同。
- 扫描基础APK全部DEX，未找到 `BAGridAnalysis`、`AnalysisDiagnosticLog`、`placementMatchingMs`；完整的新Debug APK的classes5/classes6中则存在新增计时代码。
- `code_cache/.overlay/base.apk` 存在21:43部署的5份增量DEX（classes3、5、6、8、10）。此前实际运行依赖这层IDE增量部署，而不仅是基础APK。
- 因此，隔离代理后不满足“同一代码、只改变代理”的对照条件。不能把无代理的这两次操作与6509ms基线直接比较，更不能据此确认全部慢速由Studio代理造成。

## 完整覆盖安装（用户已授权）

用户回复“继续”授权备份后完整覆盖安装。约22:18完成如下操作：

1. 停止应用后，使用 `tools/Backup-PhonePerformanceState.ps1` 将设置、经验坐标、代理、增量id及5份增量DEX共9个文件备份到电脑，逐文件核对远端与本地SHA256一致。
2. 备份目录：`backups/phone-noagent-20260831-full-install`。另保留此前读取的旧基础安装包 `previous-base.apk`。逐文件清单位于 `verified-files.json`。
3. 旧新APK的签名证书SHA256均为 `01baa55d5d51b9d5d9cf73396f141a41fe7d0644a110e2ffeaff4884c47dcd39`。
4. 使用 `adb install -r --no-incremental app-debug.apk` 完整更新，返回Success。未卸载、未清除应用数据。
5. 手机实际安装后的base.apk SHA256为 `9847494D4CDFF3BEB6B38078A3A151B21479724E8177143D013194858BAD0A87`，与本地当前完整Debug APK完全一致。
6. 安装后设置、经验坐标SHA256与备份完全一致：
   - 设置：`622037794D6091F13491113476CE377C7F312A8DADA0937C129D5AF1EE1DB3E2`
   - 经验坐标：`CAF6B8EECC32FC5DAC249BCB9F2A62FC821C30A9CBD0A7F86473F00FCDB642B6`
7. 相册权限保持已授权。安装标志保留DEBUGGABLE，TEST_ONLY已消失。系统清空了旧code_cache（含旧代理备份和IDE增量层）；这些文件已完整备份至电脑，可以恢复。
8. 通过系统入口打开新版应用后，maps 中 Studio 代理、JVMTI 和旧增量 DEX 的匹配数为 0，code_cache 为空。

## 完整APK的真实应用内复测结果

用户完成同图两次“清屏→手动截图→识别”，保持相册来源、有限前瞻算法、不翻新格、不通过IDE运行。实时日志如下（单位ms）：

| 阶段 | 旧增量部署基线 | 完整APK第1次 | 完整APK第2次 |
| --- | ---: | ---: | ---: |
| 总耗时 | 6509 | 3916 | 3328 |
| 采集/等待 | 290 | 239 | 187 |
| 识别合计 | 5921 | 3540 | 3089 |
| 棋盘检测+校正 | 613 | 322 | 114 |
| OCR | 170 | 115 | 44 |
| 模板预处理 | 314 | 132 | 19 |
| 物品分类 | 120 | 52 | 14 |
| 摆放匹配 | 4704 | 2919 | 2898 |
| 推荐合计 | 293 | 133 | 50 |
| 补全建议 | 2 | 1 | 1 |
| 探索建议 | 291 | 132 | 49 |

对应同一轮的两次完整安装后识别，输入均为 GALLERY、2400×1080。临时日志采集在读取完成后已停止。

测试后再次验证：当前进程中 Studio 代理、JVMTI 和 IDE 增量 DEX 的 maps 匹配数为 0，code_cache 为空，手机安装包 SHA256 仍与完整 Debug APK 一致。

### 结论与边界

- 完整无代理部署后的两次耗时均低于旧增量部署基线。第二次总耗时下降约49%，摆放匹配下降约38%。
- 不能将全部降幅定量归因于单一Studio代理：此次同时涉及完整安装、重启以及进程预热，旧增量部署代码层也不是可严格冻结的同包对照。
- 剩余瓶颈明确为摆放匹配：两次均约2.9秒，第二次占总耗时87%、识别耗时94%。OCR与推荐已经很短，不是当前优化优先项。
- 重复一次后其他阶段明显下降，但匹配只从2919降至2898ms；继续重复等待预热并不能解释或解决剩余大部分开销。
- 独立app_process测试的约0.3秒仍不能代替真实应用中的测量。当前完整包仍是Debug包；尚不能凭现有证据区分剩余开销中Debug运行时、调度和匹配内循环各自的占比。
- 本轮完成的是部署环境整理与性能诊断，没有进一步修改算法或宣称6秒问题已经彻底解决。

## 恢复说明

若需要恢复IDE代理，当前应使用电脑备份（手机旧缓存已在完整安装时由系统清理）。应先确认startup_agents未被IDE重新写入同名文件，停止应用，再恢复已校验的代理。不要覆盖IDE新生成的文件，不要恢复旧增量DEX来替换当前完整新版代码，也不要清除应用数据。恢复会在下次启动重新允许加载该代理，应先与用户确认是否希望恢复调试环境。
