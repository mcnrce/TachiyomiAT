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
import org.json.JSONObject

@Suppress
class GeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    apiKey: String,
    modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : TextTranslator {

    private var model: GenerativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig {
            topK = 30
            topP = 0.5f
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
                "System Instruction – Comic Translation (Strict JSON Mode)\n" +
                "You are an AI translator specialized in manhwa, manga, and manhua OCR text.\n" +
                "Input is a JSON object: keys are image filenames, values are arrays of strings.\n" +
                "Translate each string independently into ${toLang.label}.\n" +
                "If a string is a watermark, URL, or scan credit, replace it with \"RTMTH\".\n" +
                "Do not merge, split, reorder, infer, or expand text.\n" +
                "Output MUST be valid JSON only, same structure, same lengths.\n" +
                "No explanations. No comments. No extra text."
            )
        }
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val pageEntries = pages.entries.toList()
            var currentIndex = 0
            
            val MAX_WORDS_PER_BATCH = 451
            val MAX_PAGES_PER_BATCH = 25

            while (currentIndex < pageEntries.size) {
                val batch = mutableListOf<Map.Entry<String, PageTranslation>>()
                var batchWordCount = 0

                while (currentIndex < pageEntries.size && 
                       batch.size < MAX_PAGES_PER_BATCH && 
                       batchWordCount < MAX_WORDS_PER_BATCH) {
                    
                    val entry = pageEntries[currentIndex]
                    val pageText = entry.value.blocks.joinToString(" ") { it.text }
                    val wordCount = pageText.split(Regex("\\s+")).size

                    if (batch.isNotEmpty() && batchWordCount + wordCount > MAX_WORDS_PER_BATCH) break

                    batch.add(entry)
                    batchWordCount += wordCount
                    currentIndex++
                }

                val batchData = batch.associate { (key, page) ->
                    key to page.blocks.map { it.text }
                }
                val inputJson = JSONObject(batchData)

                var responseText = ""
                var attempt = 0
                while (attempt < 2 && responseText.isBlank()) {
                    val response = model.generateContent(inputJson.toString())
                    responseText = response.text ?: ""
                    if (responseText.isBlank()) {
                        attempt++
                        if (attempt < 2) Thread.sleep(2000) 
                    }
                }

                val start = responseText.indexOf('{')
                val end = responseText.lastIndexOf('}')

                if (start == -1 || end == -1 || end <= start) {
                    logcat { "Invalid or Empty JSON response at index $currentIndex" }
                    throw Exception("فشل الحصول على رد من الذكاء الاصطناعي للدفعة الحالية")
                }

                val resJson = JSONObject(responseText.substring(start, end + 1))

                // --- منطق إصلاح اتجاه النص للغات RTL ---
                // قائمة اللغات التي تكتب من اليمين لليسار بناءً على الـ Enum الخاص بك
                val rtlLanguages = setOf(
                    TextTranslatorLanguage.ARABIC,
                    TextTranslatorLanguage.PERSIAN,
                    TextTranslatorLanguage.HEBREW,
                    TextTranslatorLanguage.URDU,
                    TextTranslatorLanguage.PUSHTO_PASHTO,
                    TextTranslatorLanguage.SINDHI,
                    TextTranslatorLanguage.UIGHUR_UYGHUR,
                    TextTranslatorLanguage.YIDDISH,
                    TextTranslatorLanguage.KURDISH // تمت إضافة الكردية بناءً على طلبك
                )

                val isRTL = rtlLanguages.contains(toLang)
                val rtlMarker = if (isRTL) "\u200F" else ""

                for ((key, page) in batch) {
                    val arr = resJson.optJSONArray(key)

                    page.blocks.forEachIndexed { index, block ->
                        val translated = arr?.optString(index, "__NULL__")

                        block.translation = when {
                            block.angle < -15.0f || block.angle > 15.0f -> ""
                            translated == null || translated == "__NULL__" -> block.text
                            translated == "RTMTH" -> ""
                            // إضافة الرمز RTL Marker (U+200F) في بداية النص المترجم
                            else -> rtlMarker + translated
                        }
                    }
                }

                if (currentIndex < pageEntries.size) {
                    Thread.sleep(1000)
                }
            }

        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {}
}
