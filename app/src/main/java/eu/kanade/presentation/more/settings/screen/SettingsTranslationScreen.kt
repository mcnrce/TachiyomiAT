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

    // ─── القسم الجديد: إعدادات الأداء والتقطيع الذكي ────────────────────────────────────
    @Composable
    private fun getTranslationPerformanceGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = "الأداء والتعرف المتقدم (OCR & Performance)",
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.maxOcrHeight(),
                    title = "الحد الأقصى لارتفاع الصورة (Webtoon)",
                    subtitle = "الصور الأطول سيتم تقطيعها بذكاء للحفاظ على دقة الـ OCR.",
                    entries = listOf(1500, 2000, 2500, 3000, 4000).associateWith { "$it بكسل" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.longImageAspectRatioThreshold(),
                    title = "نسبة العرض للطول للويب تون",
                    subtitle = "الحد الأدنى لاعتبار الصورة ويب تون طويل (مثال: 2.5).",
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.batchOcrConcurrency(),
                    title = "توازي الصفحات (الترجمة العادية)",
                    subtitle = "عدد الصفحات التي تتم معالجتها معاً في الخلفية.",
                    entries = listOf(1, 2, 3, 4, 6, 8).associateWith { "$it صفحات" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.tileOcrConcurrency(),
                    title = "توازي شرائح الصور الطويلة",
                    subtitle = "عدد الشرائح المعالجة معاً للصورة الطويلة الواحدة.",
                    entries = listOf(1, 2, 3, 4, 6, 8).associateWith { "$it شرائح" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.lowBubbleCountThreshold(),
                    title = "حد الفقاعات المنخفض (وضع الأكشن)",
                    subtitle = "متوسط الفقاعات لتفعيل المعالجة السريعة (Fast-mode).",
                    entries = listOf(2, 4, 6, 8, 10).associateWith { "$it فقاعات" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.lowBubbleHistorySize(),
                    title = "حجم سجل الفقاعات",
                    subtitle = "عدد الصفحات لحساب متوسط كثافة النصوص.",
                    entries = listOf(3, 5, 8, 10).associateWith { "$it صفحات" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeLowBubbleConcurrency(),
                    title = "توازي الصفحات (الترجمة الفورية السريعة)",
                    subtitle = "زيادة السرعة في مشاهد الأكشن خفيفة النصوص.",
                    entries = listOf(1, 2, 3, 4, 6, 8).associateWith { "$it صفحات" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeIdleTimeoutSeconds(),
                    title = "مهلة الخمول للترجمة الفورية",
                    subtitle = "إيقاف المترجم لتوفير البطارية عند توقفك عن القراءة.",
                    entries = listOf(1, 3, 5, 10, 15, 30, 45, 60, 120, 300).associateWith { "$it ثانية" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimePollIntervalMs(),
                    title = "معدل فحص الصفحات (الترجمة الفورية)",
                    subtitle = "الزمن بين محاولات الفحص. رقم أقل = استجابة أسرع.",
                    entries = listOf(10, 20, 30, 40, 50, 100, 150, 250, 500).associateWith { "$it ملي ثانية" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimePreloadMemoryBudgetMb(),
                    title = "ميزانية الذاكرة للتحميل المسبق",
                    subtitle = "الحد الأقصى للرام المسموح به للتحميل المسبق (Preload).",
                    entries = listOf(50, 100, 150, 200, 300, 400, 250, 400, 500).associateWith { "$it MB" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeDefaultPreloadCount(),
                    title = "العدد الافتراضي للتحميل المسبق",
                    subtitle = "عدد الصفحات المترجمة مسبقاً قبل وصولك إليها.",
                    entries = listOf(1, 2, 3, 4, 5).associateWith { "$it صفحات" }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.realtimeMaxPreloadCount(),
                    title = "الحد الأقصى المطلق للتحميل المسبق",
                    subtitle = "يمنع تحميل عدد مفرط من الصفحات مهما كانت الميزانية.",
                    entries = listOf(1, 2, 4, 6, 8, 10).associateWith { "$it صفحات" }.toImmutableMap(),
                ),
            ),
        )
    }
}
