package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import tachiyomi.core.common.util.system.logcat
import java.net.URLEncoder
import java.util.Random

class GoogleTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {
    
    private val client1 = "gtx"
    private val okHttpClient = OkHttpClient()
    private val random = Random()

    // Regex لاكتشاف الروابط والمواقع
    private val urlPattern = Regex("(?i)(https?://\\S+|www\\.\\S+|\\S+\\.(com|net|org|io|me|cc|tv|info))")

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        pages.forEach { (_, page) ->
            if (page.blocks.isEmpty()) return@forEach

            page.blocks.forEachIndexed { index, block ->
                
                // 1) فحص الشروط: الزاوية والروابط
                val isAcceptedAngle = (block.angle >= -15.0f && block.angle <= 15.0f) || 
                                      (block.angle >= 75.0f && block.angle <= 105.0f) || 
                                      (block.angle <= -75.0f && block.angle >= -105.0f)
                
                val isUrl = urlPattern.containsMatchIn(block.text)

                if (isAcceptedAngle && !isUrl) {
                    try {
                        // 2) حفظ هيكلية الأسطر الأصلية (نهج MLKit)
                        val originalLines = block.text.split("\n")
                        val originalWordCounts = originalLines.map { line ->
                            line.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                        }

                        val fullText = block.text.replace("\n", " ").trim()
                        if (fullText.isEmpty()) {
                            block.translation = ""
                            return@forEachIndexed
                        }

                        // 3) انتظار عشوائي ذكي (بين 200 و 500 مللي ثانية)
                        // لمنع أنظمة الحماية من اكتشاف النمط الآلي
                        if (index > 0) {
                            val randomDelay = 200L + random.nextInt(300).toLong()
                            delay(randomDelay)
                        }

                        // 4) إرسال الطلب لمحرك جوجل
                        val translatedText = translateText(toLang.code, fullText)

                        if (translatedText.isNotBlank()) {
                            // 5) إعادة بناء الأسطر بناءً على توزيع الكلمات الأصلي (نهج MLKit)
                            val words = translatedText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                            val rebuiltLines = mutableListOf<String>()
                            var wordIndex = 0
                            
                            for (count in originalWordCounts) {
                                if (wordIndex >= words.size) break
                                val end = (wordIndex + count).coerceAtMost(words.size)
                                rebuiltLines.add(words.subList(wordIndex, end).joinToString(" "))
                                wordIndex = end
                            }

                            // دمج الكلمات المتبقية في السطر الأخير لضمان عدم ضياع أي كلمة
                            if (wordIndex < words.size && rebuiltLines.isNotEmpty()) {
                                val lastIdx = rebuiltLines.size - 1
                                rebuiltLines[lastIdx] = (rebuiltLines[lastIdx] + " " + words.subList(wordIndex, words.size).joinToString(" ")).trim()
                            }

                            block.translation = rebuiltLines.joinToString("\n")
                        } else {
                            block.translation = ""
                        }

                    } catch (e: Exception) {
                        logcat { "Google Translate Error in block $index: ${e.message}" }
                        block.translation = ""
                    }
                } else {
                    // مسح الترجمة للمؤثرات المائلة أو الروابط
                    block.translation = ""
                }
            }
        }
    }

    private suspend fun translateText(lang: String, text: String): String {
        return try {
            val url = getTranslateUrl(lang, text)
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).await()
            val body = response.body?.string() ?: return ""

            val rootArray = JSONArray(body)
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
            logcat { "Parsing/Network Error: ${e.message}" }
            ""
        }
    }

    private fun getTranslateUrl(lang: String, text: String): String {
        return try {
            val token = calculateToken(text)
            val encodedText = URLEncoder.encode(text, "utf-8")
            "https://translate.google.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&tk=$token&q=$encodedText"
        } catch (e: Exception) {
            "https://translate.google.com/translate_a/single?client=$client1&sl=auto&tl=$lang&dt=t&q=$text"
        }
    }

    // --- توابع حساب الـ Token و الـ RL (خوارزمية جوجل الرسمية) ---
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
