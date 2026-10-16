# 安澜智能守护音响

独立的横屏 Android 应用，包名为 `com.anlan.guardian`，与仓库根目录的 `com.example.soundguardian` 可以同时安装。

## 当前版本

- 产品：安澜智能守护音响
- 角色：小安
- 方向：深色常亮、拟人化表情、声音感知守护
- 横屏：`MainActivity` 固定 landscape
- 监听：前台麦克风服务，模型在设备本地推理，不保存录音
- 模型：与原项目相同的 Google MediaPipe YAMNet float32/1
- 预留：主动关怀、唤醒词、语音输入、对话智能体和语音输出接口

## 构建

在 Android Studio 打开仓库根目录时，Gradle 会导入两个 Android application 模块：`:app`（原应用）和 `:anlan`（安澜，实际目录 `anlan/app`），以及两者共用的 Android Library `:core`。修改配置后执行 Sync Project with Gradle Files，运行时选择 `anlan` 模块即可。两者包名、界面和数据目录保持独立。

在仓库根目录构建安澜：

```powershell
.\gradlew.bat :anlan:assembleDebug
```

也可以单独使用 Android Studio 打开 `anlan` 目录，该入口会引用同级 `../core`，因此需保留完整仓库目录；或在 `anlan` 目录执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

`../core/src/main/assets/yamnet.tflite` 不提交 Git；首次克隆后在本目录执行 `powershell -ExecutionPolicy Bypass -File tools/prepare-model.ps1`，脚本下载固定官方版本并校验 SHA-256，不覆盖已有的不同模型。标签、中文映射、规则、metadata 和 manifest 统一保存在 `core`，不在两个 App 内各存一份。安澜自己的 assets 仅保留表情行为配置。

从仓库根目录构建时，Debug APK 为 `anlan/app/build/outputs/apk/debug/anlan-debug.apk`；从 `anlan` 独立入口构建时，为 `app/build/outputs/apk/debug/app-debug.apk`。

## 设计与代码边界

- `FaceView`：眼睛、嘴巴和连续动画的几何绘制。
- `Emotion`：可表达的视觉状态。
- `BehaviorEngine`：待机、困倦、打盹、声音事件优先级、过渡和变体。
- `GuardianService`：安澜的 PCM 采集、事件广播线程；调用共享 `core` 中的推理器、配置与事件引擎，输入参数使用共享 `SoundContract`。
- `EventStore`：仅保存事件类别和时间，不保存原始录音。
- `InteractionPorts`：未来主动关怀、唤醒词、语音和对话服务接口。

首版模拟入口只用于预览表情，不会写入真实事件记录，也不会发起询问、报警或外部通信。

## 使用

1. 打开 App 即保持前台屏幕常亮，并自动开始监听。首次需要授予麦克风权限，授权后立即自动守护，无需再点击按钮；支持系统通知权限的设备也会请求通知授权。
2. 主屏和菜单不设守护开关；轻触脸部可以唤醒困倦表情，右上角菜单可查看记录、调整窗口亮度和查看模型状态。
3. Debug 版菜单包含 26 种表情预览及模拟事件；高优先级真实事件不会被预览覆盖。
4. 约 90 秒无有效互动后困倦，180 秒后打盹。阈值见 `app/src/main/assets/behavior.json`，打盹不停止声音识别。
5. 不需要守护时从最近任务中划掉安澜，或在系统设置中强行停止 App，监听服务随之结束。按 Home 退到后台仍会守护，重新打开 App 会自动监听。两个应用可并存安装，但同时录音可能受 Android 的麦克风并发策略限制。

## 验证与限制

初版在 V2505A 真机完成 4 项仪器测试：桌面 PCM 分数对照（绝对误差容差 0.002）、事件记录重开、三轮监听启停和页面重建、26 种表情及 Icon 渲染。移除守护开关后，生命周期测试已调整为自动监听、三轮移除任务后停止与重开、页面重建；该测试已编译，尚未重新在手机执行。单元测试覆盖事件合并、优先级、持续警报、冷却恢复、困倦唤醒和会话隔离；Debug 构建和 lint 通过。

共享引擎重构后已在 x86_64 模拟器通过安澜 5 项仪器测试（新增共享配置/参数/只读规则校验）及原 App 的共享配置/模型加载和静音推理测试。两款 APK 内的 6 项共享资产逐一与 `core` 原文件核对 SHA-256，一致；重复资产覆盖保护已实际验证。

当前是调试签名体验版。尚未完成数小时或数天的温度、耗电和后台稳定性测试；后续音箱型号、Android 版本、麦克风和系统权限策略确定后仍需实机回归。固定横屏会受到部分系统大屏或多窗口策略影响。

YAMNet 的输出是声音类别分数，不能据此承诺危险识别准确率，也没有“求救语义理解”。主动关怀、语音唤醒、语音识别、播报和大模型对话只提供接口，未启用。

参考：[Stack-chan](https://github.com/stack-chan/stack-chan)、[M5Stack-Avatar](https://github.com/stack-chan/m5stack-avatar)。表情绘制为 Android 原生实现，未复制这些项目或 RoboEyes 的代码。模型 manifest 与 metadata 随 assets 保留。
