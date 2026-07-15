package eu.kanade.translation

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.tasks.await
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
        if (title.isNullOrBlank() || !preferences.metadataTranslationEnabled().get() || !preferences.translateMangaTitle().get()) return title ?: ""
        return translateTextViaBubble(title, preferences.translateMangaTitleTo())
    }

    suspend fun translateDescription(description: String?): String {
        if (description.isNullOrBlank() || !preferences.metadataTranslationEnabled().get() || !preferences.translateMangaDescription().get()) return description ?: ""
        return translateTextViaBubble(description, preferences.translateMangaDescriptionTo())
    }

    suspend fun translateTags(tags: String?): String {
        if (tags.isNullOrBlank() || !preferences.metadataTranslationEnabled().get() || !preferences.translateMangaTags().get()) return tags ?: ""
        return translateTextViaBubble(tags, preferences.translateMangaTagsTo())
    }

    // 🚀 دالة الترجمة المجمعة المحدثة (Batch Translation)
    suspend fun translateFiltersBatch(texts: Set<String>): Map<String, String> {
        if (texts.isEmpty() || !preferences.metadataTranslationEnabled().get()) return emptyMap()

        val targetLangPref = preferences.translateSourceUiTo()
        val targetLangCode = targetLangPref.get()
        
        val toTranslate = mutableListOf<String>()
        val resultMap = mutableMapOf<String, String>()

        // فحص الذاكرة المؤقتة والتحقق من تطابق واستبعاد اللغات أولاً لتقليل المعالجة
        for (text in texts) {
            if (text.isBlank()) continue
            val cacheKey = "${targetLangCode}_$text"
            if (cache.containsKey(cacheKey)) {
                resultMap[text] = cache[cacheKey]!!
            } else {
                val detected = identifyRawLanguage(text)
                // إذا كانت اللغة الأصلية تطابق لغة الترجمة، أو كانت مستثناة، نرجع النص كما هو ونضعه بالكاش
                if (isSameLanguage(detected, targetLangCode) || isLanguageExcluded(detected)) {
                    resultMap[text] = text
                    cache[cacheKey] = text
                } else {
                    toTranslate.add(text)
                }
            }
        }

        if (toTranslate.isEmpty()) return resultMap

        return try {
            // تجميع النصوص في كتل داخل صفحة وهمية واحدة (محاكاة لصفحة مانجا)
            val blocks = toTranslate.map { text ->
                TranslationBlock(
                    text = text,
                    width = 0f, height = 0f, x = 0f, y = 0f, symWidth = 0f, symHeight = 0f, angle = 0f
                )
            }
            
            val dummyPage = PageTranslation(blocks = blocks.toMutableList(), imgWidth = 100f, imgHeight = 100f)
            val mapToTranslate = mutableMapOf("metadata_dummy_batch" to dummyPage)

            // دمج النصوص للتعرف على اللغة الأم بشكل أدق
            val combinedText = toTranslate.joinToString(" ")
            val fromLang = detectSourceLanguage(combinedText) 
            val toLang = TextTranslatorLanguage.fromPref(targetLangPref)
            val enginePref = preferences.metadataTranslationEngine()

            val translator = TextTranslators.fromPref(enginePref)
                .build(preferences, fromLang, toLang)

            // إرسال طلب واحد فقط لترجمة كل شيء!
            translator.translate(mapToTranslate)
            translator.close()

            val translatedBlocks = mapToTranslate["metadata_dummy_batch"]?.blocks
            
            translatedBlocks?.forEachIndexed { index, block ->
                val originalText = toTranslate[index]
                val translatedText = block.translation
       
                if (!translatedText.isNullOrBlank()) {
                    val cacheKey = "${targetLangCode}_$originalText"
                    cache[cacheKey] = translatedText
                    resultMap[originalText] = translatedText
                }
            }
            resultMap
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "فشل في ترجمة الفلاتر المجمعة (Batch)" }
            resultMap
        }
    }

    private suspend fun translateTextViaBubble(text: String, targetLangPref: Preference<String>): String {
        val targetLangCode = targetLangPref.get()
        
        // 1. تحقق من لغة النص الأصلية قبل كل شيء لتجنب العمليات غير الضرورية
        val detectedLang = identifyRawLanguage(text)
        if (isSameLanguage(detectedLang, targetLangCode) || isLanguageExcluded(detectedLang)) {
            return text
        }

        val cacheKey = "${targetLangCode}_$text"
        cache[cacheKey]?.let { return it }

        return try {
            val dummyBlock = TranslationBlock(
                text = text,
                width = 0f, height = 0f, x = 0f, y = 0f, symWidth = 0f, symHeight = 0f, angle = 0f
            )
            
            val dummyPage = PageTranslation(blocks = mutableListOf(dummyBlock), imgWidth = 100f, imgHeight = 100f)
            val mapToTranslate = mutableMapOf("metadata_dummy_file" to dummyPage)

            val fromLang = detectSourceLanguage(text) 
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

    // التعرف على اللغة الخام بشكل دقيق عبر ML Kit
    private suspend fun identifyRawLanguage(text: String): String {
        return try {
            val identifier = LanguageIdentification.getClient()
            identifier.identifyLanguage(text).await()
        } catch (e: Exception) {
            "und"
        }
    }

    // مقارنة اللغتين بعد تسوية الصيغة (Normalization)
    private fun isSameLanguage(detected: String, target: String): Boolean {
        if (detected == "und" || detected.isBlank()) return false
        val normDetected = detected.substringBefore("-").lowercase().trim()
        val normTarget = target.substringBefore("-").lowercase().trim()
        return normDetected == normTarget
    }

    // التحقق مما إذا كانت اللغة تقع ضمن قائمة اللغات المستثناة
    private fun isLanguageExcluded(langCode: String): Boolean {
    return try {
        val excluded = preferences.translationExcludedLanguages().get()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val normalizedLang = langCode.substringBefore("-").lowercase().trim()
        excluded.any { it.substringBefore("-").lowercase().trim() == normalizedLang }
    } catch (e: Exception) {
        false
    }

    private suspend fun detectSourceLanguage(text: String): TextRecognizerLanguage {
        val identifier = LanguageIdentification.getClient()
        return try {
            val languageCode = identifier.identifyLanguage(text).await()
            
            when (languageCode) {
                "zh" -> TextRecognizerLanguage.CHINESE
                "ja" -> TextRecognizerLanguage.JAPANESE
                "ko" -> TextRecognizerLanguage.KOREAN
                else -> TextRecognizerLanguage.ENGLISH
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "فشل في التعرف على اللغة عبر ML Kit" }
            TextRecognizerLanguage.ENGLISH
        }
    }
}
