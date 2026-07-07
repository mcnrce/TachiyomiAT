package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.max
import kotlin.math.min

class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()
    
    // [تحسين]: التحكم في المهام المتراكمة
    private var limitCalculationJob: Job? = null

    val recycler = WebtoonRecyclerView(activity)

    private val frame = WebtoonFrame(activity)

    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    val config = WebtoonConfig(scope)

    private val adapter = WebtoonAdapter(this)

    private var currentPage: Any? = null

    private val threshold: Int = Injekt.get<ReaderPreferences>().readerHideThreshold().get().threshold

    init {
        recycler.isVisible = false 
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        
        // [تحسين]: الحساب الأولي في الخلفية بأمان
        limitCalculationJob = scope.launch {
            recycler.setItemViewCacheSize(computeOffscreenPageLimit())
        }

        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled() // الدالة المخصصة في الأسفل هي من ستتولى أمر الحساب الذكي

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = { refreshAdapter() }
        config.themeChangedListener = { ActivityCompat.recreate(activity) }
        config.doubleTapZoomChangedListener = { frame.doubleTapZoom = it }
        config.zoomPropertyChangedListener = { frame.zoomOutDisabled = it }
        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    // ─── الحساب الذكي للتحميل المسبق للترجمة (Memory Budget) ───
    
    // [تحسين]: تحويلها لـ suspend
    private suspend fun computeOffscreenPageLimit(): Int {
        val translationPreferences = Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()
        val mangaTranslationPreferences = Injekt.get<tachiyomi.domain.translation.MangaTranslationPreferences>()
        val mangaId = activity.viewModel.manga?.id

        val realtimeEnabled = if (mangaId != null) {
            mangaTranslationPreferences.resolveRealtimeEnabled(
                mangaId = mangaId,
                globalRealtimeEnabled = translationPreferences.realtimeTranslation().get(),
            )
        } else {
            translationPreferences.realtimeTranslation().get()
        }

        if (!realtimeEnabled) return RECYCLER_VIEW_DEFAULT_CACHE_SIZE

        val estimatedPageBytes = estimateCurrentPageMemoryBytes()
        if (estimatedPageBytes <= 0L) return DEFAULT_REALTIME_OFFSCREEN_LIMIT

        val maxPagesInBudget = (MEMORY_BUDGET_BYTES / estimatedPageBytes).toInt()
        return maxPagesInBudget.coerceIn(RECYCLER_VIEW_DEFAULT_CACHE_SIZE, MAX_REALTIME_OFFSCREEN_LIMIT)
    }

    // [تحسين]: نقل الـ I/O للخلفية Dispatchers.IO
    private suspend fun estimateCurrentPageMemoryBytes(): Long = withContext(Dispatchers.IO) {
        val currentState = activity.viewModel.state.value
        val currentPage = currentState.currentChapter?.pages?.getOrNull(currentState.currentPage - 1) ?: return@withContext 0L
        val stream = currentPage.stream ?: return@withContext 0L

        return@withContext try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream().use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@withContext 0L
            opts.outWidth.toLong() * opts.outHeight.toLong() * 4L
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private const val MEMORY_BUDGET_BYTES = 150L * 1024 * 1024
        private const val RECYCLER_VIEW_DEFAULT_CACHE_SIZE = 4
        private const val DEFAULT_REALTIME_OFFSCREEN_LIMIT = 4
        private const val MAX_REALTIME_OFFSCREEN_LIMIT = 8 
    }
    // ────────────────────────────────────────────────────────────

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        page ?: return true
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    override fun getView(): View {
        return frame
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    fun onScrolled(pos: Int? = null) {
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        
        // [تحسين]: الحساب الذكي للكاش يتم فقط عندما تتغير الصفحة (وليس مع كل بكسل تمرير)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
            
            // تحديث الكاش بأمان عبر الكوروتين وإلغاء المهمة السابقة لمنع التكدس
            limitCalculationJob?.cancel()
            limitCalculationJob = scope.launch {
                recycler.setItemViewCacheSize(computeOffscreenPageLimit())
            }
        }
    }

    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    private fun scrollDown() {
        if (config.usePageTransitions) {
             recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        adapter.notifyItemRangeChanged(
            max(0, position - 3),
            min(position + 3, adapter.itemCount - 1),
        )
    }
}
