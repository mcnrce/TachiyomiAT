package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.MangaTranslationPreferences
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val mangaTranslationPreferences: MangaTranslationPreferences = Injekt.get(),
) {

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var translationJob: Job? = null

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    private var textRecognizer: TextRecognizer
    private var textTranslator: TextTranslator

    // قفل لمنع تضارب الكتابة على نفس ملف JSON من أكثر من coroutine في نفس الوقت
    private val jsonWriteMutex = Mutex()

    companion object {
        private const val MAX_OCR_HEIGHT = 1920
        private const val TILE_OVERLAP = 0.1f
        private const val REALTIME_IDLE_TIMEOUT_MS = 30_000L
        private const val REALTIME_POLL_INTERVAL_MS = 150L
    }

    init {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        textRecognizer = TextRecognizer(fromLang)
        textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
            .build(translationPreferences, fromLang, toLang)
    }

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) return false
        val pending = queueState.value.filter { it.status != Translation.State.TRANSLATED }
        pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
        isPaused = false
        launchTranslatorJob()
        return pending.isNotEmpty()
    }

    fun stop(reason: String? = null) {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.ERROR }
        if (reason != null) return
        isPaused = false
    }

    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

    private fun launchTranslatorJob() {
        if (isRunning) return
        translationJob = scope.launch {
            val activeTranslationFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeTranslations =
                        queue.asSequence()
                            .filter { it.status.value <= Translation.State.TRANSLATING.value }
                            .groupBy { it.source }
                            .toList()
                            .take(5)
                            .map { (_, translations) -> translations.first() }
                    emit(activeTranslations)
                    if (activeTranslations.isEmpty()) break
                    val activeTranslationsErroredFlow =
                        combine(activeTranslations.map(Translation::statusFlow)) { states ->
                            states.contains(Translation.State.ERROR)
                        }.filter { it }
                    activeTranslationsErroredFlow.first()
                }
            }.distinctUntilChanged()
            supervisorScope {
                val translationJobs = mutableMapOf<Translation, Job>()
                activeTranslationFlow.collectLatest { activeTranslations ->
                    val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                    translationJobsToStop.forEach { (_, job) -> job.cancel() }
                    translationJobs.keys.retainAll(activeTranslations)
                    val translationsToStart = activeTranslations.filter { it !in translationJobs }
                    translationsToStart.forEach { translation ->
                        translationJobs[translation] = launchTranslationJob(translation)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        try {
            if (translation.isRealtimeMode) {
                translateChapterRealtime(translation)
            } else {
                translateChapterBatch(translation)
            }
            if (translation.status == Translation.State.TRANSLATED) removeFromQueue(translation)
            if (areAllTranslationsFinished()) stop()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            stop()
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    // ─── Queueing: Batch Mode (الترجمة العادية بعد التنزيل) ─────────────────────

    fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) != null) return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        if (!validateEngineSupportsLanguage()) return
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val translation = Translation(source, manga, chapter, fromLang, toLang, isRealtimeMode = false)
        addToQueue(translation)
    }

    // ─── Queueing: Realtime Mode (الترجمة الفورية أثناء القراءة) ────────────────

    /**
     * يبحث عن translation فوري موجود لنفس الفصل، أو ينشئ واحداً جديداً.
     * ثم يضيف الصفحات الجديدة إليه ويبدأ التشغيل إذا لم يكن يعمل.
     * هذا هو المدخل الوحيد المستخدم من PagerPageHolder / WebtoonPageHolder.
     *
     * منطق أولوية اللغة:
     *   - إذا للمانجا إعداد خاص (hasOverride == true) → نستخدم لغة المصدر الخاصة بها.
     *   - وإلا → نستخدم لغة المصدر العامة (translationPreferences.translateFromLanguage).
     * لغة الهدف تبقى دائماً من الإعداد العام (لا يوجد سبب وجيه لتخصيصها لكل مانجا).
     *
     * ملاحظة: التحقق من "هل الترجمة الفورية مفعلة لهذه المانجا أصلاً؟" يحدث في الطبقة
     * الأعلى (PagerPageHolder/WebtoonPageHolder عبر isRealtimeTranslationEffectivelyEnabled)
     * قبل استدعاء هذه الدالة أساساً، فهذه الدالة تفترض أن الاستدعاء مشروع بالفعل.
     */
    fun queueChapterWithPages(
        manga: Manga,
        chapter: Chapter,
        pageStreams: List<Pair<String, () -> InputStream>>,
    ) {
        if (pageStreams.isEmpty()) return

        val existing = queueState.value.find { it.chapter.id == chapter.id && it.isRealtimeMode }
        if (existing != null) {
            existing.addPageStreams(pageStreams)
            if (!isRunning) start()
            return
        }

        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (!validateEngineSupportsLanguage()) return

        val fromLang = resolveSourceLanguageForManga(manga.id)
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())

        // حمّل أي ترجمة محفوظة مسبقاً على القرص لنفس الفصل لكي لا نعيد ترجمة صفحات موجودة
        val existingOnDisk = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
            ?.let { runCatching { readTranslationFile(it) }.getOrNull() }
            ?: emptyMap()

        val translation = Translation(
            source = source,
            manga = manga,
            chapter = chapter,
            fromLang = fromLang,
            toLang = toLang,
            isRealtimeMode = true,
        )
        translation.existingPages.putAll(existingOnDisk)
        translation.addPageStreams(pageStreams)
        addToQueue(translation)
        start()
    }

    // TachiyomiAT: خريطة لتتبع طلبات الإنهاء لكل translation فوري (chapterId → flag)
    // بديل آمن لـ Translation.requestFinish() التي تحتاج تعديل Translation model
    private val finishRequests = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>()

    /**
     * يُستدعى عند الخروج من القارئ — ينهي وضع realtime لفصل معين فوراً
     * (يحفظ كل ما تُرجم حتى الآن، ثم يزيل الـ translation من الـ queue).
     */
    fun finishRealtimeChapter(chapterId: Long) {
        finishRequests[chapterId]?.set(true)
    }

    private fun validateEngineSupportsLanguage(): Boolean {
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        if (engine == TextTranslators.MLKIT && !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)) {
            context.toast(ATMR.strings.error_mlkit_language_unsupported)
            return false
        }
        return true
    }

    /**
     * يحل لغة المصدر الفعلية لهذه المانجا تحديداً:
     *   - إذا لدى المانجا إعداد خاص (hasOverride) → نقرأ sourceLanguage الخاص بها.
     *   - وإلا → نقرأ translateFromLanguage العام، كما كان دائماً.
     */
    private fun resolveSourceLanguageForManga(mangaId: Long): TextRecognizerLanguage {
        val hasOverride = mangaTranslationPreferences.hasOverride(mangaId).get()
        return if (hasOverride) {
            TextRecognizerLanguage.fromPref(mangaTranslationPreferences.sourceLanguage(mangaId))
        } else {
            TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        }
    }

    // ─── Batch Translation (الوضع العادي — أرشيف كامل محمّل على الديسك) ─────────

    private suspend fun translateChapterBatch(translation: Translation) {
    try {
        ensureRecognizerAndTranslator(translation)

        val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
        val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)
        val chapterPath = downloadProvider.findChapterDir(
            translation.chapter.name,
            translation.chapter.scanlator,
            translation.manga.title,
            translation.source,
        )!!

        val pages = mutableMapOf<String, PageTranslation>()
        val tmpFile = translationMangaDir.createFile("tmp")!!
        val streams = getChapterPages(chapterPath)
        val totalPageCount = streams.size

        withContext(Dispatchers.IO) {
            for ((fileName, streamFn) in streams) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }

                val pageTranslation = recognizePage(tmpFile)
                if (pageTranslation != null && pageTranslation.blocks.isNotEmpty()) {
                    pages[fileName] = pageTranslation
                }
            }
        }
        tmpFile.delete()
        withContext(Dispatchers.IO) { textTranslator.translate(pages) }

        writeTranslationFile(translationMangaDir, saveFile, pages)
        translation.status = Translation.State.TRANSLATED

        val cid = translation.chapter.id
        if (cid != null) {
            mangaTranslationPreferences.updateTranslatedPages(cid, totalPageCount, totalPageCount)
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        translation.status = Translation.State.ERROR
        logcat(LogPriority.ERROR, error)
    }
    }

    // ─── Realtime Translation (وضع القراءة الفورية — بدون أرشيف، streaming) ────

    /**
     * يبقى هذا الـ job حياً طالما الـ translation موجود في الـ queue.
     * في كل دورة:
     *   ١. يأخذ كل الصفحات الجديدة الواصلة منذ آخر دورة (takePageStreams)
     *   ٢. لكل صفحة: OCR → ترجمة → دمج مع existingPages → حفظ JSON فوراً → بث event
     *   ٣. إذا لم تصل صفحات جديدة لمدة طويلة (IDLE_TIMEOUT) أو طُلب الإنهاء صراحة → ينتهي
     */
    private suspend fun translateChapterRealtime(translation: Translation) {
        ensureRecognizerAndTranslator(translation)

        val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
        val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)
        val chapterId = translation.chapter.id

        // سجّل علم الإنهاء لهذا الفصل
        val finishFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        if (chapterId != null) finishRequests[chapterId] = finishFlag

        var idleElapsed = 0L

        try {
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                if (finishFlag.get()) break

                val newStreams = translation.takePageStreams()

                if (newStreams.isEmpty()) {
                    idleElapsed += REALTIME_POLL_INTERVAL_MS
                    if (idleElapsed >= REALTIME_IDLE_TIMEOUT_MS) break
                    delay(REALTIME_POLL_INTERVAL_MS)
                    continue
                }
                idleElapsed = 0L

                for ((fileName, streamFn) in newStreams) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()

                    if (translation.existingPages.containsKey(fileName)) {
                        translation.emitPageTranslated(fileName, translation.existingPages[fileName]!!)
                        continue
                    }

                    val tmpFile = translationMangaDir.createFile("tmp_${fileName.hashCode()}")
                        ?: continue
                    try {
                        withContext(Dispatchers.IO) {
                            streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }
                        }

                        val pageTranslation = withContext(Dispatchers.IO) { recognizePage(tmpFile) }

                        if (pageTranslation != null && pageTranslation.blocks.isNotEmpty()) {
                            val singlePageMap = mutableMapOf(fileName to pageTranslation)
                            withContext(Dispatchers.IO) { textTranslator.translate(singlePageMap) }

                            translation.existingPages[fileName] = pageTranslation
                            translation.emitPageTranslated(fileName, pageTranslation)

                            persistRealtimeProgress(translationMangaDir, saveFile, translation)
                        }
                    } finally {
                        tmpFile.delete()
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
        } finally {
            if (chapterId != null) finishRequests.remove(chapterId)
            runCatching { persistRealtimeProgress(translationMangaDir, saveFile, translation) }
            translation.status = Translation.State.TRANSLATED
        }
    }

    private suspend fun persistRealtimeProgress(
        translationMangaDir: UniFile,
        saveFile: String,
        translation: Translation,
    ) {
        jsonWriteMutex.withLock {
            withContext(Dispatchers.IO) {
                writeTranslationFile(translationMangaDir, saveFile, translation.existingPages)
            }
        }
    }

    /**
     * يكتب في ملف مؤقت أولاً ثم ينسخه للملف النهائي — لتجنب تلف JSON
     * إذا انقطعت العملية أثناء الكتابة (مثلاً عند إغلاق التطبيق فجأة).
     */
    private fun writeTranslationFile(
        dir: UniFile,
        saveFile: String,
        pages: Map<String, PageTranslation>,
    ) {
        try {
            val tmpName = "${saveFile}.tmp"
            dir.findFile(tmpName)?.delete()
            val tmpJsonFile = dir.createFile(tmpName) ?: return
            Json.encodeToStream(pages, tmpJsonFile.openOutputStream())

            dir.findFile(saveFile)?.delete()
            val finalFile = dir.createFile(saveFile) ?: return
            tmpJsonFile.openInputStream().use { input ->
                finalFile.openOutputStream().use { output -> input.copyTo(output) }
            }
            tmpJsonFile.delete()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    private fun readTranslationFile(file: UniFile): Map<String, PageTranslation> {
        return try {
            Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun ensureRecognizerAndTranslator(translation: Translation) {
        if (translation.fromLang != textRecognizer.language) {
            textRecognizer.close()
            textRecognizer = TextRecognizer(translation.fromLang)
        }
        if (translation.fromLang != textTranslator.fromLang || translation.toLang != textTranslator.toLang) {
            withContext(Dispatchers.IO) { textTranslator.close() }
            textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
                .build(translationPreferences, translation.fromLang, translation.toLang)
        }
    }

    // ─── Image Recognition (مشترك بين الوضعين) ──────────────────────────────────

    /**
     * المدخل الرئيسي لـ OCR.
     * يقرأ ارتفاع الصورة بدون تحميلها في RAM.
     * المشكلة دائماً عمودية (صور طويلة) — لا يوجد تقطيع أفقي.
     * إذا لم يجد OCR أي فقاعات → يعيد null مباشرة (early-exit).
     */
    private fun recognizePage(tmpFile: UniFile): PageTranslation? {
        val filePath = tmpFile.filePath ?: return null

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, opts)
        val origW = opts.outWidth
        val origH = opts.outHeight
        if (origW <= 0 || origH <= 0) return null

        return if (origH <= MAX_OCR_HEIGHT) {
            val bitmap = BitmapFactory.decodeFile(filePath) ?: return null
            try {
                recognizeSingleBitmap(bitmap, origW, origH)
            } finally {
                bitmap.recycle()
            }
        } else {
            recognizeWithVerticalTiling(filePath, origW, origH)
        }
    }

    private fun recognizeSingleBitmap(bitmap: Bitmap, origW: Int, origH: Int): PageTranslation? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.recognize(image)
        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

        if (blocks.isEmpty()) return null

        val enhancedW = origW * TextRecognizer.SCALE_FACTOR
        val enhancedH = origH * TextRecognizer.SCALE_FACTOR
        val pageTranslation = convertToPageTranslation(blocks, enhancedW, enhancedH)

        for (block in pageTranslation.blocks) {
            block.x /= TextRecognizer.SCALE_FACTOR
            block.y /= TextRecognizer.SCALE_FACTOR
            block.width /= TextRecognizer.SCALE_FACTOR
            block.height /= TextRecognizer.SCALE_FACTOR
        }
        pageTranslation.imgWidth /= TextRecognizer.SCALE_FACTOR
        pageTranslation.imgHeight /= TextRecognizer.SCALE_FACTOR
        return pageTranslation
    }

    private fun recognizeWithVerticalTiling(filePath: String, origW: Int, origH: Int): PageTranslation? {
        val allBlocks = mutableListOf<TranslationBlock>()

        val tileH    = MAX_OCR_HEIGHT
        val overlapY = (tileH * TILE_OVERLAP).toInt()
        val stepY    = tileH - overlapY
        val numTiles = ceil((origH - overlapY).toFloat() / stepY).toInt()

        val decoder = BitmapRegionDecoder.newInstance(filePath, false) ?: return null

        try {
            for (row in 0 until numTiles) {
                val tileTop    = (row * stepY).coerceAtMost(origH - tileH).coerceAtLeast(0)
                val tileBottom = (tileTop + tileH).coerceAtMost(origH)
                val isLastTile = row == numTiles - 1

                val region     = Rect(0, tileTop, origW, tileBottom)
                val tileBitmap = decoder.decodeRegion(region, null) ?: continue

                try {
                    val image      = InputImage.fromBitmap(tileBitmap, 0)
                    val result     = textRecognizer.recognize(image)
                    val tileBlocks = result.textBlocks
                        .filter { it.boundingBox != null && it.text.length > 1 }

                    for (block in tileBlocks) {
                        val bounds = block.boundingBox!!

                        val tileActualH = tileBottom - tileTop
                        val inOverlapY  = !isLastTile && bounds.top > tileActualH - overlapY
                        if (inOverlapY) continue

                        val symBounds = block.lines.firstOrNull()?.elements?.firstOrNull()
                            ?.symbols?.firstOrNull()?.boundingBox

                        allBlocks.add(
                            TranslationBlock(
                                text      = block.text,
                                width     = bounds.width().toFloat(),
                                height    = bounds.height().toFloat(),
                                symWidth  = symBounds?.width()?.toFloat() ?: 12f,
                                symHeight = symBounds?.height()?.toFloat() ?: 12f,
                                angle     = block.lines.firstOrNull()?.angle ?: 0f,
                                x         = bounds.left.toFloat(),
                                y         = (tileTop + bounds.top).toFloat(),
                            ),
                        )
                    }
                } finally {
                    tileBitmap.recycle()
                }
            }
        } finally {
            decoder.recycle()
        }

        if (allBlocks.isEmpty()) return null

        val pageTranslation = PageTranslation(imgWidth = origW.toFloat(), imgHeight = origH.toFloat())
        pageTranslation.blocks = smartMergeBlocks(allBlocks, origW.toFloat(), origH.toFloat())
        return pageTranslation
    }

    // ─── Conversion ────────────────────────────────────────────────────────────

    private fun convertToPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation {
        val translation = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        for (block in blocks) {
            val bounds = block.boundingBox!!
            val symBounds = block.lines.firstOrNull()?.elements?.firstOrNull()
                ?.symbols?.firstOrNull()?.boundingBox
            translation.blocks.add(
                TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    symWidth = symBounds?.width()?.toFloat() ?: 12f,
                    symHeight = symBounds?.height()?.toFloat() ?: 12f,
                    angle = block.lines.firstOrNull()?.angle ?: 0f,
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                ),
            )
        }
        translation.blocks = smartMergeBlocks(translation.blocks, width.toFloat(), height.toFloat())
        return translation
    }

    // ─── Smart Merge ───────────────────────────────────────────────────────────

    @Suppress("NAME_SHADOWING")
    private fun smartMergeBlocks(
        blocks: List<TranslationBlock>,
        imgWidth: Float,
        imgHeight: Float,
    ): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val filteredBlocks = blocks.filter { it.text.isNotBlank() }
        val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)
        val initialBlocks = filteredBlocks.toMutableList()

        val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
        val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

        var i = 0
        while (i < initialBlocks.size) {
            var j = i + 1
            var merged = false
            while (j < initialBlocks.size) {
                if (shouldMergeTextBlock(initialBlocks[i], initialBlocks[j], xThreshold, yThresholdFactor)) {
                    initialBlocks[i] = mergeTextBlock(initialBlocks[i], initialBlocks[j], isWebtoon)
                    initialBlocks.removeAt(j)
                    i = 0
                    merged = true
                    break
                }
                j++
            }
            if (!merged) i++
        }

        val expandedBlocks = initialBlocks.map { block ->
            val cleanedText = block.text.replace("\n", " ").trim()
            val cleanedTranslation = block.translation?.replace("\n", " ")?.trim() ?: ""

            val textRatio = (cleanedTranslation.length.toFloat() / cleanedText.length.coerceAtLeast(1))
                .coerceIn(1.0f, 1.25f)
            val finalScale = sqrt(textRatio.toDouble()).toFloat()

            var newWidth = block.width * finalScale
            var newHeight = block.height * finalScale * 1.3f

            val newX = block.x - (newWidth - block.width) / 2f
            val newY = block.y - (newHeight - block.height) / 2f

            block.copy(
                text = cleanedText,
                translation = cleanedTranslation,
                width = newWidth.coerceAtMost(imgWidth),
                height = newHeight.coerceAtMost(imgHeight),
                x = newX.coerceIn(0f, imgWidth - newWidth.coerceAtMost(imgWidth)),
                y = newY.coerceIn(0f, imgHeight - newHeight.coerceAtMost(imgHeight)),
            )
        }.toMutableList()

        resolveCollisions(expandedBlocks, imgWidth, imgHeight)

        return expandedBlocks
    }

    // ─── Collision Resolution ──────────────────────────────────────────────────

    private fun resolveCollisions(
        blocks: MutableList<TranslationBlock>,
        imgWidth: Float,
        imgHeight: Float,
    ) {
        val iterations = 6

        for (step in 0 until iterations) {
            var collisionsResolved = 0

            for (idx in blocks.indices) {
                for (jdx in idx + 1 until blocks.size) {
                    val a = blocks[idx]
                    val b = blocks[jdx]
                    if (!isOverlapping(a, b)) continue

                    collisionsResolved++

                    val aArea = a.width * a.height
                    val bArea = b.width * b.height
                    val movingIdx = if (aArea <= bArea) idx else jdx
                    val staticBlock = if (movingIdx == idx) b else a
                    var movingBlock = blocks[movingIdx]

                    val overlapRight = (staticBlock.x + staticBlock.width) - movingBlock.x
                    val overlapLeft  = (movingBlock.x + movingBlock.width) - staticBlock.x
                    val overlapDown  = (staticBlock.y + staticBlock.height) - movingBlock.y
                    val overlapUp    = (movingBlock.y + movingBlock.height) - staticBlock.y

                    val isAngled = abs(movingBlock.angle) > 20f && abs(movingBlock.angle) < 160f
                    val directions = buildDirections(
                        overlapLeft, overlapRight, overlapUp, overlapDown, isAngled, movingBlock.angle,
                    )

                    var bestX = movingBlock.x
                    var bestY = movingBlock.y
                    var bestCollisions = Int.MAX_VALUE
                    var bestDist = Float.MAX_VALUE
                    var foundValid = false

                    for ((dx, dy) in directions) {
                        val testX = (movingBlock.x + dx).coerceIn(0f, imgWidth - movingBlock.width)
                        val testY = (movingBlock.y + dy).coerceIn(0f, imgHeight - movingBlock.height)
                        val tested = movingBlock.copy(x = testX, y = testY)

                        var nextCollisions = 0
                        for (k in blocks.indices) {
                            if (k != movingIdx && isOverlapping(tested, blocks[k])) nextCollisions++
                        }

                        val dist = abs(dx) + abs(dy)
                        if (nextCollisions < bestCollisions ||
                            (nextCollisions == bestCollisions && dist < bestDist)
                        ) {
                            bestCollisions = nextCollisions
                            bestDist = dist
                            bestX = testX
                            bestY = testY
                            foundValid = true
                        }
                    }

                    if (!foundValid || bestCollisions >= countCollisions(movingBlock, blocks, movingIdx)) {
                        val shrinkFactor = 0.88f
                        val newW = movingBlock.width * shrinkFactor
                        val newH = movingBlock.height * shrinkFactor
                        movingBlock = movingBlock.copy(
                            width = newW,
                            height = newH,
                            x = movingBlock.x + (movingBlock.width - newW) / 2f,
                            y = movingBlock.y + (movingBlock.height - newH) / 2f,
                        )
                        val overlapRight2 = (staticBlock.x + staticBlock.width) - movingBlock.x
                        val overlapLeft2  = (movingBlock.x + movingBlock.width) - staticBlock.x
                        val overlapDown2  = (staticBlock.y + staticBlock.height) - movingBlock.y
                        val overlapUp2    = (movingBlock.y + movingBlock.height) - staticBlock.y
                        val dirs2 = buildDirections(
                            overlapLeft2, overlapRight2, overlapUp2, overlapDown2, isAngled, movingBlock.angle,
                        )
                        for ((dx, dy) in dirs2) {
                            val testX = (movingBlock.x + dx).coerceIn(0f, imgWidth - movingBlock.width)
                            val testY = (movingBlock.y + dy).coerceIn(0f, imgHeight - movingBlock.height)
                            val tested = movingBlock.copy(x = testX, y = testY)
                            var nc = 0
                            for (k in blocks.indices) {
                                if (k != movingIdx && isOverlapping(tested, blocks[k])) nc++
                            }
                            val dist = abs(dx) + abs(dy)
                            if (nc < bestCollisions || (nc == bestCollisions && dist < bestDist)) {
                                bestCollisions = nc
                                bestDist = dist
                                bestX = testX
                                bestY = testY
                            }
                        }
                    }

                    blocks[movingIdx] = movingBlock.copy(x = bestX, y = bestY)
                }
            }

            if (collisionsResolved == 0) break
        }
    }

    private fun buildDirections(
        overlapLeft: Float,
        overlapRight: Float,
        overlapUp: Float,
        overlapDown: Float,
        isAngled: Boolean,
        angle: Float,
    ): List<Pair<Float, Float>> {
        val baseDirections = listOf(
            Pair(-overlapLeft,  0f),
            Pair( overlapRight, 0f),
            Pair(0f,           -overlapUp),
            Pair(0f,            overlapDown),
            Pair(-overlapLeft, -overlapUp),
            Pair( overlapRight, -overlapUp),
            Pair(-overlapLeft,  overlapDown),
            Pair( overlapRight, overlapDown),
        )

        return if (isAngled) {
            val rad = Math.toRadians(angle.toDouble())
            val axisX = Math.cos(rad).toFloat()
            val axisY = Math.sin(rad).toFloat()
            val magnitude = max(overlapLeft, overlapRight)

            val angledDirs = listOf(
                Pair( axisX * magnitude,  axisY * magnitude),
                Pair(-axisX * magnitude, -axisY * magnitude),
            )
            (angledDirs + baseDirections).sortedBy { abs(it.first) + abs(it.second) }
        } else {
            baseDirections.sortedBy { abs(it.first) + abs(it.second) }
        }
    }

    private fun countCollisions(block: TranslationBlock, blocks: List<TranslationBlock>, selfIdx: Int): Int {
        var count = 0
        for (k in blocks.indices) {
            if (k != selfIdx && isOverlapping(block, blocks[k])) count++
        }
        return count
    }

    private fun isOverlapping(a: TranslationBlock, b: TranslationBlock): Boolean {
        return a.x < b.x + b.width &&
            a.x + a.width > b.x &&
            a.y < b.y + b.height &&
            a.y + a.height > b.y
    }

    // ─── Merge Logic ───────────────────────────────────────────────────────────

    private fun shouldMergeTextBlock(
        r1: TranslationBlock,
        r2: TranslationBlock,
        xThreshold: Float,
        yThresholdFactor: Float,
    ): Boolean {
        val angleDiff = abs(r1.angle - r2.angle)
        val angleSimilar = angleDiff < 15 || abs(angleDiff - 180) < 15
        if (!angleSimilar) return false

        val isVertical = abs(r1.angle) in 70.0..110.0

        val r1Right = r1.x + r1.width
        val r1Bottom = r1.y + r1.height
        val r2Right = r2.x + r2.width
        val r2Bottom = r2.y + r2.height

        val sH = max(r1.symHeight, max(r2.symHeight, 12f))
        val sW = max(r1.symWidth, max(r2.symWidth, 12f))

        val maxAllowedGapX = sW * 1.2f
        val maxAllowedGapY = sH * 1.2f

        val yOverlap = max(0f, min(r1Bottom, r2Bottom) - max(r1.y, r2.y))
        val xOverlap = max(0f, min(r1Right, r2Right) - max(r1.x, r2.x))

        val minHeight = min(r1.height, r2.height)
        val isFullVerticalCover = yOverlap >= (minHeight * 0.75f)

        val minWidth = min(r1.width, r2.width)
        val isFullHorizontalCover = xOverlap >= (minWidth * 0.95f)
        val isInCross = isFullHorizontalCover || isFullVerticalCover
        if (!isInCross) return false

        if (isVertical) {
            val sideGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
            if (sideGap > maxAllowedGapX) return false
            if (isFullVerticalCover && sideGap <= 0f) return true

            val vertGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
            if (vertGap > maxAllowedGapY) return false

            val isTouchingOrClose = sideGap <= (sW * 1.2f) && vertGap <= (sH * 1.2f)
            if (!isTouchingOrClose) return false

            val dy = abs(r1.y - r2.y)
            val dx = abs(r1.x - r2.x)
            val isOriginsClose = dy < (sH * 1.2f) && dx < (sW * 1.2f)
            val isSideBySide = sideGap < (sW * 1.2f) && dy < (sH * 1.2f)
            val alignedVertically = yOverlap > (sH * 0.15f)
            val closeHorizontally = sideGap < (sW * 1.2f)
            return isOriginsClose || isSideBySide || (closeHorizontally && alignedVertically)
        } else {
            val vGap = max(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
            if (vGap > maxAllowedGapY) return false
            if (isFullHorizontalCover && vGap <= maxAllowedGapY) return true

            val sideGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
            if (sideGap > maxAllowedGapX) return false

            val hasHighSideOverlap = xOverlap > (minWidth * 0.70f)
            val centerR1X = r1.x + r1.width / 2f
            val centerR2X = r2.x + r2.width / 2f
            val centersAligned = abs(centerR1X - centerR2X) < max(r1.width, r2.width) * 0.35f
            val isTouching = vGap <= maxAllowedGapY
            return (hasHighSideOverlap || centersAligned) && isTouching
        }
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
        val minX = min(a.x, b.x)
        val minY = min(a.y, b.y)
        val maxX = max(a.x + a.width, b.x + b.width)
        val maxY = max(a.y + a.height, b.y + b.height)

        val isVertical = abs(a.angle) in 70.0..110.0
        val ordered = if (isVertical) {
            if (a.x > b.x) listOf(a, b) else listOf(b, a)
        } else {
            if (abs(a.y - b.y) > max(a.symHeight, b.symHeight) * 0.5f) {
                if (a.y < b.y) listOf(a, b) else listOf(b, a)
            } else {
                if (isWebtoon) {
                    if (a.x < b.x) listOf(a, b) else listOf(b, a)
                } else {
                    if (a.x > b.x) listOf(a, b) else listOf(b, a)
                }
            }
        }

        val totalLen = (a.text.length + b.text.length).coerceAtLeast(1)
        return TranslationBlock(
            text = ordered.joinToString(" ") { it.text.trim() },
            translation = ordered.joinToString(" ") { it.translation.trim() }.trim(),
            width = maxX - minX,
            height = maxY - minY,
            x = minX,
            y = minY,
            angle = if (a.text.length >= b.text.length) a.angle else b.angle,
            symWidth = (a.symWidth * a.text.length + b.symWidth * b.text.length) / totalLen,
            symHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / totalLen,
        )
    }

    // ─── Chapter Pages ─────────────────────────────────────────────────────────

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            val reader = chapterPath.archiveReader(context)
            return reader.useEntries { entries ->
                entries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .map { entry -> Pair(entry.name) { reader.getInputStream(entry.name)!! } }
                    .toList()
            }
        } else {
            return chapterPath.listFiles()!!
                .filter { ImageUtil.isImage(it.name) }
                .map { entry -> Pair(entry.name!!) { entry.openInputStream() } }
                .toList()
        }
    }

    // ─── Queue Management ──────────────────────────────────────────────────────

    private fun areAllTranslationsFinished(): Boolean =
        queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }

    private fun addToQueue(translation: Translation) {
        translation.status = Translation.State.QUEUE
        _queueState.update { it + translation }
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
            if (translation.status == Translation.State.TRANSLATING ||
                translation.status == Translation.State.QUEUE
            ) {
                translation.status = Translation.State.NOT_TRANSLATED
            }
            it - translation
        }
    }

    private inline fun removeFromQueueIf(predicate: (Translation) -> Boolean) {
        _queueState.update { queue ->
            val translations = queue.filter { predicate(it) }
            translations.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            queue - translations
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            emptyList()
        }
    }
}
