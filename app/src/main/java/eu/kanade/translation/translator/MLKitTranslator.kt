package eu.kanade.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage

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
    
    // Regex لاكتشاف الروابط والمواقع
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        Tasks.await(translator.downloadModelIfNeeded(conditions))

        pages.forEach { (_, page) ->
            page.blocks.forEach { block ->
                
                // 1️⃣ فحص الزاوية (أفقي وعمودي صريح فقط)
                val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) || 
                                      (block.angle >= 75.0f && block.angle <= 105.0f) || 
                                      (block.angle <= -75.0f && block.angle >= -105.0f)

                // 2️⃣ فحص الروابط
                val isUrl = urlPattern.containsMatchIn(block.text)

                // 3️⃣ التحقق من الشروط قبل الترجمة
                if (isAcceptedAngle && !isUrl) {
                    try {
                        val lines = block.text.split("\n")
                        val originalWordCounts = lines.map { line ->
                            line.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        }

                        // الترجمة (نرسل النص ككتلة واحدة للسياق المحلي)
                        val fullText = block.text.replace("\n", " ").trim()
                        if (fullText.isEmpty()) {
                            block.translation = ""
                            return@forEach
                        }

                        val translatedText = Tasks.await(translator.translate(fullText))

                        // إعادة بناء الأسطر (منطق الحفاظ على هيكل الفقاعة)
                        val words = translatedText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val rebuiltLines = mutableListOf<String>()
                        var index = 0
                        
                        for (count in originalWordCounts) {
                            if (index >= words.size) break
                            val end = (index + count).coerceAtMost(words.size)
                            rebuiltLines.add(words.subList(index, end).joinToString(" "))
                            index = end
                        }

                        // إضافة أي كلمات متبقية للسطر الأخير
                        if (index < words.size && rebuiltLines.isNotEmpty()) {
                            val lastIdx = rebuiltLines.size - 1
                            rebuiltLines[lastIdx] = (rebuiltLines[lastIdx] + " " + words.subList(index, words.size).joinToString(" ")).trim()
                        }

                        block.translation = rebuiltLines.joinToString("\n")
                    } catch (e: Exception) {
                        block.translation = ""
                    }
                } else {
                    // إذا كان مؤثراً صوتياً مائلاً أو رابطاً، نمسح النص
                    block.translation = ""
                }
            }
        }
    }

    override fun close() {
        translator.close()
    }
}
