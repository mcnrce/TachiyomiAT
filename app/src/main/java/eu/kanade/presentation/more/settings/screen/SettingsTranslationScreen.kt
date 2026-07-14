package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = ATMR.strings.pref_category_translations

    @Composable
    override fun getPreferences(): List<Preference> {
        val entries = TranslationFont.entries
        val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                pref = translationPreferences.autoTranslateAfterDownload(),
                title = stringResource(ATMR.strings.pref_translate_after_downloading),
            ),
            Preference.PreferenceItem.SwitchPreference(
                pref = translationPreferences.realtimeTranslation(),
                title = stringResource(ATMR.strings.pref_realtime_translation),
                subtitle = stringResource(ATMR.strings.pref_sub_realtime_translation),
            ),
            Preference.PreferenceItem.ListPreference(
                pref = translationPreferences.translationFont(),
                title = stringResource(ATMR.strings.pref_reader_font),
                entries = entries.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
            ),
            getTranslationLangGroup(translationPreferences),
            getMetadataTranslationGroup(translationPreferences), // القسم الجديد للبيانات الوصفية
            getTranslatioEngineGroup(translationPreferences),
            getTranslatioAdvancedGroup(translationPreferences),
            getTranslationPerformanceGroup(translationPreferences), // القسم الجديد للأداء
        )
    }

    @Composable
    private fun getTranslationLangGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val fromLangs = TextRecognizerLanguage.entries
        val toLangs = TextTranslatorLanguage.entries
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_setup),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateFromLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_from),
                    entries = fromLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateToLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_to),
                    entries = toLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
            ),
        )
    }


    @Composable
    private fun getMetadataTranslationGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val toLangs = TextTranslatorLanguage.entries
        val engines = TextTranslators.entries
        
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_metadata),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.SwitchPreference(
                    pref = translationPreferences.metadataTranslationEnabled(),
                    title = stringResource(ATMR.strings.pref_metadata_enabled),
                    subtitle = stringResource(ATMR.strings.pref_sub_metadata_enabled)
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateMangaTitleTo(),
                    title = stringResource(ATMR.strings.pref_metadata_title_lang),
                    entries = toLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateMangaDescriptionTo(),
                    title = stringResource(ATMR.strings.pref_metadata_desc_lang),
                    entries = toLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateMangaTagsTo(),
                    title = stringResource(ATMR.strings.pref_metadata_tags_lang),
                    entries = toLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                // السطر الجديد الذي أضفناه هنا:
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateSourceUiTo(),
                    title = stringResource(ATMR.strings.pref_metadata_ui_lang), // تأكد من إضافة هذا النص في ملف الـ Strings الخاص بك
                    entries = toLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.metadataTranslationEngine(),
                    title = stringResource(ATMR.strings.pref_metadata_engine),
                    entries = engines.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
                ),
            ),
        )
    }

    @Composable
    private fun getTranslatioEngineGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val engines = TextTranslators.entries
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_engine),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationEngine(),
                    title = stringResource(ATMR.strings.pref_translator_engine),
                    entries = engines.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineApiKey(),
                    subtitle = stringResource(ATMR.strings.pref_sub_engine_api_key),
                    title = stringResource(ATMR.strings.pref_engine_api_key),
                ),
            ),
        )
    }

    @Composable
    private fun getTranslatioAdvancedGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_advanced),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineModel(),
                    title = stringResource(ATMR.strings.pref_engine_model),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineTemperature(),
                    title = stringResource(ATMR.strings.pref_engine_temperature),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineMaxOutputTokens(),
                    title = stringResource(ATMR.strings.pref_engine_max_output),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationFilteredWords(),
                    title = stringResource(ATMR.strings.pref_translation_filtered_words),
                    subtitle = stringResource(ATMR.strings.pref_sub_translation_filtered_words),
                ),
            ),
        )
    }

    @Composable
    private fun getTranslationPerformanceGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_performance),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.maxOcrHeight(),
                    title = stringResource(ATMR.strings.pref_max_ocr_height),
                    subtitle = stringResource(ATMR.strings.pref_sub_max_ocr_height),
                    entries = listOf(1500, 2000, 2500, 3000, 4000).associateWith { "$it px" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.longImageAspectRatioThreshold(),
                    title = stringResource(ATMR.strings.pref_long_image_aspect_ratio),
                    subtitle = stringResource(ATMR.strings.pref_sub_long_image_aspect_ratio),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.batchOcrConcurrency(),
                    title = stringResource(ATMR.strings.pref_batch_ocr_concurrency),
                    subtitle = stringResource(ATMR.strings.pref_sub_batch_ocr_concurrency),
                    entries = listOf(1, 2, 3, 4, 6, 8).associateWith { "$it pages" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.tileOcrConcurrency(),
                    title = stringResource(ATMR.strings.pref_tile_ocr_concurrency),
                    subtitle = stringResource(ATMR.strings.pref_sub_tile_ocr_concurrency),
                    entries = listOf(1, 2, 3, 4, 6, 8).associateWith { "$it tiles" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.lowBubbleCountThreshold(),
                    title = stringResource(ATMR.strings.pref_low_bubble_threshold),
                    subtitle = stringResource(ATMR.strings.pref_sub_low_bubble_threshold),
                    entries = listOf(2, 4, 6, 8, 10).associateWith { "$it bubbles" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.lowBubbleHistorySize(),
                    title = stringResource(ATMR.strings.pref_low_bubble_history),
                    subtitle = stringResource(ATMR.strings.pref_sub_low_bubble_history),
                    entries = listOf(3, 5, 8, 10).associateWith { "$it pages" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeLowBubbleConcurrency(),
                    title = stringResource(ATMR.strings.pref_realtime_low_bubble_concurrency),
                    subtitle = stringResource(ATMR.strings.pref_sub_realtime_low_bubble_concurrency),
                    entries = listOf(1, 2, 3, 4, 6, 8).associateWith { "$it pages" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeIdleTimeoutSeconds(),
                    title = stringResource(ATMR.strings.pref_realtime_idle_timeout),
                    subtitle = stringResource(ATMR.strings.pref_sub_realtime_idle_timeout),
                    entries = listOf(1, 3, 5, 10, 15, 30, 45, 60, 120, 300).associateWith { "$it s" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimePollIntervalMs(),
                    title = stringResource(ATMR.strings.pref_realtime_poll_interval),
                    subtitle = stringResource(ATMR.strings.pref_sub_realtime_poll_interval),
                    entries = listOf(10, 20, 30, 40, 50, 100, 150, 250, 500).associateWith { "$it ms" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimePreloadMemoryBudgetMb(),
                    title = stringResource(ATMR.strings.pref_realtime_preload_memory),
                    subtitle = stringResource(ATMR.strings.pref_sub_realtime_preload_memory),
                    entries = listOf(50, 100, 150, 200, 300, 400, 250, 400, 500).associateWith { "$it MB" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeDefaultPreloadCount(),
                    title = stringResource(ATMR.strings.pref_realtime_default_preload),
                    subtitle = stringResource(ATMR.strings.pref_sub_realtime_default_preload),
                    entries = listOf(1, 2, 3, 4, 5).associateWith { "$it pages" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeMaxPreloadCount(),
                    title = stringResource(ATMR.strings.pref_realtime_max_preload),
                    subtitle = stringResource(ATMR.strings.pref_sub_realtime_max_preload),
                    entries = listOf(1, 2, 4, 6, 8, 10).associateWith { "$it pages" }.toImmutableMap(),
                ),
            ),
        )
    }
}
