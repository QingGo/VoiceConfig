# VoiceConfig 开发进度

> 系统性战略与技术债分析见 [SYSTEM_THINKING.md](SYSTEM_THINKING.md)
> 最新战略复盘见 [STRATEGIC_REVIEW.md](STRATEGIC_REVIEW.md)
> 本 Session 完整复盘见 [SESSION_RECAP.md](SESSION_RECAP.md)

> 更新时间：2026-08-27
> 依据：DESIGN.md

## Phase A：可靠性地基

| 项 | 状态 | 说明 |
|---|---|---|
| A1 安全四级 | ✅ | SafetyLevel READ_ONLY/PREPARE/CONFIRM/IRREVERSIBLE；硬拦截不可绕过 |
| A2 工具分层 | ✅ | CORE/PHONE/REMOTE 默认暴露；HOME/RESEARCH/APP_SKILL 预留 |
| A3 Run/Task 持久化 | ✅ | TaskPlan Room；AgentRunRecord Room；新增加安全指标持久化 |
| A4 Capability Preflight | ✅ | AgentPreflight 阻断缺 API/网络/UI 能力；用于后台与立即执行 |
| A5 审计与指标 | ✅ | Trace 安全事件 + RunRecord 安全计数 + 运行记录 UI 展示 |

## Phase B：确定性自动化

| 项 | 状态 | 说明 |
|---|---|---|
| B1 企业微信定时打开 | ✅ | 默认模板 + create_scheduled_task；真机验证 Agent 可打开企业微信 |
| B2 执行后验证 | ✅ | VerificationMatrix 前台包名/TASK_CREATED/UI 证据 |
| B3 失败通知 | ✅ | 后台 Agent 失败通知 + 一键重试 |
| B4 语音创建定时任务 | ⏳ | 已有 ASR 输入通道，尚未做专门的语音闭环优化 |

## Phase C：瑞幸点单

- [x] luckin_prepare_order：生成确认清单（未下单）
- [x] luckin_open：打开瑞幸 App
- [x] LuckinOrderSession：门店/饮品/加购/确认状态机
- [ ] 真实 UI 选品/加购/支付前确认
- [ ] 支付前强制人工确认与审计
- [x] 内置 4 条 APPROVED Skill（瑞幸/企业微信/HA/远程）

## Phase D：微信消息助理

- [x] wechat_draft_reply：安全生成回复草稿（不自动发送）
- [x] wechat_open / wework_open：直接打开微信/企业微信
- [x] wechat_read_messages：读取微信当前页消息（依赖无障碍/Shizuku）
- [x] wechat_send_reply：仅在 humanConfirmed=true 且系统确认后发送

## Phase E：智能家居

- [x] Home Assistant 接入：Base URL + 长期访问令牌设置
- [x] home_devices / home_control Agent 工具
- [ ] 语音控制实验（依赖语音闭环继续）

## Phase F：长程研究与购物

- [x] 商品结构化模型 ProductInfo
- [x] product_compare：价格/评分/评价比较与推荐
- [x] 长程采购清单持久化：shopping_save / shopping_list / shopping_update_status
- [x] product_search：基于 DeepSeek Web Search 的商品搜索入口
- [x] product_extract：从搜索文本提取结构化 ProductInfo

## Phase G：远程开发深化

- [x] RemoteProject 工具：inspect/build/test/install
- [x] RemoteProject 持久化：Room 表 + Repository + inspect 自动保存
- [x] 远程项目工作区界面：设置页可查看已保存项目
- [x] remote_project_verify：自动构建+测试验证
- [x] 自动修复循环：Agent 通过 stderr + remote_ssh_write + verify 闭环

## Phase H：语音闭环

- [x] TTS：Agent 结果可语音播报，设置开关
- [x] VoiceSession 状态机（多轮语音基础）
- [x] Agent 主流程已接入 VoiceSession（开始/等待/完成）
- [x] WakeWordDetector：SpeechRecognizer 关键词唤醒基础版
- [x] 唤醒开关与前台保活服务集成（需麦克风权限）
- [ ] 低功耗远场唤醒优化与真机验证

## UX/UI 重构（按 UX_REDESIGN.md / PRODUCT_RETHINK.md）

- [x] 对话优先首页：Hero + 快捷任务 + 历史收起 + 输入框常驻
- [x] 输入框内嵌麦克风，移除首页悬浮麦克风
- [x] Onboarding 实际串行请求通知/麦克风权限
- [x] 自动化页「让言控创建」主入口
- [x] 设置模型改为状态卡，Key 默认隐藏
- [x] 开发者模式隐藏 SSH/远程/调试
- [x] 设置页改为即时保存，去掉强制“保存”
- [x] 导航从 HorizontalPager 改为简单 state-based Tab，降低架构复杂度
- [x] 自动化页面从 MainActivity 拆分为独立 AutomationScreen.kt
- [x] 顶级导航改为 sealed AppDestination（Conversation/Automation/Profile）
- [x] MainScreen/MainScaffold 从 MainActivity 拆出，MainActivity 降至 ~286 行
- [x] 语音/ASR 逻辑抽出为 VoiceInputController
- [x] 新增 VoiceStatusCard 并统一我的状态面板
- [x] AgentPage 对话组件拆分为 ConversationComponents.kt（AgentPage 降至 ~880 行）
- [x] SettingsScreen 全部主要 section 已拆分（SettingsScreen 降至 ~575 行）
- [x] P0-1 首页/会话页信息层级重构：快捷任务、最近会话、轻量状态卡
- [x] P0-2 首次启动 Onboarding（4 步，可跳过）
- [x] P0-4 底部导航：首页/对话 / 自动化 / 我的
- [x] P1-6 语音开关从对话输入区移入「我的 → 语音」
- [x] P1-7 自动化页「+ 新建任务」主按钮
- [x] P1-8 执行记录去成功率，改为「最近执行 + 可展开」
- [x] P2-9 品牌色改为深靛蓝 #4F46E5，更新主题
- [x] P1-5 远程工具图标改用 Material 图标，触发器状态去掉 Emoji；聊天/文件内 Emoji 待后续替换
- [x] P2-10 Home Assistant：独立设备面板 + 设置页测试连接/开关；购物研究独立页面完成
- [x] P2-11 语音唤醒引导（会话输入区一次性提示 + 设置页语音入口）
- [x] P2-12 远程高级能力统一设计：统一入口 + Material 图标 + 默认折叠
- [x] Adaptive Icon（新增 mipmap-anydpi-v26 + 前景矢量）

## Phase 1：全局语音入口（已完成）

- [x] 正式 VoiceCommandCenter / GlobalVoiceCommandBus：Hilt Singleton + SharedFlow<GlobalVoiceCommand> + 去重/超时/ack；Service 发送，Agent/Automation ViewModel 明确订阅，MainViewModel 可变桥接已移除
- [x] 所有语音入口统一：App 内语音 / 全局悬浮球 / 唤醒词 / 调试广播均经 VoiceCommandCenter 单管道
- [x] 系统级悬浮球基础：VoiceKeepAliveService 在获得悬浮窗权限后显示可拖动「言」球，短按聆听、长按打开 App，位置本地记忆
- [x] 真机校准：悬浮球改用 dp 尺寸（默认 60dp，聆听 150x64dp）；App 前台自动隐藏系统球，回到桌面后自动恢复
- [x] 全局语音结果直接驱动 Agent：悬浮确认后开始 App 并提交 Agent，不依赖“自动发送”开关
- [x] 权限引导：设置 → 权限与系统新增「悬浮球（系统级语音入口）」，语音设置页新增「系统级悬浮球」开关
- [x] 全局唤醒词基础：WakeWordDetector 已在保活服务中监听「言控」并拉起 App
- [x] 悬浮球基础语音生命周期：点击/唤醒后悬浮球进入聆听态，识别结果交给 Agent，结束后恢复唤醒监听
- [x] 全局聆听超时保护（12 秒自动结束）
- [x] 聆听时悬浮球展开为更大胶囊并显示识别片段
- [x] 悬浮球确认面板：识别后弹出「执行/取消」确认，确认后交给 Agent
- [x] 悬浮球确认后 TTS 播报“好的，正在处理”
- [x] 低功耗：屏幕关闭时暂停唤醒词与全局聆听，亮屏自动恢复
- [ ] （后续强化）本地小模型低功耗唤醒
- [x] 低功耗与前台服务优化（亮/灭屏策略 + 聆听超时）
- [x] 悬浮球记住最后位置并在重启后恢复

## Phase 2：全局语音服务重构（已完成）

- [x] VoiceKeepAliveService：仅保留前台保活、条件触发、通知与调度；不再直接持有 Overlay/识别/确认逻辑
- [x] GlobalOverlayController：悬浮球 UI、拖拽、显隐、确认面板独立成 Singleton
- [x] GlobalVoiceSession：聆听、超时、确认、提交命令状态机，独立于 Android Service
- [x] GlobalVoiceStateMachine：纯 Kotlin 状态机，已增加 JVM 单测
- [x] GlobalSpeechRouter / GlobalSpeechInput：本地 ASR 优先、系统 SpeechRecognizer 兜底
- [x] GlobalWakeWordEngine：监听会话状态自动暂停/恢复，屏幕关闭可 pause/resume
- [ ] （后续强化）Overlay 状态机接入 Compose/截图回归；本地小模型低功耗唤醒仍属第三阶段

## Phase 3：本地语音与低功耗（组件/策略已完成，模型资产生效中）

- [x] GlobalPowerPolicy：灭屏 / 低电量 / 充电状态统一门控全局聆听
- [x] LocalSherpaKeywordSpotter：基于 sherpa-onnx KeywordSpotter + AudioRecord 的本地唤醒实现，模型缺失时自动降级系统 WakeWordDetector
- [x] GlobalSpeechRouter：本地 ASR 优先、系统 SpeechRecognizer 仅作兜底
- [x] 低电量自动关闭全局聆听，充电后恢复
- [x] 灭屏暂停，亮屏/充电恢复
- [x] KWS 模型资产已随包内置：`assets/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01`（ModelScope 下载，约 4.9MB）
- [ ] 连续唤醒 1 小时不显著耗电的真机验收

## Phase 4：安全与验证闭环（已完成核心链路）

- [x] 所有 Agent 命令统一走 `AgentPreflight → 规划 → 敏感确认 → 执行 → 验证 → 汇报`
- [x] AgentViewModel/AgentSession 已接入确定性能力预检，blocker 直接阻止执行并写入 trace
- [x] 全局语音命令携带 `commandId / source / confirmationToken / timestamp` 进入 AgentTrace
- [x] trace 可从 `voice_origin` 回放到 `user_input`、`preflight`、`safety_evaluate`、`tool_call`、`verification`、`run_finished`
- [x] 后台定时 Agent 与手动 Agent 也传入 preflight，统一安全记录
- [x] 真机验证：debug voice -> trace 已出现 `voice_origin` + `preflight`；敏感发送类命令被能力预检直接阻止，未产生工具调用
- [ ] 不可逆操作真机演练（在已具备无障碍/Shizuku 的真机上验证支付/发送/删除类命令停在确认页）
- [ ] 语音“快速确认”与 Agent 层二次确认的 UI/真机完整演练

## Phase 0：地基稳定（已完成）

- [x] 正式引入 Navigation Compose：Conversation / Automation / Profile 为三个顶层路由，Shopping / Home Assistant 为二级路由，不再使用 boolean overlay
- [x] 统一返回行为：二级页面 popBackStack，首页返回桌面，悬浮球仅首页/自动化显示
- [x] 拆分 SshViewModel：远程节点、SSH 命令/文件/Shell/服务/密钥全部移出 MainViewModel
- [x] 拆分 ProfileViewModel：模型/语音/主题/Home Assistant/AI 调试日志/设置项移出 MainViewModel
- [x] 拆分 AutomationViewModel：任务/模板/解析/运行/触发器/日志总结全部移出 MainViewModel
- [x] 拆分 AgentViewModel：Agent 会话/消息/执行/技能/语音会话状态全部移出 MainViewModel，MainViewModel 已降至约 340 行
- [x] 建立 CapabilityStatus 统一模型：AI / 网络 / 无障碍 / Shizuku / HA / 远程 / 唤醒词，首页、设置与 Onboarding 共用
- [x] 建立 DesignTokens：Spacing / Radius / SemanticColors
- [x] 新增 AppRoutesTest、CapabilityStatusTest 基础单元测试
- [x] 四个 Feature ViewModel 已全部拆分：Agent / Automation / Ssh / Profile
- [x] 新增 Phase 0 模拟器导航冒烟脚本 scripts/phase0_nav_smoke.sh（首页/自动化/设置/返回检查）
- [ ] （后续强化）Compose UI 测试与截图自动对比

## Phase 5：真实场景端到端回归（开始执行）
- [x] Real foreground verify: Luckin / WeChat / WeCom / Settings 4/4 PASS (com.lucky.luckyclient, com.tencent.mm, com.tencent.wework, com.android.settings)

- [x] 企业微信定时打开：真机 PASS（create_scheduled_task 成功）
- [x] Phase 4 敏感确认：远程 shell 命令触发 WAITING_CONFIRM，未被语音/调试命令绕过
- [x] Phase 4 能力预检：瑞幸点单 / 微信发送等 UI 命令在无无障碍/Shizuku 真机上被 preflight 阻断，trace 完整
- [x] 瑞幸点单真实 UI 选品/加购/确认：真机已到达“确认订单/免密支付”页并停住，严格 E2E 断言通过
- [x] 微信回复真实发送：真机已到“文件传输助手”输入框显示“收到，稍后回复”+ Send 按钮，未点击发送，严格 E2E 通过
- [ ] Home Assistant 控制：需配置 HA token 后跑通 home_devices/home_control
- [ ] 远程设备管理：本轮已验证敏感确认，节点清单/远程构建待配置节点后回归
- [ ] 长程购物比价：待跑通 product_compare / product_search 全链路
- [ ] 新增脚本：`scripts/phase4_safety_regression.sh`、`scripts/phase5_e2e_scenarios.json`、`scripts/phase5_e2e_regression.sh`

## P0/P1：本轮实现

- [x] `UiActionLayer`：统一 `tapById / tapByText / tapByDesc / tapCenter / swipe / back / input / waitFor / assertVisible / assertNotVisible`
- [x] `TapTool / TapTextTool / SwipeTool` 已收敛到 `UiActionLayer`
- [x] `UiAssertTool`：确定性 `visible / not_visible / wait_for` 断言，已注册进核心工具集
- [x] `UiWaitTool`：显式 `wait_for` 等待工具，与 `ui_assert` 同源并已注册
- [x] `TerminalSafetyGate`：识别确认订单 / 免密支付 / 提交订单 / 消息发送确认页
- [x] `StopVerifier` 终端页强制 `WAITING_CONFIRM`，不再判为未完成 / DONE
- [x] 单测覆盖：TerminalSafetyGateTest、TaskPlanTest 终端页、UiAssertToolTest
- [x] 真机瑞幸 E2E：已重连无障碍并在真机跑到免密支付页，严格断言 `scenarioVerified=true`
- [x] 严格 E2E 断言脚本：`agent_scenario_eval.py` 已支持 `expectedForeground / terminalText / absentText / requireWaiting / requireAutoVerify / forbiddenTools / forbiddenTerms`
- [x] 新增 `scripts/phase5_terminal_scenarios.json`：瑞幸免密支付、微信发送确认的严格终端场景
- [x] 新增无障碍截屏兜底：`AccessibilityService.takeScreenshot`，即使无 Shizuku 也能给模型提供视觉截图
- [x] 微信已通过无障碍粘贴输入“收到，稍后回复”，并停在 Send 发送前一步
- [x] 真机全量严格终端 E2E：`phase5_terminal_scenarios.json` 2/2 PASS（瑞幸 + 微信）

## 速度优化（本轮新增，待真机回归）

- [x] 截图历史裁剪：LLM 请求只保留最近 2 张截图，避免 10MB+ 请求体
- [x] 截图瘦身：`read_screen` 默认最长边 1440、JPEG 质量 80；无障碍截屏直接 JPEG
- [x] 敏感确认超时：AgentSession 90s + AgentViewModel 60s，避免远程 SSH 类操作挂 30 分钟
- [x] 视觉读屏预算：每任务最多 6 次，超限拦截并提示改用已有信息
- [x] Completion Check 从 2 次降为 1 次，减少多余 LLM 往返
- [x] `task_plan update` 容错解析 stepId，减少“未找到步骤”循环
- [x] 新增 3 个 JVM 单测覆盖截图裁剪、视觉预算、敏感确认超时
- [ ] 真机回来后重新跑严格 E2E，对比修复前后耗时基线

## 微信风控保护（2026-08-30）

- [x] `WechatRiskGuard`：默认关闭个人微信 UI 自动化，硬拦截微信打开/读取/发送/输入/点击
- [x] `AgentSafety` 接入前台包名，`com.tencent.mm` 前台时默认阻断所有 Agent UI 操作
- [x] `ApiKeyStore.wechatUiAutomationEnabled` 持久化开关，默认 false
- [x] 调试广播 `DEBUG_WECHAT_RISK` 可临时开启风险模式（仅限专属小号测试）
- [x] 单测覆盖：默认阻断、显式开启后放行
- [ ] 微信 E2E 迁移：企业微信官方 API / 专属小号人工验证
- [x] UI 设置页增加“微信小号风险模式”开关和企业微信 API 配置

## 企业微信合规发送通道（2026-08-30）

- [x] `wecom_send_message` 工具：企业微信官方 API 发送应用消息
- [x] 设置页新增：个人微信小号风险模式 + 企业微信 CorpId/AgentId/Secret 配置
- [x] 工具元数据：`HIGH / sensitive`，发送前仍需用户确认
- [x] 已注册到 Agent 工具集与 schema
- [x] 设置页已加企业微信 API 凭证测试；[ ] 真机/模拟器接入真实测试号验证发送

## 模拟器 Mock LLM 验证通道（2026-08-30）

- [x] `AgentChatClient` 支持 Mock LLM 模式，无需 API Key 即可跑 Agent 流程（模拟器 10/10）
- [x] 调试广播 `DEBUG_MOCK_LLM`：开启/关闭 mock
- [x] 调试广播 `DEBUG_AUTO_CONFIRM`：自动化验证敏感工具时临时同意
- [x] 模拟器已验证：
  - 打开设置
  - 读取当前界面
  - 截屏（base64 约 114KB～181KB，较修复前 600KB+ 大幅下降）
  - 反复读屏压力：3 次真实截屏后被重复感知拦截，未失控
  - 微信自动化：被风控守卫硬拦截，耗时 <1s
  - 企业微信发送工具：成功注册并可调用，缺配置时明确失败

## 执行层收敛与终端矩阵扩展（2026-08-30）

- [x] `PressKeyTool` 改为统一走 `UiActionLayer`，新增 `home()` / `keycode()`
- [x] `DismissPopupsTool` 的快速关闭/资源ID/坐标点击全部改为经 `UiActionLayer`
- [x] `TerminalSafetyGate` 扩展终端类型：
  - `DELETE`
  - `CONFIG`
  - `HOME_SECURITY`
  - `REMOTE_DESTRUCTIVE`
- [x] 每域定义：确认页特征、禁止动作、人工确认 UI、trace 标记
- [x] `terminal_gate` trace 事件：到达终端页时记录域/动作/确认 UI
- [x] 单测覆盖删除/配置/安防/远程破坏性终端识别与域矩阵
- [ ] 真机/模拟器分别验证这些新终端停止点

## 可见证据闭环（2026-08-30）

- [x] `input_text` 开启自动验证
- [x] `tap` / `tap_text` / `swipe` / `press_key` 开启自动验证
- [x] 自动验证 `read_ui` 失败时降级到 `read_screen`，为微信等不可读 UI 保留截图证据
- [x] 新增单测：UI 读取失败时自动回退截图验证
- [x] 新增单测：所有 UI 变更类手机工具都要求自动可见证据

## 已提交代码

- 安全四级与审计
- 能力预检
- 工具分层
- 企业微信模板与失败重试
- RemoteProject 工具与持久化
- 安全指标持久化与 UI
- Home Assistant 桥与 home_devices/home_control
- product_compare 购物比较工具
- Agent 结果 TTS

## 最新真机/模拟器执行层推进（2026-08-30）

- [x] `ReadUiTool` 完全收敛到 `UiActionLayer`
- [x] 浮层分类扩展到权限弹窗和终端确认页
- [x] 内置 4 条 APPROVED Skill，设置页展示技能清单
- [x] `AccessibilityKeepAlive` 自适应重试 + 锁屏恢复
- [x] TraceReport 支持耗时/工具序列/截图/Token/失败分类/Markdown 导出
- [x] 企业微信 API 凭证测试按钮 + CLI 脚本
- [x] Agent 真实工具失败不再被“完成”掩盖
- [x] 真机 `192.168.31.111:39865` 已连接、安装最新 APK、无障碍已绑定
- [x] 新增一键真机准备脚本：`scripts/real_device_setup.sh`
- [x] 真机 Mock LLM E2E **14/14 通过**（含自动可见证据、`ui_wait`、安全/配置缺失守卫）
- [x] 真机单步耗时基线（Mock LLM）：read_ui ~0.6s / tap_text+验证 ~0.8s / swipe+验证 ~0.6s / ui_wait ~0.4s / open_app+验证 ~1.6s
- [x] 真机打开瑞幸/企业微信：`open_app` 冷启动验证通过（修复 MIUI 冷启动无障碍延迟导致的误报失败）
- [x] 真实 LLM 瑞幸终端 E2E：当前真机 PASS（耗时 198.6s / 40 工具调用 / 停在免密支付 / 未点击支付/提交订单 / 未执行禁止动作）
- [x] 新增 `luckin_quick_order` 快速点单宏：关闭 DeepSeek 推理后，真机可压到约 80s；调用宏后工具调用降到 5 次、约 21s（当前购物车非空，尚需清空购物车后复测全新下单）
- [x] 清空购物车后全新下单复测：**11.7s / 3 次工具调用**，瑞幸真实点单全程由 `luckin_quick_order` 完成并停在免密支付页，未支付
- [x] 将专用点单宏重构为**通用 FlowScript + UiFlowExecutor**：瑞幸流程已变成数据脚本，新增 App/流程不再需要新增专用状态机工具
- [x] 新增 `DEBUG_THINKING` / `DEBUG_AUTO_VERIFY_MAX` 调试广播，便于真机性能调参
- [x] 瑞幸内置 Skill / `LuckinOpenTool` 包名统一为真机实际包名 `com.lucky.luckyclient`
- [x] 模拟器 Mock LLM E2E 14/14 通过
- [x] 真机 + 模拟器双跑：`scripts/dual_device_mock_e2e.sh` 双设备 14/14 通过
- [ ] 真实 Home Assistant / 海信空调 / 百褶帘 / 树莓派 SSH 联调
- [ ] 低成本 DIY 智能家居固化为可验证的 ESPHome/Skill 路径

## 无 Shizuku / 熄屏策略（2026-08-30）

- [x] 真机实测：熄屏 + 仅无障碍时，定时打开企业微信失败（`open_app` 无法验证前台）
- [x] 确认原因：无障碍不能操作灭屏界面；无 Shizuku 无法 shell 唤醒；后台 `startActivity` 受系统限制
- [x] 确认 Shizuku 权限不稳定：重启/断网/无线调试关闭都会导致权限丢失，不能作为消费级稳定依赖
- [ ] 实现强提醒模式：闹钟铃声 + 震动 + 全屏通知（无需 Shizuku）
- [ ] 无 Shizuku 熄屏任务默认降级：高优先级通知/全屏提醒 → 用户点亮 → Agent 继续
- [ ] 在能力状态中增加“执行模式”：Ambient（无障碍亮屏）/ Assist（Shizuku/root）/ Notify（无 Shizuku 熄屏提醒）
- [ ] 增加 `VIBRATE` 权限和震动/铃声全屏提醒工具

## FlowScript 平台化（2026-08-30 续）

- [x] `FlowScript` 增加 `schemaVersion / version / description / status / source / enabled / forbiddenActionTokens`
- [x] `FlowScriptCodec`：统一 JSON 格式 `voiceconfig-flow-script`，支持导入/导出/parse/validate
- [x] `FlowScriptStorage` 抽象 + SharedPreferences 实现；`FlowScriptStore` 管理内置 + 自定义脚本
- [x] 外部导入脚本默认 `PENDING + disabled`，必须人工审核通过后才可执行
- [x] `FlowScriptStore` 内置脚本保护：不可删除、不可停用、不可覆盖
- [x] `UiFlowExecutor` 增加禁止动作 token 护栏：步骤 label 命中 `forbiddenActionTokens` 时拒绝执行
- [x] 新增通用工具 `run_flow_script`：按 `scriptId` 执行已审核启用的 FlowScript，并注册 metadata/schema
- [x] 设置页新增 `FlowScriptSettingsSection`：列出内置/自定义流程、审核/拒绝/启停/删除/导入/复制导出
- [x] 新增 `FlowScriptCodecTest` / `FlowScriptStoreTest`：roundtrip、校验、导入审核、内置保护、导出
- [x] 全量 JVM 单测通过
- [ ] 真机 golden 回归：连续 5/5 跑通瑞幸 FlowScript

## 无 Shizuku 熄屏强提醒落地（2026-08-30 续）

- [x] AndroidManifest 增加 `android.permission.VIBRATE`
- [x] 新增 `strong_remind` 工具：全屏高优先级通知 + 震动 + 铃声，专用于熄屏/无 Shizuku 时提醒用户
- [x] 工具已注册到核心工具集、metadata、schema
- [x] 支持参数 `fullScreen / vibrate / sound`，默认全开
- [x] 通知使用 `CATEGORY_ALARM` + 公共可见性 + 全屏 Intent，锁屏也能提醒
- [x] 保留普通 `notify`，强提醒作为无 Shizuku 熄屏降级通道
- [x] 定时任务失败/等待确认且屏幕熄灭时，自动发送 strong_remind 强提醒（用户点亮后继续/确认）

## FlowScript 参数化与模板（2026-08-30 续）

- [x] `FlowScript` 新增 `parameters: Map<String, String>`，JSON 导入导出同步支持
- [x] `UiFlowExecutor` 支持参数模板：`{drink}`、`{temperature}` 等占位符替换到步骤条件/动作/终端标记/包名
- [x] 瑞幸内置脚本改为参数化：`drink` / `temperature` 可覆盖，不再硬编码“标准美式/冰”
- [x] `luckin_quick_order` 已把 drink/temperature 作为 overrides 传入执行器
- [x] `run_flow_script` 支持 `params` JSON 字符串覆盖脚本参数
- [x] 新增参数 roundtrip 单测
- [x] 全量 JVM 单测通过

## FlowScript JSON Schema 文件（2026-08-30 续）

- [x] 新增 `app/src/main/assets/flow_script_schema.json`：声明 FlowScript v1 顶层字段、步骤、动作类型、参数
- [x] schema 包含动作类型合法值与必填字段约束，可作为外部导入/审核工具参考

## FlowScript 动作扩展（2026-08-30 续）

- [x] `FlowAction` 新增 `InputText(text)` 和 `Wait(ms)`
- [x] `FlowScriptCodec` 支持新动作的 JSON 序列化/反序列化/校验
- [x] `UiFlowExecutor` 支持执行输入文本与等待动作，文本同样支持参数模板
- [x] `flow_script_schema.json` 同步加入 `input_text` / `wait` 动作类型和必填字段
- [x] 新增动作 roundtrip 单测

## 执行模式定义（2026-08-30 续）

- [x] 新增 `AgentExecutionMode`：`ASSIST / AMBIENT / NOTIFY`
- [x] `AgentCapabilitySnapshot` 增加 `executionMode` 派生属性
- [x] `CapabilityStatus` 映射并展示执行模式：Assist / Shizuku、Ambient / 无障碍、Notify / 强提醒
- [x] 新增执行模式映射单测

## FlowScript 静态 Golden 校验（2026-08-30 续）

- [x] 新增 `FlowScriptGoldenTest`：所有内置脚本通过 validate + JSON roundtrip
- [x] 检查所有 `{placeholder}` 都在 `parameters` 中声明，避免运行时替换成空
