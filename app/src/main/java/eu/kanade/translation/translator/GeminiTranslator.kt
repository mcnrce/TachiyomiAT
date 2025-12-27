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
               " ## System Instruction – Comic Translation (Strict JSON Mode)

You are an AI translator specialized in manhwa, manga, and manhua text extracted from scanned images.

Rules you MUST follow strictly:

1. Input format:
   - You will receive a JSON object.
   - Keys are image filenames (e.g. "001.jpg").
   - Values are arrays of strings.
   - Each string represents one text block.

2. Translation:
   - Translate every string into ${toLang.label}.
   - Use natural, fluent language suitable for comics.
   - Preserve tone, emotion, and intent.
   - Do NOT merge, split, reorder, or omit any strings.

3. Watermarks and site links:
   - If a string is a watermark, website name, or URL, replace its translation with the exact text "RTMTH".
   - Do NOT translate watermarks.

4. Structure preservation (CRITICAL):
   - Output MUST be valid JSON.
   - Keys MUST remain identical and in the same order.
   - Each array MUST have the exact same length as the input.
   - Index-to-index correspondence MUST be preserved.

5. Output rules:
   - Output JSON ONLY.
   - No explanations.
   - No comments.
   - No markdown.
   - No extra text.

Return type:
{ [key: string]: Array<string> }"
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val data = pages.mapValues { (k, v) -> v.blocks.map { b -> b.text } }
            val json = JSONObject(data)
            val response = model.generateContent(json.toString())
            val resJson = JSONObject("${response.text}")
            for ((k, v) in pages) {
                v.blocks.forEachIndexed { i, b ->
                    run {
                        v.blocks.forEach { block ->
    // احسب عدد الأحرف الفريدة باستثناء '\n'
    val uniqueChars = block.translation.filter { it != '\n' }.toSet().size

    // إذا كان الميلان كبير أو عدد الأحرف الفريدة أقل من 3، اجعل الفقاعة فارغة
    if (block.angle < -30.0f || block.angle > 30.0f || uniqueChars <= 3) {
        block.translation = ""
    }
                        }
                        val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                        b.translation = if (res == null || res == "NULL") b.text else res
                    }
                }
                v.blocks =
                    v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
            }
        } catch (e: Exception) {
            logcat { "Image Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
    }


}
