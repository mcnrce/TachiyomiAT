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
                "System Instruction – Comic Translation (Strict JSON, OCR Blocks)\n" +
                "You are an AI translator specialized in manhwa, manga, and manhua text extracted from scanned comic images using OCR.\n" +
                "Each string represents one independent text block or speech bubble, already segmented and ordered by the OCR system.\n" +
                "You MUST follow these rules strictly:\n" +
                "Input format:\n" +
                "Input is a JSON object.\n" +
                "Keys are image filenames (e.g. \"001.jpg\").\n" +
                "Values are arrays of strings.\n" +
                "Each string is one OCR text block.\n" +
                "Translation rules:\n" +
                "Translate every string into natural, fluent ${toLang.label}.\n" +
                "Preserve tone, emotion, and intent appropriate for comics.\n" +
                "Translate each block independently.\n" +
                "Do NOT merge, split, reorder, infer, expand, or complete text.\n" +
                "Watermarks and site links:\n" +
                "If a string is a website name, URL, scan credit, or advertisement, replace it with exactly \"RTMTH\".\n" +
                "Do NOT translate such strings.\n" +
                "Structure preservation (CRITICAL):\n" +
                "Output MUST be valid JSON only.\n" +
                "Keys MUST remain identical and in the same order.\n" +
                "Each array MUST have the exact same length as the input.\n" +
                "Index-to-index correspondence MUST be preserved.\n" +
                "Output rules:\n" +
                "Output JSON ONLY.\n" +
                "No explanations.\n" +
                "No comments.\n" +
                "No markdown.\n" +
                "No extra characters.\n" +
                "Return type: { [key: string]: Array }"
            )
        }
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val data = pages.mapValues { (k, v) -> v.blocks.map { b -> b.text } }
            val json = JSONObject(data)
            val response = model.generateContent(json.toString())
            val resJson = JSONObject(response.text ?: "{}")

            for ((k, v) in pages) {
                v.blocks.forEachIndexed { i, b ->
                    b.translation = if (b.angle < -15.0f || b.angle > 15.0f) {
                        ""
                    } else {
                        val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                        if (res == null || res == "NULL") b.text else res
                    }
                }
                v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
            }
        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {}
}
