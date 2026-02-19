package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tachiyomi.core.common.util.system.logcat
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    private val client1 = "gtx"
    private val client2 = "webapp"
    val okHttpClient = OkHttpClient()

    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        pages.forEach { (_, page) ->
            if (page.blocks.isEmpty()) return@forEach

            // 1️⃣ بناء النص الموحد مع إضافة مفتاح السياق "Say: " لكل بلوك
            val mergedText = buildString {
                page.blocks.forEach { block ->
                    val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) || 
                                          (block.angle >= 75.0f && block.angle <= 105.0f) || 
                                          (block.angle <= -75.0f && block.angle >= -105.0f)
                    
                    val isUrl = urlPattern.containsMatchIn(block.text)

                    if (isAcceptedAngle && !isUrl) {
                        // إضافة بادئة "Say: " لإجبار المحرك على ترجمة النص مهما كان قصيراً
                        append("Say: ")
                        append(block.text.replace("\n", " ").trim())
                        append("\n")
                    }
                }
            }

            if (mergedText.isBlank()) {
                page.blocks.forEach { it.translation = "" }
                return@forEach
            }

            // 2️⃣ إرسال النص للترجمة
            val translatedMergedText = try {
                translateText(toLang.code, mergedText)
            } catch (e: Exception) {
                ""
            }

            if (translatedMergedText.isBlank()) {
                page.blocks.forEach { it.translation = "" }
                return@forEach
            }

            // 3️⃣ تقسيم النص المترجم وتنظيفه بناءً على أول ظهور لعلامة ":"
            val translatedLines = translatedMergedText.split("\n")
                .map { line ->
                    val firstColonPos = line.indexOf(':')
                    if (firstColonPos != -1) {
                        // حذف كل ما قبل أول نقطتين بما في ذلك النقطتين أنفسهم
                        line.substring(firstColonPos + 1).trim()
                    } else {
                        // تنظيف احتياطي في حال غياب النقطتين
                        line.replace("Say", "").replace("قل", "").trim()
                    }
                }
                .filter { it.isNotEmpty() }

            // 4️⃣ توزيع الترجمة على البلوكات الأصلية
            var translationIndex = 0
            page.blocks.forEach { block ->
                val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) || 
                                      (block.angle >= 75.0f && block.angle <= 105.0f) || 
                                      (block.angle <= -75.0f && block.angle >= -105.0f)
                
                val isUrl = urlPattern.containsMatchIn(block.text)

                if (isAcceptedAngle && !isUrl && translationIndex < translatedLines.size) {
                    block.translation = translatedLines[translationIndex]
                    translationIndex++
                } else {
                    block.translation = ""
                }
            }
        }
    }

    private suspend fun translateText(lang: String, text: String): String {
        val access = getTranslateUrl(lang, text)
        val build: Request = Request.Builder().url(access).build()
        val response = okHttpClient.newCall(build).await()
        val string = response.body?.string() ?: return ""

        return try {
            val rootArray = JSONArray(string)
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
            logcat { "Google Translation Parsing Error: $e" }
            ""
        }
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        return try {
            val calculateToken = calculateToken(text)
            val encode: String = URLEncoder.encode(text, "utf-8")
            "https://translate.google.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&tk=$calculateToken&q=$encode"
        } catch (e: Exception) {
            "https://translate.google.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&q=$text"
        }
    }

    private fun calculateToken(str: String): String {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < str.length) {
            val charCodeAt = str.codePointAt(i)
            when {
                charCodeAt < 128 -> list.add(charCodeAt)
                charCodeAt < 2048 -> {
                    list.add((charCodeAt shr 6) or 192)
                    list.add((charCodeAt and 63) or 128)
                }
                charCodeAt in 55296..57343 && i + 1 < str.length -> {
                    val nextChar = str.codePointAt(i + 1)
                    if (nextChar in 56320..57343) {
                        val codePoint = ((charCodeAt and 1023) shl 10) + (nextChar and 1023) + 65536
                        list.add((codePoint shr 18) or 240)
                        list.add(((codePoint shr 12) and 63) or 128)
                        list.add(((codePoint shr 6) and 63) or 128)
                        list.add((codePoint and 63) or 128)
                        i++
                    }
                }
                else -> {
                    list.add((charCodeAt shr 12) or 224)
                    list.add(((charCodeAt shr 6) and 63) or 128)
                    list.add((charCodeAt and 63) or 128)
                }
            }
            i++
        }
        var j: Long = 406644
        for (num in list) j = RL(j + num.toLong(), "+-a^+6")
        var rL = RL(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) rL = (rL and 2147483647L) + 2147483648L
        val j2 = (rL % 1000000L)
        return "$j2.${406644L xor j2}"
    }

    private fun RL(j: Long, str: String): Long {
        var result = j
        var i = 0
        while (i < str.length - 2) {
            val shift = if (str[i + 2] in 'a'..'z') str[i + 2].code - 'W'.code else str[i + 2].digitToInt()
            val shiftValue = if (str[i + 1] == '+') result ushr shift else result shl shift
            result = if (str[i] == '+') (result + shiftValue) and 4294967295L else result xor shiftValue
            i += 3
        }
        return result
    }

    override fun close() {}
}
