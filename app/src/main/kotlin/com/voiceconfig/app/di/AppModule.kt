package com.voiceconfig.app.di

import android.content.Context
import androidx.room.Room
import com.voiceconfig.app.executor.DeepLinkExecutionChannel
import com.voiceconfig.app.executor.NotificationExecutionChannel
import com.voiceconfig.app.agent.AgentChat
import com.voiceconfig.app.agent.AgentToolChat
import com.voiceconfig.app.agent.AgentChatClient
import com.voiceconfig.app.agent.CoreAgentPlugin
import com.voiceconfig.app.agent.PluginRegistry
import com.voiceconfig.app.agent.ToolRegistry
import com.voiceconfig.app.ai.DeepSeekNlpParser
import com.voiceconfig.app.ai.InstalledAppProvider
import com.voiceconfig.app.executor.ShizukuExecutionChannel
import com.voiceconfig.app.scheduler.AlarmTaskScheduler
import com.voiceconfig.core.executor.ExecutionChannel
import com.voiceconfig.core.executor.ExecutionEngine
import com.voiceconfig.core.nlp.AppAliasResolver
import com.voiceconfig.core.nlp.NaturalLanguageParser
import com.voiceconfig.core.nlp.RuleBasedNlpParser
import com.voiceconfig.core.nlp.ScheduleModificationParser
import com.voiceconfig.core.scheduler.NextRunCalculator
import com.voiceconfig.core.scheduler.TaskScheduler
import com.voiceconfig.data.local.VoiceConfigDatabase
import com.voiceconfig.data.local.dao.AgentMessageDao
import com.voiceconfig.data.local.dao.AgentStepDao
import com.voiceconfig.data.local.dao.AgentSessionDao
import com.voiceconfig.data.local.dao.AiDebugLogDao
import com.voiceconfig.data.local.dao.AppAliasDao
import com.voiceconfig.data.local.dao.ExecutionLogDao
import com.voiceconfig.data.local.dao.TaskDao
import com.voiceconfig.data.local.dao.TemplateDao
import com.voiceconfig.data.local.dao.TaskEventDao
import com.voiceconfig.data.local.dao.TriggerRuleDao
import com.voiceconfig.data.local.repository.AgentHistoryRepository
import com.voiceconfig.data.local.repository.AiDebugLogRepository
import com.voiceconfig.data.local.repository.ExecutionLogRepository
import com.voiceconfig.data.local.repository.OfflineAgentHistoryRepository
import com.voiceconfig.data.local.repository.OfflineAiDebugLogRepository
import com.voiceconfig.data.local.repository.OfflineExecutionLogRepository
import com.voiceconfig.data.local.repository.OfflineTaskRepository
import com.voiceconfig.data.local.repository.OfflineTemplateRepository
import com.voiceconfig.data.local.repository.TaskRepository
import com.voiceconfig.data.local.repository.TemplateRepository
import com.voiceconfig.data.local.repository.TriggerRuleRepository
import com.voiceconfig.data.local.repository.OfflineTriggerRuleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VoiceConfigDatabase =
        Room.databaseBuilder(
            context,
            VoiceConfigDatabase::class.java,
            "voice_config.db",
        )
            .addMigrations(VoiceConfigDatabase.MIGRATION_1_2, VoiceConfigDatabase.MIGRATION_2_3, VoiceConfigDatabase.MIGRATION_3_4, VoiceConfigDatabase.MIGRATION_4_5, VoiceConfigDatabase.MIGRATION_5_6, VoiceConfigDatabase.MIGRATION_6_7, VoiceConfigDatabase.MIGRATION_7_8, VoiceConfigDatabase.MIGRATION_8_9)
            .build()

    @Provides
    fun provideTaskDao(database: VoiceConfigDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideAppAliasDao(database: VoiceConfigDatabase): AppAliasDao = database.appAliasDao()

    @Provides
    fun provideExecutionLogDao(database: VoiceConfigDatabase): ExecutionLogDao = database.executionLogDao()

    @Provides
    fun provideTemplateDao(database: VoiceConfigDatabase): TemplateDao = database.templateDao()

    @Provides
    fun provideAiDebugLogDao(database: VoiceConfigDatabase): AiDebugLogDao = database.aiDebugLogDao()

    @Provides
    fun provideAgentSessionDao(database: VoiceConfigDatabase): AgentSessionDao = database.agentSessionDao()

    @Provides
    fun provideAgentMessageDao(database: VoiceConfigDatabase): AgentMessageDao = database.agentMessageDao()

    @Provides
    fun provideAgentStepDao(database: VoiceConfigDatabase): AgentStepDao = database.agentStepDao()

    @Provides
    fun provideTaskEventDao(database: VoiceConfigDatabase): TaskEventDao = database.taskEventDao()

    @Provides
    fun provideTriggerRuleDao(database: VoiceConfigDatabase): TriggerRuleDao = database.triggerRuleDao()

    @Provides
    @Singleton
    fun provideTaskRepository(taskDao: TaskDao): TaskRepository = OfflineTaskRepository(taskDao)

    @Provides
    @Singleton
    fun provideTriggerRuleRepository(triggerRuleDao: TriggerRuleDao): TriggerRuleRepository =
        OfflineTriggerRuleRepository(triggerRuleDao)

    @Provides
    @Singleton
    fun provideUserAliasRegistry(appAliasDao: AppAliasDao): UserAliasRegistry =
        UserAliasRegistry(appAliasDao)

    @Provides
    @Singleton
    fun provideExecutionLogRepository(executionLogDao: ExecutionLogDao): ExecutionLogRepository =
        OfflineExecutionLogRepository(executionLogDao)

    @Provides
    @Singleton
    fun provideTemplateRepository(templateDao: TemplateDao): TemplateRepository =
        OfflineTemplateRepository(templateDao)

    @Provides
    @Singleton
    fun provideAiDebugLogRepository(aiDebugLogDao: AiDebugLogDao): AiDebugLogRepository =
        OfflineAiDebugLogRepository(aiDebugLogDao)

    @Provides
    @Singleton
    fun provideAgentHistoryRepository(
        agentSessionDao: AgentSessionDao,
        agentMessageDao: AgentMessageDao,
        taskEventDao: TaskEventDao,
        agentStepDao: AgentStepDao,
    ): AgentHistoryRepository = OfflineAgentHistoryRepository(
        sessionDao = agentSessionDao,
        messageDao = agentMessageDao,
        taskEventDao = taskEventDao,
        stepDao = agentStepDao,
    )

    @Provides
    @Singleton
    fun provideInstalledAppProvider(@ApplicationContext context: Context): InstalledAppProvider =
        InstalledAppProvider(context)

    @Provides
    @Singleton
    fun provideAppAliasResolver(
        installedAppProvider: InstalledAppProvider,
        userAliasRegistry: UserAliasRegistry,
    ): AppAliasResolver = AppAliasResolver(
        userAliasesProvider = { userAliasRegistry.current() },
        installedAppsProvider = { installedAppProvider.installedApps },
    )

    @Provides
    @Singleton
    fun provideNextRunCalculator(): NextRunCalculator = NextRunCalculator()

    @Provides
    @Singleton
    fun provideTaskScheduler(impl: AlarmTaskScheduler): TaskScheduler = impl

    @Provides
    @Singleton
    fun provideRuleBasedNlpParser(appAliasResolver: AppAliasResolver): RuleBasedNlpParser =
        RuleBasedNlpParser(appAliasResolver = appAliasResolver)

    @Provides
    @Singleton
    fun provideScheduleModificationParser(): ScheduleModificationParser =
        ScheduleModificationParser()

    @Provides
    @Singleton
    fun provideNaturalLanguageParser(deepSeekParser: DeepSeekNlpParser): NaturalLanguageParser =
        deepSeekParser

    @Provides
    @Singleton
    fun provideExecutionChannels(
        notificationChannel: NotificationExecutionChannel,
        deepLinkChannel: DeepLinkExecutionChannel,
        shizukuChannel: ShizukuExecutionChannel,
    ): List<ExecutionChannel> = listOf(
        shizukuChannel,
        deepLinkChannel,
        notificationChannel,
    )

    @Provides
    @Singleton
    fun provideAgentTrace(impl: com.voiceconfig.app.agent.AgentTraceLogger): com.voiceconfig.app.agent.AgentTrace = impl

    @Provides
    @Singleton
    fun provideAgentChat(impl: AgentChatClient): AgentChat = impl

    @Provides
    @Singleton
    fun provideAgentToolChat(impl: AgentChatClient): AgentToolChat = impl

    @Provides
    @Singleton
    fun provideCoreAgentPlugin(
        openAppTool: com.voiceconfig.app.agent.OpenAppTool,
        findAppTool: com.voiceconfig.app.agent.FindAppTool,
        runShellTool: com.voiceconfig.app.agent.RunShellTool,
        readUiTool: com.voiceconfig.app.agent.ReadUiTool,
        readScreenTool: com.voiceconfig.app.agent.ReadScreenTool,
        getScreenStateTool: com.voiceconfig.app.agent.GetScreenStateTool,
        tapTool: com.voiceconfig.app.agent.TapTool,
        tapTextTool: com.voiceconfig.app.agent.TapTextTool,
        reviewTapTool: com.voiceconfig.app.agent.ReviewTapTool,
        inputTextTool: com.voiceconfig.app.agent.InputTextTool,
        swipeTool: com.voiceconfig.app.agent.SwipeTool,
        pressKeyTool: com.voiceconfig.app.agent.PressKeyTool,
        waitTool: com.voiceconfig.app.agent.WaitTool,
        notifyTool: com.voiceconfig.app.agent.NotifyTool,
        webSearchTool: com.voiceconfig.app.agent.WebSearchTool,
        openSearchTool: com.voiceconfig.app.agent.OpenSearchTool,
        createCalendarEventTool: com.voiceconfig.app.agent.CreateCalendarEventTool,
        fileWriteTool: com.voiceconfig.app.agent.FileWriteTool,
        fileReadTool: com.voiceconfig.app.agent.FileReadTool,
        clipboardReadTool: com.voiceconfig.app.agent.ClipboardReadTool,
        logcatReadTool: com.voiceconfig.app.agent.LogcatReadTool,
        openFileTool: com.voiceconfig.app.agent.OpenFileTool,
    ): CoreAgentPlugin = CoreAgentPlugin(
        openAppTool = openAppTool,
        findAppTool = findAppTool,
        runShellTool = runShellTool,
        readUiTool = readUiTool,
        readScreenTool = readScreenTool,
        getScreenStateTool = getScreenStateTool,
        tapTool = tapTool,
        tapTextTool = tapTextTool,
        reviewTapTool = reviewTapTool,
        inputTextTool = inputTextTool,
        swipeTool = swipeTool,
        pressKeyTool = pressKeyTool,
        waitTool = waitTool,
        notifyTool = notifyTool,
        webSearchTool = webSearchTool,
        openSearchTool = openSearchTool,
        createCalendarEventTool = createCalendarEventTool,
        fileWriteTool = fileWriteTool,
        fileReadTool = fileReadTool,
        clipboardReadTool = clipboardReadTool,
        logcatReadTool = logcatReadTool,
        openFileTool = openFileTool,
    )

    @Provides
    @Singleton
    fun providePluginRegistry(coreAgentPlugin: CoreAgentPlugin): PluginRegistry =
        PluginRegistry().load(coreAgentPlugin)

    @Provides
    @Singleton
    fun provideToolRegistry(pluginRegistry: PluginRegistry): ToolRegistry =
        pluginRegistry.toolRegistry()

    @Provides
    @Singleton
    fun provideExecutionEngine(channels: List<@kotlin.jvm.JvmSuppressWildcards ExecutionChannel>): ExecutionEngine =
        ExecutionEngine(channels = channels)
}
