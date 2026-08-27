package com.voiceconfig.app.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 核心 Agent 插件：注册 Phase 1/2/3 所需工具。
 *
 * 当前工具（<10 核心 + 扩展）：
 * open_app / run_shell / read_ui / tap / input_text / swipe / wait / notify /
 * web_search / file_write / file_read / clipboard_read / logcat_read / open_file
 */
@Singleton
class CoreAgentPlugin @Inject constructor(
    private val openAppTool: OpenAppTool,
    private val findAppTool: FindAppTool,
    private val runShellTool: RunShellTool,
    private val readUiTool: ReadUiTool,
    private val readScreenTool: ReadScreenTool,
    private val getScreenStateTool: GetScreenStateTool,
    private val dismissPopupsTool: DismissPopupsTool,
    private val taskPlanTool: TaskPlanTool,
    private val tapTool: TapTool,
    private val tapTextTool: TapTextTool,
    private val reviewTapTool: ReviewTapTool,
    private val inputTextTool: InputTextTool,
    private val swipeTool: SwipeTool,
    private val pressKeyTool: PressKeyTool,
    private val waitTool: WaitTool,
    private val notifyTool: NotifyTool,
    private val createReminderTool: CreateReminderTool,
    private val createScheduledTaskTool: CreateScheduledTaskTool,
    private val waitUserTool: WaitUserTool,
    private val webSearchTool: WebSearchTool,
    private val openSearchTool: OpenSearchTool,
    private val createCalendarEventTool: CreateCalendarEventTool,
    private val fileWriteTool: FileWriteTool,
    private val fileReadTool: FileReadTool,
    private val clipboardReadTool: ClipboardReadTool,
    private val logcatReadTool: LogcatReadTool,
    private val openFileTool: OpenFileTool,
    private val remoteNodeTool: RemoteNodeTool,
    private val remoteSshExecTool: RemoteSshExecTool,
    private val remoteSshReadTool: RemoteSshReadTool,
    private val remoteSshWriteTool: RemoteSshWriteTool,
    private val remoteSshListTool: RemoteSshListTool,
    private val remoteSshSearchTool: RemoteSshSearchTool,
    private val remoteProjectInspectTool: RemoteProjectInspectTool,
    private val remoteProjectBuildTool: RemoteProjectBuildTool,
    private val remoteProjectTestTool: RemoteProjectTestTool,
    private val remoteProjectInstallTool: RemoteProjectInstallTool,
    private val homeDevicesTool: HomeDevicesTool,
    private val homeControlTool: HomeControlTool,
    private val productCompareTool: ProductCompareTool,
    private val productSearchTool: ProductSearchTool,
    private val productExtractTool: ProductExtractTool,
    private val shoppingSaveTool: ShoppingSaveTool,
    private val shoppingListTool: ShoppingListTool,
    private val shoppingUpdateStatusTool: ShoppingUpdateStatusTool,
    private val luckinPrepareOrderTool: LuckinPrepareOrderTool,
    private val wechatDraftReplyTool: WechatDraftReplyTool,
) : AgentPlugin {

    override val id: String = "core-tools"
    override val name: String = "核心工具集"
    override val version: String = "1.0"

    override fun provideTools(): List<AgentTool> = listOf(
        openAppTool,
        findAppTool,
        runShellTool,
        readUiTool,
        readScreenTool,
        getScreenStateTool,
        dismissPopupsTool,
        taskPlanTool,
        tapTool,
        tapTextTool,
        reviewTapTool,
        inputTextTool,
        swipeTool,
        pressKeyTool,
        waitTool,
        notifyTool,
        createReminderTool,
        createScheduledTaskTool,
        waitUserTool,
        webSearchTool,
        openSearchTool,
        createCalendarEventTool,
        fileWriteTool,
        fileReadTool,
        clipboardReadTool,
        logcatReadTool,
        openFileTool,
        remoteNodeTool,
        remoteSshExecTool,
        remoteSshReadTool,
        remoteSshWriteTool,
        remoteSshListTool,
        remoteSshSearchTool,
        remoteProjectInspectTool,
        remoteProjectBuildTool,
        remoteProjectTestTool,
        remoteProjectInstallTool,
        homeDevicesTool,
        homeControlTool,
        productCompareTool,
        productSearchTool,
        productExtractTool,
        shoppingSaveTool,
        shoppingListTool,
        shoppingUpdateStatusTool,
        luckinPrepareOrderTool,
        wechatDraftReplyTool,
    )
}
