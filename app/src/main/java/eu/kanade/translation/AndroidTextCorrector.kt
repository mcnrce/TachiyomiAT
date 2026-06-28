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

    private var currentDeferred: CompletableDeferred<String>? = null
    private var textUnderCheck: String = ""

    init {
        try {
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
     * تصحيح النص بشكل إجباري، وإذا حدث أي خطأ يعود بالنص الأصلي فوراً
     */
    suspend fun correctText(originalText: String): String {
        val trimmed = originalText.trim()
        if (spellCheckerSession == null || trimmed.isBlank()) return originalText

        // تخطي الكلمات الفردية المبدوءة بحرف كبير لحماية أسماء الشخصيات والمانجا
        if (!trimmed.contains(" ") && trimmed.firstOrNull()?.isUpperCase() == true) {
            return originalText
        }

        val deferred = CompletableDeferred<String>()
        currentDeferred = deferred
        textUnderCheck = trimmed

        handler?.post {
            try {
                spellCheckerSession?.getSentenceSuggestions(arrayOf(TextInfo(trimmed)), 3)
            } catch (e: Exception) {
                deferred.complete(trimmed)
            }
        }

        return try {
            // انتظار الرد لمدة أقصاها 800 ملي ثانية لضمان عدم تعليق الواجهة
            kotlinx.coroutines.withTimeout(800) {
                deferred.await()
            }
        } catch (e: Exception) {
            trimmed
        }
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val deferred = currentDeferred
        if (results == null || results.isEmpty()) {
            deferred?.complete(textUnderCheck)
            return
        }

        val sb = StringBuilder()
        val info = results[0]
        val count = info.suggestionsCount

        if (count == 0) {
            deferred?.complete(textUnderCheck)
            return
        }

        try {
            for (i in 0 until count) {
                val suggestionsInfo = info.getSuggestionsInfoAt(i)
                val offset = info.getOffsetAt(i)
                val length = info.getLengthAt(i)
                
                val originalWord = try {
                    textUnderCheck.substring(offset, offset + length)
                } catch (e: Exception) {
                    ""
                }

                val isTypo = (suggestionsInfo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0

                if (isTypo && suggestionsInfo.suggestionsCount > 0) {
                    val bestSuggestion = suggestionsInfo.getSuggestionAt(0)
                    sb.append(bestSuggestion)
                } else {
                    if (originalWord.isNotEmpty()) {
                        sb.append(originalWord)
                    } else {
                        val fallback = suggestionsInfo.getSuggestionAt(0) ?: ""
                        sb.append(fallback)
                    }
                }

                if (i < count - 1) sb.append(" ")
            }

            val resultText = sb.toString().trim()
            if (resultText.isNotEmpty()) {
                deferred?.complete(resultText)
            } else {
                deferred?.complete(textUnderCheck)
            }
        } catch (e: Exception) {
            deferred?.complete(textUnderCheck)
        }
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        // إجبارية من واجهة النظام
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
