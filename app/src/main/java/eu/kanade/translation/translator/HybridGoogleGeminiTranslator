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
                // نتحقق إذا كانت الترجمة لا تزال تحتوي على كلمات أصلية
                if (!block.translation.isNullOrBlank() && hasUntranslatedWords(block.text, block.translation!!)) {
                    failedBlocks.add(block)
                }
            }

            if (failedBlocks.isNotEmpty()) {
                // تعديل هنا: إنشاء الكائن بالبلوكات فقط لتجنب خطأ Compilation
                failedBlocksMap[pageKey] = PageTranslation(
                    blocks = failedBlocks
                )
            }
        }

        // 2. إرسال البلوكات الفاشلة إلى Gemini
        if (failedBlocksMap.isNotEmpty()) {
            try {
                logcat { "Hybrid: Fixing errors in ${failedBlocksMap.size} pages via Gemini" }
                geminiTranslator.translate(failedBlocksMap)
            } catch (e: Exception) {
                logcat { "Gemini fallback failed: ${e.message}" }
            }
        }
    }

    private fun hasUntranslatedWords(original: String, translated: String): Boolean {
        val cleanOriginal = original.replace(cleanRegex, " ").trim()
        val cleanTranslated = translated.replace(cleanRegex, " ").trim()

        val originalWords = cleanOriginal.split(Regex("\\s+")).filter { it.length > 2 }
        val translatedWordsLower = cleanTranslated.lowercase()

        return originalWords.any { word -> 
            translatedWordsLower.contains(word.lowercase()) 
        }
    }

    override fun close() {
        googleTranslator.close()
        geminiTranslator.close()
    }
}
