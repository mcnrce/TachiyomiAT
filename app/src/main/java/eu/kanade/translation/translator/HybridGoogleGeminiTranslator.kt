package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import tachiyomi.core.common.util.system.logcat 

class HybridGoogleGeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiKey: String,
    private val modelName: String,
    private val maxOutputTokens: Int,
    private val temperature: Float
) : TextTranslator {

    // إنشاء المترجمين داخلياً باستخدام المعطيات الممرة
    private val googleTranslator = GoogleTranslator(fromLang, toLang)
    private val geminiTranslator = GeminiTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)

    private val cleanRegex = Regex("[0-9\\p{Punct}]+")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // 1. تشغيل جوجل أولاً
        googleTranslator.translate(pages)

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

        // 2. إرسال البلوكات الفاشلة فقط إلى Gemini
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
