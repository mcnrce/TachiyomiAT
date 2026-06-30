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
import kotlinx.serialization.json.encodeToStream
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TranslationManager(
    private val context: Context,
    private val provider: TranslationProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {

    val translator = ChapterTranslator(context)
    val queueState = translator.queueState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // الدالة الجديدة لتهيئة وإغلاق الجلسة فور خروج المستخدم من القارئ
    fun clearRealtimeSession(chapter: Chapter, manga: Manga, source: Source) {
        launchIO {
            // 1. استخراج الجلسة الحالية لمعرفة إن كان هناك ترجمات جديدة تم إنتاجها في الوقت الحقيقي
            val currentTranslation = queueState.value.firstOrNull { it.chapter.id == chapter.id }
            
            if (currentTranslation != null && currentTranslation.existingPages.isNotEmpty()) {
                // 2. الحفظ النهائي والدائم في نفس ملف الـ JSON الموحد في الذاكرة الدائمة
                val chapterDir = provider.getChapterDir(chapter.name, manga.title, source)
                if (chapterDir != null) {
                    val jsonFile = chapterDir.createFile("translation.json")
                    jsonFile?.openOutputStream()?.use { outputStream ->
                        Json.encodeToStream(currentTranslation.existingPages, outputStream)
                    }
                }
            }
            
            // 3. مسح الفصل من الـ Queue فوراً وتعديل حالته لمنع حدوث الـ Timeout أو العلوق
            translator.forceRemoveRealtimeTranslation(chapter.id)
        }
    }

    fun deleteManga(manga: Manga, source: Source, removeQueued: Boolean = true) {
        launchIO {
            if (removeQueued) {
                translator.removeFromQueue(manga)
            }
            provider.findMangaDir(manga.title, source)?.delete()
            val sourceDir = provider.findSourceDir(source)
            if (sourceDir?.listFiles()?.isEmpty() == true) {
                sourceDir.delete()
            }
        }
    }

    fun cancelQueuedTranslation(translation: Translation) {
        removeFromTranslationQueue(translation.chapter)
    }

    private fun removeFromTranslationQueue(chapter: Chapter) {
        val wasRunning = translator.isRunning
        if (wasRunning) {
            translator.pause()
        }
        translator.removeFromQueue(chapter)
        if (wasRunning) {
            if (queueState.value.isEmpty()) {
                translator.stop()
            } else if (queueState.value.isNotEmpty()) {
                translator.start()
            }
        }
    }

    fun statusFlow(): Flow<Translation> = queueState
        .flatMapLatest { translations ->
            translations
                .map { translation ->
                    translation.statusFlow.drop(1).map { translation }
                }
                .merge()
        }
        .onStart {
            emitAll(
                queueState.value.filter { translation -> translation.status == Translation.State.TRANSLATING }.asFlow(),
            )
        }
}
