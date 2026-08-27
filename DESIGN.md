# VoiceConfig 设计与发展文档

> 版本：V1.0  
> 日期：2026-08-27  
> 定位：个人生活与工作 Agent  
> 本文档基于多轮调研、真机验证和现有代码架构整理。

---

## 1. 产品愿景

> 用户用一句话/一段语音交给 VoiceConfig 一个目标；
> VoiceConfig 负责理解、规划、执行、验证，并在安全边界内完成数字生活中的重复、繁琐、多步任务。

### 1.1 目标场景

```text
手机自动化
├─ 定时/条件打开企业微信
├─ Agent 点瑞幸
├─ 微信消息起草与回复
├─ 跨 App 信息研究

远程设备与代码
├─ 树莓派/服务器
├─ 读写项目
├─ 安装依赖
├─ 编译测试

智能家居
├─ 空调温度
├─ 窗帘
├─ 电视
├─ 音乐

长程任务
├─ 母婴用品采购研究
├─ 多平台比价
├─ 评价与品质分析
├─ 旅行规划
```

### 1.2 非目标

```text
- 不替代系统级语音助手（短期）
- 不自动完成支付/发送/删除等不可逆操作
- 不做通用“万能手机 Agent”承诺
- 不重复实现智能家居协议栈
- 不在没有证据时报告成功
```

---

## 2. 总体架构

```text
┌────────────────────────────────────────────────────────────┐
│ 用户层：语音 / 文字 / 定时 / 条件触发                        │
├────────────────────────────────────────────────────────────┤
│ 交互层：ASR / TTS / 多轮对话 / 确认 / 进度播报               │
├────────────────────────────────────────────────────────────┤
│ Agent 编排层                                              │
│ ├─ 意图理解                                               │
│ ├─ TaskPlan                                               │
│ ├─ 工具选择                                               │
│ ├─ StopVerifier                                           │
│ └─ 失败重试 / 断点恢复                                     │
├────────────────────────────────────────────────────────────┤
│ 能力层                                                    │
│ ├─ PHONE：Shizuku / Accessibility / UI 自动化              │
│ ├─ REMOTE：SSH / 节点 / 远程项目                           │
│ ├─ HOME：Home Assistant / 米家 / 本地 API                  │
│ ├─ RESEARCH：搜索 / 比价 / 评价 / 信息结构化                 │
│ └─ APP_SKILL：瑞幸 / 微信 / 购物等专用技能                  │
├────────────────────────────────────────────────────────────┤
│ 安全层                                                    │
│ ├─ READ_ONLY   自动                                       │
│ ├─ PREPARE     可自动但展示确认                             │
│ └─ IRREVERSIBLE 强制人工确认                               │
├────────────────────────────────────────────────────────────┤
│ 基础设施层                                                │
│ ├─ 持久化：Room / 文件 / 审计                              │
│ ├─ 证据：截图 / UI 文本 / 命令输出 / 设备状态               │
│ ├─ Skill 资产：成功路径 / 版本 / 验证证据                    │
│ └─ 指标：成功率 / 验证率 / 轮数 / 成本 / 人工介入率          │
└────────────────────────────────────────────────────────────┘
```

---

## 3. 关键模块设计

### 3.1 语音交互层

#### 目标

最终支持：

```text
手机不在手上
→ 语音唤醒
→ 多轮对话
→ 长任务进度播报
→ TTS 回复
```

#### 分阶段

| 阶段 | 能力 |
|---|---|
| V0 | 手动按钮触发 ASR |
| V1 | ASR + TTS 播报结果 |
| V2 | 多轮语音对话：Agent 反问 → 用户补充 |
| V3 | 远场唤醒（评估可行性） |

#### 技术选型建议

- ASR：现有本地/云端路径继续
- TTS：Android `TextToSpeech` 或云端 TTS
- 唤醒词：Edge Impulse / 小模型关键词唤醒，持续监听
- 对话状态：`VoiceSession` 管理 awaiting_user / clarifying / executing

---

### 3.2 Agent 编排层

#### 核心组件

```text
AgentSession
├─ LLM 调用
├─ 工具注册表
├─ TaskPlan
├─ StopVerifier
├─ 安全闸门
├─ 进度回调
└─ RunControl（暂停/取消/恢复）
```

#### 工具分层

| 层 | 工具 | 是否默认给模型 |
|---|---|---|
| CORE | task_plan / wait_user / read_ui / open_app | ✅ |
| PHONE | tap / input_text / swipe / press_key | ✅ |
| REMOTE | remote_ssh_* | ✅ |
| HOME | home_list / home_control | 后续 |
| RESEARCH | web_search / product_search / price_compare | 后续 |
| APP_SKILL | luckin_order / wechat_reply_draft | 后续 |
| DEBUG | file / logcat / clipboard | ❌ 默认隐藏 |

---

### 3.3 远程开发层

#### RemoteProject 数据模型

```kotlin
data class RemoteProject(
    val id: String,
    val nodeId: String,
    val name: String,
    val rootPath: String,
    val repoType: String,       // gradle / python / node / go / generic
    val buildCommand: String?,
    val testCommand: String?,
    val installCommand: String?,
    val updatedAt: Long,
)
```

#### 工具

```text
remote_project_inspect
  └─ 自动识别项目类型和构建/测试命令

remote_project_build
  └─ 执行构建，返回退出码和错误摘要

remote_project_test
  └─ 执行测试，返回失败用例摘要

remote_project_install
  └─ 安装依赖（默认需确认）
```

---

### 3.4 智能家居层

#### 架构

```text
VoiceConfig Agent
  → home_devices
  → home_control
  → Home Assistant REST / WebSocket
  → 空调 / 窗帘 / 电视 / 音乐
```

#### 工具

```text
home_devices
参数：无
返回：设备列表、状态、能力

home_control
参数：device, action, value
示例：
  home_control(device=air_conditioner, action=set_temperature, value=26)
  home_control(device=curtain, action=open)
  home_control(device=tv, action=power, value=on)
  home_control(device=music, action=play, value=playlist)
```

#### 安全

- 空调/窗帘/电视/音乐：低风险，可自动
- 涉及门锁/摄像头/安防：必须人工确认

---

### 3.5 长程研究与购物

#### 流程

```text
用户目标
→ 拆解采购清单
→ 多平台搜索
→ 商品信息结构化
→ 价格/评价/品质比较
→ 输出推荐清单
→ 用户确认
→ 可选加入购物车
→ 支付前人工确认
```

#### 需要的数据模型

```kotlin
data class ProductInfo(
    val id: String,
    val title: String,
    val platform: String,
    val price: Double,
    val originalPrice: Double?,
    val rating: Double?,
    val reviewCount: Int?,
    val sales: Int?,
    val tags: List<String>,
    val url: String,
)
```

#### 工具

```text
product_search
product_detail
price_compare
quality_evaluate
shopping_plan
```

---

### 3.6 消费/通信 App Skill

#### 瑞幸点单

```text
luckin_open
luckin_select_store
luckin_select_drink
luckin_add_to_cart
luckin_confirm_order
→ 展示确认清单
→ 支付前强制人工确认
```

#### 微信回复

```text
wechat_read_messages
wechat_draft_reply
wechat_confirm_send
→ 默认不自动发送
→ 用户确认后 send
```

---

## 4. 安全模型

### 4.1 四级安全

| 级别 | 动作 | 策略 |
|---|---|---|
| READ_ONLY | 读文件、查价格、看消息 | 自动执行 |
| PREPARE | 加购、起草回复、生成方案 | 可自动，展示结果 |
| CONFIRM | 发送消息、创建订单、修改重要配置 | 必须人工确认 |
| IRREVERSIBLE | 支付、删除、覆盖、系统级操作 | 强制人工确认且不可绕过 |

### 4.2 安全边界

```text
- 模型不能绕过安全闸门
- 自动确认开关不能覆盖 IRREVERSIBLE
- 每个动作必须有证据
- 证据不足 = 按失败处理
- 敏感操作记录审计
```

---

## 5. 持久化与审计

### 5.1 数据存储

| 数据 | 存储 |
|---|---|
| Agent 会话 | Room |
| RunRecord | Room |
| TaskPlan | Room |
| Skill | Room + JSON |
| SSH 凭据 | Android Keystore 加密 |
| known_hosts | 本地文件 |
| 审计 | JSONL |
| 购物研究结果 | Room / JSON |

### 5.2 审计字段

```text
timestamp
runId
toolName
argsSummary
resultSummary
evidenceType
evidenceHash
approvedBy
```

---

## 6. UI 设计原则

1. 配置页使用可折叠分组；
2. 不常用功能默认折叠；
3. Agent 执行过程展示步骤和证据；
4. 敏感操作必须展示明确确认卡片；
5. 文件/终端/服务是辅助工具，不是主路径；
6. 语音只是输入方式之一，不阻塞文字和自动化。

---

## 7. 开发路线

### Phase A：可靠性地基

```text
A1 安全四级体系
A2 工具分层
A3 Run/task 持久化
A4 Capability Preflight
A5 审计与指标
```

### Phase B：确定性自动化

```text
B1 企业微信定时打开
B2 执行后验证
B3 失败通知
B4 语音创建定时任务
```

### Phase C：第一个消费 Skill

```text
C1 瑞幸点单
C2 Skill 沉淀
C3 下单确认清单
```

### Phase D：微信助理

```text
D1 读取消息
D2 起草
D3 确认发送
```

### Phase E：智能家居

```text
E1 Home Assistant 接入
E2 home_devices / home_control
E3 语音控制实验
```

### Phase F：长程研究

```text
F1 商品结构化提取
F2 多平台搜索
F3 比价与推荐
F4 采购清单
```

### Phase G：远程开发深化

```text
G1 RemoteProject
G2 remote_project_build/test/install
G3 自动修复循环
```

### Phase H：语音闭环

```text
H1 TTS
H2 多轮语音
H3 远场唤醒（可行性评估）
```

---

## 8. 借鉴与不冲突

| 来源 | 借鉴 | 不做什么 |
|---|---|---|
| AutoGLM / Mobile-Agent | Agent 操作手机 UI | 不把 UI 自动化当唯一核心 |
| Claude Code | 项目上下文、构建测试循环 | 不重复做桌面 IDE |
| Home Assistant | 设备抽象、本地控制 | 不重复实现协议栈 |
| Shortcuts / Tasker | 确定性触发、稳定动作 | 不把 Agent 变成纯规则引擎 |
| Stripe / ACP | 交易安全边界 | 不自动支付 |
| SkillDroid / KnowAct | 成功路径沉淀 Skill | 不把 Skill 当规则库 |
| AndroidWorld / Appium | 真机回归与证据 | 不做测试框架 |
| Alexa / Siri | 语音体验 | 短期不做远场唤醒 |
| Zapier / n8n | 长任务编排/断点 | 不引入重量级调度器 |

---

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| 跨 App UI 易失效 | 专用 Skill + 版本管理 + 回归 |
| 长任务被系统杀死 | TaskPlan 持久化 + checkpoint |
| 微信/支付风控 | 只读/草稿/确认，不自动发送支付 |
| 智能家居生态复杂 | 优先 Home Assistant 统一 |
| 模型工具过多 | 分层 + 每轮按任务注入 |
| 语音唤醒功耗 | 小模型关键词 + 手动兜底 |
| 文档丢失 | 纳入版本管理 |

---

## 10. 参考

- AutoGLM / Mobile-Agent：手机 GUI Agent 研究
- K²-Agent：分层移动设备控制与知识演化
- SkillDroid / KnowAct：移动 Agent 技能与记忆
- Mobile-Agent-RAG：长程移动自动化多 Agent
- Claude Code / Cursor：编码 Agent 设计与安全
- Home Assistant：智能家居本地抽象
- Stripe ACP / AP2：Agentic Commerce 安全协议
- AndroidWorld / Appium：移动 Agent 评测与回归
- Edge Impulse：Android 低功耗关键词唤醒
