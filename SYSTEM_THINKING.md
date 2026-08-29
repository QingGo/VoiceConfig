# 言控 · 系统性思考与技术债

> 更新时间：2026-08-29
> 本文档回答三个问题：
> 1. 终极目标是什么？
> 2. 本轮 Session 发现了哪些技术债？
> 3. 之后的开发计划应如何安排？

---

## 1. 终极目标

我们不是在做“又一个语音助手”，而是在做**一个以手机为入口、以人为中心的个人数字执行 Agent**。

> 用户用一句话（或一个触发），Agent 能稳定理解、规划、执行、验证、汇报，完成真实世界里的多步数字任务。

核心不是“能聊天”，而是：

| 维度 | 目标 |
|---|---|
| 可靠 | 能执行、能验证，失败能降级/重试/通知 |
| 安全 | 敏感操作必须经过人确认，不可绕过 |
| 系统级 | 不只在 App 内；桌面、其他 App、锁屏附近都能入口 |
| 可积累 | 执行经验、技能、用户偏好能沉淀，越用越准 |
| 本地优先 | 核心能力不依赖单一云服务；语音与唤醒逐步本地化 |

---

## 2. 本轮 Session 发现的技术债

### 2.1 架构上仍有“隐形耦合”

`MainViewModel` 已经从 2282 行拆分到约 340 行，但当前用了运行时桥接：

```kotlin
viewModel.automationViewModel = automationViewModel
viewModel.agentViewModel = agentViewModel
viewModel.flushPendingGlobalVoice()
```

这是明显的过渡方案：

- 依赖注入关系不清晰
- 命令可能因注入时序丢失
- 测试困难
- 后续维护容易踩坑

**结论：需要正式的“跨层命令/事件总线”或“显式输入管道”。**

---

### 2.2 `VoiceConfigService` 开始变成上帝服务

当前它同时承担：

- 前台保活
- 定时任务恢复
- 无障碍保活
- 条件触发器
- 唤醒词
- 全局语音识别
- 悬浮球 UI
- 确认面板
- TTS 播报
- 前后台显隐控制

任何一个模块出问题，都会影响其他模块。

**结论：服务职责过重，无法独立测试、独立降级。**

---

### 2.3 全局语音链路仍是临时管道

当前链路：

```text
悬浮球/唤醒词
  → Service 内 SpeechRecognizer
  → Overlay 确认面板
  → startActivity + extra
  → MainActivity.handleGlobalVoiceIntent()
  → MainViewModel.submitGlobalVoiceCommand()
  → AgentViewModel.sendAgentMessage()
```

问题：

- 用 Activity Intent 携带命令，不是事件流
- 没有命令 ID / 去重 / 确认回执
- 没有统一的语音会话状态机跨 Service / App
- 极难写端到端测试

**结论：需要把全局语音改造成一条可观测、可测试、可恢复的管道。**

---

### 2.4 语音识别仍是最大不稳定点

当前全局聆听使用 Android `SpeechRecognizer`：

- 依赖系统 ASR
- 在 MIUI 后台容易被限制
- 高耗电
- 无法真正“低功耗远场唤醒”
- 真机上权限/系统策略会导致不稳定

**结论：需要本地 ASR / 本地唤醒词作为主路径，系统 ASR 只做兜底。**

---

### 2.5 悬浮窗权限与生命周期不够稳

真机验证发现：

- 通过 adb `appops` 临时授权的悬浮窗权限，过一段时间可能被系统恢复成 `ignore`
- 用户必须通过系统设置正式授权
- 目前只有提示，缺少“权限被收回后自动检测并引导”的强闭环

**结论：权限状态应纳入统一 CapabilityStatus，并做到实时检测、主动引导。**

---

### 2.6 UI 仍有一些不一致

- 自动化页悬浮球会遮挡任务卡片
- 信息密度仍然偏高
- 还没有截图自动对比，无法防止未来回归
- 系统球与 App 内球已经做了显隐控制，但缺少更系统的“全局语音 UI 状态”

---

### 2.7 缺少端到端验证

当前验证主要是：

- 单元测试
- 模拟器导航冒烟
- 真机手动截图

缺少：

- 全局语音 → 确认 → Agent → 敏感操作确认的自动验证
- 悬浮球状态机测试
- 命令丢失/重复/乱序测试
- Luckin / 微信 / HA 真实闭环回归

---

## 3. 之后的开发计划

### 第一阶段：把“全局语音管道”做成正式组件

> 状态：✅ 已完成（本轮实现 `VoiceCommandCenter`，MainViewModel 桥接已移除）

目标：消除隐形桥接，让全局语音成为和 App 内语音完全同等的输入源。

#### 要做

1. 新增 `VoiceCommandCenter` / `GlobalVoiceCommandBus`
   - Hilt Singleton
   - `SharedFlow<GlobalVoiceCommand>`
   - 命令包含：`commandId`、`text`、`source`、`timestamp`、`confirmationToken`
   - Service 发送，App 订阅
   - 带去重、超时、ack

2. 移除 `MainViewModel` 中的可变桥接：

```kotlin
var automationViewModel: AutomationViewModel?
var agentViewModel: AgentViewModel?
```

改为由 `VoiceCommandCenter` + 明确 ViewModel 订阅。

3. 所有语音入口统一走同一个入口函数：

```text
App内语音
全局悬浮球
唤醒词
调试广播
→ VoiceCommandCenter
→ Agent 单管道
```

**验收：不再存在“命令因注入时序丢失”。**

---

### 第二阶段：重构全局语音服务

把 `VoiceConfigService` 拆成职责清晰的组件：

| 组件 | 职责 |
|---|---|
| `VoiceKeepAliveService` | 前台保活、定时、条件触发 |
| `GlobalOverlayController` | 悬浮球 UI、拖拽、显隐、确认面板 |
| `GlobalVoiceSession` | 聆听、超时、确认、播报状态机 |
| `WakeWordEngine` | 本地唤醒词，低功耗 |
| `AsrEngine` | 本地 ASR 优先，系统 ASR 兜底 |

服务只做“宿主”，全部逻辑下沉到可测试的 Singleton。

**验收：Overlay 状态机可以脱离 Android 服务做单元测试。**

---

### 第三阶段：本地语音与低功耗

这是真正走向“系统级 Agent”的关键：

- 引入本地唤醒模型（类似 Porcupine / Vosk / sherpa-onnx keyword）
- 本地 ASR 优先（已有 sherpa-onnx 基础）
- 系统 `SpeechRecognizer` 仅作为降级
- 屏幕关闭时暂停，亮屏/充电时恢复
- 增加“低电量自动关闭全局聆听”策略

**验收：连续唤醒 1 小时不显著耗电，且不需要云服务。**

---

### 第四阶段：安全与验证闭环

所有语音命令，不分来源，必须走同一套安全管线：

```text
AgentPreflight
 → Agent 规划
 → 敏感操作确认
 → 执行
 → 验证
 → 汇报
```

全局球可以负责“快速确认”，但：

- 不可逆操作仍必须在 Agent 层二次确认
- 不能因为“已经语音确认过”就绕过系统安全策略
- 每个全局命令都有 trace，能从语音到执行完整回放

---

### 第五阶段：真实场景端到端回归

优先级顺序：

1. 企业微信定时打开
2. 瑞幸点单
3. 微信回复
4. Home Assistant 控制
5. 远程设备管理
6. 长程购物比价

每个场景都要有：

- 自动化脚本
- 真机冒烟
- 失败降级路径
- 敏感操作确认测试

---

## 4. 从类似项目借鉴什么

### Google Assistant / Alexa

**借鉴：**

- 全局唤醒 + 多轮对话
- 明确的 Voice Session 状态机
- 系统级入口不只是一个按钮，而是一条完整会话

**不学：**

- 不依赖云 ASR
- 不做大面积隐私采集
- 不做“未确认就执行”的激进路径

---

### Raycast / Alfred

**借鉴：**

- 全局命令入口
- 命令注册表
- 最近使用
- 键盘/快捷触发
- 本地优先、低延迟

**启示：**

- 悬浮球应该是“全局命令入口”，不是“打开 App 的快捷方式”
- Agent 技能应该像 Raycast 插件一样可注册、可启用、可禁用、可审计

---

### Tasker / Automate / iOS Shortcuts

**借鉴：**

- 触发器 + 条件 + 动作的确定性模型
- 用户可理解、可配置
- 执行日志和重试

**启示：**

- 不要把所有判断交给 LLM
- 定时、位置、Wi-Fi、电量这些应该由确定性调度器完成
- LLM 只负责“语义理解”和“复杂规划”

---

### Home Assistant

**借鉴：**

- 统一设备/实体模型
- Service Call 抽象
- 状态可查询、可验证
- 高度可扩展

**启示：**

- HA、远程节点、手机 App、SSH 都应该统一成“能力/实体”
- 不要每个功能各写一套状态

---

### Agent / Tool Use 框架

**借鉴：**

- Tool Schema
- Function Calling
- 计划-执行-验证循环
- 记忆与技能沉淀

**启示：**

- Agent 必须是唯一执行器
- 语音、自动化、悬浮球都只是“输入触发器”
- 所有工具调用要有 schema、权限、审计

---

## 5. 如何避免相互冲突

我们最大的风险不是缺功能，而是**多个入口各自维护一套逻辑**。

### 硬原则

1. **只有一个执行器**
   - `AgentSession` 是唯一执行器
   - 语音、悬浮球、自动化、调试广播都只是输入

2. **只有一份能力状态**
   - `CapabilityStatus` 是唯一事实来源
   - 权限、ASR、无障碍、Shizuku、HA、远程都从它读取

3. **只有一条命令管道**
   - 全局语音、App 内语音、快捷任务都进同一套 Command Pipeline
   - 不允许 Service 直接调 ViewModel 内部状态

4. **所有敏感操作统一安全策略**
   - 不能因为来源不同而绕过确认
   - 悬浮球确认只算“意图确认”，不算“安全确认”

5. **先稳定再扩展**
   - 不再新增小功能
   - 优先级：全局语音管道稳定 → 本地 ASR/唤醒 → 真实场景闭环 → 自动化回归

---

## 6. 下一步唯一选择

如果只能选下一步，我会选：

> **把全局语音从“临时 Intent 管道”升级为正式的 `VoiceCommandCenter`，并让悬浮球状态机成为可测试的独立组件。**

因为这是当前离“系统级 Agent”最近、也最脆弱的一环。

这一步做完后：

- 全局语音可测试
- 不再有桥接丢命令
- 服务可以拆分
- 本地 ASR 可以平滑替换
- 真实场景回归可以自动化

然后才进入本地唤醒、低功耗和场景闭环。
