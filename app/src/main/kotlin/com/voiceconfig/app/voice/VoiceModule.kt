package com.voiceconfig.app.voice

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds
    abstract fun bindGlobalSpeechInputFactory(
        impl: GlobalSpeechRouter,
    ): GlobalSpeechInputFactory
}
