# VoiceConfig Session Recap

> 记录时间：2026-08-30
> 适用设备：`192.168.31.109:42097`（M2102K1C / Android 14 / 1440x3200）
> 关联文档：[SYSTEM_THINKING.md](../SYSTEM_THINKING.md)、[PROGRESS.md](../PROGRESS.md)

---

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
- [x] 无障碍点击/滑动/返回实际可用

### 脚本

- [x] `scripts/phase4_safety_regression.sh`
- [x] `scripts/phase5_e2e_scenarios.json`
- [x] `scripts/phase5_e2e_regression.sh`
- [x] `scripts/phase5_real_scenarios.json`
- [x] `scripts/phase5_real_e2e.sh`
- [x] `scripts/phase5_real_foreground.py`

---

## 5. 新发现的问题

### 5.1 UI Action 层缺失

这是目前最大的技术债。

当前工具是：

```text
tap(x, y)
tap_text(text)
input_text(text)
swipe(...)
```

缺少统一选择器层：

```text
tap_by_id
tap_by_text
tap_by_desc
wait_for
assert_visible
```

导致：

- 坐标点错商品
- 无文字按钮找不到
- 页面状态无法稳定断言

### 5.2 Terminal Safety Gate 缺失

- 已经到达“免密支付”页
- 但 StopVerifier 仍可能判为：
  ```text
  任务计划尚未完成
  ```
- 需要明确识别：
  ```text
  确认订单 / 免密支付 / 发送确认 / 提交订单
  ```
- 到达后应强制：
  ```text
  WAITING_CONFIRM
  ```

### 5.3 E2E 验证不够严谨

- 目前 `agent_scenario_eval.py` 主要看 `result.ok`
- 需要断言：
  - 前台包名
  - 关键 UI 文本
  - 浮层不存在
  - 未点击最终支付/发送
  - 有 trace/截图

### 5.4 Overlay 识别仍是启发式

- `close_iv` 已修
- 但其他 App 浮层/营销层缺少通用确定性关闭
- 需要统一“可关闭层”识别和关闭原语

### 5.5 模型行为需要收敛

- 重复读屏
- 长篇推理
- 猜坐标
- 同一流程不稳定

### 5.6 MIUI/Shizuku 生态

- 无障碍 force-stop 后掉线
- Shizuku 不可用时：
  - 截图不可用
  - 部分 shell 输入不可用
  - 部分场景只能降级

---

## 6. 计划要完成的部分

### P0：UiActionLayer

建立统一 UI 操作层：

```text
tap_by_id
tap_by_text
tap_by_desc
tap_center
swipe
back
input
wait_for
assert_visible
assert_not_visible
```

原则：

- 优先 resource-id
- 其次 text / content-desc
- 坐标只作最后兜底

### P1：Terminal Safety Gate

StopVerifier 增加终端状态识别：

```text
确认订单
免密支付
发送消息确认
提交订单
```

到达后：

```text
清理浮层
→ 强制 WAITING_CONFIRM
→ 不再判 incomplete
```

### P2：严格 E2E 断言

每个场景自动断言：

- 前台包名
- 关键 UI 文本
- 浮层不存在
- 未执行最终动作
- trace/截图留档

### P3：场景化 Skill

瑞幸、微信、企业微信、HA、远程做成确定性 Skill：

```text
LLM 理解需求
→ 选择 Skill
→ Skill 执行精确 UI 步骤
→ 停在终端安全门
```

### P4：可靠性

- AccessibilityKeepAlive 状态机
- 真机/模拟器双跑
- force-stop 后自动重连
- 失败重试/降级

### P5：本地化与功耗

- 本地 KWS 已验证接入
- 本地 ASR 优先
- 低电量策略
- 连续唤醒功耗验收

---

## 7. 后续不做什么

- 不新增“听起来很酷但不可验证”的功能
- 不依赖 LLM 做精确点击
- 不把“草稿/准备”当成端到端通过
- 不绕过安全策略
- 不重复造多套执行器
