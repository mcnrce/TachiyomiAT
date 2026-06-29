package eu.kanade.translation

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class AndroidTextCorrector(private val context: Context) {

    private val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE)
        as TextServicesManager

    // Session مفتوحة دائماً لكل locale
    private val sessions = mutableMapOf<Locale, SpellCheckerSession?>()

    fun getOrCreateSession(locale: Locale): SpellCheckerSession? {
        return sessions.getOrPut(locale) {
            tsm.newSpellCheckerSession(null, locale, DummyListener, true)
        }
    }

    /**
     * يصحح نص block كامل دفعة واحدة.
     * يستخدم getSentenceSuggestions لأنها تعطي offset لكل كلمة داخل النص.
     */
    suspend fun correctBlock(text: String, locale: Locale = Locale.ENGLISH): String {
        if (text.isBlank()) return text

        val session = getOrCreateSession(locale) ?: return text

        val result = withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SpellCheckerSessionListener {
                    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
                        // لا نستخدم هذه — نستخدم getSentenceSuggestions
                    }

                    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                        if (cont.isActive) cont.resume(results)
                    }
                }

                // نعيد إنشاء session مؤقتة للاستماع (لأن الـ session الدائمة لا تقبل listener مختلف)
                val tempSession = tsm.newSpellCheckerSession(null, locale, listener, true)
                tempSession?.getSentenceSuggestions(arrayOf(TextInfo(text)), 3)

                cont.invokeOnCancellation { tempSession?.close() }
            }
        } ?: return text // timeout → ابقِ النص كما هو

        return applyCorrections(text, result)
    }

    /**
     * يطبق التصحيحات على النص الأصلي بناءً على offsets التي أعطاها النظام.
     */
    private fun applyCorrections(
        original: String,
        results: Array<out SentenceSuggestionsInfo>?,
    ): String {
        if (results.isNullOrEmpty()) return original

        val sentenceInfo = results[0]
        val corrected = StringBuilder(original)
        var offset = 0 // تتبع إزاحة النص بعد كل استبدال

        // نجمع كل التصحيحات أولاً ثم نطبقها بالترتيب
        data class Correction(val start: Int, val end: Int, val replacement: String)
        val corrections = mutableListOf<Correction>()

        for (i in 0 until sentenceInfo.suggestionsCount) {
            val info = sentenceInfo.getSuggestionsInfoAt(i)
            val wordStart = sentenceInfo.getOffsetAt(i)
            val wordLen = sentenceInfo.getLengthAt(i)
            val wordEnd = wordStart + wordLen

            if (wordStart < 0 || wordEnd > original.length) continue

            val originalWord = original.substring(wordStart, wordEnd)

            // كلمة تبدأ بحرف كبير → اسم علم، تجاهل
            if (originalWord.isNotEmpty() && originalWord[0].isUpperCase()) continue

            // تحقق إذا النظام يعتبرها خطأ إملائي
            val attrs = info.suggestionsAttributes
            val isTypo = (attrs and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0
            val notInDict = (attrs and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) == 0

            if ((isTypo || notInDict) && info.suggestionsCount > 0) {
                val suggestion = info.getSuggestionAt(0)
                // لا نستبدل إذا الاقتراح أطول بكثير (قد يكون خطأ)
                if (suggestion.length <= originalWord.length * 2) {
                    corrections.add(Correction(wordStart, wordEnd, suggestion))
                }
            }
        }

        // طبّق التصحيحات من الآخر للأول لكي لا تتأثر الـ offsets
        for (correction in corrections.sortedByDescending { it.start }) {
            corrected.replace(
                correction.start + offset,
                correction.end + offset,
                correction.replacement,
            )
            // لا نحتاج offset هنا لأننا نعمل من الآخر للأول
        }

        return corrected.toString()
    }

    fun closeAll() {
        sessions.values.forEach { it?.close() }
        sessions.clear()
    }

    // listener فارغ للـ session الدائمة
    private object DummyListener : SpellCheckerSessionListener {
        override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
        override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {}
    }
}
