package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.tasks.await // تأكد من استيراد هذه للتعامل مع Coroutines بشكل أفضل

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    private var translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build()
    )

    private var conditions = DownloadConditions.Builder().build()
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // تحميل الموديل مرة واحدة في البداية
        translator.downloadModelIfNeeded(conditions).await()

        pages.forEach { (_, page) ->
            // تحسين: تصفية البلوكات التي تحتاج ترجمة فقط لتقليل العمليات
            val validBlocks = page.blocks.filter { block ->
                val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) || 
                                      (block.angle >= 75.0f && block.angle <= 105.0f) || 
                                      (block.angle <= -75.0f && block.angle >= -105.0f)
                
                isAcceptedAngle && !urlPattern.containsMatchIn(block.text) && block.text.isNotBlank()
            }

            // تنفيذ الترجمة بالتوازي لزيادة السرعة القصوى
            validBlocks.forEach { block ->
                try {
                    // تنظيف النص قبل الإرسال (حذف الأسطر الزائدة والمسافات)
                    val cleanText = block.text.replace("\n", " ").trim()
                    
                    if (cleanText.length < 2) {
                        // إذا كان حرفاً واحداً (ضوضاء OCR)، غالباً لا يحتاج ترجمة
                        block.translation = cleanText 
                    } else {
                        // استخدام await() الخاص بـ Coroutines بدلاً من Tasks.await()
                        val result = translator.translate(cleanText).await()
                        block.translation = result
                    }
                } catch (e: Exception) {
                    block.translation = ""
                }
            }

            // مسح ترجمة البلوكات غير الصالحة (الروابط والمؤثرات المائلة)
            page.blocks.filter { it !in validBlocks }.forEach { it.translation = "" }
        }
    }

    override fun close() {
        translator.close()
    }
}
