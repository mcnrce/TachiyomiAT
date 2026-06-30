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
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Holder of the webtoon viewer holding one page image.
 */
class WebtoonPageHolder(
    private val frame: FrameLayout,
    private val viewer: WebtoonViewer,
) : ReaderPageImageView(viewer.activity) {

    private val readerPreferences: ReaderPreferences = Injekt.get()

    /**
     * Page of an item.
     */
    var page: ReaderPage? = null
        private set

    /**
     * Layout containing the error progress bar.
     */
    private var progressIndicator: ReaderProgressIndicator? = null

    /**
     * Layout containing the error view.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private var translationsView: WebtoonTranslationsView? = null

    private val translationManager: TranslationManager = Injekt.get()
    private var translationJob: Job? = null
    private val scope = MainScope()

    init {
        frame.addView(
            this.imageView,
            ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        translationJob?.cancel()
        translationJob = null
        // تنظيف ذاكرة الترجمة الفورية والصفحات المنتهية لمنع استهلاك مساحة الذاكرة العشوائية (RAM)
        page?.let {
            translationManager.translator.forceRemoveRealtimeTranslation(it.chapter.chapter.id)
        }
    }

    /**
     * Binds the given [page] to this view holder, setting the image and any parameters necessary.
     *
     * @param page the page to bind.
     */
    fun bind(page: ReaderPage) {
        this.page = page
        refreshOnUiThread()
    }

    /**
     * Clean up status when view is recycled.
     */
    fun onViewRecycled() {
        translationJob?.cancel()
        translationJob = null
        removeErrorLayout()
        imageView.clear()
        page = null
    }

    private fun initProgressIndicator(): ReaderProgressIndicator {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            frame.addView(progressIndicator)
        }

        val parentHeight = viewer.root.height
        progressIndicator?.updateLayoutParams<FrameLayout.LayoutParams> {
            updateMargins(top = parentHeight / 4)
        }

        return progressIndicator!!
    }

    /**
     * Initializes a button to retry pages.
     */
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
            if (imageUrl.startsWith(\"http\", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val intent = WebViewActivity.newIntent(context, imageUrl)
                    context.startActivity(intent)
                }
            }
        }

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}
