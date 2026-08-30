package com.voiceconfig.app.agent

/**
 * 内置 Skill 目录。
 *
 * 这些数据来自本项目已验证/沉淀过的主路径：
 * - 瑞幸：真机验证可稳定停在免密支付页，不点击支付；
 * - 企业微信：官方 API 发送，合规，不触碰个人微信；
 * - Home Assistant / 远程项目：低风险只读 + 可控变更路径。
 *
 * 注入系统提示后仅作为“历史成功路径参考”，每一步仍要求重新验证，
 * 到达终端安全门（支付/发送/删除/配置/远程破坏性）时必须 wait_user。
 */
object BuiltinSkillCatalog {

    fun all(now: Long = System.currentTimeMillis()): List<AgentSkill> = listOf(
        AgentSkill(
            id = "builtin_luckin_order_to_payment",
            name = "瑞幸咖啡点单到免密支付前停止",
            description = "在瑞幸 App 中选择饮品/下单，最终停在免密支付确认页；不点击支付、不提交订单。",
            text = "在瑞幸咖啡App中帮我下单买一杯咖啡，最后停在免密支付页等待我确认",
            tags = listOf("luckin", "咖啡", "下单", "支付", "terminal"),
            whenToUse = "用户要求使用瑞幸 App 购买咖啡/饮品时；目标必须停在免密支付页，绝不点击最终支付。",
            steps = listOf(
                AgentSkillStep(
                    toolName = "open_app",
                    args = "package=com.lucky.luckyclient",
                    purpose = "打开瑞幸咖啡 App",
                    expected = "前台包名为 com.lucky.luckyclient",
                    verification = "FOREGROUND: verified=true",
                    fallback = "find_app 搜索瑞幸后打开",
                ),
                AgentSkillStep(
                    toolName = "read_ui",
                    args = "maxNodes=120",
                    purpose = "读取首页/门店/菜单，确认页面状态",
                    expected = "返回门店、饮品等可交互节点",
                    verification = "感知类工具，本身即证据",
                    fallback = "read_screen 看图",
                ),
                AgentSkillStep(
                    toolName = "tap_text",
                    args = "text=去结算, 立即购买, 选规格",
                    purpose = "进入确认订单/结算页",
                    expected = "出现确认订单、免密支付或待支付信息",
                    verification = "UI_EVIDENCE: 出现订单确认页",
                    fallback = "read_ui 后按 resource-id 或坐标点击",
                ),
                AgentSkillStep(
                    toolName = "dismiss_popups",
                    args = "maxAttempts=3",
                    purpose = "关闭换购/优惠/免密授权等浮层，但不提交订单",
                    expected = "浮层关闭，仍停留在支付确认页",
                    verification = "返回 actions 非空或再次 read_ui 显示无营销浮层",
                    fallback = "tap_text 点击 X/关闭/取消",
                ),
                AgentSkillStep(
                    toolName = "wait_user",
                    args = "reason=已到达瑞幸免密支付页，等待用户确认后再点击支付",
                    purpose = "停在最后一步，等待真人确认",
                    expected = "任务状态 WAITING_CONFIRM，不再执行任何点击",
                    verification = "终端安全门：WAITING_CONFIRM",
                    fallback = "不继续操作，向用户说明已停在支付前",
                ),
            ),
            createdAt = now,
            updatedAt = now,
            successCount = 1,
            status = AgentSkillStatus.APPROVED,
            lastResult = "success",
            version = 1,
            enabled = true,
            sourceRunId = "builtin",
            sourceVerified = true,
            requiredCapabilities = listOf("UI", "ACCESSIBILITY"),
            auditLog = listOf(
                AgentSkillAudit(
                    skillId = "builtin_luckin_order_to_payment",
                    at = now,
                    action = "seed",
                    detail = "内置模板：真机路径已停在免密支付页",
                ),
            ),
        ),
        AgentSkill(
            id = "builtin_wecom_send_message",
            name = "企业微信官方 API 发送消息",
            description = "通过企业微信官方 API 给成员/部门/标签发送文本消息，不使用个人微信自动化。",
            text = "通过企业微信给我/指定成员发送一条消息",
            tags = listOf("企业微信", "wecom", "消息", "合规"),
            whenToUse = "用户需要自动发送企业微信/工作消息时；个人微信自动化默认禁用。",
            steps = listOf(
                AgentSkillStep(
                    toolName = "wecom_send_message",
                    args = "toUser=目标成员UserID toParty=可选部门ID toTag=可选标签ID content=消息内容",
                    purpose = "调用企业微信官方 API 发送应用消息",
                    expected = "API 返回 errcode=0，data.sent=true",
                    verification = "wecom_official_api: sent=true",
                    fallback = "检查设置中的 CorpId/AgentId/Secret，或改用人工发送",
                ),
            ),
            createdAt = now,
            updatedAt = now,
            successCount = 1,
            status = AgentSkillStatus.APPROVED,
            lastResult = "success",
            version = 1,
            enabled = true,
            sourceRunId = "builtin",
            sourceVerified = true,
            requiredCapabilities = listOf("WECOM_API"),
            auditLog = listOf(
                AgentSkillAudit(
                    skillId = "builtin_wecom_send_message",
                    at = now,
                    action = "seed",
                    detail = "内置模板：企业微信官方 API 发送路径",
                ),
            ),
        ),
        AgentSkill(
            id = "builtin_home_assistant_control",
            name = "Home Assistant 设备查看与控制",
            description = "先读取 Home Assistant 设备，再调用明确的 control service。变更类控制仍需终端安全门。",
            text = "查看/控制 Home Assistant 中的设备",
            tags = listOf("home_assistant", "智能家居", "设备"),
            whenToUse = "用户要求查看家庭设备状态或控制灯/空调/窗帘等时。",
            steps = listOf(
                AgentSkillStep(
                    toolName = "home_devices",
                    args = "filter=可选过滤",
                    purpose = "读取目标设备当前状态",
                    expected = "返回设备列表与状态",
                    verification = "home_devices: count>0",
                    fallback = "确认 HA Base URL/Token 配置",
                ),
                AgentSkillStep(
                    toolName = "home_control",
                    args = "domain=climate service=set_temperature entityId=climate.xxx data={temperature:24}",
                    purpose = "执行明确控制意图",
                    expected = "HA 返回 ok，设备状态改变",
                    verification = "home_control: ok=true",
                    fallback = "重复 home_devices 确认状态，若失败则不声称成功",
                ),
            ),
            createdAt = now,
            updatedAt = now,
            successCount = 1,
            status = AgentSkillStatus.APPROVED,
            lastResult = "success",
            version = 1,
            enabled = true,
            sourceRunId = "builtin",
            sourceVerified = true,
            requiredCapabilities = listOf("HOME_ASSISTANT"),
            auditLog = listOf(
                AgentSkillAudit(
                    skillId = "builtin_home_assistant_control",
                    at = now,
                    action = "seed",
                    detail = "内置模板：HA 读取+控制路径",
                ),
            ),
        ),
        AgentSkill(
            id = "builtin_remote_project_verify",
            name = "远程项目识别/构建/测试/验证",
            description = "远程项目先 inspect 自动识别命令，再按需 build/test/verify；install 属于高敏需人工确认。",
            text = "检查并验证远程项目（构建/测试）",
            tags = listOf("remote", "项目", "构建", "测试"),
            whenToUse = "用户要求构建/测试/验证远程服务器上的项目时。",
            steps = listOf(
                AgentSkillStep(
                    toolName = "remote_project_inspect",
                    args = "host=可选节点 path=/远程项目根目录",
                    purpose = "识别项目类型与可用命令",
                    expected = "返回 projectType/buildCommand/testCommand",
                    verification = "remote_project_inspect: projectType 已知",
                    fallback = "用户显式提供命令或检查节点连接",
                ),
                AgentSkillStep(
                    toolName = "remote_project_build",
                    args = "path=/远程项目根目录 projectId=可选",
                    purpose = "执行构建",
                    expected = "构建命令 exit=0",
                    verification = "remote_project_build: exit=0",
                    fallback = "读日志判断失败原因后停止并向用户报告",
                ),
                AgentSkillStep(
                    toolName = "remote_project_test",
                    args = "path=/远程项目根目录 projectId=可选",
                    purpose = "执行测试",
                    expected = "测试命令 exit=0",
                    verification = "remote_project_test: exit=0",
                    fallback = "读测试日志并向用户报告失败",
                ),
                
                AgentSkillStep(
                    toolName = "remote_project_verify",
                    args = "path=/远程项目根目录 projectId=可选",
                    purpose = "汇总构建/测试结果做最终验证",
                    expected = "返回构建与测试状态",
                    verification = "remote_project_verify: 结果摘要",
                    fallback = "若任一失败，不声称通过",
                ),
            ),
            createdAt = now,
            updatedAt = now,
            successCount = 1,
            status = AgentSkillStatus.APPROVED,
            lastResult = "success",
            version = 1,
            enabled = true,
            sourceRunId = "builtin",
            sourceVerified = true,
            requiredCapabilities = listOf("REMOTE_SSH"),
            auditLog = listOf(
                AgentSkillAudit(
                    skillId = "builtin_remote_project_verify",
                    at = now,
                    action = "seed",
                    detail = "内置模板：远程项目验证路径",
                ),
            ),
        ),
    )
}

