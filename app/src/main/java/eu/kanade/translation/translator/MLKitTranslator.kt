package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.tasks.await 

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
        // --- إعداد منطق إصلاح الاتجاه (RTL) ---
        val rtlLanguages = setOf(
            TextTranslatorLanguage.ARABIC,
            TextTranslatorLanguage.PERSIAN,
            TextTranslatorLanguage.HEBREW,
            TextTranslatorLanguage.URDU,
            TextTranslatorLanguage.PUSHTO_PASHTO,
            TextTranslatorLanguage.SINDHI,
            TextTranslatorLanguage.UIGHUR_UYGHUR,
            TextTranslatorLanguage.YIDDISH,
            TextTranslatorLanguage.KURDISH
        )
        val isRTL = rtlLanguages.contains(toLang)
        val rtlMarker = if (isRTL) "\u200F" else ""

        // تحميل الموديل مرة واحدة في البداية
        translator.downloadModelIfNeeded(conditions).await()

        pages.forEach { (_, page) ->
            val validBlocks = page.blocks.filter { block ->
                val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) || 
                                      (block.angle >= 75.0f && block.angle <= 105.0f) || 
                                      (block.angle <= -75.0f && block.angle >= -105.0f)
                
                isAcceptedAngle && !urlPattern.containsMatchIn(block.text) && block.text.isNotBlank()
            }

            validBlocks.forEach { block ->
                try {
                    val cleanText = block.text.replace("\n", " ").trim()
                    
                    if (cleanText.length < 2) {
                        // في حال النص قصير جداً، نضيف الرمز أيضاً لضمان الاتجاه إذا كان رقماً
                        block.translation = rtlMarker + cleanText 
                    } else {
                        val result = translator.translate(cleanText).await()
                        // إضافة الرمز للنتيجة المترجمة
                        block.translation = rtlMarker + result
                    }
                } catch (e: Exception) {
                    block.translation = ""
                }
            }

            page.blocks.filter { it !in validBlocks }.forEach { it.translation = "" }
        }
    }

    override fun close() {
        translator.close()
    }
}
