package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.presentation.PagerTranslationsView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    translationPreferences: TranslationPreferences = Injekt.get(),
    private val font: TranslationFont = TranslationFont.fromPref(translationPreferences.translationFont()),
    readerPreferences: ReaderPreferences = Injekt.get(),
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    // TachiyomiAT
    private var showTranslations = true
    private var translationsView: PagerTranslationsView? = null

    override val item get() = page

    private var progressIndicator: ReaderProgressIndicator? = null
    private var errorLayout: ReaderErrorBinding? = null
    private val scope = MainScope()
    private var loadJob: Job? = null

    init {
        loadJob = scope.launch { loadPageAndProcessStatus() }
        
        // TachiyomiAT: مراقبة إعدادات إظهار الترجمة
        showTranslations = readerPreferences.showTranslations().get()
        readerPreferences.showTranslations().changes().onEach {
            showTranslations = it
            if (it) translationsView?.show() else translationsView?.hide()
        }.launchIn(scope)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
    }

    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO { loader.loadPage(page) }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.QUEUE, Page.State.LOAD_PAGE, Page.State.DOWNLOAD_IMAGE -> {
                        initProgressIndicator()
                        progressIndicator?.show()
                        if (state == Page.State.DOWNLOAD_IMAGE) {
                            page.progressFlow.collectLatest { progressIndicator?.setProgress(it) }
                        }
                    }
                    Page.State.READY -> {
                        setImage()
                        addTranslationsView() // TachiyomiAT: استدعاء إضافة الترجمة عند الجاهزية
                    }
                    Page.State.ERROR -> setError()
                }
            }
        }
    }

    private suspend fun setImage() {
        progressIndicator?.setProgress(0)
        val streamFn = page.stream ?: return
        try {
            val (source, isAnimated, background) = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else null
                Triple(source, isAnimated, background)
            }
            withUIContext {
                setImage(source, isAnimated, Config(
                    zoomDuration = viewer.config.doubleTapAnimDuration,
                    minimumScaleType = viewer.config.imageScaleType,
                    cropBorders = viewer.config.imageCropBorders,
                    zoomStartPosition = viewer.config.imageZoomType,
                    landscapeZoom = viewer.config.landscapeZoom,
                ))
                if (!isAnimated) pageBackground = background
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext { setError() }
        }
    }

    // --- TachiyomiAT: منطق الترجمة الخاص بالـ Pager ---

    private fun addTranslationsView() {
        if (page.translation == null) return
        removeView(translationsView)
        
        // نفس طريقة الويبتون: إنشاء الـ View وإضافته كـ Child
        translationsView = PagerTranslationsView(context, translation = page.translation!!, font = font)
        if (!showTranslations) translationsView?.hide()
        
        // إضافته بحجم كامل مبدئياً
        addView(translationsView, MATCH_PARENT, MATCH_PARENT)

        // تحديث الموقع فوراً إذا كانت الصورة محملة
        (pageView as? SubsamplingScaleImageView)?.let { vi ->
            if (vi.isReady) post { syncTranslationsWithImage(vi) }
        }
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
        (pageView as? SubsamplingScaleImageView)?.let { syncTranslationsWithImage(it) }
    }

    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        (pageView as? SubsamplingScaleImageView)?.let { syncTranslationsWithImage(it) }
    }

    override fun onCenterChanged(newCenter: PointF?) {
        super.onCenterChanged(newCenter)
        (pageView as? SubsamplingScaleImageView)?.let { syncTranslationsWithImage(it) }
    }

    /**
     * هذه الدالة هي "المحرك" الذي يجعل الترجمة تتبع الصورة في الـ Pager.
     * تقوم بمحاذاة حاوية الترجمة مع أبعاد الصورة الفعلية بعد الـ Zoom والـ Pan.
     */
    private fun syncTranslationsWithImage(vi: SubsamplingScaleImageView) {
        val tv = translationsView ?: return
        val translation = page.translation ?: return
        if (!vi.isReady) return

        // 1. حساب مكان الزاوية العليا اليسرى للصورة على الشاشة
        val origin = vi.sourceToViewCoord(0f, 0f) ?: return
        
        // 2. حساب العرض الفعلي للصورة بعد التكبير
        // (عرض الصورة الأصلي * نسبة التكبير الحالية)
        val currentScale = vi.scale
        val renderedWidth = (translation.imgWidth * currentScale).toInt()
        val renderedHeight = (translation.imgHeight * currentScale).toInt()

        // 3. تحديث حجم حاوية الترجمة لتطابق الصورة تماماً
        tv.layoutParams = FrameLayout.LayoutParams(renderedWidth, renderedHeight)

        // 4. تحديث الموقع (X, Y)
        var posX = origin.x
        val posY = origin.y

        // تصحيح النصف الثاني (InsertPage) في الصفحات المقسومة
        if (page is InsertPage) {
            posX -= (translation.imgWidth / 2f) * currentScale
        }

        tv.x = posX
        tv.y = posY
    }

    // --- منطق Tachiyomi الأصلي ---

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) return rotateDualPage(imageSource)
        if (!viewer.config.dualPageSplit) return imageSource
        if (page is InsertPage) return splitInHalf(imageSource)
        if (!ImageUtil.isWideImage(imageSource)) return imageSource
        onPageSplit(page)
        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
        return if (isDoublePage) ImageUtil.rotateImage(imageSource, rotation) else imageSource
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            else -> ImageUtil.Side.RIGHT
        }
        if (viewer.config.dualPageInvert) side = if (side == ImageUtil.Side.RIGHT) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) { viewer.onPageSplit(page, InsertPage(page)) }

    private fun setError() {
        progressIndicator?.hide()
        showErrorLayout()
        translationsView?.hide()
    }

    private fun showErrorLayout(): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener { page.chapter.pageLoader?.retryPage(page) }
        }
        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null && imageUrl.startsWith("http", true)) {
            errorLayout?.actionOpenInWebView?.viewer = viewer
            errorLayout?.actionOpenInWebView?.setOnClickListener {
                context.startActivity(WebViewActivity.newIntent(context, imageUrl))
            }
        }
        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
