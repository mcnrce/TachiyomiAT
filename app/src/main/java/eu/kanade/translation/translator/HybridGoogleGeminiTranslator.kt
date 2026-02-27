package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.logcat

/**
 * Hybrid translator that uses Google Translate first, then checks for untranslated words
 * and sends only the problematic blocks to Gemini as a fallback.
 */
class HybridGoogleGeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val googleTranslator: GoogleTranslator,
    private val geminiTranslator: GeminiTranslator
) : TextTranslator {

    // Regular expression to remove digits and punctuation (keeping letters and spaces)
    private val cleanRegex = Regex("[0-9\\p{Punct}]+")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // Step 1: Run Google Translate on all pages
        googleTranslator.translate(pages)

        // Step 2: Identify blocks that still contain untranslated words
        val failedBlocksMap = mutableMapOf<String, PageTranslation>()

        for ((pageKey, page) in pages) {
            val failedBlocks = mutableListOf<TranslationBlock>()

            for (block in page.blocks) {
                // Only consider blocks that have a non-empty translation (Google actually tried)
                if (!block.translation.isNullOrBlank() && hasUntranslatedWords(block.text, block.translation)) {
                    failedBlocks.add(block)
                }
            }

            if (failedBlocks.isNotEmpty()) {
                // Create a new PageTranslation containing only the problematic blocks
                // Important: we keep the same block objects so updates will reflect in the original map
                failedBlocksMap[pageKey] = PageTranslation(
                    blocks = failedBlocks,
                    imageWidth = page.imageWidth,
                    imageHeight = page.imageHeight
                )
            }
        }

        // Step 3: If there are any failed blocks, try to re-translate them with Gemini
        if (failedBlocksMap.isNotEmpty()) {
            try {
                geminiTranslator.translate(failedBlocksMap)
                // The translation property of each block in failedBlocksMap has been updated directly
                // because we passed the same block instances.
            } catch (e: Exception) {
                logcat { "Gemini fallback failed: ${e.message}. Keeping Google translations." }
            }
        }
    }

    /**
     * Checks if a translated block still contains words from the original text
     * after removing digits and punctuation.
     *
     * @param original The original text block
     * @param translated The translated text block
     * @return true if at least one word (longer than 2 chars) appears in both cleaned texts.
     */
    private fun hasUntranslatedWords(original: String, translated: String): Boolean {
        // Clean both texts: remove digits and punctuation, collapse multiple spaces
        val cleanOriginal = original.replace(cleanRegex, " ").trim()
        val cleanTranslated = translated.replace(cleanRegex, " ").trim()

        // Split into words, ignore empty entries
        val originalWords = cleanOriginal.split(Regex("\\s+")).filter { it.isNotBlank() }
        val translatedWords = cleanTranslated.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()

        // Look for any original word (longer than 2 chars) that exists in the translated set (case-insensitive)
        return originalWords.any { word ->
            word.length > 2 && word.lowercase() in translatedWords
        }
    }

    override fun close() {
        // Close both translators if they hold resources
        googleTranslator.close()
        geminiTranslator.close()
    }
}
