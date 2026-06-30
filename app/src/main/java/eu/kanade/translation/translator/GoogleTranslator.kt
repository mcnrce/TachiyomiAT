package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tachiyomi.core.common.util.system.logcat
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@Suppress("PropertyName", "MaxLineLength", "FunctionName")
class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    private val client1 = "gtx"
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")
    private val symbolsFilterPattern = Regex("""[_~•°^@#$&|\\-]""")
    private val SAFE_SEPARATOR = " . "

    // يكتشف الكلمات اللاتينية/الإنجليزية المتبقية في النص المترجم
    private val untranslatedWordPattern = Regex("""[a-zA-Z]{2,}""")

    // لغات لا تستخدم الأحرف اللاتينية — نُفحّص فيها الكلمات غير المترجمة
    private val nonLatinLanguages = setOf(
        TextTranslatorLanguage.ARABIC,
        TextTranslatorLanguage.JAPANESE,
        TextTranslatorLanguage.CHINESESIM,
        TextTranslatorLanguage.CHINESETRAD,
        TextTranslatorLanguage.KOREAN,
        TextTranslatorLanguage.RUSSIAN,
        TextTranslatorLanguage.GREEK_MODERN,
        TextTranslatorLanguage.HEBREW,
        TextTranslatorLanguage.HINDI,
        TextTranslatorLanguage.THAI,
        TextTranslatorLanguage.PERSIAN,
        TextTranslatorLanguage.URDU,
        TextTranslatorLanguage.BENGALI,
        TextTranslatorLanguage.TAMIL,
        TextTranslatorLanguage.TELUGU,
        TextTranslatorLanguage.MALAYALAM,
        TextTranslatorLanguage.KANNADA,
        TextTranslatorLanguage.GUJARATI,
        TextTranslatorLanguage.PANJABI_PUNJABI,
        TextTranslatorLanguage.MARATHI,
        TextTranslatorLanguage.ARMENIAN,
        TextTranslatorLanguage.GEORGIAN,
        TextTranslatorLanguage.UKRAINIAN,
        TextTranslatorLanguage.BELARUSIAN,
        TextTranslatorLanguage.BULGARIAN,
        TextTranslatorLanguage.SERBIAN,
        TextTranslatorLanguage.MACEDONIAN,
        TextTranslatorLanguage.MONGOLIAN,
        TextTranslatorLanguage.KAZAKH,
        TextTranslatorLanguage.KIRGHIZ_KYRGYZ,
        TextTranslatorLanguage.TAJIK,
        TextTranslatorLanguage.TIBETAN,
        TextTranslatorLanguage.LAO,
        TextTranslatorLanguage.CENTRAL_KHMER,
        TextTranslatorLanguage.BURMESE,
        TextTranslatorLanguage.SINHALA_SINHALESE,
        TextTranslatorLanguage.AMHARIC,
        TextTranslatorLanguage.KURDISH,
        TextTranslatorLanguage.PUSHTO_PASHTO,
        TextTranslatorLanguage.UIGHUR_UYGHUR,
        TextTranslatorLanguage.SINDHI,
        TextTranslatorLanguage.YIDDISH,
        TextTranslatorLanguage.DIVEHI_DHIVEHI_MALDIVIAN,
        TextTranslatorLanguage.NEPALI,
        TextTranslatorLanguage.DZONGKHA,
    )

    // هل لغة الترجمة الحالية لا تستخدم لاتيني؟
    private val shouldFixUntranslated = nonLatinLanguages.contains(toLang)

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
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

        // 1. تجميع البلوكات الصالحة
        val allValidBlocks = mutableListOf<TranslationBlock>()
        pages.forEach { (_, page) ->
            page.blocks.forEach { block ->
                val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) ||
                    (block.angle >= 75.0f && block.angle <= 105.0f) ||
                    (block.angle <= -75.0f && block.angle >= -105.0f)
                val isUrl = urlPattern.containsMatchIn(block.text)

                if (isAcceptedAngle && !isUrl) {
                    allValidBlocks.add(block)
                } else {
                    block.translation = ""
                }
            }
        }

        if (allValidBlocks.isEmpty()) return

        // 2. التحزيم
        val batches = mutableListOf<MutableList<TranslationBlock>>()
        var currentBatch = mutableListOf<TranslationBlock>()
        var currentLength = 0

        for (block in allValidBlocks) {
            val cleanedBlockText = cleanSymbols(block.text)
            val textToAdd = cleanedBlockText + SAFE_SEPARATOR

            if (currentLength + textToAdd.length > 1700 && currentBatch.isNotEmpty()) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentLength = 0
            }
            currentBatch.add(block)
            currentLength += textToAdd.length + 1
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)

        // 3. الترجمة + إصلاح الكلمات غير المترجمة
        batches.forEach { batch ->
            val mergedText = batch.joinToString("\n") {
                cleanSymbols(it.text) + SAFE_SEPARATOR
            }

            val translatedMergedText = try {
                translateText(toLang.code, mergedText)
            } catch (e: Exception) {
                logcat { "Translation failed: ${e.message}" }
                ""
            }

            if (translatedMergedText.isNotBlank()) {
                val translatedLines = translatedMergedText.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                batch.forEachIndexed { index, block ->
                    if (index < translatedLines.size) {
                        var cleanText = translatedLines[index]
                            .removeSuffix(".")
                            .removeSuffix(" .")
                            .trim()

                        // === إصلاح الكلمات غير المترجمة (فقط للغات غير اللاتينية) ===
                        cleanText = fixUntranslatedWords(cleanText, toLang.code)

                        block.translation = rtlMarker + cleanText
                    }
                }
            }
        }
    }

    /**
     * إذا وجدت كلمات لاتينية في النص المترجم، نستخرجها ونترجمها بمفردها
     * ثم نستبدلها في النص. يُطبّق فقط عندما تكون لغة الترجمة غير لاتينية.
     */
    private suspend fun fixUntranslatedWords(translatedText: String, langCode: String): String {
        // إذا كانت لغة الترجمة تستخدم لاتيني، لا نُفحّص
        if (!shouldFixUntranslated) return translatedText

        // البحث عن الكلمات اللاتينية المتبقية (2 حروف أو أكثر)
        val untranslatedWords = untranslatedWordPattern.findAll(translatedText)
            .map { it.value }
            .distinct()
            .toList()

        if (untranslatedWords.isEmpty()) return translatedText

        var fixedText = translatedText

        // ترجمة كل كلمة غير مترجمة بمفردها
        for (word in untranslatedWords) {
            try {
                val singleTranslation = translateText(langCode, word)
                    .trim()
                    .removeSuffix(".")
                    .removeSuffix(" .")

                // إذا ترجمت بنجاح ولم تبقَ لاتينية
                if (singleTranslation.isNotBlank() && !untranslatedWordPattern.containsMatchIn(singleTranslation)) {
                    fixedText = fixedText.replace(word, singleTranslation)
                }
            } catch (e: Exception) {
                logcat { "Failed to translate word '$word': ${e.message}" }
            }
        }

        return fixedText
    }

    private fun cleanSymbols(rawText: String): String {
        return rawText.replace("\n", " ")
            .replace(symbolsFilterPattern, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private suspend fun translateText(lang: String, text: String): String {
        val access = getTranslateUrl(lang, text)
        val request: Request = Request.Builder()
            .url(access)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            .build()

        val response = okHttpClient.newCall(request).await()
        val responseBody = response.body?.string() ?: return ""

        return try {
            val rootArray = JSONArray(responseBody)
            val sentencesArray = rootArray.getJSONArray(0)
            val result = StringBuilder()

            for (i in 0 until sentencesArray.length()) {
                val sentence = sentencesArray.getJSONArray(i)
                if (!sentence.isNull(0)) {
                    result.append(sentence.getString(0))
                }
            }
            result.toString()
        } catch (e: Exception) {
            logcat { "Parsing Error: $e" }
            ""
        }
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        val encode: String = URLEncoder.encode(text, "utf-8")
        val tk = calculateToken(text)

        return "https://translate.google.com/translate_a/single" +
            "?client=$client1" +
            "&sl=auto" +
            "&tl=$lang" +
            "&tk=$tk" +
            "&q=$encode" +
            "&dt=t" +
            "&ie=UTF-8" +
            "&oe=UTF-8"
    }

    private fun calculateToken(str: String): String {
        var j: Long = 406644
        val list = mutableListOf<Int>()
        var i = 0
        while (i < str.length) {
            val codePoint = str.codePointAt(i)
            if (codePoint < 128) {
                list.add(codePoint)
            } else if (codePoint < 2048) {
                list.add((codePoint shr 6) or 192)
                list.add((codePoint and 63) or 128)
            } else {
                list.add((codePoint shr 12) or 224)
                list.add(((codePoint shr 6) and 63) or 128)
                list.add((codePoint and 63) or 128)
            }
            i += if (Character.isSupplementaryCodePoint(codePoint)) 2 else 1
        }
        for (num in list) j = RL(j + num.toLong(), "+-a^+6")
        var rL = RL(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) rL = (rL and 2147483647L) + 2147483648L
        val res = (rL % 1000000L)
        return "$res.${406644L xor res}"
    }

    private fun RL(j: Long, str: String): Long {
        var res = j
        var i = 0
        while (i < str.length - 2) {
            val shift = if (str[i + 2] in 'a'..'z') str[i + 2].code - 'W'.code else str[i + 2].digitToInt()
            val shiftValue = if (str[i + 1] == '+') res ushr shift else res shl shift
            res = if (str[i] == '+') (res + shiftValue) and 4294967295L else res xor shiftValue
            i += 3
        }
        return res
    }

    override fun close() {}
}
