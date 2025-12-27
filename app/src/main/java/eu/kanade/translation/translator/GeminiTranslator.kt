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
                "If the text is a website link or url, do not translate it; simply replace the translation with blank text. " +
                "Keep keys, order, and structure unchanged. Return JSON only."
            )
        }
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {  
    try {  
        val requestJson = JSONObject()  
        val filteredIndexes = mutableMapOf<String, MutableList<Int>>()  

        pages.forEach { (pageName, page) ->  
            val arr = JSONArray()  
            val pageFilteredIndexes = mutableListOf<Int>()  

            page.blocks.forEachIndexed { index, block ->  
                var charCount = 0  
                val seenChars = mutableMapOf<Char, Boolean>()  
                for (c in block.text) {  
                    if (!seenChars.containsKey(c)) {  
                        seenChars[c] = true  
                        charCount += 1  
                    }  
                }  

                val angleOk = block.angle >= -2.0 && block.angle <= 2.0  

                if (charCount >= 4 && angleOk) {  
                    arr.put(block.text)  
                    pageFilteredIndexes.add(index)  
                } else {  
                    block.translation = ""  
                }  
            }  

            if (arr.length() > 0) {  
                requestJson.put(pageName, arr)  
                filteredIndexes[pageName] = pageFilteredIndexes  
            }  
        }  

        val response = model.generateContent(requestJson.toString())  

        // ✅ التحقق مباشرة من JSON قبل معالجة النتيجة
        val responseText = response.text
        val responseJson = try { 
            JSONObject(responseText ?: "{}") 
        } catch (e: Exception) {  
            logcat { "Gemini Translation Error: Response is not valid JSON\n$responseText" }  
            throw e  
        }  

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
