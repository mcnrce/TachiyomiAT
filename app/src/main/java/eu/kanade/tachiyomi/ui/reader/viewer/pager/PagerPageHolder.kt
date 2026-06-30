package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
import eu.kanade.translation.TranslationManager
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * View holder for a page in the pager viewer.
 */
class PagerPageHolder(
    private val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(viewer.activity), ViewPagerAdapter.PositionableView {

    private val readerPreferences: ReaderPreferences = Injekt.get()

    private var item: ReaderPage? = null

    /**

     * Layout containing the error progress bar.
     */
    private var progressIndicator: ReaderProgressIndicator? = null

    /**
     * Layout containing the error view.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private var translationsView: PagerTranslationsView? = null

    private val translationManager: TranslationManager = Injekt.get()
    private var translationJob: Job? = null
    private val scope = MainScope()

    init {
        addView(
            this.imageView,
            LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        refreshOnUiThread()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        translationJob?.cancel()
        translationJob = null
        // تفريغ فوري لمجاري بث الفصل المنتهي لمنع تسريب الذاكرة وتجهيز الفصل التالي
        translationManager.translator.forceRemoveRealtimeTranslation(page.chapter.chapter.id)
    }

    override fun getPagePosition(): Int {
        return page.number
    }

    /**
     * Called when this holder is populated with an [item].
     */
    fun onSetValues(item: ReaderPage) {
        this.item = item
        if (item is InsertPage) {
            imageView.setImage(ImageSource.bitmap(item.bitmap))
        } else if (item.chapter.state is ReaderChapter.State.Loaded) {
            refreshOnUiThread()
        } else {
            imageView.clear()
            removeErrorLayout()
        }
    }

    /**
     * Clean up status when view is recycled.
     */
    fun onViewRecycled() {
        translationJob?.cancel()
        translationJob = null
        this.item = null
        imageView.clear()
        removeErrorLayout()
    }

    /**
     * Called when a page is ready to be shown. Usually after data has been loaded or layout changed.
     */
    fun onPageSelected() {
        if (translationsView == null && page.translation != null) {
            translationsView = PagerTranslationsView(context).apply {
                val fontPref = readerPreferences.translationFont().get()
                setFont(TranslationFont.fromPref(fontPref).getAssetPath())
                setPageTranslation(page.translation!!)
            }
            addView(translationsView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            imageView.setOnImageEventListener(
                object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                    override fun onImageLoadError(e: Exception?) {
                        super.onImageLoadError(e)
                    }

                    override fun onReady() {
                        super.onReady()
                        updateTranslationCoords(imageView)
                    }
                },
            )
            imageView.setOnStateChangeListener(
                object : SubsamplingScaleImageView.OnStateChangeListener {
                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        updateTranslationCoords(imageView)
                    }

                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        updateTranslationCoords(imageView)
                    }
                },
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateTranslationCoords(vi: SubsamplingScaleImageView) {
        if (page.translation == null) return
        val coords = vi.sourceToViewCoord(0f, 0f)
        if (coords != null) {
            translationsView?.viewTLState?.value = coords
        }
        translationsView?.scaleState?.value = vi.scale
    }

    private fun showErrorLayout(): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith(\"http\", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer\n                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}
