package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
// تصحيح مسار logcat ليطابق المستخدم في الملفات الأخرى
import tachiyomi.core.common.util.system.logcat 

class HybridGoogleGeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val googleTranslator: GoogleTranslator,
    private val geminiTranslator: GeminiTranslator
) : TextTranslator {

    private val cleanRegex = Regex("[0-9\\p{Punct}]+")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // الخطوة 1: تشغيل مترجم جوجل أولاً
        googleTranslator.translate(pages)

        // الخطوة 2: تحديد البلوكات التي فشل جوجل في ترجمتها (بقيت كما هي)
        val failedBlocksMap = mutableMapOf<String, PageTranslation>()

        for ((pageKey, page) in pages) {
            val failedBlocks = mutableListOf<TranslationBlock>()

            for (block in page.blocks) {
                // نتحقق إذا كانت الترجمة لا تزال تحتوي على كلمات أصلية لم تترجم
                if (!block.translation.isNullOrBlank() && hasUntranslatedWords(block.text, block.translation!!)) {
                    failedBlocks.add(block)
                }
            }

            if (failedBlocks.isNotEmpty()) {
                failedBlocksMap[pageKey] = PageTranslation(
                    blocks = failedBlocks,
                    imageWidth = page.imageWidth,
                    imageHeight = page.imageHeight
                )
            }
        }

        // الخطوة 3: إرسال البلوكات الفاشلة فقط إلى Gemini
        if (failedBlocksMap.isNotEmpty()) {
            try {
                logcat { "Hybrid: Sending ${failedBlocksMap.size} pages with issues to Gemini" }
                geminiTranslator.translate(failedBlocksMap)
            } catch (e: Exception) {
                logcat { "Gemini fallback failed: ${e.message}" }
            }
        }
    }

    private fun hasUntranslatedWords(original: String, translated: String): Boolean {
        val cleanOriginal = original.replace(cleanRegex, " ").trim()
        val cleanTranslated = translated.replace(cleanRegex, " ").trim()

        val originalWords = cleanOriginal.split(Regex("\\s+")).filter { it.isNotBlank() }
        val translatedWords = cleanTranslated.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()

        return originalWords.any { word ->
            word.length > 2 && word.lowercase() in translatedWords
        }
    }

    override fun close() {
        googleTranslator.close()
        geminiTranslator.close()
    }
}
