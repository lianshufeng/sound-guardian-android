# 真机环境音持续观察

使用仓库根目录的 `tools/monitor-anlan.ps1` 做只读采样，例如：

```powershell
pwsh -NoProfile -File tools/monitor-anlan.ps1 -Device 设备序列号 -OutputDirectory E:/采样结果目录 -Minutes 30 -IntervalSeconds 60
```

采样包含起点与终点，30分钟每分钟一次共31个时间点。按单调时间安排，单条ADB命令18秒超时；设备断开或采样失败记入错误，不自动启动App、安装APK、重启服务、修改设置或清除系统日志。监控结束只停止采样，不停止用户的守护服务。

结果目录包含运行状态`run.json`、进度`progress.json`、最新样本`latest.json`、逐次样本`samples.jsonl`和各分钟内存/温度原始输出。CPU通过同一PID及进程启动标记的`/proc/<pid>/stat`差分计算，时基为设备uptime与CLK_TCK；单核100%与整机多核100%分别记录，不把不同进程的累计值直接相减。线程、PSS、RSS、native和Java已分配内存用于比较驻留、释放及增长，单独增长不能直接定性泄漏。

只保留异常类别、时间/标签前缀、异常类名和进程归属等元数据，不保存任意业务日志正文、转写、凭据或录音。退出历史必须按实际观察起点筛选；初版采样保存时间/PID/内存等摘要，最终判定退出原因时补查`dumpsys activity exit-info`的reason/status，禁止把历史安装或强停当作本次崩溃。

温度应使用`Current temperatures from HAL`段，区分CPU、GPU、机身和电池；缓存温度可能过期。USB供电、充电或满电条件下不能根据电量、charge counter直接推算纯电池续航。无法读取的内存压力或电流字段应标为缺失，不能填零。

本次观察窗口为2026-10-18 14:56:10—15:26:10（Asia/Shanghai），设备vivo V2505A / Android16 / ARM64。正式数据位于`evaluation/runtime-20261018-1456/`，`runtime-20261018-1458/`是初始化权限诊断失败的已停止目录，不参与结果。31次采样已完整结束，0次失败；结论和版本限制见[完整观察报告](../../evaluation/runtime-environment-20261018-report.md)，不能将该环境音结果当作最新工作区APK或连续语音交互验收。

每分钟快照无法捕获所有瞬时峰值，系统日志也可能轮转；环境音守护观察不等于连续唤醒、动态TTS、真实网络多轮、低端设备或长期稳定性验收。正式报告需披露有效样本数、丢样、进程变化、CPU/内存趋势、温升、异常证据和剩余限制。报告回访由本聊天自动化触发，采样由独立隐藏进程执行；宿主休眠、ADB断开或应用关闭均可能影响完整性。
