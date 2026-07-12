package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)
    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "CHINESE")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ENGLISH")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationEngine() = preferenceStore.getInt("translation_engine", 0)
    fun translationEngineModel() = preferenceStore.getString("translation_engine_model", "gemini-1.5-pro")
    fun translationEngineApiKey() = preferenceStore.getString("translation_engine_api_key", "")
    fun translationEngineTemperature() = preferenceStore.getString("translation_engine_temperature", "1")
    fun translationEngineMaxOutputTokens() = preferenceStore.getString("translation_engine_output_tokens", "8192")

    // كلمات مفلترة — يكتبها المستخدم مفصولة بفاصلة
    fun translationFilteredWords() = preferenceStore.getString("translation_filtered_words", "")

    // ترجمة فورية أثناء القراءة
    fun realtimeTranslation() = preferenceStore.getBoolean("realtime_translation", false)

    // ─── Performance & OCR ─────────────────────────────────────────────────
    fun maxOcrHeight() = preferenceStore.getInt("ocr_max_height", 2500)
    fun longImageAspectRatioThreshold() = preferenceStore.getString("ocr_aspect_ratio_threshold", "2.5")
    fun batchOcrConcurrency() = preferenceStore.getInt("ocr_batch_concurrency", 3)
    fun tileOcrConcurrency() = preferenceStore.getInt("ocr_tile_concurrency", 3)
    fun lowBubbleCountThreshold() = preferenceStore.getInt("ocr_low_bubble_threshold", 4)
    fun lowBubbleHistorySize() = preferenceStore.getInt("ocr_low_bubble_history_size", 5)
    fun realtimeLowBubbleConcurrency() = preferenceStore.getInt("realtime_low_bubble_concurrency", 3)
    fun realtimeIdleTimeoutSeconds() = preferenceStore.getInt("realtime_idle_timeout_seconds", 30)
    fun realtimePollIntervalMs() = preferenceStore.getInt("realtime_poll_interval_ms", 150)
    fun realtimePreloadMemoryBudgetMb() = preferenceStore.getInt("realtime_preload_memory_budget_mb", 150)
    fun realtimeDefaultPreloadCount() = preferenceStore.getInt("realtime_default_preload_count", 2)
    fun realtimeMaxPreloadCount() = preferenceStore.getInt("realtime_max_preload_count", 6)

    // ─── ترجمة بيانات المانجا (Metadata Translation) ───────────────────────

    // التفعيل العام لميزة ترجمة النصوص (الزر الشامل)
    fun metadataTranslationEnabled() = preferenceStore.getBoolean("metadata_translation_enabled", false)

    // تفعيل الترجمة لكل جزء على حدة (تظهر في إعدادات الفصل)
    fun translateMangaTitle() = preferenceStore.getBoolean("pref_translate_manga_title", true)
    fun translateMangaDescription() = preferenceStore.getBoolean("pref_translate_manga_description", true)
    fun translateMangaTags() = preferenceStore.getBoolean("pref_translate_manga_tags", true)

    // اختيار لغة الهدف لكل جزء بشكل مستقل
    fun translateMangaTitleTo() = preferenceStore.getString("metadata_target_lang_title", "ar")
    fun translateMangaDescriptionTo() = preferenceStore.getString("metadata_target_lang_description", "ar")
    fun translateMangaTagsTo() = preferenceStore.getString("metadata_target_lang_tags", "ar")
    fun translateSourceUiTo() = preferenceStore.getString("metadata_target_lang_ui", "ar") // لترجمة واجهة الإضافة

    // محرك الترجمة الخاص بالنصوص (مستقل عن محرك ترجمة الصور)
    fun metadataTranslationEngine() = preferenceStore.getInt("metadata_translation_engine", 0)
    fun metadataTranslationEngineModel() = preferenceStore.getString("metadata_translation_engine_model", "gemini-1.5-flash") 
    fun metadataTranslationEngineApiKey() = preferenceStore.getString("metadata_translation_engine_api_key", "")
}
