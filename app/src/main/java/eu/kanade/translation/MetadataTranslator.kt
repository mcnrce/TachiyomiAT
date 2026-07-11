package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

class MetadataTranslator(
    private val preferences: TranslationPreferences = Injekt.get()
) {
    // 匕丕賰乇丞 鬲禺夭賷賳 賲丐賯鬲丞 (Cache) 賱賰賷 賱丕 賳乇爻賱 賳賮爻 丕賱賳氐 賱賱鬲乇噩賲丞 賲乇鬲賷賳 賵賳爻鬲賴賱賰 丕賱賭 API
    private val cache = ConcurrentHashMap<String, String>()

    suspend fun translateTitle(title: String?): String {
        if (title.isNullOrBlank() || !preferences.metadataTranslationEnabled().get()) return title ?: ""
        val targetLangCode = preferences.translateMangaTitleTo().get()
        return translateTextViaBubble(title, targetLangCode)
    }

    suspend fun translateDescription(description: String?): String {
        if (description.isNullOrBlank() || !preferences.metadataTranslationEnabled().get()) return description ?: ""
        val targetLangCode = preferences.translateMangaDescriptionTo().get()
        return translateTextViaBubble(description, targetLangCode)
    }

    suspend fun translateTags(tags: String?): String {
        if (tags.isNullOrBlank() || !preferences.metadataTranslationEnabled().get()) return tags ?: ""
        val targetLangCode = preferences.translateMangaTagsTo().get()
        return translateTextViaBubble(tags, targetLangCode)
    }

    /**
     * 馃殌 鬲胤亘賷賯 賮賰乇鬲賰 丕賱毓亘賯乇賷丞: 鬲睾賱賷賮 丕賱賳氐 賮賷 "賮賯丕毓丞 賵賴賲賷丞" 賵廿乇爻丕賱賴丕 賱賱賲丨乇賰
     */
    private suspend fun translateTextViaBubble(text: String, targetLangCode: String): String {
        // 1. 丕賱鬲丨賯賯 賲賳 丕賱匕丕賰乇丞 丕賱賲丐賯鬲丞 兀賵賱丕賸
        val cacheKey = "${targetLangCode}_$text"
        cache[cacheKey]?.let { return it }

        return try {
            // 2. 廿賳卮丕亍 賮賯丕毓丞 (Block) 賵賴賲賷丞 兀亘毓丕丿賴丕 氐賮乇 鬲丨鬲賵賷 毓賱賶 丕賱賳氐
            val dummyBlock = TranslationBlock(
                text = text,
                width = 0f, height = 0f, x = 0f, y = 0f, symWidth = 0f, symHeight = 0f, angle = 0f
            )

            // 3. 賵囟毓 丕賱賮賯丕毓丞 丿丕禺賱 氐賮丨丞 賵賴賲賷丞 (Page)
            val dummyPage = PageTranslation(blocks = mutableListOf(dummyBlock), imgWidth = 100f, imgHeight = 100f)
            val mapToTranslate = mutableMapOf("metadata_dummy_file" to dummyPage)

            // 4. 亘賳丕亍 賲丨乇賰 丕賱鬲乇噩賲丞 丕賱禺丕氐 亘丕賱賳氐賵氐 亘賳丕亍賸 毓賱賶 廿毓丿丕丿丕鬲賰
            // 鬲賲乇賷乇 賰丕卅賳 preferences 賰丕賲賱 賱兀賳 build() 鬲鬲賵賯毓 TranslationPreferences
            // 賵鬲爻鬲禺乇噩 賴賷 亘賳賮爻賴丕 賯賷賲丞 丕賱賲丨乇賰 賵丕賱賲賵丿賷賱 賲賳 丕賱賭 Preference
            val fromLang = TextRecognizerLanguage.ENGLISH 
            val toLang = TextTranslatorLanguage.fromPref(targetLangCode)

            val translator = TextTranslators.fromPref(preferences.metadataTranslationEngine().get())
                .build(preferences, fromLang, toLang)

            // 5. 兀賲乇 丕賱鬲乇噩賲丞! (爻賷鬲毓丕賲賱 賲毓賴丕 賰兀賳賴丕 氐賮丨丞 賲丕賳噩丕 毓丕丿賷丞)
            translator.translate(mapToTranslate)
            translator.close() // 廿睾賱丕賯 丕賱丕鬲氐丕賱 亘毓丿 丕賱丕賳鬲賴丕亍

            // 6. 丕爻鬲禺乇丕噩 丕賱賳氐 丕賱賲鬲乇噩賲 賲賳 丕賱賮賯丕毓丞 丕賱賵賴賲賷丞
            val translatedText = mapToTranslate["metadata_dummy_file"]?.blocks?.firstOrNull()?.translation

            if (!translatedText.isNullOrBlank()) {
                cache[cacheKey] = translatedText // 丨賮馗 丕賱賳鬲賷噩丞 賮賷 丕賱賰丕卮
                translatedText
            } else {
                text // 賮賷 丨丕賱 乇噩毓 賮丕乇睾丕賸貙 賳毓乇囟 丕賱賳氐 丕賱兀氐賱賷
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "賮卮賱 賮賷 鬲乇噩賲丞 丕賱亘賷丕賳丕鬲 丕賱賵氐賮賷丞 (Metadata)" }
            text // 毓乇囟 丕賱賳氐 丕賱兀氐賱賷 毓賳丿 丨丿賵孬 禺胤兀
        }
    }
}
