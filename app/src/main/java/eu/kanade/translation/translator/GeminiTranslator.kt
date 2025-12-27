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
        val MAX_SIZE = 5000

        val pendingJson = JSONObject()
        val pendingIndexes = mutableMapOf<String, MutableList<Int>>()

        fun sendCurrentBatch() {
            if (pendingJson.length() == 0) return

            val response = model.generateContent(pendingJson.toString())
            val responseJson = JSONObject(response.text ?: "{}")

            pendingIndexes.forEach { (pageName, indexes) ->
                val translatedArr = responseJson.optJSONArray(pageName) ?: return@forEach
                val page = pages[pageName] ?: return@forEach

                for (i in 0 until translatedArr.length()) {
                    val blockIndex = indexes[i]
                    page.blocks[blockIndex].translation =
                        translatedArr.optString(i, page.blocks[blockIndex].text)
                }
            }

            pendingJson.keys().asSequence().toList().forEach { pendingJson.remove(it) }
            pendingIndexes.clear()
        }

        pages.forEach { (pageName, page) ->
            val arr = JSONArray()
            val pageFilteredIndexes = mutableListOf<Int>()

            page.blocks.forEachIndexed { index, block ->

                val angleOk = block.angle >= -2.0 && block.angle <= 2.0

                if (angleOk) {
                    arr.put(block.text)
                    pageFilteredIndexes.add(index)
                } else {
                    block.translation = ""
                }
            }

            if (arr.length() > 0) {
                // جرّب الإضافة
                pendingJson.put(pageName, arr)
                pendingIndexes[pageName] = pageFilteredIndexes

                // إن تجاوز الحجم → أرسل السابق وابدأ من جديد
                if (jsonSizeApprox(pendingJson) > MAX_SIZE) {
                    pendingJson.remove(pageName)
                    pendingIndexes.remove(pageName)

                    sendCurrentBatch()

                    // أعد إضافة الصفحة الحالية بعد التفريغ
                    pendingJson.put(pageName, arr)
                    pendingIndexes[pageName] = pageFilteredIndexes
                }
            }
        }

        // إرسال ما تبقى
        sendCurrentBatch()

    } catch (e: Exception) {
        logcat { "Gemini Translation Error:\n${e.stackTraceToString()}" }
        throw e
    }
}
