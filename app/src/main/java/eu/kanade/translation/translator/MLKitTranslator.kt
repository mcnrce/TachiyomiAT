package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlin.math.abs

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

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        Tasks.await(translator.downloadModelIfNeeded(conditions))

        pages.forEach { (_, page) ->
            page.blocks.forEach { block ->
                
                // 1️⃣ تطبيق الفلاتر (الحروف الفريدة والزاوية)
                val cleanedText = block.text.filter { it.isLetterOrDigit() }
                val uniqueCount = cleanedText.toSet().size
                
                // فحص الزاوية (نطاق 5 درجات للتعامل مع ميلان المانجا)
                val angleOk = block.angle in -5f..5f
                
                // شرط الجودة: نص حقيقي وليس ضجيجاً
                val isNotNoise = block.text.length > 1 && uniqueCount >= 3

                if (isNotNoise && angleOk) {
                    try {
                        // منطق تقسيم الأسطر الأصلي الخاص بك
                        val lines = block.text.split("\n")
                        val originalWordCounts = lines.map { line ->
                            line.split(" ").size
                        }

                        // ترجمة النص كاملاً ككتلة واحدة لضمان السياق
                        val fullText = block.text.replace("\n", " ")
                        val translatedText = Tasks.await(translator.translate(fullText))

                        // إعادة تقسيم النص المترجم حسب عدد الكلمات لكل سطر
                        val words = translatedText.split(" ")
                        val rebuiltLines = mutableListOf<String>()
                        var index = 0
                        
                        for (count in originalWordCounts) {
                            if (index >= words.size) break
                            val end = minOf(index + count, words.size)
                            rebuiltLines.add(words.subList(index, end).joinToString(" "))
                            index = end
                        }

                        // إضافة أي كلمات متبقية للسطر الأخير
                        if (index < words.size && rebuiltLines.isNotEmpty()) {
                            rebuiltLines[rebuiltLines.size - 1] += " " + words.subList(index, words.size).joinToString(" ")
                        }

                        block.translation = rebuiltLines.joinToString("\n")
                    } catch (e: Exception) {
                        block.translation = "" 
                    }
                } else {
                    // إذا كان النص ضجيجاً أو مائلاً جداً، نمسح الترجمة
                    block.translation = ""
                }
            }
        }
    }

    override fun close() {
        translator.close()
    }
}
