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

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    private val client1 = "gtx"
    private val okHttpClient = OkHttpClient()
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")
    
    // المعيار الآمن: فاصل يجبر جوجل على إنهاء الجملة ولا يختفي في الترجمة
    private val SAFE_SEPARATOR = " . " 

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        // 1. تجميع كل البلوكات الصالحة من كل الصفحات
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

        // 2. تحزيم البلوكات (Batching) بناءً على طول النص (الحد الآمن 2500 حرف)
        val batches = mutableListOf<MutableList<TranslationBlock>>()
        var currentBatch = mutableListOf<TranslationBlock>()
        var currentLength = 0

        for (block in allValidBlocks) {
            // نحسب طول النص + الفاصل + سطر جديد
            val textToAdd = block.text.replace("\n", " ").trim() + SAFE_SEPARATOR
            if (currentLength + textToAdd.length > 2000 && currentBatch.isNotEmpty()) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentLength = 0
            }
            currentBatch.add(block)
            currentLength += textToAdd.length + 1
        }
        if (currentBatch.isNotEmpty()) batches.add(currentBatch)

        // 3. إرسال الحزم ومعالجة الرد
        batches.forEach { batch ->
            val mergedText = batch.joinToString("\n") { 
                it.text.replace("\n", " ").trim() + SAFE_SEPARATOR 
            }

            val translatedMergedText = try {
                translateText(toLang.code, mergedText)
            } catch (e: Exception) { "" }

            if (translatedMergedText.isNotBlank()) {
                // تقسيم الرد بناءً على السطر الجديد
                val translatedLines = translatedMergedText.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                // توزيع الترجمة مع تنظيف الفاصل الآمن
                batch.forEachIndexed { index, block ->
                    if (index < translatedLines.size) {
                        // إزالة النقطة الفاصلة التي قد تعود في نهاية الترجمة
                        block.translation = translatedLines[index]
                            .removeSuffix(".")
                            .removeSuffix(" .")
                            .trim()
                    }
                }
            }
        }
    }

    private suspend fun translateText(lang: String, text: String): String {
        val access = getTranslateUrl(lang, text)
        val request: Request = Request.Builder().url(access).build()
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
        return "https://translate.google.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&tk=$tk&q=$encode"
    }

    // --- حساب الـ Token (نفس منطقك الأصلي للحفاظ على الصلاحية) ---
    private fun calculateToken(str: String): String {
        var j: Long = 406644
        val list = mutableListOf<Int>()
        var i = 0
        while (i < str.length) {
            val codePoint = str.codePointAt(i)
            if (codePoint < 128) list.add(codePoint)
            else if (codePoint < 2048) {
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
