package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.filter
import kotlinx.coroutines.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.transformLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
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

    companion object {
        private const val MAX_OCR_HEIGHT = 1920
        private const val TILE_OVERLAP = 0.1f
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

    /**
     * لتفريغ مخلفات الفصل وتنظيف الكاش عند الخروج من القارئ لتهيئته للفصل القادم.
     */
    fun forceRemoveRealtimeTranslation(chapterId: Long) {
        _queueState.update { queue ->
            val toCancel = queue.filter { it.chapter.id == chapterId && it.isRealtimeMode }
            toCancel.forEach { translation ->
                translation.status = Translation.State.NOT_TRANSLATED
                translation.takePageStreams() // إفراغ مجاري البث
                translation.existingPages.clear() // مسح الكاش المحلي
            }
            queue - toCancel
        }
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
            translateChapter(translation)
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

    fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) != null) return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        if (engine == TextTranslators.MLKIT && !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)) {
            context.toast(ATMR.strings.error_mlkit_language_unsupported)
            return
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang)
        addToQueue(translation)
    }

    fun addToQueue(translation: Translation) {
        _queueState.update { queue ->
            if (queue.any { it.chapter.id == translation.chapter.id }) queue
            else queue + translation
        }
        start()
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.all { it.status == Translation.State.TRANSLATED || it.status == Translation.State.ERROR }
    }

    fun removeFromQueue(translation: Translation) {
        _queueState.update { queue ->
            if (translation.status == Translation.State.TRANSLATING ||
                translation.status == Translation.State.QUEUE
            ) {
                translation.status = Translation.State.NOT_TRANSLATED
            }
            queue - translation
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

    private suspend fun translateChapter(translation: Translation) {
        try {
            if (translation.fromLang != textRecognizer.language) {
                textRecognizer.close()
                textRecognizer = TextRecognizer(translation.fromLang)
            }
            if (translation.fromLang != textTranslator.fromLang || translation.toLang != textTranslator.toLang) {
                withContext(Dispatchers.IO) { textTranslator.close() }
                textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
                    .build(translationPreferences, translation.fromLang, translation.toLang)
            }

            val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
            val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)

            // التحقق أولاً وقبل كل شيء من وجود ملف الـ JSON القديم وتحميل الكاش منه فوراً
            val existingJsonFile = translationMangaDir.findFile(saveFile)
            if (existingJsonFile != null && existingJsonFile.exists()) {
                try {
                    existingJsonFile.openInputStream().use { stream ->
                        val loadedPages: Map<String, PageTranslation> = Json.decodeFromStream(stream)
                        translation.existingPages.putAll(loadedPages)
                        // بث الصفحات المخزنة تلقائياً إلى الواجهة لتعرض للمستخدم فوراً
                        loadedPages.forEach { (fileName, pageTrans) ->
                            translation.emitPageTranslated(fileName, pageTrans)
                        }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "فشل في قراءة ملف JSON الترجمة القديم، سيتم معالجته مجدداً" }
                }
            }

            translation.status = Translation.State.TRANSLATING

            while (translation.status == Translation.State.TRANSLATING) {
                ensureActive()

                val pendingPages = translation.takePageStreams()
                if (pendingPages.isEmpty()) {
                    if (translation.isRealtimeMode) {
                        delay(200)
                        continue
                    } else {
                        // المسار العادي لقراءة مجلد الفصول محلياً
                        val chapterPath = downloadProvider.findChapterDir(
                            translation.chapter.name,
                            translation.chapter.scanlator,
                            translation.manga.title,
                            translation.source,
                        )
                        if (chapterPath != null) {
                            val streams = getChapterPages(chapterPath)
                            val localPages = mutableMapOf<String, PageTranslation>()
                            val tmpFile = translationMangaDir.createFile("tmp")!!

                            withContext(Dispatchers.IO) {
                                for ((fileName, streamFn) in streams) {
                                    coroutineContext.ensureActive()
                                    if (translation.status != Translation.State.TRANSLATING) break

                                    // عدم إعادة معالجة الصورة إذا كانت موجودة ومحملة مسبقاً من الـ JSON
                                    if (translation.existingPages.containsKey(fileName)) continue

                                    streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }
                                    val pageTranslation = recognizePage(tmpFile)
                                    if (pageTranslation != null && pageTranslation.blocks.isNotEmpty()) {
                                        localPages[fileName] = pageTranslation
                                    }
                                }
                            }
                            tmpFile.delete()
                            withContext(Dispatchers.IO) { textTranslator.translate(localPages) }
                            translation.existingPages.putAll(localPages)
                        }

                        val finalFile = translationMangaDir.createFile(saveFile)!!
                        Json.encodeToStream(translation.existingPages, finalFile.openOutputStream())
                        translation.status = Translation.State.TRANSLATED
                        break
                    }
                }

                // معالجة تدفق الصفحات في الوقت الحقيقي
                val tmpFile = translationMangaDir.createFile("tmp_rt")!!
                for ((fileName, streamFn) in pendingPages) {
                    ensureActive()
                    if (translation.status != Translation.State.TRANSLATING) break

                    // تحقق الوقت الحقيقي: إذا كانت موجودة مسبقاً، نقوم ببثها فوراً وتخطي المعالجة المتكررة
                    if (translation.existingPages.containsKey(fileName)) {
                        translation.emitPageTranslated(fileName, translation.existingPages[fileName]!!)
                        continue
                    }

                    try {
                        streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }
                        val pageTranslation = recognizePage(tmpFile)
                        if (pageTranslation != null) {
                            val singlePageMap = mutableMapOf(fileName to pageTranslation)
                            withContext(Dispatchers.IO) { textTranslator.translate(singlePageMap) }

                            translation.existingPages[fileName] = pageTranslation
                            translation.emitPageTranslated(fileName, pageTranslation)

                            // تحديث ملف الـ JSON دورياً لحفظ الترجمات الفورية المستقرة أولاً بأول
                            val finalFile = translationMangaDir.createFile(saveFile)!!
                            Json.encodeToStream(translation.existingPages, finalFile.openOutputStream())
                        }
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "خطأ في معالجة الصفحة في الوقت الحقيقي: $fileName" }
                    }
                }
                tmpFile.delete()
            }
        } catch (error: Throwable) {
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error)
        }
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        return chapterPath.listFiles()
            ?.filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
            ?.sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            ?.map { file -> file.name to { file.openInputStream() } } ?: emptyList()
    }

    // ─── Image Tiling ──────────────────────────────────────────────────────────

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

        val tileH = MAX_OCR_HEIGHT
        val overlapY = (tileH * TILE_OVERLAP).toInt()
        val stepY = tileH - overlapY
        val numTiles = ceil((origH - overlapY).toFloat() / stepY).toInt()

        val decoder = android.graphics.BitmapRegionDecoder.newInstance(filePath, false) ?: return null

        try {
            for (row in 0 until numTiles) {
                val tileTop = (row * stepY).coerceAtMost(origH - tileH).coerceAtLeast(0)
                val tileBottom = (tileTop + tileH).coerceAtMost(origH)
                val isLastTile = row == numTiles - 1

                val region = Rect(0, tileTop, origW, tileBottom)
                val tileBitmap = decoder.decodeRegion(region, null) ?: continue

                try {
                    val image = InputImage.fromBitmap(tileBitmap, 0)
                    val result = textRecognizer.recognize(image)
                    val tileBlocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

                    for (block in tileBlocks) {
                        val bounds = block.boundingBox!!
                        val tileActualH = tileBottom - tileTop
                        val inOverlapY = !isLastTile && bounds.top > tileActualH - overlapY
                        if (inOverlapY) continue

                        val symBounds = block.lines.firstOrNull()?.elements?.firstOrNull()
                            ?.symbols?.firstOrNull()?.boundingBox

                        allBlocks.add(
                            TranslationBlock(
                                text = block.text,
                                width = bounds.width().toFloat(),
                                height = bounds.height().toFloat(),
                                symWidth = symBounds?.width()?.toFloat() ?: 12f,
                                symHeight = symBounds?.height()?.toFloat() ?: 12f,
                                angle = block.lines.firstOrNull()?.angle ?: 0f,
                                x = bounds.left.toFloat(),
                                y = (tileTop + bounds.top).toFloat(),
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

    private fun shouldMergeTextBlock(
        a: TranslationBlock,
        b: TranslationBlock,
        xThreshold: Float,
        yThresholdFactor: Float,
    ): Boolean {
        val xDist = abs(a.x - b.x)
        val yDist = abs(a.y - b.y)
        return xDist < xThreshold * max(a.symWidth, b.symWidth) &&
            yDist < yThresholdFactor * max(a.symHeight, b.symHeight)
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
        val minX = min(a.x, b.x)
        val maxX = max(a.x + a.width, b.x + b.width)
        val minY = min(a.y, b.y)
        val maxY = max(a.y + a.height, b.y + b.height)

        val mergedText = if (a.y <= b.y) "${a.text}\n${b.text}" else "${b.text}\n${a.text}"
        return a.copy(
            text = mergedText,
            x = minX,
            y = minY,
            width = maxX - minX,
            height = maxY - minY,
        )
    }

    // ─── Collision Resolution ──────────────────────────────────────────────────

    private fun resolveCollisions(blocks: MutableList<TranslationBlock>, imgWidth: Float, imgHeight: Float) {
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
                    val overlapLeft = (movingBlock.x + movingBlock.width) - staticBlock.x
                    val overlapDown = (staticBlock.y + staticBlock.height) - movingBlock.y
                    val overlapUp = (movingBlock.y + movingBlock.height) - staticBlock.x

                    val isAngled = abs(movingBlock.angle) > 20f && abs(movingBlock.angle) < 160f
                    val directions = buildDirections(
                        overlapLeft,
                        overlapRight,
                        overlapUp,
                        overlapDown,
                        isAngled,
                        movingBlock.angle,
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
                        if (nextCollisions < bestCollisions || (nextCollisions == bestCollisions && dist < bestDist)) {
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
                        val overlapLeft2 = (movingBlock.x + movingBlock.width) - staticBlock.x
                        val overlapDown2 = (staticBlock.y + staticBlock.height) - movingBlock.y
                        val overlapUp2 = (movingBlock.y + movingBlock.height) - staticBlock.y
                        val dirs2 = buildDirections(
                            overlapLeft2,
                            overlapRight2,
                            overlapUp2,
                            overlapDown2,
                            isAngled,
                            movingBlock.angle,
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

    private fun isOverlapping(a: TranslationBlock, b: TranslationBlock): Boolean {
        return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
    }

    private fun countCollisions(block: TranslationBlock, blocks: List<TranslationBlock>, ignoreIdx: Int): Int {
        var count = 0
        for (i in blocks.indices) {
            if (i != ignoreIdx && isOverlapping(block, blocks[i])) count++
        }
        return count
    }

    private fun buildDirections(
        left: Float,
        right: Float,
        up: Float,
        down: Float,
        isAngled: Boolean,
        angle: Float,
    ): List<Pair<Float, Float>> {
        val list = mutableListOf<Pair<Float, Float>>()
        if (!isAngled) {
            list.add(Pair(-left, 0f))
            list.add(Pair(right, 0f))
            list.add(Pair(0f, -up))
            list.add(Pair(0f, down))
        } else {
            val rad = Math.toRadians(angle.toDouble())
            val cos = cos(rad).toFloat()
            val sin = sin(rad).toFloat()
            list.add(Pair(-left * cos, -left * sin))
            list.add(Pair(right * cos, right * sin))
            list.add(Pair(-up * sin, up * cos))
            list.add(Pair(down * sin, -down * cos))
        }
        return list
    }

    private fun internalClearQueue() {
        _queueState.update { queue ->
            queue.forEach { translation ->
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
