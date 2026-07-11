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
    // ذاكرة تخزين مؤقتة (Cache) لكي لا نرسل نفس النص للترجمة مرتين ونستهلك الـ API
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
     * 🚀 تطبيق فكرتك العبقرية: تغليف النص في "فقاعة وهمية" وإرسالها للمحرك
     */
    private suspend fun translateTextViaBubble(text: String, targetLangCode: String): String {
        // 1. التحقق من الذاكرة المؤقتة أولاً
        val cacheKey = "${targetLangCode}_$text"
        cache[cacheKey]?.let { return it }

        return try {
            // 2. إنشاء فقاعة (Block) وهمية أبعادها صفر تحتوي على النص
            val dummyBlock = TranslationBlock(
                text = text,
                width = 0f, height = 0f, x = 0f, y = 0f, symWidth = 0f, symHeight = 0f, angle = 0f
            )
            
            // 3. وضع الفقاعة داخل صفحة وهمية (Page)
            val dummyPage = PageTranslation(blocks = mutableListOf(dummyBlock), imgWidth = 100f, imgHeight = 100f)
            val mapToTranslate = mutableMapOf("metadata_dummy_file" to dummyPage)

            // 4. بناء محرك الترجمة الخاص بالنصوص بناءً على إعداداتك
            val engineType = preferences.metadataTranslationEngine().get()
            // نستخدم لغة المصدر كمجهول/إنجليزي لأن الـ API (مثل جيميناي) يتعرف على لغة النص تلقائياً
            val fromLang = TextRecognizerLanguage.ENGLISH 
            val toLang = TextTranslatorLanguage.fromPref(targetLangCode)

            val translator = TextTranslators.fromPref(engineType)
                .build(preferences, fromLang, toLang)

            // 5. أمر الترجمة! (سيتعامل معها كأنها صفحة مانجا عادية)
            translator.translate(mapToTranslate)
            translator.close() // إغلاق الاتصال بعد الانتهاء

            // 6. استخراج النص المترجم من الفقاعة الوهمية
            val translatedText = mapToTranslate["metadata_dummy_file"]?.blocks?.firstOrNull()?.translation
            
            if (!translatedText.isNullOrBlank()) {
                cache[cacheKey] = translatedText // حفظ النتيجة في الكاش
                translatedText
            } else {
                text // في حال رجع فارغاً، نعرض النص الأصلي
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "فشل في ترجمة البيانات الوصفية (Metadata)" }
            text // عرض النص الأصلي عند حدوث خطأ
        }
    }
}
