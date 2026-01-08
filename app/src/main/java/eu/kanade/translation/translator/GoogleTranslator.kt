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

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
    pages.forEach { (_, page) ->
        if (page.blocks.isEmpty()) return@forEach

        // 1️⃣ بناء النص الموحد: نضع كل بلوك في سطر منفصل تماماً
        val mergedText = buildString {
            page.blocks.forEach { block ->
                // نزيل أي سطر داخلي في البلوك لضمان أن كل سطر في mergedText يمثل بلوك واحد
                append(block.text.replace("\n", " ").trim())
                append("\n")
            }
        }

        // 2️⃣ ترجمة النص كاملًا
        val translatedMergedText = try {
            translateText(toLang.code, mergedText)
        } catch (e: Exception) {
            ""
        }

        if (translatedMergedText.isBlank()) {
            page.blocks.forEach { it.translation = "" }
            return@forEach
        }

        // 3️⃣ إعادة التقسيم: نعتمد على تقسيم النص المترجم إلى أسطر
        // دالة split("\n") هي دالة قياسية في Kotlin ولا تحتاج مكتبات
        val translatedLines = translatedMergedText.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 4️⃣ توزيع الترجمة على البلوكات بالترتيب
        page.blocks.forEachIndexed { index, block ->
            if (index < translatedLines.size) {
                block.translation = translatedLines[index]
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
        // الاستجابة من جوجل تكون مصفوفة بداخلها مصفوفات
        val rootArray = JSONArray(string)
        val sentencesArray = rootArray.getJSONArray(0)
        val result = StringBuilder()

        // 💡 التعديل الجوهري هنا:
        // نمر على كل العناصر في المصفوفة الأولى ونجمعها
        // لأن جوجل يضع كل جزء مترجم في عنصر منفصل
        for (i in 0 until sentencesArray.length()) {
            val sentence = sentencesArray.getJSONArray(i)
            // التأكد من وجود نص مترجم في هذا الجزء
            if (!sentence.isNull(0)) {
                result.append(sentence.getString(0))
            }
        }
        
        result.toString()
    } catch (e: Exception) {
        logcat { "Image Translation Error : $e" }
        ""
    }
}


    private fun getTranslateUrl(lang: String, text: String): String {
        try {
            val client = client1
            val calculateToken = calculateToken(text)
            val encode: String = URLEncoder.encode(text, "utf-8")
            return "https://translate.google.com/translate_a/single?client=$client&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$calculateToken&q=$encode"
        } catch (unused: UnsupportedEncodingException) {
            val client2 = client1
            val calculateToken2 = calculateToken(text)
            return "https://translate.google.com/translate_a/single?client=$client2&sl=auto&tl=$lang&dt=at&dt=bd&dt=ex&dt=ld&dt=md&dt=qca&dt=rw&dt=rm&dt=ss&dt=t&otf=1&ssel=0&tsel=0&kc=1&tk=$calculateToken2&q=$text"
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
        for (num in list) {
            j = RL(j + num.toLong(), "+-a^+6")
        }
        var rL = RL(j, "+-3^+b+-f") xor 3293161072L
        if (rL < 0) {
            rL = (rL and 2147483647L) + 2147483648L
        }
        val j2 = rL % 1000000L
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


    override fun close() {
    }

}
