package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Suppress("PropertyName", "MaxLineLength")
class OpenRouterTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    val apiKey: String,
    val modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : TextTranslator {

    private val okHttpClient = OkHttpClient()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val pageEntries = pages.entries.toList()
            var currentIndex = 0

            // ميزة جيميناي 1: تقسيم الشغل إلى دفعات لحماية الذاكرة ومنع ضياع المفاتيح
            val MAX_WORDS_PER_BATCH = 450
            val MAX_PAGES_PER_BATCH = 25

            while (currentIndex < pageEntries.size) {
                val batch = mutableListOf<Map.Entry<String, PageTranslation>>()
                var batchWordCount = 0

                while (currentIndex < pageEntries.size &&
                    batch.size < MAX_PAGES_PER_BATCH &&
                    batchWordCount < MAX_WORDS_PER_BATCH
                ) {
                    val entry = pageEntries[currentIndex]
                    val pageText = entry.value.blocks.joinToString(" ") { it.text }
                    val wordCount = pageText.split(Regex("\\s+")).size

                    if (batch.isNotEmpty() && batchWordCount + wordCount > MAX_WORDS_PER_BATCH) break

                    batch.add(entry)
                    batchWordCount += wordCount
                    currentIndex++
                }

                // تجهيز بيانات الدفعة الحالية فقط
                val batchData = batch.associate { (key, page) ->
                    key to page.blocks.map { it.text }
                }
                val jsonInput = JSONObject(batchData)

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val jsonObject = buildJsonObject {
                    put("model", modelName)
                    putJsonObject("response_format") { put("type", "json_object") }
                    put("top_p", 0.5f)
                    put("top_k", 30)
                    put("temperature", temp)
                    put("max_tokens", maxOutputToken)
                    putJsonArray("messages") {
                        addJsonObject {
                            put("role", "system")
                            put(
                                "content",
                                "System Instruction – Comic Translation (Strict JSON Mode)\n" +
                                    "You are an AI translator specialized in manhwa, " +
                                    "manga, and manhua OCR text.\n" +
                                    "Input is a JSON object: keys are image filenames, " +
                                    "values are arrays of strings.\n" +
                                    "Translate each string independently into ${toLang.label}.\n" +
                                    "If a string is a watermark, URL, or scan credit, " +
                                    "replace it with \"RTMTH\".\n" +
                                    "Do not merge, split, reorder, infer, " +
                                    "or expand text.\n" +
                                    "Output MUST be valid JSON only, same structure, " +
                                    "same lengths.\n" +
                                    "No explanations. No comments. No extra text.",
                            )
                        }
                        addJsonObject {
                            put("role", "user")
                            put("content", "JSON $jsonInput")
                        }
                    }
                }.toString()

                val body = jsonObject.toRequestBody(mediaType)
                val access = "https://openrouter.ai/api/v1/chat/completions"
                val build: Request = Request.Builder()
                    .url(access)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()

                // ميزة جيميناي 2: آلية إعادة المحاولة عند الفشل أو الردود الفارغة
                var responseText = ""
                var attempt = 0
                while (attempt < 2 && responseText.isBlank()) {
                    try {
                        val response = okHttpClient.newCall(build).await()
                        responseText = response.body?.string() ?: ""
                    } catch (e: Exception) {
                        logcat { "OpenRouter Request Attempt ${attempt + 1} failed: ${e.message}" }
                    }

                    if (responseText.isBlank()) {
                        attempt++
                        if (attempt < 2) Thread.sleep(2000) // انتظار ثانيتين قبل الإعادة
                    }
                }

                if (responseText.isBlank()) {
                    throw Exception("فشل الحصول على رد من OpenRouter للدفعة الحالية بعد محاولتين")
                }

                // ميزة جيميناي 3: تنظيف الـ JSON وقص الزوائد النصية الذكي
                val start = responseText.indexOf('{')
                val end = responseText.lastIndexOf('}')

                if (start == -1 || end == -1 || end <= start) {
                    logcat { "Invalid or Empty JSON response at index $currentIndex" }
                    throw Exception("رد السيرفر لا يحتوي على كائن JSON صالح")
                }

                val jsonResponseRaw = JSONObject(responseText.substring(start, end + 1))

                // جلب الـ JSON الداخلي الخاص بمحتوى الرسالة من هيكل OpenRouter
                val resJson = JSONObject(
                    jsonResponseRaw.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content"),
                )

                // ميزة جيميناي 4: تهيئة وإصلاح اتجاه النصوص للغات الشرقية (RTL Marker)
                val rtlLanguages = setOf(
                    TextTranslatorLanguage.ARABIC,
                    TextTranslatorLanguage.PERSIAN,
                    TextTranslatorLanguage.HEBREW,
                    TextTranslatorLanguage.URDU,
                    TextTranslatorLanguage.PUSHTO_PASHTO,
                    TextTranslatorLanguage.SINDHI,
                    TextTranslatorLanguage.UIGHUR_UYGHUR,
                    TextTranslatorLanguage.YIDDISH,
                    TextTranslatorLanguage.KURDISH,
                )

                val isRTL = rtlLanguages.contains(toLang)
                val rtlMarker = if (isRTL) "\u200F" else ""

                // distribution of translations
                for ((key, page) in batch) {
                    val arr = resJson.optJSONArray(key)

                    page.blocks.forEachIndexed { index, block ->
                        val translated = arr?.optString(index, "__NULL__")

                        block.translation = when {
                            // ميزة جيميناي 5: تصفير النصوص المائلة جداً لمنع تشويه الصفحة
                            block.angle < -15.0f || block.angle > 15.0f -> ""
                            translated == null || translated == "__NULL__" -> block.text
                            translated == "RTMTH" -> "RTMTH"
                            else -> rtlMarker + translated
                        }
                    }

                    page.blocks = page.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
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
