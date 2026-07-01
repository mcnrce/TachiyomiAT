package eu.kanade.tachiyomi.ui.reader.setting

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderSettingsScreenModel(
    readerState: StateFlow<ReaderViewModel.State>,
    val hasDisplayCutout: Boolean,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
    val preferences: ReaderPreferences = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : ScreenModel {

    val viewerFlow = readerState
        .map { it.viewer }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    val mangaFlow = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, null)

    /** TachiyomiAT: الفصل الحالي المفتوح — لزر "مسح ترجمة هذا الفصل" */
    val currentChapter: tachiyomi.domain.chapter.model.Chapter?
        get() = readerState.value.currentChapter?.chapter?.toDomainChapter()

    /**
     * TachiyomiAT: يُستخدم من تبويب إعدادات الترجمة (زر "مسح الترجمة")
     * للحصول على HttpSource الفعلي اللازم لـ TranslationManager.deleteManga.
     */
    fun getHttpSourceOrNull(): HttpSource? {
        val manga = mangaFlow.value ?: return null
        return sourceManager.get(manga.source) as? HttpSource
    }
}
