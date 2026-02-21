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
                    translationJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        translationJobs.remove(download)
                    }

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
            // Check if recognizer reinitialization is needed
            if (translation.fromLang != textRecognizer.language) {
                textRecognizer.close()
                textRecognizer = TextRecognizer(translation.fromLang)
            }
            // Check if translator reinitialization is needed
            if (translation.fromLang != textTranslator.fromLang || translation.toLang != textTranslator.toLang) {
                withContext(Dispatchers.IO) {
                    textTranslator.close()
                }
                textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
                    .build(translationPreferences, translation.fromLang, translation.toLang)
            }
            // Directory where translations for a manga is stored
            val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)

            // translations save file
            val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)

            // Directory where chapter images is stored
            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            )!!

            val pages = mutableMapOf<String, PageTranslation>()
            val tmpFile = translationMangaDir.createFile("tmp")!!
            val streams = getChapterPages(chapterPath)
            /**
             * saving the stream to tmp file cuz i can't get the
             * BitmapFactory.decodeStream() to work with the stream from .cbz archive
             */
            withContext(Dispatchers.IO) {
                for ((fileName, streamFn) in streams) {
                    coroutineContext.ensureActive()
                    streamFn().use { tmpFile.openOutputStream().use { out -> it.copyTo(out) } }
                    val image = InputImage.fromFilePath(context, tmpFile.uri)
                    val result = textRecognizer.recognize(image)
                    val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
                    val pageTranslation = convertToPageTranslation(blocks, image.width, image.height)
                    if (pageTranslation.blocks.isNotEmpty()) pages[fileName] = pageTranslation
                }
            }
            tmpFile.delete()
            withContext(Dispatchers.IO) {
                // Translate the text in blocks , this mutates the original blocks
                textTranslator.translate(pages)
            }
            // Serialize the Map and save to translations json file
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
        val symBounds = block.lines.first().elements.first().symbols.first().boundingBox!!
        translation.blocks.add(
            TranslationBlock(
                text = block.text,
                width = bounds.width().toFloat(),
                height = bounds.height().toFloat(),
                symWidth = symBounds.width().toFloat(),
                symHeight = symBounds.height().toFloat(),
                angle = block.lines.first().angle,
                x = bounds.left.toFloat(),
                y = bounds.top.toFloat(),
            ),
        )
    }
    
    // التعديل هنا: نمرر عرض وطول الصورة بدلاً من أرقام ثابتة
    translation.blocks = smartMergeBlocks(translation.blocks, width.toFloat(), height.toFloat())

    return translation
}


    
            

private fun smartMergeBlocks(
    blocks: List<TranslationBlock>,
    imgWidth: Float,
    imgHeight: Float
): MutableList<TranslationBlock> {
    if (blocks.isEmpty()) return mutableListOf()

    // 1. تنظيف أولي
    val filteredBlocks = blocks.filter { it.text.isNotBlank() }
    
    val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)
    var initialBlocks = filteredBlocks.toMutableList()
    
    // إعدادات العتبات
    val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
    val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

    // المرحلة 1: الدمج
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

    // المرحلة 2: التوسيع الموزون
    val minSafeHeight = 25f 
    val MAX_SCALE_LIMIT = 1.25f 

    val expandedBlocks = initialBlocks.map { block ->
        val cleanedText = block.text.replace("\n", " ").trim()
        val cleanedTranslation = block.translation?.replace("\n", " ")?.trim() ?: ""

        val textRatio = (cleanedTranslation.length.toFloat() / cleanedText.length.coerceAtLeast(1))
            .coerceIn(1.0f, 1.25f)

        val fontRatio = (minSafeHeight / block.symHeight.coerceAtLeast(10f))
            .coerceAtLeast(1.0f)

        var finalScale = kotlin.math.sqrt((textRatio * fontRatio).toDouble()).toFloat()
        finalScale = finalScale.coerceAtMost(MAX_SCALE_LIMIT)

        if (finalScale > 1.02f) {
            val newWidth = block.width * finalScale
            val newHeight = block.height * finalScale
            
            var newX = block.x - (newWidth - block.width) / 2f
            var newY = block.y - (newHeight - block.height) / 2f
            
            if (newX < 0) newX = 0f
            if (newX + newWidth > imgWidth) newX = (imgWidth - newWidth).coerceAtLeast(0f)
            if (newY < 0) newY = 0f
            if (newY + newHeight > imgHeight) newY = (imgHeight - newHeight).coerceAtLeast(0f)

            block.copy(
                text = cleanedText,
                translation = cleanedTranslation,
                width = newWidth,
                height = newHeight,
                x = newX,
                y = newY
            )
        } else {
            block.copy(text = cleanedText, translation = cleanedTranslation)
        }
    }.toMutableList()

    // المرحلة 3: حل التصادم
    for (idx in expandedBlocks.indices) {
        for (jdx in idx + 1 until expandedBlocks.size) {
            val a = expandedBlocks[idx]
            val b = expandedBlocks[jdx]
            
            if (isOverlapping(a, b)) {
                val overlapX = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
                val overlapY = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
                
                if (overlapX < overlapY) {
                    val shift = (overlapX / 2f) + 1f
                    if (a.x < b.x) {
                        expandedBlocks[idx] = expandedBlocks[idx].copy(width = (a.width - shift).coerceAtLeast(10f))
                        expandedBlocks[jdx] = expandedBlocks[jdx].copy(width = (b.width - shift).coerceAtLeast(10f), x = b.x + shift)
                    } else {
                        expandedBlocks[idx] = expandedBlocks[idx].copy(width = (a.width - shift).coerceAtLeast(10f), x = a.x + shift)
                        expandedBlocks[jdx] = expandedBlocks[jdx].copy(width = (b.width - shift).coerceAtLeast(10f))
                    }
                } else {
                    val shift = (overlapY / 2f) + 1f
                    if (a.y < b.y) {
                        expandedBlocks[idx] = expandedBlocks[idx].copy(height = (a.height - shift).coerceAtLeast(10f))
                        expandedBlocks[jdx] = expandedBlocks[jdx].copy(height = (b.height - shift).coerceAtLeast(10f), y = b.y + shift)
                    } else {
                        expandedBlocks[idx] = expandedBlocks[idx].copy(height = (a.height - shift).coerceAtLeast(10f), y = a.y + shift)
                        expandedBlocks[jdx] = expandedBlocks[jdx].copy(height = (b.height - shift).coerceAtLeast(10f))
                    }
                }
            }
        }
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
    a: TranslationBlock,
    b: TranslationBlock,
    xThreshold: Float,
    yThresholdFactor: Float
): Boolean {
    val angleDiff = abs(a.angle - b.angle)
    if (!(angleDiff < 10 || abs(angleDiff - 180) < 10)) return false

    val isVertical = abs(a.angle) in 70.0..110.0 // النطاق الأصلي للمانجا
    
    val aRight = a.x + a.width
    val bRight = b.x + b.width
    val aBottom = a.y + a.height
    val bBottom = b.y + b.height

    val sW = maxOf(a.symWidth, b.symWidth, 12f)
    val sH = maxOf(a.symHeight, b.symHeight, 12f)

    val hOverlap = minOf(aRight, bRight) - maxOf(a.x, b.x)
    val vOverlap = minOf(aBottom, bBottom) - maxOf(a.y, b.y)
    val hGap = maxOf(0f, if (a.x < b.x) b.x - aRight else a.x - bRight)
    val vGap = maxOf(0f, if (a.y < b.y) b.y - aBottom else a.y - bBottom)

    return if (isVertical) {
        /* --- خوارزمية المانجا العمودية الأصلية --- */
        val dx = abs(a.x - b.x)
        val dy = abs(a.y - b.y)
        
        val originsClose = dy < (sH * 2.2f) && dx < (sW * 4.5f)
        val sideBySide = hGap < (sW * 2.5f) && dy < (sH * 2.2f)
        val aligned = vOverlap > (sH * 0.15f) && hGap < (sW * 2.2f)
        
        originsClose || sideBySide || aligned
    } else {
        /* --- منطق الأسطر الأفقية الصارم --- */
        val centerDiff = abs((a.x + a.width / 2f) - (b.x + b.width / 2f))
        val isStacked = centerDiff < (maxOf(a.width, b.width) * 0.45f) && 
                        vGap < (sH * 0.5f * yThresholdFactor)

        val minWidth = minOf(a.width, b.width)
        val hasOverlapMerge = hOverlap > (minWidth * 0.2f) && vGap < (sH * 0.4f)

        isStacked || hasOverlapMerge
    }
}



     



    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
    val minX = minOf(a.x, b.x)
    val minY = minOf(a.y, b.y)
    val maxX = maxOf(a.x + a.width, b.x + b.width)
    val maxY = maxOf(a.y + a.height, b.y + b.height)

    var finalWidth = maxX - minX
    var finalX = minX
    val isVertical = abs(a.angle) in 70.0..110.0

    // توسيع العرض للترجمة العربية (30%)
    if (isVertical) {
        val expansion = finalWidth * 0.30f
        finalWidth += expansion
        finalX -= expansion / 2f 
    }

    // ترتيب النص (يمين ليسار للمانجا)
    val ordered = if (isVertical) {
        if (a.x > b.x) listOf(a, b) else listOf(b, a)
    } else {
        if (abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f) {
            if (a.y < b.y) listOf(a, b) else listOf(b, a)
        } else {
            if (isWebtoon) (if (a.x < b.x) listOf(a, b) else listOf(b, a))
            else (if (a.x > b.x) listOf(a, b) else listOf(b, a))
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
        symHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / totalLen
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
        _queueState.update {
            it + translation
        }
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
