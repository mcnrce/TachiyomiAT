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
import kotlinx.coroutines.delay

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
            
            val MAX_WORDS_PER_BATCH = 450 // تقليل القيمة قليلاً لزيادة الاستقرار
            val MAX_PAGES_PER_BATCH = 25 
            val MAX_BLOCKS_PER_BATCH = 80

            while (currentIndex < pageEntries.size) {
                val currentBatch = mutableListOf<Map.Entry<String, PageTranslation>>()
                var currentBatchWordCount = 0
                var currentBatchBlockCount = 0

                // تجميع الدفعة
                while (currentIndex < pageEntries.size && 
                       currentBatch.size < MAX_PAGES_PER_BATCH && 
                       currentBatchWordCount < MAX_WORDS_PER_BATCH &&
                       currentBatchBlockCount < MAX_BLOCKS_PER_BATCH) {
                    
                    val entry = pageEntries[currentIndex]
                    val pageText = entry.value.blocks.joinToString(" ") { it.text }
                    val wordCount = pageText.split(Regex("\\s+")).size
                    val blockCount = entry.value.blocks.size

                    currentBatch.add(entry)
                    currentBatchWordCount += wordCount
                    currentBatchBlockCount += blockCount
                    currentIndex++
                    if (wordCount > MAX_WORDS_PER_BATCH) break
                }

                if (currentBatch.isEmpty()) break

                // منطق إعادة المحاولة (Retry Logic)
                var retryCount = 0
                val maxRetries = 3
                var resJson: JSONObject? = null

                while (retryCount < maxRetries && resJson == null) {
                    try {
                        val batchData = currentBatch.associate { (key, page) ->
                            key to page.blocks.map { it.text }
                        }
                        val inputJson = JSONObject(batchData)

                        val response = model.generateContent(inputJson.toString())
                        val rawText = response.text ?: ""

                        val start = rawText.indexOf('{')
                        val end = rawText.lastIndexOf('}')

                        if (start != -1 && end != -1 && end > start) {
                            val jsonContent = rawText.substring(start, end + 1)
                            resJson = JSONObject(jsonContent)
                        } else {
                            throw Exception("Invalid JSON format in response")
                        }
                    } catch (e: Exception) {
                        retryCount++
                        logcat { "Batch Error (Attempt $retryCount/$maxRetries): ${e.message}" }
                        if (retryCount < maxRetries) {
                            delay(2000L * retryCount) // انتظار متزايد (2ث، 4ث، 6ث)
                        } else {
                            logcat { "Failed all retries for batch. Skipping or keeping original text." }
                        }
                    }
                }

                // تطبيق الترجمة (فقط إذا نجحنا في الحصول على JSON)
                if (resJson != null) {
                    for ((key, page) in currentBatch) {
                        val arr = resJson.optJSONArray(key)
                        page.blocks.forEachIndexed { index, block ->
                            val translated = arr?.optString(index, "__NULL__")
                            block.translation = when {
                                !(
                                    (block.angle >= -15.0f && block.angle <= 15.0f) ||
                                    (block.angle >= 75.0f && block.angle <= 105.0f) ||
                                    (block.angle <= -75.0f && block.angle >= -105.0f)
                                ) -> ""
                                translated == null || translated == "__NULL__" -> block.text
                                translated == "RTMTH" -> ""
                                else -> translated
                            }
                        }
                    }
                }

                if (currentIndex < pageEntries.size) {
                    delay(1000) // ثانية كاملة للأمان
                }
            }
        } catch (e: Exception) {
            logcat { "Critical Translation Error: ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {}
}
