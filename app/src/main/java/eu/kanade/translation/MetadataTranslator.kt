package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class MetadataTranslator(
    private val preferences: TranslationPreferences = Injekt.get()
) {
    // ذاكرة تخزين مؤقتة (Cache) لتفادي الترجمة المتكررة
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun translateTitle(title: String?): String {
        if (title.isNullOrBlank() || !preferences.metadataTranslationEnabled().get()) return title ?: ""
        return translateTextViaBubble(title, preferences.translateMangaTitleTo())
    }

    suspend fun translateDescription(description: String?): String {
        if (description.isNullOrBlank() || !preferences.metadataTranslationEnabled().get()) return description ?: ""
        return translateTextViaBubble(description, preferences.translateMangaDescriptionTo())
    }

    suspend fun translateTags(tags: String?): String {
        if (tags.isNullOrBlank() || !preferences.metadataTranslationEnabled().get()) return tags ?: ""
        return translateTextViaBubble(tags, preferences.translateMangaTagsTo())
    }

    /**
     * تغليف النص في "فقاعة وهمية" وتمرير الـ Preference مباشرة للمحرك
     */
    private suspend fun translateTextViaBubble(text: String, targetLangPref: Preference<String>): String {
        val targetLangCode = targetLangPref.get()
        val cacheKey = "${targetLangCode}_$text"
        cache[cacheKey]?.let { return it }

        return try {
            val dummyBlock = TranslationBlock(
                text = text,
                width = 0f, height = 0f, x = 0f, y = 0f, symWidth = 0f, symHeight = 0f, angle = 0f
            )
            
            val dummyPage = PageTranslation(blocks = mutableListOf(dummyBlock), imgWidth = 100f, imgHeight = 100f)
            val mapToTranslate = mutableMapOf("metadata_dummy_file" to dummyPage)

            // تمرير كائنات الـ Preference مباشرة بدلاً من القيم المستخرجة
            val fromLang = TextRecognizerLanguage.ENGLISH 
            val toLang = TextTranslatorLanguage.fromPref(targetLangPref)
            val enginePref = preferences.metadataTranslationEngine()

            val translator = TextTranslators.fromPref(enginePref)
                .build(preferences, fromLang, toLang)

            translator.translate(mapToTranslate)
            translator.close()

            val translatedText = mapToTranslate["metadata_dummy_file"]?.blocks?.firstOrNull()?.translation
            
            if (!translatedText.isNullOrBlank()) {
                cache[cacheKey] = translatedText
                translatedText
            } else {
                text
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "فشل في ترجمة البيانات الوصفية (Metadata)" }
            text
        }
    }
}
