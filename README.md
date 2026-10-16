# AI智能守护 · Android Demo

基于 Google 官方预训练 YAMNet，在手机本地持续观察家庭声音。无需账号、后端或运行时网络；不训练、不微调、不自动联系他人或外部报警。识别声音不等于判断危险，确认事件仅表示满足应用规则。

## 当前基线
采用 [Google 官方 MediaPipe YAMNet float32/1](https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite)，通过 TFLite Interpreter 直接推理，未同时引入 MediaPipe 推理框架。

| 项目 | 实际模型契约 |
|---|---|
| 版本 | MediaPipe YAMNet float32/1 |
| 输入 | float32 `[15600]`，16 kHz 单声道，约 0.975 秒 |
| 输出 | float32 `[1, 521]`，按内嵌标签顺序 |
| 输入归一化 | PCM16 / 32768；音频特征前处理包含在模型图中 |
| 更新步长 | 7680 样本，约 0.48 秒 |
| 权重大小 | 4,126,810 字节 |
| SHA-256 | `4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf` |
| 许可证 | 模型 metadata 声明 Apache License 2.0 |

输入输出为 float32，但模型 metadata 明确注明内部采用 8-bit 量化，不能把它当作完整 int8 输入模型。分数不是识别正确率，多个类别可同时有较高分数。

首次准备模型，建议使用 Python 3.12，在仓库根目录执行：

```powershell
python -m pip install -r tools/requirements.txt
python tools/model_tool.py --install
```

脚本下载固定版本并校验 SHA-256、tensor、metadata 和 521 个标签；从模型内嵌 `yamnet_label_list.txt` 提取标签，无需手动下载 CSV。

- 官方包：`models/official/yamnet-float32-1/`，含 `model.tflite`、`labels.txt`、`metadata.json`、`manifest.json`。
- Android 启用包：`core/src/main/assets/` 中的 `yamnet.tflite`、`yamnet_labels.txt`、`model_metadata.json`、`model_manifest.json`，由两款应用共同引用。
- 安装前发现不同权重时，原 assets 权重备份到 `models/android-backup/<SHA-256>.tflite`，不会删除原文件。
- 应用启动会验证固定哈希和输入输出契约；回退官方版本可重新执行 `--install`。
- 权重由脚本获取，不提交 Git；manifest、metadata、标签和中文映射提交源码库。Gradle 不自动下载模型，依赖首次解析仍需要网络。
- `tools/get_yamnet.ps1` 是已停用的早期下载脚本，避免误写旧应用 assets；当前流程请使用 `model_tool.py --install`，或 `anlan/tools/prepare-model.ps1`。

## 打开与构建
用 Android Studio Jellyfish（支持 AGP 8.4）或更新版本打开仓库根目录，不能只打开 `app/`。固定环境：Gradle Wrapper 8.6、AGP 8.4.2、compile/target SDK 34、最低 Android 8.0（API 26）。建议 JDK 17；本机也使用 JDK 21 完成过构建。项目使用 Java 17，无 Kotlin 编译插件；Kotlin BOM 1.9.24 用于统一传递依赖。

在 SDK Manager 安装 Android SDK Platform 34、Build Tools 34.0.0 和 Platform Tools。Gradle JDK 设置为 17 或兼容的 21。`local.properties` 由本机生成，不提交仓库，例如：

```properties
sdk.dir=D:/sdk/android/sdk
```

```powershell
# 打包
.\gradlew.bat assembleDebug
# 明确选择真机后直接安装，不启动模拟器
adb devices -l
$env:ANDROID_SERIAL = '你的真机序列号'
.\gradlew.bat installDebug
adb -s $env:ANDROID_SERIAL shell am start -n com.example.soundguardian/.MainActivity
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。本次用于转发的副本：`app/build/outputs/share/AI智能守护-0.1.0-测试版.apk`；副本不会随以后构建自动更新，重新分发前需重新复制并校验。当前使用调试签名，适合体验测试，尚未配置正式发布签名。

真机开启 USB 调试并允许调试授权。若提示 `INSTALL_FAILED_ABORTED: User rejected permissions`，需要在手机上允许 USB 安装/安装确认；命令返回失败不能视为新版本已安装。给其他用户直接发送 APK 即可，首次安装可能需要允许对应来源安装应用。

## 使用与界面

- 打开“AI智能守护”，允许麦克风权限，点击“开始守护”；拒绝权限后需重新授权再点击。
- 前台服务负责持续监听，系统提供麦克风使用指示和服务通知；通知展示受系统通知授权及设置影响。
- 雷达扫描、标签颜色和淡入淡出用于观察候选；标签位置仅为视觉展示，不表示方向、距离。
- 数字角标为模型分数 × 100 后的整数，不是准确率。候选总显示时长为模型分数 × 5 秒，包含淡入淡出，例如 60 分显示 3 秒。
- 左上角小字为输入电平 dBFS，未校准成实际环境声压级 dB SPL。
- 确认事件出现可点击气泡；事件记录入口显示未读数量，点击详情可查看时间、状态和估计持续时间。
- 右上角 `•••` 进入调试页：先显示事件规则，向下连续滚动查看模型、实时耗时、输入和完整 521 类原生分数。
- 点击“停止守护”结束监听。守护主页前台保持常亮；进入其他页面、退到后台或手动按电源键不保证屏幕继续点亮。
- 原始录音不默认保存；目前没有测试录音保存/导出/清理功能，也没有反馈标注入口。

## 工程约定
工程包含 `:app`（原应用）、`:anlan`（安澜）和 Android Library `:core`（共享声音引擎）。两款应用都依赖 `:core`，包名、界面、通知和历史数据独立。

模型权重、原生标签、中文映射、`event_rules.json` 仅在 `core/src/main/assets/` 维护；推理器、配置加载、事件确认与音频参数仅在 `core/src/main/java/com/soundguardian/core/` 维护。`core:verifySharedAssets` 在构建前校验模型哈希、标签和规则，并禁止 App 的 assets 覆盖同名文件。修改共享规则后需要重新打包两款应用；已安装 APK 不会自动更新。

| 文件 | 职责 |
|---|---|
| `ListenService.java` | 前台服务、AudioRecord、采集缓冲和广播 |
| `YamnetClassifier.java` | 模型哈希和 tensor 校验、TFLite 推理 |
| `EventEngine.java` | 有序帧上下文、滞回确认、结束与同类合并 |
| `AppConfig.java` | 原生标签、中文映射与事件规则加载 |
| `RadarDashboard.java`、`SoundRadarView.java` | 守护首页、雷达、事件气泡和详情 |
| `DebugActivity.java` | 独立调试页 |

原生 `yamnet_labels.txt` 与展示映射 `zh_labels.json` 分开，已补齐 521 类中文。`event_rules.json` 是实际事件配置来源。

## 3 秒上下文与事件规则

采集满 15600 样本即开始推理，以约 0.48 秒重叠更新；最近 3 秒按时间保存帧结果，不拼接各类别的历史最高分作为当前判断。所有 521 类都参与候选排序，包括说话、音乐、噪声和安静。

以下为当前确认/关闭阈值（模型分数，百分比形式）：

| 事件 | 确认 | 关闭 | 确认证据 |
|---|---:|---:|---|
| 咳嗽 | 30% | 12% | 单帧 |
| 清嗓 | 24% | 10% | 单帧 |
| 喷嚏 | 32% | 12% | 单帧 |
| 尖叫 | 30% | 14% | 1.2 秒内两帧 |
| 哭泣 | 30% | 14% | 1.2 秒内两帧 |
| 婴儿哭声 | 26% | 12% | 1.2 秒内两帧 |
| 哀鸣、呻吟（Wail, moan） | 25% | 10% | 1.2 秒内两帧 |
| 呻吟（Groan） | 25% | 10% | 单帧 |
| 敲击 | 40% | 18% | 单帧 |
| 轻敲 | 38% | 16% | 单帧 |
| 拍击 | 32% | 14% | 单帧 |
| 破碎、撞击声 | 32% | 12% | 单帧 |
| 玻璃相关声音 | 30% | 12% | 单帧 |
| 碎裂声 | 30% | 12% | 单帧 |
| 脚步 | 30% | 14% | 1.2 秒内两帧 |
| 警报 | 32% | 14% | 1.2 秒内两帧 |
| 烟雾警报 | 28% | 12% | 1.2 秒内两帧 |
| 火警声 | 28% | 12% | 1.2 秒内两帧 |

长事件的两帧不要求连续。开启后按关闭阈值维持证据；最后证据后等待约 650 ms 或 1100 ms 再结束，同类按配置的约 0.9～1.3 秒合并。各类独立判定，分数不相加。配置中的 `candidate` 尚未用于过滤雷达候选；当前雷达使用全类别前 3 名，不设此门槛。

这些规则未经校准；敲击不解释为敲门或拍桌子，撞击不解释为跌倒，呻吟不解释为身体不适。事件和未读状态主要保存在当前服务/页面内存中，重启服务或应用不保证保留。时间由帧结束时间回推窗口起点，持续时间只是估计值。

数据目录 `data/raw`、`annotations`、`processed`、`splits` 仅作未来脚手架；`configs`、`outputs`、`evaluation` 用于未来训练/评估说明。按演员/录音组/设备分组切分，区分片段弱标签和事件起止标签；未来可冻结 YAMNet 编码器训练分类头，或可选微调后导出、校验并替换 assets 模型。

尚无可执行训练管线或训练结果。未来流程应为：分组划分数据 → 冻结编码器训练分类头（可选微调）→ 独立测试集回放评估 → 导出 TFLite → 核对 tensor/metadata/标签 → 同 PCM 跨端对照 → 更新安装脚本、manifest 和 Android 加载契约 → 真机回归。当前脚本和应用固定官方哈希，不能直接替换任意自训练文件；变更模型必须同步更新这些校验点，并保留官方基线。

## Git 与本地文件

提交源码、Gradle Wrapper、脚本、配置、中文映射、模型来源清单及必要文档。`.gitignore` 忽略构建产物、IDE 缓存、本地 SDK 路径、密钥/签名、模型权重、音频、Python 缓存、运行日志和逐帧报告；离线 `summary.json` 当前保留为可追踪摘要。忽略规则不会删除本地数据。

原始回放样本放在 `data/raw/`，处理数据放在 `data/processed/`。标注和划分目录当前也被忽略，如未来需要提交脱敏标注或划分配置，应调整对应规则。训练输出应保存在 `outputs/`；当前忽略文件尚未覆盖该目录，勿直接将大型输出加入 Git。跨端测试 `parity.pcm` 也受音频忽略规则影响，首次克隆后通过离线脚本再生成。

## 离线回放结果
已运行 `tools/offline_eval.py`：静音首帧为 Silence 0.8008；100 ms 短输入按零填充处理；440 Hz 合成音为 Sine wave 0.9180；官方 miaow 样本为 Cat 0.8008。每个案例输出逐帧原生 521 类分数到 `evaluation/reports/`，平均单帧推理约 1.4–1.5 ms（桌面 CPU）。这些案例只验证输入和输出链路，不是事件准确率。

```powershell
python tools/offline_eval.py
python tools/offline_eval.py "data/raw/自备录音.wav"
.\gradlew.bat testDebugUnitTest
```

离线脚本支持 PCM16 WAV，可混合声道并重采样；短片段末尾补零，实时采集则等待完整输入。首次执行会下载官方猫叫 WAV。生成的逐帧 JSONL 包含 521 类原生分数和咳嗽、清嗓、敲击、背景分数；不能只挑高分样例评估效果。

同一 15600 样本 PCM 与桌面输出保存在 `core/src/androidTest/assets/`，两款应用的仪器测试共用同一组夹具。

## 限制与验证
已执行 Debug 构建、签名与 APK 内模型哈希校验；已有短咳嗽事件确认、结束、合并单元测试。早期曾执行启动/Activity 重建仪器测试；后续界面多次调整，不能视作当前版本所有交互均完成回归。当前已在 V2505A 真机安装、启动并体验监听；后续仅在用户明确要求时启动模拟器。

短时性能观测：雷达改为硬件绘制并限制约 30 FPS 后，App CPU 采样约 28%～32%（单核 100% 口径），采集/推理工作线程约 3%～4%，内存 PSS 曾观测约 95 MiB。数值随场景、温度、设备和调试状态变化，工作线程占用不是独立模型基准。此前温度来自系统缓存，不作为持续热测试结论；USB 供电时也未测得独立电池功耗，不能据此承诺续航。

尚未完成或已知限制：

- 当前目录没有先前六段用户录音，未做旧模型诊断对照；无事件起止真值，未计算事件准确率、召回率。
- 远距离、低音量、背景噪声下的咳嗽/拍击等混淆需要真实样本验证，官方模型不保证比旧模型更准。
- 同 PCM 跨端夹具已生成过，但尚未执行数值一致性断言。
- 采集与推理仍在同一工作线程，尚无独立队列、缓冲溢出和掉帧统计。
- 重复启停、停止时资源释放竞争、旋转/返回页面后的状态恢复、厂商后台限制仍需完整回归。
- 当前雷达源码仍使用四个预设落点随机分配，并非连续随机坐标；此前沟通中关于全区域随机落点的描述未完全落实。
- 没有录音测试管理、持久历史、导出、反馈训练或完整训练脚手架实现。

参考：[Google Audio Classifier 指南](https://ai.google.dev/edge/mediapipe/solutions/audio/audio_classifier)、[Android 接入示例](https://ai.google.dev/edge/mediapipe/solutions/audio/audio_classifier/android)、[YAMNet 官方源码及类别表](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet)、[Apache 2.0 许可证](https://www.apache.org/licenses/LICENSE-2.0)。



