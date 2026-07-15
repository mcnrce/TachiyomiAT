package eu.kanade.translation

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.MangaTranslationPreferences
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val mangaTranslationPreferences: MangaTranslationPreferences = Injekt.get(),
) {
    private val translator = ChapterTranslator(context, provider)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _globalPageTranslatedFlow =
        MutableSharedFlow<Triple<Long, String, PageTranslation>>(extraBufferCapacity = 128)
    val globalPageTranslatedFlow = _globalPageTranslatedFlow.asSharedFlow()

    val isRunning: Boolean
        get() = translator.isRunning

    val queueState
        get() = translator.queueState

    // ✅ FIX: إضافة LruCache للترجمات (10 فصول كحد أقصى)
    // يقلل من قراءة القرص المتكررة ويحافظ على الذاكرة
    private val translationCache = android.util.LruCache<String, Map<String, PageTranslation>>(10)

    init {
        queueState
            .flatMapLatest { queue ->
                queue.map { translation ->
                    translation.pageTranslatedFlow.map { (fileName, pageTranslation) ->
                        Triple(translation.chapter.id, fileName, pageTranslation)
                    }
                }.merge()
            }
            .onEach { event -> _globalPageTranslatedFlow.emit(event) }
            .launchIn(scope)
    }

    fun translatorStart() = translator.start()
    fun translatorStop(reason: String? = null) = translator.stop(reason)

    fun startTranslation() {
        if (translator.isRunning) return
        translator.start()
    }

    fun pauseTranslation() { translator.pause() }

    fun clearQueue() { translator.clearQueue() }

    fun getQueuedTranslationOrNull(chapterId: Long): Translation? =
        queueState.value.find { it.chapter.id == chapterId }

    fun getQueuedTranslationForChapter(chapterId: Long): Translation? =
        queueState.value.find { it.chapter.id == chapterId }

    fun translateChapter(manga: Manga, chapters: Chapter) {
        translator.queueChapter(manga, chapters)
        startTranslation()
    }

    fun queueChapterWithPages(
        manga: Manga,
        chapter: Chapter,
        pageStreams: List<Pair<String, () -> InputStream>>,
    ) {
        translator.queueChapterWithPages(manga, chapter, pageStreams)
    }

    fun finishRealtimeChapter(chapterId: Long) {
        translator.finishRealtimeChapter(chapterId)
    }

    // ─── Translation Status ───────────────────────────────────────────────────

    fun getChapterTranslationStatus(
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        title: String,
        sourceId: Long,
    ): Translation.State {
        val queued = getQueuedTranslationOrNull(chapterId)
        if (queued != null) return queued.status
        if (isChapterTranslated(chapterId, chapterName, scanlator, title, sourceId)) {
            return Translation.State.TRANSLATED
        }
        return Translation.State.NOT_TRANSLATED
    }

    /**
     * مصدر الحقيقة الجديد:
     * ١. العداد الجديد (isChapterFullyTranslated) — الأدق
     * ٢. Fallback: file.exists() للفصول المترجمة قبل إضافة العداد
     */
    fun isChapterTranslated(
        chapterId: Long,
        chapterName: String,
        chapterScanlator: String?,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        if (mangaTranslationPreferences.isChapterFullyTranslated(chapterId)) return true
        val source = sourceManager.get(sourceId) ?: return false
        return provider.findTranslationFile(chapterName, chapterScanlator, mangaTitle, source)
            ?.exists() == true
    }

    // ─── Read Translation ─────────────────────────────────────────────────────

    // ✅ FIX: getChapterTranslation مع LruCache
    fun getChapterTranslation(
        chapterName: String,
        scanlator: String?,
        title: String,
        source: Source,
    ): Map<String, PageTranslation> {
        val cacheKey = "$title|$chapterName|$scanlator"
        
        // ✅ FIX: التحقق من الكاش أولاً
        translationCache.get(cacheKey)?.let { return it }
        
        return try {
            val file = provider.findTranslationFile(chapterName, scanlator, title, source)
                ?: return emptyMap()
            val result = getChapterTranslation(file)
            // ✅ FIX: تخزين في الكاش إذا كانت النتيجة غير فارغة
            if (result.isNotEmpty()) {
                translationCache.put(cacheKey, result)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun getChapterTranslation(file: UniFile): Map<String, PageTranslation> {
        return try {
            Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
        } catch (e: Exception) {
            file.delete()
            emptyMap()
        }
    }

    // ✅ FIX: دالة لمسح الكاش بالكامل (يمكن استدعاؤها من ReaderViewModel عند الخروج)
    fun clearTranslationCache() {
        translationCache.evictAll()
    }

    // ✅ FIX: دالة لمسح فصل محدد من الكاش (مفيد عند حذف ترجمة)
    fun removeTranslationFromCache(chapterName: String, scanlator: String?, title: String) {
        val cacheKey = "$title|$chapterName|$scanlator"
        translationCache.remove(cacheKey)
    }

    // ─── Delete Translation ───────────────────────────────────────────────────

    fun deleteTranslation(chapter: Chapter, manga: Manga, source: Source) {
        launchIO {
            removeFromTranslationQueue(chapter)
            provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
                ?.delete()
            // ✅ FIX: مسح من الكاش أيضاً
            removeTranslationFromCache(chapter.name, chapter.scanlator, manga.title)
            // أعد العداد للصفر → أيقونة الترجمة ترجع لـ "غير مترجم" فوراً
            mangaTranslationPreferences.clearChapterTranslation(chapter.id)
        }
    }

    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) translator.removeFromQueue(manga)
            provider.findMangaDir(manga.title, source)?.delete()
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) sourceDir.delete()
            // ✅ FIX: مسح الكاش بالكامل عند حذف المانجا
            clearTranslationCache()
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) translator.pause()
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) translator.stop()
            else translator.start()
        }
    }

    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation -> translation.statusFlow.drop(1).map { translation } }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value
                    .filter { it.status == Translation.State.TRANSLATING }
                    .asFlow(),
            )
        }
}
