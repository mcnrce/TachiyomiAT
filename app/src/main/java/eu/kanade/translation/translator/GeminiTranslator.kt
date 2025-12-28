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
                "System Instruction – Comic Translation (Strict JSON, OCR Blocks)
You are an AI translator specialized in manhwa, manga, and manhua text extracted from scanned comic images using OCR.
Each string represents one independent text block or speech bubble, already segmented and ordered by the OCR system.
You MUST follow these rules strictly:
Input format:
Input is a JSON object.
Keys are image filenames (e.g. "001.jpg").
Values are arrays of strings.
Each string is one OCR text block.
Translation rules:
Translate every string into natural, fluent ${toLang.label}.
Preserve tone, emotion, and intent appropriate for comics.
Translate each block independently.
Do NOT merge, split, reorder, infer, expand, or complete text.
Watermarks and site links:
If a string is a website name, URL, scan credit, or advertisement, replace it with exactly "RTMTH".
Do NOT translate such strings.
Structure preservation (CRITICAL):
Output MUST be valid JSON only.
Keys MUST remain identical and in the same order.
Each array MUST have the exact same length as the input.
Index-to-index correspondence MUST be preserved.
Output rules:
Output JSON ONLY.
No explanations.
No comments.
No markdown.
No extra characters.
Return type: { [key: string]: Array }",
            )
        },
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val data = pages.mapValues { (k, v) -> v.blocks.map { b -> b.text } }
            val json = JSONObject(data)
            val response = model.generateContent(json.toString())
            
            // حل مشكلة النصوص الزائدة قبل أو بعد الـ JSON
            val rawText = response.text ?: ""
            val startIndex = rawText.indexOf('{')
            val endIndex = rawText.lastIndexOf('}')
            
            val jsonContent = if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
                rawText.substring(startIndex, endIndex + 1)
            } else {
                rawText
            }

            val resJson = JSONObject(jsonContent)
            
            for ((k, v) in pages) {
                v.blocks.forEachIndexed { i, b ->
                    if (b.angle < -15.0f || b.angle > 15.0f) {
                        b.translation = ""
                    } else {
                        val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                        b.translation = if (res == null || res == "NULL") b.text else res
                    }
                }
                v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
            }
        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
    }
}
