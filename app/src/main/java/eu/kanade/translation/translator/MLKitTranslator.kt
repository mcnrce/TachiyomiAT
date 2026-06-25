package eu.kanade.translation.translator

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

// دالة مساعدة لتحويل Google Tasks إلى coroutines بدون حاجة لمكتبة إضافية
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { e -> cont.resumeWithException(e) }
}

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    private var translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build(),
    )

    private var conditions = DownloadConditions.Builder().build()
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) = coroutineScope {
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
            TextTranslatorLanguage.KURDISH,
        )
        val isRTL = rtlLanguages.contains(toLang)
        val rtlMarker = if (isRTL) "\u200F" else ""

        // تحميل الموديل مرة واحدة في البداية
        translator.downloadModelIfNeeded(conditions).await()

        pages.forEach { (_, page) ->
            // 1. تصفية ذكية محسنة وقبول مرن للزوايا لحماية النصوص المدمجة عمودياً
            val validBlocks = page.blocks.filter { block ->
                val angle = abs(block.angle)
                val isAcceptedAngle = angle <= 15.0f || angle >= 75.0f
                isAcceptedAngle && !urlPattern.containsMatchIn(block.text) && block.text.isNotBlank()
            }

            // 2. معالجة وترجمة النصوص بشكل متوازٍ
            val deferredTranslations = validBlocks.map { block ->
                async {
                    try {
                        var cleanText = block.text.trim()
                        val isVerticalText = abs(block.angle) in 65.0f..115.0f

                        if (isVerticalText) {
                            cleanText = cleanText.replace(Regex("[ー\u2014\u2015]"), "")
                            cleanText = cleanText.replace(Regex("\\s+"), "")
                        } else {
                            cleanText = cleanText.replace(Regex("\\s+"), " ")
                        }

                        if (cleanText.length < 2) {
                            block.translation = rtlMarker + cleanText
                        } else {
                            val result = translator.translate(cleanText).await()
                            if (result.isNotBlank()) {
                                block.translation = rtlMarker + result.trim()
                            } else {
                                block.translation = block.text
                            }
                        }
                    } catch (e: Exception) {
                        block.translation = block.text
                    }
                }
            }

            deferredTranslations.awaitAll()

            page.blocks.filter { it !in validBlocks }.forEach { it.translation = "" }
            page.blocks = page.blocks.filterNot { it.translation.isBlank() && it.text.isNotBlank() }.toMutableList()
        }
    }

    override fun close() {
        translator.close()
    }
}
