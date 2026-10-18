# Android 9 兼容性验收

日期：2026-10-18。结论：当前用户提供的 Android 9 环境中，两款 APK 安装成功；安澜 69 项、原 App 1 项设备用例均取得通过记录。本轮未修改业务代码、模型或共享规则。

## 环境

- ADB：`emulator-5554`；型号标识 M973Q，产品指纹 `samsung/star2qltezh/star2qltechn:9/PQ3B.190801.03011045/G9650ZHU2ARC6:user/release-keys`。
- Android 9 / API 28；系统内存 4,040,992 KiB；应用实际选择 `x86_64` ABI。系统另声明 ARM 转译支持，本轮不等于 ARM 真机验证。
- 两款应用 minSdk 26、targetSdk 34；本次只验证 API 28，不能扩展为 API 26/27 或所有旧系统通过。
- 安澜 Debug APK 795,252,174 字节，原 App 22,648,712 字节。构建时四项共享资产校验通过，使用当前构建产物。

## 实际执行

| 项目 | 结果 | 证据 |
|---|---|---|
| 两款应用及测试 APK 构建 | 通过 | `evaluation/android9-compatibility-build.log` |
| 两款 APK 安装 | 成功 | ADB install 返回 Success |
| 安澜全量普通设备测试 | 首轮 69 项中 63 通过、6 失败，488.211 秒 | `evaluation/android9-compatibility-tests.log` |
| 界面与流式页面复验 | 10 项全部通过，包含全部 6 项失败用例，50.266 秒 | `evaluation/android9-compatibility-retest.log` |
| 原 App 共享资产与实际推理 | 1 项通过，0.106 秒 | `evaluation/android9-original-app-tests.log` |
| crash buffer | 本轮读取为空 | `evaluation/android9-crash.log` |

安澜 69 项包含关怀会话和存储 15、关怀真实服务链路 1、关怀场景矩阵 14、守护/界面/设置 9、统一播报 10、语音理解 8、唤醒 5、天气 7。复验中重复通过的用例不累计为新增项。

首轮失败的 `automaticListeningStopsWithTaskAndRestoresPage`、`renderAllExpressionsAndIcon`、`careSettingsNavigateAndPersistExplicitChoices`、`modelBubblesFloatFadeAndThrottleDuplicates`、`drawerReturnsToParentWithoutOpeningDialogs` 均在页面启动阶段超时；`streamedDeltasPlayBeforeEndAndKeepCaptionOrder` 未取得页面实例。现场 dumpsys 显示前台为系统 `GrantPermissionsActivity`，首次粗略定位权限弹窗占据前台。

通过测试 shell 授予 `ACCESS_COARSE_LOCATION`，结束弹窗状态后重跑全部 GuardianDeviceTest 和该流式用例，10 项全部通过。这里修正的是测试前置权限状态；没有绕过应用运行时权限或关闭模型校验。未将首轮写作全通过。首次安装时需要用户响应定位弹窗，拒绝定位后的完整交互本轮未单独验收。

## 已验证能力

- 前台服务、共享音频采集、启停与恢复、YAMNet 资产及推理对照。
- 本地唤醒正负样本、快读样本、自声抑制和共享 PCM 守护连续性。
- Kokoro FP32 中文合成、非空/有限/非静音样本、真实播放与字幕同步、流式增量顺序、替换取消、停止服务及回收重载。
- VAD、SenseVoice 中文文字与原始情绪元数据、输入回调隔离、有界队列过载、多引擎共存、触摸与共情路径。
- NOTICE/ASK、回复超时、重复请求、优先级、关怀设置、日程、数据库迁移与清理。
- 天气真实 API、城市搜索、离线字典、缓存/页面恢复及异常响应隔离。

## 资源观察与限制

测试记录的阶段 PSS：额外 TTS 模型加载时 1,398,750 KiB，三次合成后 621,268 KiB，释放后 112,991 KiB；流式复验首段 PSS 638,109 KiB、完成后 676,726 KiB，首段音频等待 6,778 ms。以上含测试进程、页映射及模型生命周期差异，是阶段快照，不能作为严格峰值或各模型净占用。测试采到合成 PCM 峰值约 0.418、RMS 约 0.079，非有限值为 0；不能据此认定真人已听到声音。

验收后补采：正常守护前台 PSS 132,331 KiB、切后台后 130,289 KiB（约 129.2 / 127.2 MiB）。进程 4058、四核环境，前后台各等待约 15 秒，以 `/proc/<pid>/stat` 的 user+system tick 差除以 `/proc/stat` 前八项系统 tick 差，再乘四核，得到单核口径 CPU 约 16.07% / 5.24%。含前台表情、守护与唤醒；期间未主动执行 TTS/ASR，不代表播报识别时 CPU。原始差值见 `evaluation/android9-cpu-delta.json`。`top` 的重复刷新显示不作为平均值依据。

本轮没有重跑一小时持续验收或 TTS/ASR 单项 CPU 性能剖析；Android 17 上的一小时结论不移用于 Android 9。物理麦克风真人实录、远场/多人/电视误识别率、各情绪准确率、ARM 真机、温度耗电及厂商长时间后台策略均未验收。规则矩阵仍使用人工构造事件，ASR 使用固定官方 PCM、部分唤醒使用合成样本；模拟器音频链路通过不等于真实声学效果通过。

本轮仅新增本报告并在实施记录加入链接，文件使用 UTF-8 无 BOM；现有项目规范无需调整。
