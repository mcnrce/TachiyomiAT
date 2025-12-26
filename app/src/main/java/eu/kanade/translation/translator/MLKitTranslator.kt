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
            // جمع النصوص الأصلية لكل بلوك في فقاعات كاملة
            page.blocks.map { block ->
                val lines = block.text.split("\n")
                val originalWordCounts = lines.map { line ->
                    // عدد الكلمات = عدد الفراغات + 1
                    line.split(" ").size
                }

                // ترجمة النص كاملاً دفعة واحدة
                val fullText = block.text.replace("\n", " ")
                val translatedText = Tasks.await(translator.translate(fullText))

                // إعادة تقسيم النص المترجم حسب عدد الكلمات لكل سطر
                val words = translatedText.split(" ")
                val rebuiltLines = mutableListOf<String>()
                var index = 0
                for (count in originalWordCounts) {
                    if (index >= words.size) break
                    val end = (index + count).coerceAtMost(words.size)
                    rebuiltLines.add(words.subList(index, end).joinToString(" "))
                    index = end
                }
                // إذا بقيت كلمات، نضعها في السطر الأخير
                if (index < words.size && rebuiltLines.isNotEmpty()) {
                    rebuiltLines[rebuiltLines.size - 1] += " " + words.subList(index, words.size).joinToString(" ")
                }

                block.translation = rebuiltLines.joinToString("\n")
            }
        }
    }

    override fun close() {
        translator.close()
    }
}
