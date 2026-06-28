package eu.kanade.translation

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidTextCorrector(
    context: Context, 
    locale: Locale
) : SpellCheckerSession.SpellCheckerSessionListener {

    private val textServicesManager = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
    private var spellCheckerSession: SpellCheckerSession? = null
    
    private var latch: CountDownLatch? = null
    private var correctedText: String = ""

    init {
        // إنشاء جلسة التدقيق الإملائي بناءً على لغة الفصل المصدر
        spellCheckerSession = textServicesManager.newSpellCheckerSession(null, locale, this, true)
    }

    /**
     * دالة معلقة تقوم بفحص النص المكتشف وإرجاع النص المصحح تلقائياً.
     * تم ضبطها لتتخطى الكلمات المبتدئة بحرف كبير لحماية أسماء المانجا.
     */
    suspend fun correctText(originalText: String): String {
        if (spellCheckerSession == null || originalText.isBlank()) return originalText
        
        // إذا كان النص عبارة عن كلمة واحدة تبدأ بحرف كبير، فغالباً هو اسم علم (تخطى الفحص)
        val trimmed = originalText.trim()
        if (!trimmed.contains(" ") && trimmed.firstOrNull()?.isUpperCase() == true) {
            return originalText
        }

        latch = CountDownLatch(1)
        correctedText = trimmed

        // طلب الاقتراحات من محرك النظام الافتراضي (بحد أقصى 3 اقتراحات للكلمة)
        spellCheckerSession?.getSentenceSuggestions(arrayOf(TextInfo(trimmed)), 3)

        // انتظار الرد لمدة أقصاها 800 ملي ثانية لضمان عدم تأثر سرعة القراءة الفورية
        latch?.await(800, TimeUnit.MILLISECONDS)
        
        return correctedText
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        if (results == null || results.isEmpty()) {
            latch?.countDown()
            return
        }

        val sb = StringBuilder()
        val info = results[0]
        val count = info.suggestionsCount

        if (count == 0) {
            latch?.countDown()
            return
        }

        // إعادة بناء الجملة واستبدال الكلمات الخاطئة بأول اقتراح صحيح يقدمه النظام
        for (i in 0 until count) {
            val suggestionsInfo = info.getSuggestionsInfoAt(i)
            val offset = info.getOffsetAt(i)
            val length = info.getLengthAt(i)
            
            // استخراج الكلمة الأصلية قبل التعديل
            val originalWord = try {
                correctedText.substring(offset, offset + length)
            } catch (e: Exception) {
                ""
            }

            if (originalWord.isBlank()) continue

            // تحقق مما إذا كان المحرك يرى الكلمة كخطأ إملائي (Typo) وأن الاسم ليس علماً مبدوءاً بحرف كبير
            val isTypo = (suggestionsInfo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
            val isCapitalized = originalWord.firstOrNull()?.isUpperCase() == true

            if (isTypo && !isCapitalized && suggestionsInfo.suggestionsCount > 0) {
                // استبدال الخطأ بأول اقتراح ذكي من أندرويد
                sb.append(suggestionsInfo.getSuggestionAt(0))
            } else {
                sb.append(originalWord)
            }
            
            if (i < count - 1) sb.append(" ")
        }

        if (sb.isNotEmpty()) {
            correctedText = sb.toString()
        }
        latch?.countDown()
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        // تم تنفيذها لأنها إجبارية في الـ Interface، لكن الاعتماد الفعلي على دالة الجمل أعلاه
    }

    fun close() {
        spellCheckerSession?.cancel()
        spellCheckerSession = null
    }
}
