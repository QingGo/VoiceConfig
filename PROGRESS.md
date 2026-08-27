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
- [ ] 打开瑞幸 / 选择门店 / 选饮品 / 加购
- [ ] 支付前强制人工确认与审计
- [ ] Skill 沉淀

## Phase D：微信消息助理

- [x] wechat_draft_reply：安全生成回复草稿（不自动发送）
- [ ] 读取未读消息
- [ ] 人工确认后发送

## Phase E：智能家居

- [x] Home Assistant 接入：Base URL + 长期访问令牌设置
- [x] home_devices / home_control Agent 工具
- [ ] 语音控制实验（依赖语音闭环继续）

## Phase F：长程研究与购物

- [x] 商品结构化模型 ProductInfo
- [x] product_compare：价格/评分/评价比较与推荐
- [x] 长程采购清单持久化：shopping_save / shopping_list / shopping_update_status
- [x] product_search：基于 DeepSeek Web Search 的商品搜索入口
- [ ] 搜索结果自动结构化提取

## Phase G：远程开发深化

- [x] RemoteProject 工具：inspect/build/test/install
- [x] RemoteProject 持久化：Room 表 + Repository + inspect 自动保存
- [ ] 自动识别工作区（多项目聚合界面）
- [ ] 自动修复循环

## Phase H：语音闭环

- [x] TTS：Agent 结果可语音播报，设置开关
- [x] VoiceSession 状态机（多轮语音基础）
- [x] Agent 主流程已接入 VoiceSession（开始/等待/完成）
- [ ] ASR 持续监听与远场唤醒
- [ ] 远场唤醒可行性评估

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
