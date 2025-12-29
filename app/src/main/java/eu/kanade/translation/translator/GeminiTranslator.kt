package eu.kanade.translation.translator

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
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
            // 1) بناء JSON الإدخال
            val data = pages.mapValues { (_, v) -> v.blocks.map { it.text } }
            val inputJson = JSONObject(data)

            // 2) استدعاء النموذج
            val response = model.generateContent(inputJson.toString())
            val rawText = response.text ?: ""

            // 3) استخراج JSON بشكل دفاعي
            val start = rawText.indexOf('{')
            val end = rawText.lastIndexOf('}')

            if (start == -1 || end == -1 || end <= start) {
                // فشل كامل في الاستجابة → لا نعدّل أي ترجمة
                logcat { "Invalid JSON response from model" }
                return
            }

            val jsonContent = rawText.substring(start, end + 1)

            val resJson = try {
                JSONObject(jsonContent)
            } catch (e: Exception) {
                // JSON غير صالح → تجاهل الترجمة بالكامل
                logcat { "JSON parse error: ${e.message}" }
                return
            }

            // 4) تطبيق الترجمة بدون كسر البنية
            for ((key, page) in pages) {
                val arr = resJson.optJSONArray(key)

                page.blocks.forEachIndexed { index, block ->
    val translated = arr?.optString(index, "__NULL__")

    block.translation = when {
        block.angle < -15.0f || block.angle > 15.0f -> ""
        translated == null -> block.text
        translated == "__NULL__" -> block.text
        translated == "RTMTH" -> ""
        else -> translated
    }
                }
            }

        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
    }
}
