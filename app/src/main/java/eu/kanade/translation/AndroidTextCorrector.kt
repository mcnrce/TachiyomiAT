package eu.kanade.translation

import android.content.Context
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Locale

/**
 * مصحح نصوص OCR يعتمد على خوارزمية SymSpell المضمنة محلياً.
 * لا يحتاج إنترنت أو إعدادات النظام أو لوحة مفاتيح معينة.
 * القاموس: app/src/main/assets/symspell/en.txt
 */
class AndroidTextCorrector(private val context: Context) {

    // القاموس: كلمة → تكرارها في النصوص الإنجليزية
    private val dictionary = mutableMapOf<String, Long>()

    // أقصى مسافة تحريف نقبلها (1 = أسرع وأدق لأخطاء OCR)
    private val maxEditDistance = 2

    // أقل تكرار للكلمة لكي نقبلها كتصحيح (يمنع الكلمات النادرة الغريبة)
    private val minFrequency = 1L

    // هل تم تحميل القاموس
    @Volatile
    private var isLoaded = false

    init {
        loadDictionary()
    }

    // ── تحميل القاموس ────────────────────────────────────────────────────────

    private fun loadDictionary() {
        try {
            context.assets.open("symspell/en.txt").bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    val parts = line.trim().split(" ", "\t")
                    if (parts.size >= 2) {
                        val word = parts[0].lowercase()
                        val freq = parts[1].toLongOrNull() ?: return@forEach
                        if (freq >= minFrequency) {
                            dictionary[word] = freq
                        }
                    }
                }
            }
            isLoaded = true
            logcat(LogPriority.DEBUG) { "SymSpell dictionary loaded: ${dictionary.size} words" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "SymSpell dictionary load failed: ${e.message}" }
        }
    }

    // ── الواجهة الرئيسية ──────────────────────────────────────────────────────

    /**
     * يصحح نص block كامل كلمة كلمة.
     * - كلمات تبدأ بحرف كبير → تُترك (أسماء علم)
     * - كلمات موجودة في القاموس → تُترك
     * - كلمات خاطئة → تُستبدل بأقرب كلمة في القاموس
     */
    suspend fun correctBlock(text: String, locale: Locale = Locale.ENGLISH): String {
        if (!isLoaded || text.isBlank()) return text

        // SymSpell حالياً للإنجليزية فقط
        if (locale != Locale.ENGLISH && locale.language != "en") return text

        val tokens = tokenize(text)
        val result = StringBuilder()

        for (token in tokens) {
            if (!token.isWord) {
                result.append(token.value)
                continue
            }

            val word = token.value

            // كلمة تبدأ بحرف كبير → اسم علم، تجاهل
            if (word[0].isUpperCase()) {
                result.append(word)
                continue
            }

            // كلمة قصيرة جداً (حرف أو حرفان) → تجاهل
            if (word.length <= 2) {
                result.append(word)
                continue
            }

            val lower = word.lowercase()

            // الكلمة موجودة في القاموس → صحيحة
            if (dictionary.containsKey(lower)) {
                result.append(word)
                continue
            }

            // ابحث عن أقرب تصحيح
            val suggestion = findBestSuggestion(lower)
            if (suggestion != null) {
                // حافظ على حالة الأحرف الأصلية إذا كانت الكلمة كلها كبيرة
                val corrected = if (word.all { it.isUpperCase() }) {
                    suggestion.uppercase()
                } else {
                    suggestion
                }
                result.append(corrected)
            } else {
                result.append(word)
            }
        }

        return result.toString()
    }

    // ── خوارزمية SymSpell المبسطة ─────────────────────────────────────────────

    /**
     * يجد أفضل اقتراح للكلمة الخاطئة.
     * يولّد كل المتغيرات ضمن مسافة التحريف ويبحث عنها في القاموس.
     */
    private fun findBestSuggestion(word: String): String? {
        var bestWord: String? = null
        var bestFreq = 0L
        var bestDist = Int.MAX_VALUE

        // توليد كل الكلمات ضمن مسافة تحريف 1
        val candidates1 = generateEdits(word)

        for (candidate in candidates1) {
            val freq = dictionary[candidate] ?: continue
            val dist = 1
            if (dist < bestDist || (dist == bestDist && freq > bestFreq)) {
                bestDist = dist
                bestFreq = freq
                bestWord = candidate
            }
        }

        // إذا لم نجد شيئاً بمسافة 1، نجرب مسافة 2
        if (bestWord == null && maxEditDistance >= 2) {
            for (candidate1 in candidates1) {
                val candidates2 = generateEdits(candidate1)
                for (candidate2 in candidates2) {
                    val freq = dictionary[candidate2] ?: continue
                    val dist = 2
                    if (dist < bestDist || (dist == bestDist && freq > bestFreq)) {
                        bestDist = dist
                        bestFreq = freq
                        bestWord = candidate2
                    }
                }
            }
        }

        // لا نقبل تصحيحاً إذا كان مختلفاً جداً عن الكلمة الأصلية
        if (bestWord != null && bestWord.length > word.length * 2) return null

        return bestWord
    }

    /**
     * يولّد كل الكلمات التي تبعد مسافة تحريف واحدة عن الكلمة.
     * عمليات التحريف: حذف، استبدال، إدراج، تبديل موضع
     */
    private fun generateEdits(word: String): Set<String> {
        val edits = mutableSetOf<String>()
        val letters = "abcdefghijklmnopqrstuvwxyz"

        for (i in word.indices) {
            // حذف حرف
            edits.add(word.removeRange(i, i + 1))

            // تبديل موضع حرفين متجاورين
            if (i < word.length - 1) {
                edits.add(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2))
            }

            // استبدال حرف
            for (c in letters) {
                edits.add(word.substring(0, i) + c + word.substring(i + 1))
            }

            // إدراج حرف
            for (c in letters) {
                edits.add(word.substring(0, i) + c + word.substring(i))
            }
        }

        // إدراج حرف في النهاية
        for (c in letters) {
            edits.add(word + c)
        }

        return edits
    }

    // ── Tokenizer: يفصل الكلمات عن علامات الترقيم والمسافات ────────────────

    private data class Token(val value: String, val isWord: Boolean)

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var inWord = false

        for (char in text) {
            val isWordChar = char.isLetter()
            if (isWordChar == inWord) {
                current.append(char)
            } else {
                if (current.isNotEmpty()) {
                    tokens.add(Token(current.toString(), inWord))
                    current.clear()
                }
                current.append(char)
                inWord = isWordChar
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(Token(current.toString(), inWord))
        }
        return tokens
    }

    // ── تنظيف ────────────────────────────────────────────────────────────────

    fun closeAll() {
        // لا يوجد sessions لإغلاقها — SymSpell يعمل محلياً بالكامل
        dictionary.clear()
        isLoaded = false
    }
}
