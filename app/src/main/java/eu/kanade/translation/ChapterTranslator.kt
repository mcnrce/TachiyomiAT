package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
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
    private var textRecognizer: TextRecognizer
    private var textTranslator: TextTranslator

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

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
                    val activeTranslations = queue.asSequence()
                        .filter { it.status.value <= Translation.State.TRANSLATING.value }
                        .groupBy { it.source }
                        .toList()
                        .take(5)
                        .map { (_, translations) -> translations.first() }
                    emit(activeTranslations)

                    if (activeTranslations.isEmpty()) break
                    val errored = combine(activeTranslations.map(Translation::statusFlow)) { states ->
                        states.contains(Translation.State.ERROR)
                    }.filter { it }
                    errored.first()
                }
            }.distinctUntilChanged()
            supervisorScope {
                val jobs = mutableMapOf<Translation, Job>()
                activeTranslationFlow.collectLatest { active ->
                    jobs.filter { it.key !in active }.forEach { (_, job) -> job.cancel() }
                    jobs.keys.retainAll(active)
                    active.filter { it !in jobs }.forEach { t -> jobs[t] = launchTranslationJob(t) }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(t: Translation) = launchIO {
        try {
            translateChapter(t)
            if (t.status == Translation.State.TRANSLATED) removeFromQueue(t)
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
        addToQueue(Translation(source, manga, chapter, fromLang, toLang))
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
        val mangaDir = provider.getMangaDir(translation.manga.title, translation.source)
        val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)
        val path = downloadProvider.findChapterDir(
            translation.chapter.name,
            translation.chapter.scanlator,
            translation.manga.title,
            translation.source,
        )!!

        val pages = mutableMapOf<String, PageTranslation>()
        val tmp = mangaDir.createFile("tmp")!!
        val streams = getChapterPages(path)

        withContext(Dispatchers.IO) {
            for ((name, streamFn) in streams) {
                coroutineContext.ensureActive()
                streamFn().use { tmp.openOutputStream().use { out -> it.copyTo(out) } }
                val img = InputImage.fromFilePath(context, tmp.uri)
                
                // ✅ الحل: استخدام getEnhancedBitmap مباشرة
                val bitmap = textRecognizer.getEnhancedBitmap(img)
                val result = textRecognizer.recognize(img)
                val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
                val pt = convertToPageTranslation(blocks, bitmap.width, bitmap.height)

                // OCR ثانوي على كل كتلة
                for (block in pt.blocks) {
                    try {
                        val cx = block.x.toInt().coerceIn(0, bitmap.width - 1)
                        val cy = block.y.toInt().coerceIn(0, bitmap.height - 1)
                        val cw = block.width.toInt().coerceAtMost(bitmap.width - cx).coerceAtLeast(1)
                        val ch = block.height.toInt().coerceAtMost(bitmap.height - cy).coerceAtLeast(1)
                        val cropped = Bitmap.createBitmap(bitmap, cx, cy, cw, ch)
                        val res2 = textRecognizer.recognize(InputImage.fromBitmap(cropped, 0))
                        if (res2.textBlocks.isNotEmpty()) {
                            val txt = res2.textBlocks.joinToString(" ") { it.text.trim() }
                            if (txt.length > block.text.length) block.text = txt
                        }
                        cropped.recycle()
                    } catch (e: Exception) { 
                        logcat(LogPriority.WARN, e) { "Failed second OCR" } 
                    }
                }
                
                // تطبيق Scale Factor
                pt.blocks.forEach { b ->
                    b.x /= TextRecognizer.SCALE_FACTOR
                    b.y /= TextRecognizer.SCALE_FACTOR
                    b.width /= TextRecognizer.SCALE_FACTOR
                    b.height /= TextRecognizer.SCALE_FACTOR
                }
                pt.imgWidth /= TextRecognizer.SCALE_FACTOR
                pt.imgHeight /= TextRecognizer.SCALE_FACTOR
                
                if (pt.blocks.isNotEmpty()) pages[name] = pt
                bitmap.recycle()
            }
        }
        tmp.delete()
        withContext(Dispatchers.IO) { textTranslator.translate(pages) }
        Json.encodeToStream(pages, mangaDir.createFile(saveFile)!!.openOutputStream())
        translation.status = Translation.State.TRANSLATED
    } catch (e: Throwable) {
        translation.status = Translation.State.ERROR
        logcat(LogPriority.ERROR, e)
    }
    }

    private fun convertToPageTranslation(blocks: List<Text.TextBlock>, w: Int, h: Int): PageTranslation {
        val pt = PageTranslation(imgWidth = w.toFloat(), imgHeight = h.toFloat())
        blocks.forEach { b ->
            val bounds = b.boundingBox!!
            val sym = b.lines.first().elements.first().symbols.first().boundingBox!!
            pt.blocks.add(
                TranslationBlock(
                    text = b.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    symWidth = sym.width().toFloat(),
                    symHeight = sym.height().toFloat(),
                    angle = b.lines.first().angle,
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                ),
            )
        }
        pt.blocks = smartMergeBlocks(pt.blocks, w.toFloat(), h.toFloat())
        return pt
    }

    private fun smartMergeBlocks(
        blocks: List<TranslationBlock>,
        w: Float,
        h: Float,
    ): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()
        val isW = h > 2300f || h > (w * 2f)
        val initial = blocks.filter { it.text.isNotBlank() }.toMutableList()
        val xTh = (2.5f * (w / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
        val yTh = (1.6f * (h / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

        var i = 0
        while (i < initial.size) {
            var j = i + 1
            var merged = false
            while (j < initial.size) {
                if (shouldMergeTextBlock(initial[i], initial[j], xTh, yTh)) {
                    initial[i] = mergeTextBlock(initial[i], initial[j], isW)
                    initial.removeAt(j)
                    i = 0
                    merged = true
                    break
                }
                j++
            }
            if (!merged) i++
        }
        return initial
    }

    private fun shouldMergeTextBlock(r1: TranslationBlock, r2: TranslationBlock, xT: Float, yT: Float): Boolean {
        val aDiff = abs(r1.angle - r2.angle)
        if (!(aDiff < 15 || abs(aDiff - 180) < 15)) return false
        val isV = abs(r1.angle) in 70.0..110.0
        val sH = maxOf(r1.symHeight, r2.symHeight, 12f)
        val sW = maxOf(r1.symWidth, r2.symWidth, 12f)
        val yOv = maxOf(0f, minOf(r1.y + r1.height, r2.y + r2.height) - maxOf(r1.y, r2.y))
        val xOv = maxOf(0f, minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x))
        if (!(xOv >= (minOf(r1.width, r2.width) * 0.95f) || yOv >= (minOf(r1.height, r2.height) * 0.75f))) return false
        return if (isV) {
            val side = if (r1.x < r2.x) r2.x - (r1.x + r1.width) else r1.x - (r2.x + r2.width)
            side <= (sW * 1.1f)
        } else {
            val vGap = maxOf(0f, if (r1.y < r2.y) r2.y - (r1.y + r1.height) else r1.y - (r2.y + r2.height))
            vGap <= (sH * 1.1f)
        }
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock, isW: Boolean): TranslationBlock {
        val minX = minOf(a.x, b.x)
        val minY = minOf(a.y, b.y)
        val maxX = maxOf(a.x + a.width, b.x + b.width)
        val maxY = maxOf(a.y + a.height, b.y + b.height)
        val ordered = if (abs(a.angle) in 70.0..110.0) {
            if (a.x > b.x) listOf(a, b) else listOf(b, a)
        } else if (abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f) {
            if (a.y < b.y) listOf(a, b) else listOf(b, a)
        } else if (isW) {
            if (a.x < b.x) listOf(a, b) else listOf(b, a)
        } else {
            if (a.x > b.x) listOf(a, b) else listOf(b, a)
        }
        val tLen = (a.text.length + b.text.length).coerceAtLeast(1)
        return TranslationBlock(
            text = ordered.joinToString(" ") { it.text.trim() },
            translation = ordered.joinToString(" ") { it.translation?.trim() ?: "" }.trim(),
            width = maxX - minX,
            height = maxY - minY,
            x = minX,
            y = minY,
            angle = if (a.text.length >= b.text.length) a.angle else b.angle,
            symWidth = (a.symWidth * a.text.length + b.symWidth * b.text.length) / tLen,
            symHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / tLen,
        )
    }

    private fun getChapterPages(path: UniFile): List<Pair<String, () -> InputStream>> {
        return if (path.isFile) {
            path.archiveReader(context).useEntries { e ->
                e.filter { it.isFile && ImageUtil.isImage(it.name) { path.archiveReader(context).getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                    .map { Pair(it.name) { path.archiveReader(context).getInputStream(it.name)!! } }.toList()
            }
        } else {
            path.listFiles()!!.filter { ImageUtil.isImage(it.name) }
                .map { Pair(it.name!!) { it.openInputStream() } }.toList()
        }
    }

    private fun areAllTranslationsFinished() = queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    private fun addToQueue(t: Translation) { t.status = Translation.State.QUEUE; _queueState.update { it + t } }
    private fun removeFromQueue(t: Translation) {
        _queueState.update { q ->
            if (t.status == Translation.State.TRANSLATING || t.status == Translation.State.QUEUE) t.status = Translation.State.NOT_TRANSLATED
            q - t
        }
    }

    private fun removeFromQueueIf(p: (Translation) -> Boolean) {
        _queueState.update { q ->
            val ts = q.filter(p)
            ts.forEach { if (it.status == Translation.State.TRANSLATING || it.status == Translation.State.QUEUE) it.status = Translation.State.NOT_TRANSLATED }
            q - ts
        }
    }

    fun removeFromQueue(c: Chapter) = removeFromQueueIf { it.chapter.id == c.id }
    fun removeFromQueue(m: Manga) = removeFromQueueIf { it.manga.id == m.id }
    private fun internalClearQueue() {
        _queueState.update { q ->
            q.forEach { if (it.status == Translation.State.TRANSLATING || it.status == Translation.State.QUEUE) it.status = Translation.State.NOT_TRANSLATED }
            emptyList()
        }
    }
}
