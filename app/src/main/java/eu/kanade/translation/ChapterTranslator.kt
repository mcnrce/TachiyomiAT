package eu.kanade.translation

import android.content.Context
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

    init {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        textRecognizer = TextRecognizer(fromLang)
        textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
            .build(translationPreferences, fromLang, toLang)
    }

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

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
                        queue.asSequence().filter { it.status.value <= Translation.State.TRANSLATING.value }
                            .groupBy { it.source }.toList().take(5).map { (_, translations) -> translations.first() }
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
                    translationJobsToStop.forEach { (_, job) ->
                        job.cancel()
                    }
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
            if (translation.status == Translation.State.TRANSLATED) {
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
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
                withContext(Dispatchers.IO) {
                    textTranslator.close()
                }
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
                    val image = InputImage.fromFilePath(context, tmpFile.uri)

                    val result = textRecognizer.recognize(image)
                    val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

                    val enhancedWidth = image.width * TextRecognizer.SCALE_FACTOR
                    val enhancedHeight = image.height * TextRecognizer.SCALE_FACTOR
                    val pageTranslation = convertToPageTranslation(blocks, enhancedWidth, enhancedHeight)

                    for (block in pageTranslation.blocks) {
                        block.x /= TextRecognizer.SCALE_FACTOR
                        block.y /= TextRecognizer.SCALE_FACTOR
                        block.width /= TextRecognizer.SCALE_FACTOR
                        block.height /= TextRecognizer.SCALE_FACTOR
                    }
                    pageTranslation.imgWidth /= TextRecognizer.SCALE_FACTOR
                    pageTranslation.imgHeight /= TextRecognizer.SCALE_FACTOR

                    // تعيين المحرك المستخدم كـ ML Kit فقط
                    pageTranslation.engineUsed = "mlkit"

                    if (pageTranslation.blocks.isNotEmpty()) pages[fileName] = pageTranslation
                }
            }
            tmpFile.delete()
            withContext(Dispatchers.IO) {
                textTranslator.translate(pages)
            }
            Json.encodeToStream(pages, translationMangaDir.createFile(saveFile)!!.openOutputStream())
            translation.status = Translation.State.TRANSLATED
        } catch (error: Throwable) {
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error)
        }
    }

    private fun convertToPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation {
        val translation = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        for (block in blocks) {
            val bounds = block.boundingBox!!
            val firstLine = block.lines.firstOrNull()
            val firstElement = firstLine?.elements?.firstOrNull()
            val symBounds = firstElement?.symbols?.firstOrNull()?.boundingBox ?: bounds
            
            translation.blocks.add(
                TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    symWidth = symBounds.width().toFloat(),
                    symHeight = symBounds.height().toFloat(),
                    angle = firstLine?.angle ?: 0f,
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                ),
            )
        }
        translation.blocks = smartMergeBlocks(translation.blocks, width.toFloat(), height.toFloat())

        val filteredWords = translationPreferences.translationFilteredWords().get()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        translation.blocks = translation.blocks.filter { block ->
            val blockText = block.text.trim()
            val letters = blockText.filter { it.isLetter() }

            val isAllEnglish = letters.isNotEmpty() && letters.all { it in 'A'..'Z' || it in 'a'..'z' }
            if (isAllEnglish) {
                val uniqueLetters = letters.lowercase().toSet().size
                if (uniqueLetters < 3) return@filter false
            }

            if (filteredWords.isNotEmpty()) {
                val blockLower = blockText.lowercase()
                if (filteredWords.any { word -> blockLower == word }) return@filter false
            }

            true
        }.toMutableList()

        return translation
    }

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
            val finalScale = kotlin.math.sqrt(textRatio.toDouble()).toFloat()

            var newWidth = block.width * finalScale
            var newHeight = block.height * finalScale

            val verticalBonus = 1.3f
            newHeight *= verticalBonus

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

        val iterations = 4
        for (step in 0 until iterations) {
            var collisionsResolved = 0
            for (idx in expandedBlocks.indices) {
                for (jdx in idx + 1 until expandedBlocks.size) {
                    val a = expandedBlocks[idx]
                    val b = expandedBlocks[jdx]

                    if (isOverlapping(a, b)) {
                        collisionsResolved++

                        val aArea = a.width * a.height
                        val bArea = b.width * b.height
                        val movingIdx = if (aArea <= bArea) idx else jdx
                        val staticBlock = if (movingIdx == idx) b else a
                        var movingBlock = expandedBlocks[movingIdx]

                        val moveRightAmt = (staticBlock.x + staticBlock.width) - movingBlock.x + 2f
                        val moveLeftAmt = (movingBlock.x + movingBlock.width) - staticBlock.x + 2f
                        val moveDownAmt = (staticBlock.y + staticBlock.height) - movingBlock.y + 2f
                        val moveUpAmt = (staticBlock.y + staticBlock.height) - movingBlock.y + 2f

                        var directions = listOf(
                            Pair(-moveLeftAmt, 0f),
                            Pair(moveRightAmt, 0f),
                            Pair(0f, -moveUpAmt),
                            Pair(0f, moveDownAmt),
                            Pair(-moveLeftAmt, -moveUpAmt),
                            Pair(moveRightAmt, -moveUpAmt),
                            Pair(-moveLeftAmt, moveDownAmt),
                            Pair(moveRightAmt, moveDownAmt),
                        )

                        var allSidesBlocked = true
                        for (d in directions.indices) {
                            val testX = (movingBlock.x + directions[d].first).coerceIn(0f, imgWidth - movingBlock.width)
                            val testY = (movingBlock.y + directions[d].second).coerceIn(
                                0f,
                                imgHeight - movingBlock.height,
                            )
                            val testedBlock = movingBlock.copy(x = testX, y = testY)

                            var hasCollision = false
                            for (k in expandedBlocks.indices) {
                                if (k != idx && k != jdx && isOverlapping(testedBlock, expandedBlocks[k])) {
                                    hasCollision = true
                                    break
                                }
                            }
                            if (!hasCollision) {
                                allSidesBlocked = false
                                break
                            }
                        }

                        if (allSidesBlocked) {
                            val shrinkFactor = 0.85f
                            val newWidth = movingBlock.width * shrinkFactor
                            val newHeight = movingBlock.height * shrinkFactor
                            val newX = movingBlock.x + (movingBlock.width - newWidth) / 2f
                            val newY = movingBlock.y + (movingBlock.height - newHeight) / 2f
                            movingBlock = movingBlock.copy(width = newWidth, height = newHeight, x = newX, y = newY)

                            val adjRight = (staticBlock.x + staticBlock.width) - movingBlock.x + 1f
                            val adjLeft = (movingBlock.x + movingBlock.width) - staticBlock.x + 1f
                            val adjDown = (staticBlock.y + staticBlock.height) - movingBlock.y + 1f
                            val adjUp = (staticBlock.y + staticBlock.height) - movingBlock.y + 1f
                            directions = listOf(
                                Pair(-adjLeft, 0f),
                                Pair(adjRight, 0f),
                                Pair(0f, -adjUp),
                                Pair(0f, adjDown),
                                Pair(-adjLeft, -adjUp),
                                Pair(adjRight, -adjUp),
                                Pair(-adjLeft, adjDown),
                                Pair(adjRight, adjDown),
                            )
                        }

                        var bestDirection = 0
                        var minNextCollisions = Int.MAX_VALUE
                        var minMoveAmount = Float.MAX_VALUE

                        for (d in directions.indices) {
                            val testX = (movingBlock.x + directions[d].first).coerceIn(0f, imgWidth - movingBlock.width)
                            val testY = (movingBlock.y + directions[d].second).coerceIn(
                                0f,
                                imgHeight - movingBlock.height,
                            )
                            val testedBlock = movingBlock.copy(x = testX, y = testY)

                            var nextCollisions = 0
                            for (k in expandedBlocks.indices) {
                                if (k != idx && k != jdx && isOverlapping(testedBlock, expandedBlocks[k])) {
                                    nextCollisions++
                                }
                            }

                            val moveDist = abs(directions[d].first) + abs(directions[d].second)
                            if (nextCollisions < minNextCollisions ||
                                (nextCollisions == minNextCollisions && moveDist < minMoveAmount)
                            ) {
                                minNextCollisions = nextCollisions
                                minMoveAmount = moveDist
                                bestDirection = d
                            }
                        }

                        val finalX = (movingBlock.x + directions[bestDirection].first).coerceIn(
                            0f,
                            imgWidth - movingBlock.width,
                        )
                        val finalY = (movingBlock.y + directions[bestDirection].second).coerceIn(
                            0f,
                            imgHeight - movingBlock.height,
                        )
                        expandedBlocks[movingIdx] = movingBlock.copy(x = finalX, y = finalY)
                    }
                }
            }
            if (collisionsResolved == 0) break
        }
        return expandedBlocks
    }

    private fun isOverlapping(a: TranslationBlock, b: TranslationBlock): Boolean {
        return a.x < b.x + b.width &&
            a.x + a.width > b.x &&
            a.y < b.y + b.height &&
            a.y + a.height > b.y
    }

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

        val sH = maxOf(r1.symHeight, r2.symHeight, 12f)
        val sW = maxOf(r1.symWidth, r2.symWidth, 12f)

        val maxAllowedGapX = sW * 1.2f
        val maxAllowedGapY = sH * 1.2f

        val yOverlap = maxOf(0f, minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y))
        val xOverlap = maxOf(0f, minOf(r1Right, r2Right) - maxOf(r1.x, r2.x))

        val minHeight = minOf(r1.height, r2.height)
        val isFullVerticalCover = yOverlap >= (minHeight * 0.75f)

        val minWidth = minOf(r1.width, r2.width)
        val isFullHorizontalCover = xOverlap >= (minWidth * 0.95f)
        val isInCross = isFullHorizontalCover || isFullVerticalCover
        if (!isInCross) return false

        if (isVertical) {
            val sideGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
            if (sideGap > maxAllowedGapX) return false

            if (isFullVerticalCover && sideGap <= 0f) {
                return true
            }

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
            val vGap = maxOf(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
            if (vGap > maxAllowedGapY) return false

            if (isFullHorizontalCover) {
                if (vGap <= maxAllowedGapY) return true
            }

            val sideGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
            if (sideGap > maxAllowedGapX) return false

            val hasHighSideOverlap = xOverlap > (minWidth * 0.70f)
            val centerR1X = r1.x + r1.width / 2f
            val centerR2X = r2.x + r2.width / 2f
            val centersAligned = abs(centerR1X - centerR2X) < maxOf(r1.width, r2.width) * 0.35f
            val isTouching = vGap <= maxAllowedGapY

            return (hasHighSideOverlap || centersAligned) && isTouching
        }
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
        val minX = minOf(a.x, b.x)
        val minY = minOf(a.y, b.y)
        val maxX = maxOf(a.x + a.width, b.x + b.width)
        val maxY = maxOf(a.y + a.height, b.y + b.height)

        val finalWidth = maxX - minX
        val finalX = minX
        val isVertical = abs(a.angle) in 70.0..110.0

        val ordered = if (isVertical) {
            if (a.x > b.x) listOf(a, b) else listOf(b, a)
        } else {
            if (abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f) {
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
            width = finalWidth,
            height = maxY - minY,
            x = finalX,
            y = minY,
            angle = if (a.text.length >= b.text.length) a.angle else b.angle,
            symWidth = (a.symWidth * a.text.length + b.symWidth * b.text.length) / totalLen,
            symHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / totalLen,
        )
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            val reader = chapterPath.archiveReader(context)
            return reader.useEntries { entries ->
                entries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                        Pair(entry.name) { reader.getInputStream(entry.name)!! }
                    }.toList()
            }
        } else {
            return chapterPath.listFiles()!!.filter { ImageUtil.isImage(it.name) }.map { entry ->
                Pair(entry.name!!) { entry.openInputStream() }
            }.toList()
        }
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        translation.status = Translation.State.QUEUE
        _queueState.update { it + translation }
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
            if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
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
