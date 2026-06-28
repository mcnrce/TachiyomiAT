package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.translation.TranslationManager
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val manga: Manga,
    private val source: Source,
    private val translationManager: TranslationManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withIOContext {
            logcat { "Loading pages for ${chapter.chapter.name}" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.last_page_read
                }

                // TachiyomiAT: تحميل الترجمة الموجودة لأي فصل بغض النظر عن حالة التحميل
                // (DownloadPageLoader يقرأ الترجمة بنفسه، هنا نغطي HttpPageLoader وغيره)
                if (loader !is DownloadPageLoader) {
                    val existingTranslation = translationManager.getChapterTranslation(
                        chapter.chapter.name,
                        chapter.chapter.scanlator,
                        manga.title,
                        source,
                    )

                    if (existingTranslation.isNotEmpty()) {
                        // نطابق بـ index كمفتاح ثابت — نفس ما يستخدمه الـ holder
                        pages.forEach { page ->
                            val key = String.format("%03d.jpg", page.index)
                            page.translation = existingTranslation[key]
                        }
                    }

                    // إذا كان وضع الترجمة الفورية مفعلاً وعدد الصفحات المترجمة أقل من إجمالي الصفحات
                    // نضع علامة على الصفحات غير المترجمة حتى يطلب الـ holder ترجمتها عند READY
                    if (translationPreferences.realtimeTranslation().get()) {
                        val translatedCount = existingTranslation.size
                        if (translatedCount < pages.size) {
                            // الصفحات التي لا تزال بدون ترجمة ستُعالج في triggerRealtimeTranslation
                            // عندما تصبح READY في الـ holder — لا نحتاج شيئاً هنا
                            // فقط نتأكد أن الصفحات المترجمة حُملت صحيحاً أعلاه
                        }
                    }
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
            } catch (e: Throwable) {
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        val isDownloaded = downloadManager.isChapterDownloaded(
            dbChapter.name,
            dbChapter.scanlator,
            manga.title,
            manga.source,
            skipCache = true,
        )
        return when {
            isDownloaded -> DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
            source is LocalSource -> source.getFormat(chapter.chapter).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context), emptyMap())
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
            source is HttpSource -> HttpPageLoader(chapter, source)
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }
}
