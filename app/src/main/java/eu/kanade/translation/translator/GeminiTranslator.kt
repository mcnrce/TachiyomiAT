package eu.kanade.translation.translator

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.logcat
import org.json.JSONArray
import org.json.JSONObject

class GeminiTranslator(
override val fromLang: TextRecognizerLanguage,
override val toLang: TextTranslatorLanguage,
apiKey: String,
modelName: String,
val maxOutputToken: Int,
val temp: Float,
) : TextTranslator {

private val model = GenerativeModel(  
    modelName = modelName,  
    apiKey = apiKey,  
    generationConfig = generationConfig {  
        temperature = temp  
        maxOutputTokens = maxOutputToken  
        responseMimeType = "application/json"  
    },  
    safetySettings = listOf(  
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),  
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),  
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),  
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),  
    ),  
    systemInstruction = content {  
        text(  
            "Translate all strings in this JSON to ${toLang.label}. " + 
            "If the text is a website link or url, do not translate it; simply replace the translation with blank text. "
            +"Keep keys, order, and structure unchanged. Return JSON only."  
        )  
    }  
)  

override suspend fun translate(pages: MutableMap<String, PageTranslation>) {  
    try {  
        // 1️⃣ إنشاء JSON مؤقت وخفيف مع فلترة أولية  
        val requestJson = JSONObject()  
        val filteredIndexes = mutableMapOf<String, MutableList<Int>>() // لتتبع أي بلوك سيتم ترجمته  

        pages.forEach { (pageName, page) ->  
            val arr = JSONArray()  
            val pageFilteredIndexes = mutableListOf<Int>()  
            page.blocks.forEachIndexed { index, block -> 
                val seenChars = mutableMapOf<Char, Boolean>()  
                // فحص الزاوية: يجب أن تكون بين -2 و 2  
                val angleOk = block.angle >= -2.0 && block.angle <= 2.0  

                if (angleOk) {  
                    arr.put(block.text)  
                    pageFilteredIndexes.add(index)  
                } else {  
                    // مسح الترجمة للنصوص التي لا تلبي الشروط  
                    block.translation = ""  
                }  
            }  
            if (arr.length() > 0) {  
                requestJson.put(pageName, arr)  
                filteredIndexes[pageName] = pageFilteredIndexes  
            }  
        }  

        // 2️⃣ إرسال النصوص المفلترة فقط  
        val response = model.generateContent(requestJson.toString())  
        val responseJson = JSONObject(response.text ?: "{}")  

        // 3️⃣ إعادة الدمج في الملف الأصلي  
        pages.forEach { (pageName, page) ->  
            val translatedArr = responseJson.optJSONArray(pageName) ?: return@forEach  
            val indexes = filteredIndexes[pageName] ?: return@forEach  
            for (i in 0 until translatedArr.length()) {  
                val idx = indexes[i]  
                page.blocks[idx].translation = translatedArr.optString(i, page.blocks[idx].text)  
            }  
        }  

    } catch (e: Exception) {  
        logcat { "Gemini Translation Error:\n${e.stackTraceToString()}" }  
        throw e  
    }  
}  

override fun close() {  
    // لا يوجد موارد لإغلاقها  
}

}
