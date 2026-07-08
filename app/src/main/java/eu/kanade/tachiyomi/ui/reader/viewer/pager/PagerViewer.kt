package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
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
import kotlin.math.min

@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()
    
    private var limitCalculationJob: Job? = null

    val pager = createPager()

    val config = PagerConfig(this, scope)

    private val adapter = PagerViewerAdapter(this)

    private var currentPage: Any? = null

    private var awaitingIdleViewerChapters: ViewerChapters? = null

    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
                    }
                }
            }
        }

    init {
        pager.isVisible = false
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        
        limitCalculationJob = scope.launch {
            pager.offscreenPageLimit = computeOffscreenPageLimit()
        }

        pager.addOnPageChangeListener(
            object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    if (!activity.isScrollingThroughPages) {
                        activity.hideMenu()
                    }
                    onPageChange(position)
                    
                    limitCalculationJob?.cancel()
                    limitCalculationJob = scope.launch {
                        pager.offscreenPageLimit = computeOffscreenPageLimit()
                    }
                }

                override fun onPageScrollStateChanged(state: Int) {
                    isIdle = state == ViewPager.SCROLL_STATE_IDLE
                }
            },
        )
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val item = adapter.items.getOrNull(pager.currentItem)
                if (item is ReaderPage) {
                    activity.onPageLongTap(item)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
    }

    // ─── الحساب الذكي للتحميل المسبق للترجمة (Memory Budget الديناميكي) ───
    
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

        // إذا لم تكن الترجمة مفعلة، نحافظ على تحميل صفحة واحدة لتقليل الضغط
        if (!realtimeEnabled) return 1 

        // قراءة الإعدادات الديناميكية من TranslationPreferences
        val defaultPreload = translationPreferences.realtimeDefaultPreloadCount().get()
        val maxPreload = translationPreferences.realtimeMaxPreloadCount().get()
        val memoryBudgetMb = translationPreferences.realtimePreloadMemoryBudgetMb().get()
        val memoryBudgetBytes = memoryBudgetMb * 1024L * 1024L // تحويل الميجابايت إلى بايت

        val estimatedPageBytes = estimateCurrentPageMemoryBytes()
        
        // إذا فشلنا في حساب الحجم، نستخدم العدد الافتراضي
        if (estimatedPageBytes <= 0L) return defaultPreload

        // حساب عدد الصفحات التي يمكن للرام تحملها
        val maxPagesInBudget = (memoryBudgetBytes / estimatedPageBytes).toInt()
        
        // إرجاع القيمة مع ضمان عدم تخطي الحد الأقصى المطلق أو النزول عن 1
        return maxPagesInBudget.coerceIn(1, maxPreload)
    }

    private suspend fun estimateCurrentPageMemoryBytes(): Long = withContext(Dispatchers.IO) {
        val currentState = activity.viewModel.state.value
        val currentPage = currentState.currentChapter?.pages?.getOrNull(currentState.currentPage - 1) ?: return@withContext 0L
        val stream = currentPage.stream ?: return@withContext 0L

        return@withContext try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream().use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return@withContext 0L
            // ARGB_8888 = 4 bytes per pixel
            opts.outWidth.toLong() * opts.outHeight.toLong() * 4L
        } catch (e: Exception) {
            0L
        }
    }

    override fun destroy() {
        super.destroy()
        scope.cancel()
    }

    abstract fun createPager(): Pager

    override fun getView(): View {
        return pager
    }

    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance(PagerPageHolder::class.java)
            .firstOrNull { it.item == page }

    private fun onPageChange(position: Int) {
        val page = adapter.items.getOrNull(position)
        if (page != null && currentPage != page) {
            val allowPreload = checkAllowPreload(page as? ReaderPage)
            val forward = when {
                currentPage is ReaderPage && page is ReaderPage -> {
                    if (page.number == (currentPage as ReaderPage).number) {
                        page is InsertPage
                    } else {
                        page.number > (currentPage as ReaderPage).number
                    }
                }
                currentPage is ChapterTransition.Prev && page is ReaderPage -> false
                else -> true
            }
            currentPage = page
            when (page) {
                is ReaderPage -> onReaderPageSelected(page, allowPreload, forward)
                is ChapterTransition -> onTransitionSelected(page)
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        page ?: return true
        currentPage ?: return true

        return when (page.chapter) {
            (currentPage as? ChapterTransition.Next)?.to -> true
            (currentPage as? ReaderPage)?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    private fun onReaderPageSelected(page: ReaderPage, allowPreload: Boolean, forward: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onReaderPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)

        getPageHolder(page)?.onPageSelected(forward)

        if (page is InsertPage) {
            return
        }

        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
        }
    }

    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            activity.showMenu()
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    private fun setChaptersInternal(chapters: ViewerChapters) {
        val forceTransition = config.alwaysShowChapterTransition || adapter.items.getOrNull(pager.currentItem) is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            pager.isVisible = true
        }
    }

    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            val currentPosition = pager.currentItem
            pager.setCurrentItem(position, true)
            if (currentPosition == position) {
                onPageChange(position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    open fun moveToNext() { moveRight() }

    open fun moveToPrevious() { moveLeft() }

    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanRight()) {
                holder.panRight()
            } else {
                pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
            }
        }
    }

    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = (currentPage as? ReaderPage)?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canPanLeft()) {
                holder.panLeft()
            } else {
                pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
            }
        }
    }

    protected open fun moveUp() { moveToPrevious() }

    protected open fun moveDown() { moveToNext() }

    private fun refreshAdapter() {
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    fun refreshTranslation(clearExisting: Boolean = false) {
        if (clearExisting) {
            adapter.items.filterIsInstance<ReaderPage>().forEach { it.translation = null }
        }
        refreshAdapter()
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.cleanupPageSplit()
    }
}
