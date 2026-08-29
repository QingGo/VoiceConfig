# VoiceConfig 开发进度

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
- [ ] Skill 沉淀

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

- [x] 系统级悬浮球基础：VoiceConfigService 在获得悬浮窗权限后显示可拖动「言」球，短按聆听、长按打开 App，位置本地记忆
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
