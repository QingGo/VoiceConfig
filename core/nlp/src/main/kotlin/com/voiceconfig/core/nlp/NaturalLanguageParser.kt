package com.voiceconfig.core.nlp

import com.voiceconfig.core.model.TaskDraft

interface NaturalLanguageParser {
    fun parse(input: String): TaskDraft?
}
