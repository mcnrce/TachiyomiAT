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

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        Tasks.await(translator.downloadModelIfNeeded(conditions))

        pages.mapValues { (_, page) ->
            page.blocks.map { block ->
                
                // 1️⃣ تنظيف النص من الأسطر والمسافات للفحص فقط
                // نستخدم replace لضمان أن charCount يحسب الحروف الحقيقية فقط
                val textForFiltering = block.text.replace("\n", "").replace(" ", "")
                
                // 2️⃣ بداية الفلترة اليدوية على النص المنظف
                var charCount = 0
                val seenChars = mutableMapOf<Char, Boolean>()
                for (c in textForFiltering) {
                    if (!seenChars.containsKey(c)) {
                        seenChars[c] = true
                        charCount += 1
                    }
                }

                // فحص الزاوية
                val angleOk = block.angle >= -15.0 && block.angle <= 15.0

                // 3️⃣ التحقق من الشروط
                if (charCount >= 4 && angleOk) {
                    try {
                        // هنا نعود للنص الأصلي (block.text) لتقسيم الأسطر بشكل صحيح
                        val lines = block.text.split("\n")
                        val originalWordCounts = lines.map { line ->
                            line.split(" ").size
                        }

                        // الترجمة (نستخدم مسافة بدلاً من السطر الجديد للسياق)
                        val fullText = block.text.replace("\n", " ")
                        val translatedText = Tasks.await(translator.translate(fullText))

                        // إعادة البناء (منطقك الأصلي)
                        val words = translatedText.split(" ")
                        val rebuiltLines = mutableListOf<String>()
                        var index = 0
                        for (count in originalWordCounts) {
                            if (index >= words.size) break
                            val end = (index + count).coerceAtMost(words.size)
                            rebuiltLines.add(words.subList(index, end).joinToString(" "))
                            index = end
                        }

                        if (index < words.size && rebuiltLines.isNotEmpty()) {
                            rebuiltLines[rebuiltLines.size - 1] += " " + words.subList(index, words.size).joinToString(" ")
                        }

                        block.translation = rebuiltLines.joinToString("\n")
                    } catch (e: Exception) {
                        block.translation = ""
                    }
                } else {
                    block.translation = ""
                }
            }
        }
    }

    override fun close() {
        translator.close()
    }
}
