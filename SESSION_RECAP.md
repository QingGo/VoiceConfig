# VoiceConfig Session Recap

> 记录时间：2026-08-30
> 适用设备：`192.168.31.106:37459`（M2102K1C / Android 14 / 1440x3200）
> 关联文档：[SYSTEM_THINKING.md](SYSTEM_THINKING.md)、[STRATEGIC_REVIEW.md](STRATEGIC_REVIEW.md)、[PROGRESS.md](PROGRESS.md)

---

## 0. 最终整理（本 Session 完整清单）

### 做过的尝试

- 全局语音中心、悬浮球、本地唤醒、低功耗策略
- 统一 Agent 入口：文本 / 语音 / 调试广播
- AccessibilityService 读屏 / 点击 / 手势 / 截屏降级
- `UiActionLayer` 统一 UI 原语
- `TerminalSafetyGate` 终端安全门
- 真机瑞幸、微信严格终端 E2E
- 速度优化：截图裁剪、截图瘦身、视觉预算、Completion Check 降次、`task_plan` 容错
- 微信风控保护：默认禁用个人微信 UI 自动化
- 企业微信官方 API 发送工具 + 设置页凭证测试
- `AccessibilityKeepAlive` 状态机 + 自适应重试 + 锁屏恢复
- Mock LLM 离线验证通道 + 模拟器 E2E 套件
- UI 工具收敛：Tap / TapText / Swipe / PressKey / DismissPopups / ReadUiTool
- 浮层规则化：PROMO / PERMISSION / FUNCTIONAL_PICKER / TERMINAL_CONFIRM
- 内置 4 条 APPROVED Skill，并在设置页展示
- Skill 相关性算法：按标签 / 适用场景提升命中
- Agent 系统提示强化：优先参考 Skill、使用 `ui_assert`、可见证据规则
- TraceReport：失败分类 / Token / 请求体 / Markdown 导出
- 企业微信 CLI 脚本：`scripts/wecom_send_test.py`
- 模拟器场景从 4 个扩展到 13 个，覆盖更多安全与工具路径
- 智能家居 / 树莓派 / 百褶帘 / 海信空调改造方向调研

### 踩过的坑

- “看似通过”但未真实打开
- MIUI 无障碍掉线、重装后不重连
- 无障碍能读不能点，后来补 `canPerformGestures`
- 瑞幸“一键换购”浮层 close_iv 点不到
- TTS 朗读 Markdown 符号
- 微信不暴露可读 UI，`read_ui` 失效
- 微信输入假成功，改为无障碍粘贴优先
- 微信真实触发账号风控，模拟器 + 脚本是高风险
- 截图全量塞进 LLM，请求体 10MB+，单轮 10～35 秒
- 敏感确认无超时，曾出现 29 分钟空档
- 模型反复读屏、任务计划步骤找不到导致死循环
- 重装 APK 后模拟器无障碍设置被重置
- **模拟器 force-stop 会让 Accessibility 设置被清空**，导致首次 2/4 通过；已改为先启动 App、再写回设置、并保持 App 进程
- **无 Shizuku 时 `dismiss_popups` 在“没有弹窗”时误报失败**；已修复为成功
- **真实工具失败被模型“完成”掩盖**：
  - 企业微信未配置、Home Assistant 未配置时，工具失败但 Agent 仍可能回复“完成”
  - 已修复为强制 FAILED 并显示失败原因
- **自动验证的辅助 `read_ui` 失败被误判为真实工具失败**；已排除
- **用户拒绝敏感确认 / 安全硬拦截不能作为真实工具失败**；已排除
- 测试文件追加到类外导致 JUnit InvalidTestClass；已修正（开发过程坑）

### 已完成部分

- 真机严格 E2E：瑞幸停在免密支付 + 微信停在 Send 前
- `AccessibilityKeepAlive` 状态机基础版 + 自适应重试 + 健康指标 + `USER_PRESENT` 解锁恢复
- `WechatRiskGuard` 默认禁用个人微信自动化
- 设置页：微信小号风险模式 + 企业微信 API 配置 + 凭证测试按钮
- `wecom_send_message` 官方 API 工具 + `scripts/wecom_send_test.py`
- `ReadUiTool` 完全收敛到 `UiActionLayer`
- 浮层分类扩展到权限弹窗和终端确认页
- `DismissPopupsTool` 无 Shizuku 无弹窗时返回成功
- `coordinateFallback=true` 标记
- 内置 4 条 APPROVED Skill，设置页展示
- Skill 相关性算法与系统提示强化
- `ui_assert` 写入系统提示
- TraceReport：耗时 / 工具序列 / 截图 / 验证 / 安全拦截 / LLM 错误 / Token / 请求体 / 失败分类 / Markdown 导出
- 模拟器 Mock LLM E2E：**13/13 通过**
- 真实工具失败强制 FAILED 并暴露失败原因
- 企业微信 / Home Assistant 未配置时明确失败
- 个人微信自动化模拟器验证被安全拦截
- 智能家居 / 树莓派方向完成调研并更新战略文档

### 新发现的问题

- 个人微信自动化不可作为产品路径，否则有账号风控/封号风险
- 微信类不可读 UI 仍依赖视觉 + 模型文本回退，证据不够强
- `ReadUiTool` 已收敛；`tap` / `tap_text` / `swipe` / `press_key` 已开启自动可见证据，但仍需真机/模拟器回归确认耗时与证据质量
- 真机耗时基线还未重新测量
- 模拟器 Mock 不能替代真机 MIUI / 锁屏 / 离线验证
- 企业微信 / Home Assistant / 树莓派 / 海信空调 / 百褶帘均无真实联调
- Skill 驱动仍只是“prompt 参考”，还不是确定性执行器
- 低成本 DIY 智能家居不能作为稳定产品能力承诺
- 官方 API 缺少凭证安全存储、权限最小化、调用审计
- 本地 KWS/ASR 1 小时 soak、低功耗回归仍缺失

### 计划要完成的部分

- P0：真机设备矩阵、无障碍自愈、锁屏/离线、企业微信真实联调、真实 HA/树莓派/智能家居联调
- P1：`tap` / `tap_text` / `swipe` 可见证据、完整自动断言矩阵、每域终端特征与人工确认 UI
- P2：从“Skill 参考”升级为“Skill 确定性执行器”
- P3：终端安全矩阵补全 UI 特征、禁止动作、人工确认、trace 标记
- P4：真机耗时基线 + 自动 trace 报告 + 真机/模拟器双跑
- P5：本地语音 1 小时 soak、低功耗回归、语音 → Agent → TTS 端到端
- P6：稳定后再扩展更多 App / 智能家居 / 远程 / 购物能力

## 1. 本 Session 目标

把 VoiceConfig 从“能听能说”推进到：

```text
个人数字执行 Agent
语音/文本/触发 → 理解 → 规划 → 执行 → 验证 → 安全停靠 → 汇报
```

本 Session 主要推进：

- Phase 1：正式全局语音命令管道
- Phase 2：全局语音服务组件化
- Phase 3：本地唤醒与低功耗策略
- Phase 4：统一安全预检与 trace
- Phase 5：真实场景端到端回归

---

## 2. 做过的尝试

### 2.1 正式语音管道

- 新增 `VoiceCommandCenter` / `GlobalVoiceCommandBus`
- Hilt Singleton + `SharedFlow<GlobalVoiceCommand>`
- 命令包含：
  - `commandId`
  - `text`
  - `source`
  - `timestamp`
  - `confirmationToken`
- 支持 replay、去重、超时、ack
- Service 直接发送，App 内 ViewModel 订阅

### 2.2 全局语音服务拆分

- `VoiceConfigService` 重命名为 `VoiceKeepAliveService`
- 拆出：
  - `GlobalOverlayController`
  - `GlobalVoiceSession`
  - `GlobalVoiceStateMachine`
  - `GlobalSpeechRouter`
  - `GlobalSpeechInput`
  - `GlobalWakeWordEngine`
- Service 只做宿主，状态机可单元测试

### 2.3 本地唤醒与低功耗

- 找到 sherpa-onnx `KeywordSpotter` API
- 下载 wenetspeech KWS 模型（约 4.9MB）
- 内置到：
  ```text
  app/src/main/assets/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01/
  ```
- 新增 `LocalSherpaKeywordSpotter`
- 本地 KWS 优先，系统 `WakeWordDetector` 降级
- 新增 `GlobalPowerPolicy`
  - 灭屏暂停
  - 低电量自动关闭
  - 充电恢复
  - 亮屏恢复

### 2.4 安全与验证闭环

- `AgentSession` 增加：
  - `VoiceCommandOrigin`
  - `AgentPreflightResult`
- trace 增加：
  ```text
  voice_origin
  preflight
  safety_evaluate
  tool_call
  verification
  run_finished
  ```
- 所有 Agent 发送路径都经过 `AgentPreflight`
- 全局语音命令从语音到执行可完整回放

### 2.5 真实场景端到端

- 新增：
  - `scripts/phase5_real_scenarios.json`
  - `scripts/phase5_real_e2e.sh`
  - `scripts/phase5_real_foreground.py`
  - `scripts/phase4_safety_regression.sh`
- 验证真实打开：
  - 瑞幸 `com.lucky.luckyclient`
  - 微信 `com.tencent.mm`
  - 企业微信 `com.tencent.wework`
  - 设置 `com.android.settings`

### 2.6 UI Action Layer

- 新增 `UiActionLayer`，统一：
  - `tapById / tapByText / tapByDesc / tapCenter`
  - `swipe / back / input`
  - `waitFor / assertVisible / assertNotVisible`
- `TapTool / TapTextTool / SwipeTool` 改为调用该层
- 新增 `UiAssertTool`：
  - `visible`
  - `not_visible`
  - `wait_for`
- 优先级：
  ```text
  resource-id → text/content-desc → 无障碍真实点击 → 坐标兜底
  ```

### 2.7 Terminal Safety Gate

- 新增 `TerminalSafetyGate`
- 识别终端页：
  ```text
  确认订单 / 确认下单 / 免密支付 / 提交订单
  确认支付 / 立即支付 / 确认付款 / 付款
  确认发送 / 发送消息确认 / 确认发送消息 / 发送（通信目标）
  ```
- `StopVerifier` 强制：
  ```text
  终端页 → WAITING_CONFIRM
  ```
- 即使任务计划未全部完成，甚至没有任务计划，也会强制等待用户

### 2.8 严格 E2E 断言

- 增强 `scripts/agent_scenario_eval.py`
- 支持：
  - `expectedForeground`
  - `terminalText`
  - `absentText`
  - `requireWaiting`
- 新增：
  ```text
  scripts/phase5_terminal_scenarios.json
  ```
- 包含瑞幸、微信两个严格终端场景

### 2.9 真机 E2E 尝试（前期受阻，本轮已解决）

- 前期安装新版 APK 到旧真机 `192.168.31.109:42097`
- 曾遇到：
  - `AgentAccessibilityService` 系统层 Bound，但 App 进程 `instance` 为 null
  - 设备锁屏/离线，无法继续
- 本轮换用恢复后的真机 `192.168.31.106:37459`，重新启用无障碍并验证：
  - 服务连接正常，`instance` 可用
  - 瑞幸、微信严格终端 E2E 均跑通
- 结论：重装 APK 后需要重新写回 `enabled_accessibility_services`，并确认服务实际连接后再执行

---

## 3. 踩过的坑

### 3.1 “看似通过”但不是真实打开

- 之前把“草稿/准备/定时创建”算作通过
- 用户指出：没有实际打开任何 App
- 根因：后台 `context.startActivity` 受限；debug 广播时 VoiceConfig 不在前台，目标 App 不会真的到前台

**修复：**
- 每次发送调试命令前，先把 VoiceConfig 带到前台
- 增加真实前台断言：
  ```text
  dumpsys activity activities → topResumedActivity
  ```

### 3.2 MIUI 无障碍掉线

- `force-stop` 后无障碍服务会掉线
- 之前脚本每次结束 force-stop，导致下一轮读屏失败
- 曾尝试 `settings put` 写回，但部分设备需要重新触发连接

**修复：**
- 脚本支持 `VC_KEEP_APP=1`
- force-stop 后重新写回无障碍设置
- 等待 `AgentAccessibilityService connected` 再继续

### 3.3 无障碍能读屏但不能点击/按键

- 原始配置只有：
  ```xml
  android:canRetrieveWindowContent="true"
  ```
- `tap / tap_text / press_key / swipe` 在无 Shizuku 时全部失败
- 表现：
  ```text
  tap 需要 Shizuku 授权或开启无障碍服务
  tap_text 需要 Shizuku 授权或开启无障碍服务
  ```

**修复：**
- 增加：
  ```xml
  android:canPerformGestures="true"
  ```
- 使用 `AccessibilityService.dispatchGesture`
- `TabTool / TabTextTool / SwipeTool / PressKeyTool / DismissPopupsTool` 全部增加无障碍手势兜底

### 3.4 悬浮球/确认/坐标可靠性

- 坐标点击会点错商品
- `tap_text` 对无文字 X 按钮找不到
- 瑞幸“一键换购”浮层的关闭按钮是：
  ```text
  com.lucky.luckyclient:id/close_iv
  [678,2801][762,2885]
  ```
- 之前只按“关闭/跳过/取消”文本识别，识别不到

**修复：**
- `AgentAccessibilityService.clickByResourceId()`
- `DismissPopupsTool` 优先按 `close_iv` 资源 id 点击
- `WaitUserTool` 暂停前自动执行一次 `dismiss_popups`

### 3.5 Hilt/AGP 编译问题

- `GlobalVoiceStateMachine` 缺少 `@Inject`
- `GlobalSpeechInputFactory` 没有 `@Binds`
- 新增 `VoiceModule`

### 3.6 TTS 朗读 Markdown

- 用户反馈会读出 `**`
- 新增 `SpeechTextCleaner`
- 朗读前清除：
  - `**` / `__`
  - 反引号
  - 标题/引用/列表
  - Markdown 链接保留文字

### 3.7 模型行为不稳定

- 同一瑞幸流程，不同轮次差异很大
- 有时顺利到支付页，有时卡在商品详情
- 模型会：
  - 反复读屏
  - 长篇推理
  - 尝试裸坐标点击
- 不能依赖 LLM 做精确 UI 操作

### 3.8 重装 APK 后无障碍服务不重连

- 现象：
  - `dumpsys accessibility` 显示 `Bound services` 包含言控
  - 但 `AgentAccessibilityService.instance == null`
  - `AgentPreflight` 仍判“缺少无障碍”
  - `read_ui` 返回“需要 Shizuku 或无障碍”
- 尝试：
  - 重新写 `enabled_accessibility_services`
  - 开关 `accessibility_enabled`
  - force-stop + 重新启动 App
  - 通过 adb 打开无障碍设置页
- 结果：前期仍未能让 App 进程拿到服务实例
- 修复尝试：
  - 在 `AgentAccessibilityService.onCreate()` 中提前设置 `instance`
  - 每次读取/点击前主动刷新 `rootInActiveWindow`
- 本轮真机恢复后验证通过：重装 APK → 重新写回无障碍设置 → 服务重新连接 → `instance` 可用

### 3.9 真机锁屏/离线导致 E2E 中断（本轮已恢复）

- 真机曾进入锁屏，显示“Draw pattern or use fingerprint to unlock”
- 后续 ADB 设备从列表消失，无法继续测试
- 本轮新真机重新上线并解锁后继续，严格 E2E 已跑通
- 教训：
  - 自动化必须处理锁屏/亮屏/设备掉线
  - 多设备矩阵与自动恢复仍是 P0 必须完成项（AccessibilityKeepAlive 已加自适应重试与指标）

---

## 4. 已完成部分

### 架构

- [x] `VoiceCommandCenter` / `GlobalVoiceCommandBus`
- [x] `VoiceKeepAliveService` 瘦身
- [x] `GlobalVoiceSession`
- [x] `GlobalVoiceStateMachine` + JVM 单测
- [x] `GlobalSpeechRouter`（本地 ASR 优先）
- [x] `LocalSherpaKeywordSpotter`
- [x] `GlobalPowerPolicy`
- [x] `GlobalWakeWordEngine`
- [x] `AgentPreflight` 接入所有 Agent 发送
- [x] `voice_origin` trace
- [x] TTS Markdown 清洗

### 真机验证

- [x] 瑞幸实际打开：`com.lucky.luckyclient`
- [x] 微信实际打开：`com.tencent.mm`
- [x] 企业微信实际打开：`com.tencent.wework`
- [x] 设置实际打开：`com.android.settings`
- [x] 瑞幸进入：
  ```text
  com.lucky.luckyclient/.preview2.OrderPreviewActivity2
  ```
- [x] 页面出现：
  ```text
  确认订单
  生椰拿铁（首创）
  应付 ¥10.9
  免密支付
  ```
- [x] 未点击“免密支付”
- [x] 当前 UI 已无：
  ```text
  close_iv
  一键换购
  ```
- [x] 微信“文件传输助手”输入框显示“收到，稍后回复”，停在 Send 前未发送
- [x] 无障碍点击/滑动/返回/粘贴/截屏实际可用
- [x] 严格 E2E 全量 2/2 PASS

### 本轮新增代码

- [x] `UiActionLayer`
- [x] `UiAssertTool`
- [x] `TerminalSafetyGate`
- [x] `TapTool / TapTextTool / SwipeTool` 收敛到 `UiActionLayer`
- [x] `StopVerifier` 接入 Terminal Safety Gate
- [x] `AgentSession` 终端等待时自动持久化 `waitingForHuman`
- [x] `AgentAccessibilityService.onCreate()` 提前设置 instance
- [x] `AgentAccessibilityService` 读取/点击前主动刷新 `rootInActiveWindow`
- [x] `AccessibilityService.takeScreenshot` 截屏兜底（无 Shizuku 可用）
- [x] `TerminalSafetyGate` 前台包名感知 + 英文 Send 识别
- [x] `TextInputManager` 改为先粘贴再设置文本，微信输入成功
- [x] `input_text` 支持可选 `x/y` 先点击输入框

### 本轮新增测试

- [x] `TerminalSafetyGateTest`
- [x] `TaskPlanTest` 终端安全门（有计划 / 无计划 / 支付页 / 发送页）
- [x] `UiAssertToolTest`
- [x] `AgentToolsetTest` 校验 `ui_assert` 分组

### 本轮新增/增强脚本

- [x] `scripts/agent_scenario_eval.py`：
  - `expectedForeground`
  - `terminalText`
  - `absentText`
  - `requireWaiting`
- [x] `scripts/phase5_terminal_scenarios.json`

### 脚本

- [x] `scripts/phase4_safety_regression.sh`
- [x] `scripts/phase5_e2e_scenarios.json`
- [x] `scripts/phase5_e2e_regression.sh`
- [x] `scripts/phase5_real_scenarios.json`
- [x] `scripts/phase5_real_e2e.sh`
- [x] `scripts/phase5_real_foreground.py`

---

## 5. 新发现的问题

### 5.1 真机执行通道曾是最脆弱地基（本轮已验证，仍需自愈）

- `AgentAccessibilityService` 系统层 Bound，但 App 进程 `instance` 曾为 null
- 重装 / 更新 APK 后服务不一定自动重连
- 真机锁屏、离线、MIUI 回收都会中断自动化
- 本轮已修复：重新写回设置 + 主动刷新 root + 无障碍截屏兜底
- 仍需要长期方案：
  - `AccessibilityKeepAlive` 状态机
  - 自动重连
  - 设备矩阵
  - 锁屏/亮屏/掉线自愈

### 5.2 UiActionLayer 已建立，但未完成全量收敛

- 已新增统一原语和 `UiAssertTool`
- 但仍有工具保留自己的读树/点击逻辑
- `DismissPopupsTool / ReadUiTool / PressKeyTool / InputTextTool` 尚未全部改走该层
- 坐标仍可能被模型当成常规方案，需要继续强化“坐标仅兜底”

### 5.3 Terminal Safety Gate 已在真机证明，但矩阵仍待扩展

- `StopVerifier` 已能强制 `WAITING_CONFIRM`
- 瑞幸、微信真实终端场景已跑通并通过严格断言
- 安全矩阵只在支付/发送域，仍需扩展：
  - 删除
  - 配置修改
  - Home Assistant 安防域
  - 远程破坏性命令
- 需要统一“终端页特征 → 禁止动作 → 人工确认 UI → trace 标记”

### 5.4 E2E 断言已有真实通过记录，但微信证据依赖 message fallback

- `agent_scenario_eval.py` 已支持：
  - `expectedForeground`
  - `terminalText`
  - `absentText`
  - `requireWaiting`
- 新增了 `phase5_terminal_scenarios.json`
- 真机严格套件已 2/2 PASS
- 微信 UI 不暴露节点，终端文字通过 Agent 最终消息回退验证
- 还需要把 trace / 截图 / 未执行最终动作自动纳入报告，并逐步补 OCR/截图抽检

### 5.5 缺少确定性 Skill

- 已有 Skill 基础设施
- 但瑞幸、微信、企业微信、HA、远程尚未沉淀为：
  - 可审核
  - 可复放
  - 可验证
  - 有明确终端停止点
- 当前每次执行仍由 LLM 自由探索，稳定性差

### 5.6 Overlay 识别仍是启发式

- `close_iv` 已修复
- 但其他 App 的广告 / 权限 / 功能选择层 / 终端确认层没有统一分类
- 需要规则化：
  - `AD`
  - `PERMISSION`
  - `FUNCTIONAL_PICKER`
  - `TERMINAL_CONFIRM`

### 5.7 模型行为需要收敛

- 重复读屏
- 长篇推理
- 猜坐标
- 同一流程每次路径不同
- 需要从“LLM 探索”改为“LLM 选择 Skill + 验证 + 执行”

### 5.8 本地语音和低功耗缺少长期数据

- 本地 KWS / ASR 已接入
- 缺 1 小时连续唤醒、误唤醒率、功耗、灭屏/亮屏切换数据

### 5.9 安全确认交互仍缺完整设计

- 已有安全硬拦截
- 但语音确认、Agent 二次确认、用户实际最后操作三者的关系需要统一 UI/状态机
- 不可逆操作必须永远无法被自动确认绕过

### 5.10 微信类不可读 UI 带来的新问题（本轮新发现）

- 微信等 App 不向 Accessibility/UIAutomator 暴露可读节点
- `read_ui / get_screen_state` 无效，只能依赖 `read_screen` 视觉截图
- 视觉截图会导致：
  - 上下文/token 急剧膨胀
  - 模型可能在已有目标消息时过早回复“已完成”
- 微信文字输入靠“无障碍粘贴”成功，但不是通用输入通道
- `TerminalSafetyGate` 在不可读 UI 下需要参考模型最终文本识别 Send/发送
- 需要后续：
  - Skill 固化路径
  - 截图存档/OCR/人工抽检
  - 输入成功必须截图验证

---

## 6. 计划要完成的部分（按当前优先级）

> 更完整的战略分析见 [STRATEGIC_REVIEW.md](STRATEGIC_REVIEW.md)

### P0：真机执行地基（当前最高阻塞）

- [x] `AccessibilityKeepAlive` 状态机：
  - `DISCONNECTED / CONNECTING / CONNECTED / CRASHED`
- [x] 自动重连：
  - 重装/更新后重新写回无障碍设置
  - 检测 App 进程是否拿到 accessibility instance
  - 锁屏/亮屏/设备掉线处理（自适应重试+USER_PRESENT）
- [ ] 设备矩阵：
  - MIUI 真机
  - 模拟器
  - 有/无 Shizuku
  - 有/无障碍
- [x] 真机跑通：
  - 瑞幸稳定停在免密支付
  - 微信停在发送确认（“文件传输助手”可复现场景）

### P1：完成确定性执行层

- [x] 所有 UI 工具统一走 `UiActionLayer`
  - 已包括 `DismissPopupsTool / ReadUiTool / PressKeyTool / InputTextTool`
- [x] 输入/点击等 UI 变更工具已纳入自动可见证据：`tap` / `tap_text` / `swipe` / `press_key` / `input_text`
- [ ] `ui_assert / ui_wait` 纳入模型常规验证路径
- [x] 浮层规则化：
  - `PROMO(AD) / PERMISSION / FUNCTIONAL_PICKER / TERMINAL_CONFIRM`
- [x] 坐标仅作为最后兜底，并明确返回 `coordinateFallback=true` 标记

### P2：沉淀场景 Skill

- [x] 瑞幸、企业微信官方 API、HA、远程各 1 条内置 Skill（个人微信除外）
- [x] Skill 记录：目的 / 预期 / 验证 / 兜底 / 终端停止点（已内置）
- [ ] 模型改为：
  ```text
  LLM 选择 Skill → 验证当前界面 → 按 Skill 执行 → 停在终端安全门
  ```

### P3：终端安全矩阵

- [x] 支付 / 消息发送 / 删除 / 配置修改 / Home Assistant 安防域 / 远程破坏性命令（类型矩阵已扩展）
- [ ] 每域定义：
  - 确认页特征
  - 禁止动作
  - 人工确认 UI
  - trace 标记

### P4：严格 E2E 与可观测性

- [x] 自动断言（脚本能力已落地）：
  - 前台包名
  - 关键 UI 文本
  - 浮层缺失
  - 未执行最终动作（`forbiddenTools` / `forbiddenTerms`）
  - `WAITING_CONFIRM`
  - 自动可见证据（`requireAutoVerify`）
- [x] trace 自动报告：
  - 路径 / 耗时 / 失败原因 / 截图 / 证据（`AgentTraceReportBuilder`；token/真机基线待补）
- [ ] 真机 + 模拟器双跑
- [ ] 失败自动归类

### P5：本地语音与长期稳定性

- [ ] 本地 KWS / ASR 1 小时 soak
- [ ] 低电量 / 灭屏 / 亮屏策略回归
- [ ] 语音触发 → Agent → TTS 端到端
- [ ] 唤醒率、误唤醒、功耗数据

### P6：能力扩展

- [ ] 日历 / 提醒 / 健康 / 出行 / 购物
- [ ] 远程开发 / 智能家居扩展
- [ ] 必须以稳定地基为前提，不反向引入新债

---

## 7. 本轮已落地（P0/P1）

- `UiActionLayer`：统一 UI 原语（id / text / desc / 坐标 / 等待 / 断言）
- `TapTool / TapTextTool / SwipeTool` 改走该层
- 新增 `UiAssertTool`，Agent 可用 `visible / not_visible / wait_for` 做确定性验证
- `TerminalSafetyGate` + `StopVerifier`：到达确认订单 / 免密支付 / 提交订单 / 发送确认页时强制 `WAITING_CONFIRM`
- 单测已补：`TerminalSafetyGateTest`、`TaskPlanTest` 终端页、`UiAssertToolTest`
- 真机回归受“重装后无障碍未重连 + 设备锁屏”阻塞，已记录到 PROGRESS 待恢复后验证

---

## 7.5 真机恢复后的最新进展（2026-08-30）

真机 `192.168.31.106:37459` 已重新在线并解锁，无障碍服务已成功连接：

- [x] `AgentAccessibilityService` 重连成功，App 进程 `instance` 正常
- [x] 修复：无障碍读取时主动刷新 `rootInActiveWindow`，解决切到新 App 后读不到当前窗口的问题
- [x] 修复：`TerminalSafetyGate` 增加前台包名感知，避免在言控自身界面把任务描述中的“免密支付/发送”误判为终端页
- [x] 新增：`AccessibilityService.takeScreenshot` 截屏兜底，不再依赖 Shizuku 也能返回带坐标网格截图
- [x] 通行证：瑞幸严格终端 E2E 已真正跑通，最终停在 `com.lucky.luckyclient` 的“确认订单/免密支付”页，未支付，`scenarioVerified=true`
- [x] 脚本修复：`launch_app` 改为 `am start -W` 并等待 1 秒，避免动态 receiver 未就绪导致广播丢失；补 `import re`
- [x] `input_text` 支持可选 `x/y` 先点击输入框再输入

### 真机全量严格 E2E 已通过

- [x] 瑞幸：停到确认订单/免密支付页，未支付，`scenarioVerified=true`
- [x] 微信：在“文件传输助手”输入框显示“收到，稍后回复”，出现绿色 Send 按钮，未点击发送，`scenarioVerified=true`
- [x] 微信文字注入修复：调整 `TextInputManager` 为「先无障碍粘贴、再无障碍设置文本」，微信输入框成功接收文字
- [x] `TerminalSafetyGate` 增加英文 `Send` 识别；由于微信不暴露可读节点，允许在最终消息中验证终端关键词（仅用于不可读 UI 的 App）
- [x] 全量 `phase5_terminal_scenarios.json` 真机 2/2 PASS
- 注意：微信“最近联系人”存在多个候选（公众号/群/文件传输助手），自动化中已改成明确“文件传输助手”作为可复现终端场景；真实使用仍需用户指定收件人。

---

## 7.6 真机执行耗时 Bad Case 分析与修复（2026-08-30）

已把真机 `files/agent_trace/agent_trace.log` 导入到仓库：

```text
imported_logs/real_device_agent_trace.log
```

从 trace 中发现以下会“大大拉长执行时间”的 bad case，并完成代码修复（尚未真机回归）：

### Bad Case 1：截图全量累积到 LLM 上下文

- 现象：微信不可读 UI 场景连续调用 `read_screen` 后，单次 LLM 请求从几百 KB 涨到 **10.6 MB**，单轮 LLM 等待可达 10～35 秒。
- 修复：
  - LLM 请求只保留最近 **2 张**截图，旧截图在历史中也会被裁剪。
  - `read_screen` 默认最长边从 0（原图）改为 **1440**，并把 JPEG 质量降到 80。
  - 无 Shizuku 的无障碍截屏也直接输出 JPEG，减少中间传输/解码成本。

### Bad Case 2：敏感操作确认无超时，可能挂 30 分钟

- 现象：远程 SSH 敏感操作进入 `WAITING_CONFIRM` 后，如果用户没有及时点击确认，Agent 会一直等待，trace 中出现约 **29 分钟**的空档，直到整体 600s 超时。
- 修复：
  - AgentSession 层增加敏感确认超时（默认 90 秒），超时按“未确认/拒绝”处理。
  - AgentViewModel 确认弹窗增加 60 秒超时并清理 pending 状态。
  - 后台/自动化路径仍然受立即同意/拒绝策略约束，不会无限挂起。

### Bad Case 3：视觉读屏无预算，模型反复截图

- 现象：微信/瑞幸卡住时，模型反复调用 `read_screen` / `get_screen_state(includeImage)`，某次任务 45 轮、19 张截图。
- 修复：
  - 新增 `maxVisualReadsPerRun`（默认 6）。超过后系统拦截并明确提示“不要再截图，改用已有信息或说明卡点”。
  - 保留原有连续重复感知拦截，形成双保险。

### Bad Case 4：Completion Check 两轮造成额外 LLM 往返

- 现象：很多成功任务在模型已经结束/停靠后，仍额外执行 2 轮 completion check，单次 8～13 秒，累计明显拖慢。
- 修复：
  - `maxCompletionChecks` 从 2 降到 **1**，减少一次不必要的完整 LLM 往返。

### Bad Case 5：`task_plan` 找不到步骤导致反复失败

- 现象：模型使用不精确 stepId 更新计划，连续出现“未找到步骤”，每个失败又触发一轮 LLM。
- 修复：
  - `task_plan update` 增加容错解析：支持 `step_N`、纯数字、序号、标题包含匹配。
  - 仍未找到时不再返回硬失败，而是返回当前计划并给出可读提示，让模型能基于计划继续/结束。

### 本次新增测试

- `vision history sent to llm keeps only last two screenshots`
- `visual read budget stops runaway screenshot loops`
- `sensitive confirmation times out and is recorded as denied`
- 原有 completion check 相关测试已同步为单次检查

> 注意：以上优化尚未在真机重新跑完整耗时基线；下次设备在离开前/回来后执行严格 E2E 对比耗时即可。

---

## 7.7 微信风控：默认禁用个人微信自动化（2026-08-30）

真机测试触发微信“账号安全使用提醒”，风险来源是模拟器/无障碍脚本自动操作个人微信。

处理原则：

- **个人微信自动化默认关闭**，即使 Agent 曾跑通过，也不再作为常规回归路径。
- 新增 `WechatRiskGuard`：
  - 默认 `automationAllowed=false`
  - 拦截 `wechat_open / wechat_read_messages / wechat_send_reply`
  - 拦截 `open_app` 目标为 `com.tencent.mm`
  - 拦截当前前台为个人微信时的所有 UI 读写/点击/输入工具
  - 企业微信 `com.tencent.wework` 不受影响
- 新增持久化开关 `wechatUiAutomationEnabled`，默认 false。
- 只有显式开启“微信小号风险模式”后才允许在专属小号上测试。
- 调试广播可切换：
  ```bash
  adb shell am broadcast -a com.voiceconfig.app.DEBUG_WECHAT_RISK --ez allowed true
  ```
- 微信 E2E 暂停：
  - 不再在模拟器/主号上跑微信打开、输入、发送类自动化。
  - 后续演示优先走企业微信官方 API 或人工小号验证。
- 新增单测：
  - `personal wechat automation is blocked by default`
  - `personal wechat automation can be enabled for explicit test accounts`

---

## 7.8 企业微信官方 API 与设置页落地（2026-08-30）

为替代个人微信自动化，新增合规发送通道：

- 新工具 `wecom_send_message`：
  - 通过企业微信官方 API 给成员/部门/标签发送应用消息
  - 需要配置 CorpId / AgentId / Secret
  - 工具元数据标记为 `HIGH / sensitive`，仍会进入敏感确认流程
- 新设置页 `EnterpriseWechatSettingsSection`：
  - 个人微信“小号风险模式”开关（默认关）
  - 企业微信 API 三要素配置
- 个人微信仍默认禁用；企业微信走官方 API 可作为产品化自动发送路径

---

## 7.9 模拟器 Mock LLM 与验证进展（2026-08-30）

新增无云模型依赖的 Mock LLM 调试模式，使模拟器可以真实验证 Agent 执行链路：

```bash
adb shell am broadcast -a com.voiceconfig.app.DEBUG_MOCK_LLM --ez enabled true
adb shell am broadcast -a com.voiceconfig.app.DEBUG_AUTO_CONFIRM --ez enabled true
```

模拟器验证结果：

- 打开设置：Agent 调用 open_app，整体约 5s。
- 读取当前界面：read_ui 正常返回无障碍节点。
- 截屏：read_screen 返回 base64 约 114KB～181KB，已从修复前 600KB+ 明显下降。
- 反复读屏压力：只真实执行 3 次截图即被“连续重复感知”拦截，未失控。
- 微信自动化：WechatRiskGuard 硬拦截，耗时 <1s，不再进入敏感确认等待。
- 企业微信发送：wecom_send_message 已注册并可调用；未配置凭证时明确失败。

---

## 7.10 执行层收敛与终端矩阵扩展（2026-08-30）

- `PressKeyTool` 已收敛到 `UiActionLayer`，减少重复无障碍/Shizuku 分支。
- `TerminalSafetyGate` 从支付/发送扩展到：
  - 删除/清空
  - 配置修改/覆盖
  - 智能家居安防
  - 远程破坏性操作
- 这些新增终端类型都会强制 `WAITING_CONFIRM`，不会自动执行。

---

## 7.11 可见证据闭环（2026-08-30）

- `input_text` 已纳入自动验证。
- `tap` / `tap_text` / `swipe` / `press_key` 已全部纳入自动验证。
- 自动验证优先 `read_ui`；当微信等 App 不暴露无障碍节点导致失败时，自动降级 `read_screen` 截图，保留可见证据。
- 单测覆盖：UI 读取失败 → 截图验证回退。
- 单测覆盖：所有 UI 变更类手机工具均要求自动可见证据。

---

## 8. 后续不做什么

- 不新增“听起来很酷但不可验证”的功能
- 不依赖 LLM 做精确点击
- 不把“草稿/准备”当成端到端通过
- 不绕过安全策略
- 不重复造多套执行器
