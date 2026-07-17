package eu.kanade.translation.model

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class Translation(
    val source: HttpSource,
    val manga: Manga,
    val chapter: Chapter,
    val fromLang: TextRecognizerLanguage = TextRecognizerLanguage.CHINESE,
    val toLang: TextTranslatorLanguage = TextTranslatorLanguage.ENGLISH,
    @Transient private val _pageStreams: MutableList<Pair<String, () -> InputStream>> = mutableListOf(),
    // [الإصلاح]: استخدام ConcurrentHashMap ليتحمل الإضافة من مسارات (Threads) متعددة في نفس الوقت
    @Transient val existingPages: MutableMap<String, PageTranslation> = ConcurrentHashMap(),
    val isRealtimeMode: Boolean = false,
    // هل اللغة نهائية أم مؤقتة تنتظر إعادة التصويت؟
    val isLangResolved: Boolean = true,
    // هل اللغة مُحددة يدوياً؟ إذن لا تصويت دوري أبداً
    val isLangFixed: Boolean = false,
    // العدد الكلي لصفحات الفصل — لاكتشاف الفصول القصيرة (أقل من 10 صفحات)
    val totalPages: Int = 0,
) {

    @Transient
    private val _statusFlow = MutableStateFlow(State.NOT_TRANSLATED)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()

    @Transient
    private val _pageTranslatedFlow = MutableSharedFlow<Pair<String, PageTranslation>>(extraBufferCapacity = 64)

    @Transient
    val pageTranslatedFlow = _pageTranslatedFlow.asSharedFlow()

    var status: State
        get() = _statusFlow.value
        set(status) { _statusFlow.value = status }

    fun emitPageTranslated(fileName: String, pageTranslation: PageTranslation) {
        _pageTranslatedFlow.tryEmit(Pair(fileName, pageTranslation))
    }

    @Synchronized
    fun addPageStreams(pages: List<Pair<String, () -> InputStream>>) {
        _pageStreams.addAll(pages)
    }

    @Synchronized
    fun takePageStreams(): List<Pair<String, () -> InputStream>> {
        val snapshot = _pageStreams.toList()
        _pageStreams.clear()
        return snapshot
    }

    @Synchronized
    fun hasPendingPages(): Boolean = _pageStreams.isNotEmpty()

    enum class State(val value: Int) {
        NOT_TRANSLATED(0),
        QUEUE(1),
        TRANSLATING(2),
        TRANSLATED(3),
        ERROR(4),
    }

    companion object {
        suspend fun fromChapterId(
            chapterId: Long,
            getChapter: GetChapter = Injekt.get(),
            getManga: GetManga = Injekt.get(),
            sourceManager: SourceManager = Injekt.get(),
        ): Translation? {
            val chapter = getChapter.await(chapterId) ?: return null
            val manga = getManga.await(chapter.mangaId) ?: return null
            val source = sourceManager.get(manga.source) as? HttpSource ?: return null
            return Translation(source, manga, chapter)
        }
    }
}
