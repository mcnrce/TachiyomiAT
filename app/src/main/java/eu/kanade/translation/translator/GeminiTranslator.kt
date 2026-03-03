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
    private val englishScriptRegex = Regex("[a-zA-Z]")

    // قائمة اللغات التي لا تستخدم الأحرف الإنجليزية/اللاتينية في كتابتها الأساسية
    private val nonLatinLanguages = setOf(
        TextTranslatorLanguage.ARABIC,
        TextTranslatorLanguage.PERSIAN,
        TextTranslatorLanguage.KURDISH,
        TextTranslatorLanguage.URDU,
        TextTranslatorLanguage.HINDI,
        TextTranslatorLanguage.CHINESE,
        TextTranslatorLanguage.JAPANESE,
        TextTranslatorLanguage.KOREAN,
        TextTranslatorLanguage.RUSSIAN,
        TextTranslatorLanguage.THAI,
        TextTranslatorLanguage.GREEK,
        TextTranslatorLanguage.HEBREW
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        googleTranslator.translate(pages)

        val failedBlocksMap = mutableMapOf<String, PageTranslation>()

        for ((pageKey, page) in pages) {
            val failedBlocks = mutableListOf<TranslationBlock>()

            for (block in page.blocks) {
                val translation = block.translation
                if (!translation.isNullOrBlank()) {
                    
                    // الفحص 1: هل بقيت كلمات من النص الأصلي (فشل ترجمة حرفي)
                    val hasUntranslated = hasUntranslatedWords(block.text, translation)
                    
                    // الفحص 2: هل اللغة الهدف لا تدعم اللاتينية وظهرت أحرف إنجليزية؟
                    val hasWrongScript = isNonLatinLanguageWithEnglish(translation)

                    if (hasUntranslated || hasWrongScript) {
                        failedBlocks.add(block)
                    }
                }
            }

            if (failedBlocks.isNotEmpty()) {
                failedBlocksMap[pageKey] = PageTranslation(blocks = failedBlocks)
            }
        }

        if (failedBlocksMap.isNotEmpty()) {
            try {
                logcat { "Hybrid: Re-translating ${failedBlocksMap.size} blocks with Gemini (Logic: Script/Content check)" }
                geminiTranslator.translate(failedBlocksMap)
            } catch (e: Exception) {
                logcat { "Gemini fallback failed: ${e.message}" }
            }
        }
    }

    private fun isNonLatinLanguageWithEnglish(translated: String): Boolean {
        // نتحقق أولاً إذا كانت اللغة المختارة في الإعدادات ضمن قائمتنا للغات غير اللاتينية
        if (toLang in nonLatinLanguages) {
            // نتحقق إذا كان النص المترجم يحتوي على أي حرف إنجليزي
            return englishScriptRegex.containsMatchIn(translated)
        }
        return false
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
