package com.emma019.ondevicebubble.translate

object EngineRegistry {
    fun all(context: android.content.Context): List<TranslationEngine> = listOf(
        MlKitTranslationEngine(),
        LocalLlmTranslationEngine(context.applicationContext),
    )
}
