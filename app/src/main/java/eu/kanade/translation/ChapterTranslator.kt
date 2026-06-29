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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import mihon.core.archive.archiveReader
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
import kotlin.math.max
import kotlin.math.min
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
        // الحد الأقصى للارتفاع — المشكلة دائماً عمودية، لا تقطيع أفقي
        private const val MAX_OCR_HEIGHT = 1920
        // نسبة التداخل بين الـ tiles العمودية لتجنب قطع الفقاعات على الحدود
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
            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )!!

            val pages = mutableMapOf<String, PageTranslation>()
            val tmpFile = translationMangaDir.createFile("tmp")!!
            val streams = getChapterPages(chapterPath)

            withContext(Dispatchers.IO) {
                for ((fileName, streamFn) in streams) {
                    coroutineContext.ensureActive()
                    streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }

                    val pageTranslation = recognizePage(tmpFile)

                    if (pageTranslation != null && pageTranslation.blocks.isNotEmpty()) {
                        pages[fileName] = pageTranslation
                    }
                }
            }
            tmpFile.delete()
            withContext(Dispatchers.IO) { textTranslator.translate(pages) }
            Json.encodeToStream(pages, translationMangaDir.createFile(saveFile)!!.openOutputStream())
            translation.status = Translation.State.TRANSLATED
        } catch (error: Throwable) {
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error)
        }
    }

    // ─── Image Tiling ──────────────────────────────────────────────────────────

    /**
     * المدخل الرئيسي لـ OCR.
     * يقرأ ارتفاع الصورة بدون تحميلها في RAM.
     * المشكلة دائماً عمودية (صور طويلة) — لا يوجد تقطيع أفقي.
     * إذا لم يجد OCR أي فقاعات → يعيد null مباشرة (early-exit).
     */
    private fun recognizePage(tmpFile: UniFile): PageTranslation? {
        val filePath = tmpFile.filePath ?: return null

        // قراءة الأبعاد فقط — صفر RAM
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, opts)
        val origW = opts.outWidth
        val origH = opts.outHeight
        if (origW <= 0 || origH <= 0) return null

        return if (origH <= MAX_OCR_HEIGHT) {
            // الارتفاع مناسب — تحميل كامل ومعالجة مباشرة
            val bitmap = BitmapFactory.decodeFile(filePath) ?: return null
            try {
                recognizeSingleBitmap(bitmap, origW, origH)
            } finally {
                bitmap.recycle()
            }
        } else {
            // الصورة طويلة عمودياً — تقطيع عمودي فقط بـ BitmapRegionDecoder
            recognizeWithVerticalTiling(filePath, origW, origH)
        }
    }

    /**
     * OCR على صورة واحدة.
     * تعيد null إذا لم تجد أي فقاعات (early-exit).
     */
    private fun recognizeSingleBitmap(bitmap: Bitmap, origW: Int, origH: Int): PageTranslation? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.recognize(image)
        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

        // early-exit: لا فقاعات → لا داعي لأي معالجة
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

    /**
     * تقطيع عمودي فقط باستخدام BitmapRegionDecoder.
     * كل tile يُحمَّل من الديسك مباشرة — لا تُحمَّل الصورة كاملة أبداً.
     *
     * مثال: 1104×10000
     *   tileH=1920, overlap=192px, step=1728px → 6 tiles
     *   ذاكرة كل tile: ~8MB بدل ~42MB للصورة كاملة
     *
     * تعيد null إذا لم تجد أي فقاعات في كامل الصورة (early-exit).
     */
    private fun recognizeWithVerticalTiling(filePath: String, origW: Int, origH: Int): PageTranslation? {
        val allBlocks = mutableListOf<TranslationBlock>()

        val tileH    = MAX_OCR_HEIGHT
        val overlapY = (tileH * TILE_OVERLAP).toInt()
        val stepY    = tileH - overlapY
        val numTiles = ceil((origH - overlapY).toFloat() / stepY).toInt()

        val decoder = android.graphics.BitmapRegionDecoder.newInstance(filePath, false)
            ?: return null

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

                        // تجاهل الكتل في منطقة الـ overlap إلا في آخر tile
                        // (الكتلة ستظهر في الـ tile التالي بإحداثيات صحيحة)
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
                                x         = bounds.left.toFloat(),           // العرض كامل دائماً
                                y         = (tileTop + bounds.top).toFloat(), // إحداثي مطلق
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

        // early-exit: لا فقاعات في كامل الصورة
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

        // ─── توسيع الكتل لاستيعاب النص المترجم ───────────────────
        val expandedBlocks = initialBlocks.map { block ->
            val cleanedText = block.text.replace("\n", " ").trim()
            val cleanedTranslation = block.translation?.replace("\n", " ")?.trim() ?: ""

            val textRatio = (cleanedTranslation.length.toFloat() / cleanedText.length.coerceAtLeast(1))
                .coerceIn(1.0f, 1.25f)
            val finalScale = sqrt(textRatio.toDouble()).toFloat()

            var newWidth = block.width * finalScale
            var newHeight = block.height * finalScale * 1.3f  // توسيع عمودي

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

        // ─── Collision Resolution (مُصلَح) ────────────────────────
        resolveCollisions(expandedBlocks, imgWidth, imgHeight)

        return expandedBlocks
    }

    // ─── Collision Resolution ──────────────────────────────────────────────────

    /**
     * خوارزمية حل التصادمات المُصلَحة:
     * ١. تصحيح bug moveUpAmt (كان يساوي moveDownAmt)
     * ٢. الاتجاهات تأخذ بعين الاعتبار زاوية الكتلة
     * ٣. الكتل المائلة تتحرك بمحاذاة محورها
     * ٤. إذا كانت كل الاتجاهات مسدودة نصغّر ثم نحاول مجدداً
     */
    private fun resolveCollisions(
        blocks: MutableList<TranslationBlock>,
        imgWidth: Float,
        imgHeight: Float,
    ) {
        val iterations = 6  // زيادة من 4 إلى 6

        for (step in 0 until iterations) {
            var collisionsResolved = 0

            for (idx in blocks.indices) {
                for (jdx in idx + 1 until blocks.size) {
                    val a = blocks[idx]
                    val b = blocks[jdx]
                    if (!isOverlapping(a, b)) continue

                    collisionsResolved++

                    // الكتلة الأصغر مساحةً هي التي تتحرك
                    val aArea = a.width * a.height
                    val bArea = b.width * b.height
                    val movingIdx = if (aArea <= bArea) idx else jdx
                    val staticBlock = if (movingIdx == idx) b else a
                    var movingBlock = blocks[movingIdx]

                    // ─── حساب مقدار الإزاحة الصحيح لكل اتجاه ───
                    val overlapRight = (staticBlock.x + staticBlock.width) - movingBlock.x
                    val overlapLeft  = (movingBlock.x + movingBlock.width) - staticBlock.x
                    val overlapDown  = (staticBlock.y + staticBlock.height) - movingBlock.y
                    val overlapUp    = (movingBlock.y + movingBlock.height) - staticBlock.y  // ← BUG FIX

                    // ─── اتجاهات مرتبة من الأقل إزاحة للأكثر ───
                    val isAngled = abs(movingBlock.angle) > 20f && abs(movingBlock.angle) < 160f
                    val directions = buildDirections(
                        overlapLeft, overlapRight, overlapUp, overlapDown, isAngled, movingBlock.angle
                    )

                    // ─── اختبار كل اتجاه ───
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

                    // ─── إذا كل الاتجاهات تسبب تصادمات: نصغّر ───
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
                        // أعد المحاولة بعد التصغير
                        val overlapRight2 = (staticBlock.x + staticBlock.width) - movingBlock.x
                        val overlapLeft2  = (movingBlock.x + movingBlock.width) - staticBlock.x
                        val overlapDown2  = (staticBlock.y + staticBlock.height) - movingBlock.y
                        val overlapUp2    = (movingBlock.y + movingBlock.height) - staticBlock.y
                        val dirs2 = buildDirections(
                            overlapLeft2, overlapRight2, overlapUp2, overlapDown2, isAngled, movingBlock.angle
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

    /**
     * بناء قائمة الاتجاهات مع مراعاة زاوية الكتلة.
     * الاتجاهات مرتبة من الأقل إزاحة للأكثر.
     */
    private fun buildDirections(
        overlapLeft: Float,
        overlapRight: Float,
        overlapUp: Float,
        overlapDown: Float,
        isAngled: Boolean,
        angle: Float,
    ): List<Pair<Float, Float>> {
        val baseDirections = listOf(
            Pair(-overlapLeft,  0f),           // يسار
            Pair( overlapRight, 0f),            // يمين
            Pair(0f,           -overlapUp),     // أعلى   ← مُصلَح
            Pair(0f,            overlapDown),   // أسفل
            Pair(-overlapLeft, -overlapUp),     // يسار-أعلى
            Pair( overlapRight, -overlapUp),    // يمين-أعلى
            Pair(-overlapLeft,  overlapDown),   // يسار-أسفل
            Pair( overlapRight, overlapDown),   // يمين-أسفل
        )

        return if (isAngled) {
            // للكتل المائلة: أضف اتجاهات موازية للمحور الرئيسي
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
