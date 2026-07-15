package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.presentation.WebtoonTranslationsView
import eu.kanade.translation.TranslationManager
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.cancel
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.MangaTranslationPreferences
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Holder of the webtoon reader for a single page of a chapter.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
    translationPreferences: TranslationPreferences = Injekt.get(),
    mangaTranslationPreferences: MangaTranslationPreferences = Injekt.get(),
    private val font: TranslationFont = TranslationFont.fromPref(translationPreferences.translationFont()),
    readerPreferences: ReaderPreferences = Injekt.get(),
    private val translationManager: TranslationManager = Injekt.get(),
    private val realtimeTranslation: Boolean = run {
        val mangaId = viewer.activity.viewModel.manga?.id
        if (mangaId == null) {
            translationPreferences.realtimeTranslation().get()
        } else {
            mangaTranslationPreferences.resolveRealtimeEnabled(
                mangaId = mangaId,
                globalRealtimeEnabled = translationPreferences.realtimeTranslation().get(),
            )
        }
    },
) : WebtoonBaseHolder(frame, viewer) {

    private var showTranslations = true
    private var translationsView: WebtoonTranslationsView? = null

    private val progressIndicator = createProgressIndicator()
    private lateinit var progressContainer: ViewGroup
    private var errorLayout: ReaderErrorBinding? = null

    private val parentHeight
        get() = viewer.recycler.height

    private var page: ReaderPage? = null
    private val scope = MainScope()

    private var loadJob: Job? = null
    private var translationCollectorJob: Job? = null

    init {
        refreshLayoutParams()

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { setError() }
        frame.onScaleChanged = { viewer.activity.hideMenu() }

        showTranslations = readerPreferences.showTranslations().get()
        readerPreferences.showTranslations().changes().onEach {
            showTranslations = it
            if (it) {
                translationsView?.show()
            } else {
                translationsView?.hide()
            }
        }.launchIn(scope)
    }

    fun bind(page: ReaderPage) {
        // ✅ FIX: إلغاء المشترك السابق فوراً قبل إنشاء مشترك جديد
        translationCollectorJob?.cancel()
        translationCollectorJob = null
        
        this.page = page
        loadJob?.cancel()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        refreshLayoutParams()

        observeRealtimeTranslation(page)
    }

    private fun observeRealtimeTranslation(boundPage: ReaderPage) {
        if (!realtimeTranslation) return
        
        translationCollectorJob = scope.launch {
            val pageFileName = String.format("%03d.jpg", boundPage.index)
            val chapterId = boundPage.chapter.chapter.id
            
            translationManager.globalPageTranslatedFlow.collectLatest { event ->
                if (event.first == chapterId && event.second == pageFileName && boundPage.translation == null) {
                    boundPage.translation = event.third
                    withUIContext { addTranslationsView() }
                }
            }
        }
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }
            val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
            marginEnd = margin.toInt()
            marginStart = margin.toInt()
        }
    }

    override fun recycle() {
        loadJob?.cancel()
        loadJob = null
        
        translationCollectorJob?.cancel()
        translationCollectorJob = null

        // ✅ FIX: حذف الترجمة من الذاكرة لتحرير RAM
        page?.translation = null

        removeErrorLayout()
        frame.recycle()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true

        frame.removeView(translationsView)
        translationsView = null
        
        // ✅ FIX: حذف مرجع الصفحة لتسريع GC
        page = null
    }

    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.QUEUE -> setQueued()
                    Page.State.LOAD_PAGE -> setLoading()
                    Page.State.DOWNLOAD_IMAGE -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator.setProgress(value)
                        }
                    }
                    Page.State.READY -> {
                        setImage()
                        addTranslationsView()
                    }
                    Page.State.ERROR -> setError()
                }
            }
        }
    }

    private fun setQueued() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    private suspend fun setImage() {
        progressIndicator.setProgress(0)
        val streamFn = page?.stream ?: return

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            withUIContext {
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        cropBorders = viewer.config.imageCropBorders,
                    ),
                )
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError()
            }
        }
    }

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }
        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }
        return imageSource
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun setError() {
        progressContainer.isVisible = false
        initErrorLayout()
        translationsView?.hide()
    }

    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
        translationsView?.show()
        
        val page = this.page
        if (page != null && page.translation == null && realtimeTranslation) {
            triggerRealtimeTranslation(page)
        }
    }

    private fun triggerRealtimeTranslation(page: ReaderPage) {
        val stream = page.stream ?: return
        val manga = viewer.activity.viewModel.manga ?: return
        val domainChapter = page.chapter.chapter.toDomainChapter() ?: return
        val fileName = String.format("%03d.jpg", page.index)
        
        if (page.translation != null) return
        
        val queued = translationManager.getQueuedTranslationOrNull(domainChapter.id!!)
        if (queued != null && queued.existingPages.containsKey(fileName)) return
        
        translationManager.queueChapterWithPages(manga, domainChapter, listOf(Pair(fileName, stream)))
    }

    private fun addTranslationsView() {
        if (page?.translation == null) return
        frame.removeView(translationsView)
        translationsView = WebtoonTranslationsView(context, translation = page!!.translation!!, font = font)
        if (!showTranslations) translationsView?.hide()
        frame.addView(translationsView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(context).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    private fun initErrorLayout(): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl)
                    context.startActivity(intent)
                }
            }
        }

        return errorLayout!!
    }

    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
