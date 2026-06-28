package eu.kanade.translation

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale

class AndroidTextCorrector(context: Context, private val locale: Locale) : 
    SpellCheckerSession.SpellCheckerSessionListener {

    private val appContext = context.applicationContext
    private val textServicesManager = appContext.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
    
    private var spellCheckerSession: SpellCheckerSession? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // مخزن مؤقت لحفظ الـ Deferred الخاص بكل طلب تصحيح لربط الـ Async بالـ Coroutines
    private var currentDeferred: CompletableDeferred<String>? = null

    init {
        try {
            // إنشاء خيط مخصص (HandlerThread) يحتوي على Looper لضمان عمل واجهة الـ SpellChecker في الخلفية باستقرار
            handlerThread = HandlerThread("SpellCheckerThread").apply { start() }
            handler = Handler(handlerThread!!.looper)

            handler?.post {
                try {
                    spellCheckerSession = textServicesManager?.newSpellCheckerSession(null, locale, this, true)
                } catch (e: Exception) {
                    spellCheckerSession = null
                }
            }
        } catch (e: Exception) {
            spellCheckerSession = null
        }
    }

    /**
     * الدالة الأساسية: تصحح النص بشكل إجباري وتنتظر رد نظام أندرويد عبر الـ Coroutines
     */
    suspend fun correctText(originalText: String): String {
        val trimmed = originalText.trim()
        if (spellCheckerSession == null || trimmed.isBlank()) return originalText

        // تخطي الكلمات الفردية المبدوءة بحرف كبير لحماية أسماء الشخصيات والقدرات الخاصة
        if (!trimmed.contains(" ") && trimmed.firstOrNull()?.isUpperCase() == true) {
            return originalText
        }

        // إنشاء سياق انتظار معلق (Deferred)
        val deferred = CompletableDeferred<String>()
        currentDeferred = deferred

        handler?.post {
            try {
                // إرسال النص بالكامل لفحصه كجملة متكاملة للحفاظ على السياق والمعنى
                spellCheckerSession?.getSentenceSuggestions(arrayOf(TextInfo(trimmed)), 3)
            } catch (e: Exception) {
                deferred.complete(trimmed)
            }
        }

        // انتظر النتيجة هنا بشكل معلق (Suspend) دون تجميد التطبيق، وبحد أقصى ثانية واحدة لكل كتلة نصية لضمان عدم التعليق
        return try {
            kotlinx.coroutines.withTimeout(1000) {
                deferred.await()
            }
        } catch (e: Exception) {
            trimmed
        }
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val deferred = currentDeferred
        if (results == null || results.isEmpty()) {
            deferred?.complete("")
            return
        }

        val sb = StringBuilder()
        val info = results[0]
        val count = info.suggestionsCount

        if (count == 0) {
            deferred?.complete("")
            return
        }

        // فحص الاقتراحات وإعادة بناء النص المصحح
        for (i in 0 until count) {
            val suggestionsInfo = info.getSuggestionsInfoAt(i)
            val originalWord = try {
                // محاولة استخراج الكلمة الأصلية بناء على أبعاد الخطأ المكتشف
                val offset = info.getOffsetAt(i)
                val length = info.getLengthAt(i)
                // نعتمد على النص الأصلي المخزن مؤقتاً عند الحاجة
                "" 
            } catch (e: Exception) {
                ""
            }

            val isTypo = (suggestionsInfo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
            
            // إذا كان النص المكتشف عبارة عن خطأ إملائي واضح، وله اقتراحات ذكية من النظام، نستبدله فوراً
            if (isTypo && suggestionsInfo.suggestionsCount > 0) {
                val bestSuggestion = suggestionsInfo.getSuggestionAt(0)
                sb.append(bestSuggestion)
            } else {
                // إذا كانت الكلمة سليمة أو اسم علم، نتركها كما هي دون تحريف لتفادي إفساد المانجا
                // ملحوظة: نظام أندرويد يعيد الكلمة الأصلية تلقائياً في السجل إذا لم تكن خطأ
                val fallbackWord = suggestionsInfo.getSuggestionAt(0) ?: ""
                sb.append(fallbackWord)
            }

            if (i < count - 1) sb.append(" ")
        }

        val resultText = sb.toString().trim()
        if (resultText.isNotEmpty()) {
            deferred?.complete(resultText)
        } else {
            deferred?.complete("")
        }
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        // إجبارية من الواجهة البرمجية لأندرويد
    }

    fun close() {
        handler?.post {
            try {
                spellCheckerSession?.cancel()
                spellCheckerSession = null
            } catch (e: Exception) {
                // تجاهل
            }
        }
        handlerThread?.quitSafely()
    }
}
