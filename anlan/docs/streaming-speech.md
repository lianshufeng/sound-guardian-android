# 流式文字播报接入与验收

本文描述统一流式文字输入与本地播报。最初阶段未连接网络；现由安澜应用层 AI 对话复用该入口，真实接口及当前验收见 [AI 对话](ai-dialogue-development-plan.md)，以下历史结果保留原日期。

## 统一入口

一次性短句继续调用 `SpeechPlayback.speak(text)`，新请求替换旧请求，单次上限 2000 个 UTF-16 码元。
持续文字使用 `SpeechPlayback.openStream(listener)`，默认来源为 `DIALOGUE`；也可显式传入 `Source` 和 `eventId`。

```java
InteractionPorts.SpeechStream stream = SpeechPlayback.openStream(
        new InteractionPorts.SpeechStreamListener() {
            @Override public void onSegmentStarted(InteractionPorts.SpeechRequest segment) {
                // 已经开始播放；字幕由服务统一同步，调用方不创建播放器。
            }
            @Override public void onComplete() { /* 所有已接收正文播报结束 */ }
            @Override public void onCancelled() { /* 停止上游生成，清理本会话 */ }
            @Override public void onError(String message) { /* 明确报告中断，停止上游 */ }
        });

boolean accepted = stream.append("你好，我是小安。");
// accepted=false：整片段未接收，必须保留该片段并暂停上游，待缓冲有空间再重试。
// 单个片段超过配置容量时须先拆小；不能反复重试同一个超大片段。
stream.end();       // 上游正常结束；播放剩余正文后才回调 onComplete。
// stream.cancel(); // 用户打断，只取消这个句柄所属会话；不影响后续新会话。
```

不要对每个 token 调用 `speak()`；不要把 LLM 重发的累计全文当作增量追加，否则会重复朗读。
`append()` 只接受新增内容，拒收不会部分写入；结束、失败或取消后的追加抛出 `IllegalStateException`。
回调在主线程执行。新流、一次性播报、守护高优先级事件、服务关闭及低内存回收都可以取消旧流。

## 分句、语速与资源边界

配置只维护在 `core/src/main/assets/tts/config.json`，构建通过 `core:verifyTtsAssets` 校验。

| 参数 | 当前值 | 行为 |
| --- | --- | --- |
| `speed` | 0.95 | 所有段落保持相同语速，不随 LLM 吐字速度调整 |
| `stream.min_chars` | 12 | 达到长度后允许按逗号、冒号或空格提前切分 |
| `stream.max_chars` | 40 | 无标点内容也有分段上限，不拆 Unicode 代理对 |
| `stream.max_pending_chars` | 512 | 待消费文字有界，满时整片段拒收；不是整场回答上限 |
| `stream.flush_ms` | 700 | 未形成完整句子的少量文字也会等待有限时间后开始合成 |
| `stream.input_timeout_ms` | 30000 | 无待消费文字且上游未结束，长期无新增内容时明确报错并中断 |

遇到句号、问号、叹号、分号或换行可提前切分；英文小数点不作为硬句界。
纯布局或标点片段不单独合成；包含正文的片段保留原有标点，字幕来自同一段请求。
当前段播放时最多预合成下一段。合成始终在原有全局串行线程执行，只维护一个 native TTS 模型；
新增播放消费线程只调用 `LocalSpeechOutput` 内同一套 AudioTrack 实现，不另设播放器或 TTS。
不通过加速追赶积压；上游快则背压，上游慢则在句间等待。此方案减少整段等待，不承诺零延迟或无缝播放。

2026-10-18普通整句播报也改为合成后交给同一播放消费线程；叮声及命中缓存的“我在”/响指回应直接排入播放，不等待旧native合成退出。缓存按PCM16字节限额及模型/配置版本失效，取消仍使旧结果无效。TTS与SenseVoice重型作业共享单项许可；未调整语速、分句或流缓冲参数，未接入native PCM增量播放。RTF及首声实测/待验收项见[性能验收](interaction-performance-acceptance.md)。

字幕只在真实开始播放时显示当前段；段落结束进入 `WAITING` 后清除字幕。
流内空隙继续抑制唤醒和语音理解，整场结束或取消后才恢复；YAMNet 守护与共享 PCM 采集持续运行。
模型空闲释放仍遵守设备预算；低内存回收会显式取消当前播报（包括普通整句和流）并排队释放模型，旧合成不得重新填充已回收缓存；调用方可提示中断，禁止静默丢失正文。

## 功能验收

纯 Java 测试：`SpeechTextBufferTest` 验证增量边界、定时分句、尾句排空、超载原子拒收及重试、
超过 2000 字的有界消费、Unicode、英文及小数、取消和输入超时；`SpeechStreamPipelineTest` 验证
播放期间只预取下一段、顺序、失败、取消和空输入，不加载真实模型。

真机测试在 `SpeechPlaybackDeviceTest`，通过正式统一接口以每 25 毫秒一个字符模拟 LLM：

```powershell
./gradlew.bat :core:testDebugUnitTest :anlan:testDebugUnitTest :anlan:assembleDebug :anlan:assembleDebugAndroidTest :anlan:lintDebug
adb install -r anlan/build/outputs/apk/debug/anlan-debug.apk
adb install -r anlan/build/outputs/apk/androidTest/debug/anlan-debug-androidTest.apk
adb shell am instrument -w -r -e class 'com.anlan.guardian.SpeechPlaybackDeviceTest#streamedDeltasPlayBeforeEndAndKeepCaptionOrder,com.anlan.guardian.SpeechPlaybackDeviceTest#replacedStreamCannotCancelNewStreamOrOrdinarySpeech,com.anlan.guardian.SpeechPlaybackDeviceTest#serviceStopCancelsWaitingStreamWithoutStartingAudio' com.anlan.guardian.test/androidx.test.runner.AndroidJUnitRunner
```

验收关注：未调用 `end()` 时已开始首句、字幕与段落顺序一致、无漏读或重复、超载拒收、
新会话替换、旧取消不影响新播报、服务关闭取消空流、首段开始耗时及内存阶段快照。
测试应保持应用在前台，避免 vivo 冻结后台 instrumentation 干扰结论；真实可听输出仍需人工确认。
阶段内存快照不等于完整峰值；4 GB 目标手机仍须单独验收。

## 本轮执行状态（2026-10-17）

- 已通过：core 12 项、安澜 39 项单元测试，共 51 项（含本次新增 16 项）；两款 Debug 构建、测试 APK 构建、安澜 lint 和四项共享资产校验。
- 已校验：原 App 6 项、安澜 392 项共享资产的 APK 内 SHA-256 均与源文件一致；原 App 未包含 TTS、唤醒、理解目录。安澜 APK 约 758 MiB，资源体积未因流式功能明显增加。
- 此前安装因设备断开失败；恢复连接后新版已安装，vivo V2505A / Android 16 / ARM64 上述 3 项流式设备测试及唤醒播报回归通过，共 4 项、耗时 39.416 秒。增量首句在 end 前播放、字幕及顺序、新旧会话替换、普通播报替换及服务关闭取消均通过；可听体验仍须人工确认。
- 此次打开流到首段开始播放回调约 2640 毫秒；首段及结束阶段 PSS 分别为 676,805 / 726,641 KiB，native 已分配为 590,977 / 662,423 KiB。这是守护共存下的阶段快照，结束时模型尚处于空闲保留期，不是峰值或释放后内存；不作为 4 GB 低端真机验收结论。
- 原 App 本轮仅完成构建及资产校验；共享模型未变，新的设备推理复验未执行。后台采样冻结等历史结论不能替代本轮流式验收。
