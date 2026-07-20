package eu.kanade.translation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.tasks.await
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// كلاس يحدد اللغة وهل هي مؤكدة أم تحتاج تصويت
data class ResolvedLanguage(
    val language: TextRecognizerLanguage,
    val isDefinitive: Boolean // true إذا كانت من تفضيل المستخدم أو العنوان
)

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

    private val jsonWriteMutex = Mutex()

    private val finishRequests = ConcurrentHashMap<Long, AtomicBoolean>()

    private val longImageSemaphore = Semaphore(1)

    @Volatile
    private var rollingAvgBubbles = 4.0f
    private val rollingAvgLock = Any()

    private fun updateRollingAvg(count: Int) {
        synchronized(rollingAvgLock) {
            val historySize = translationPreferences.lowBubbleHistorySize().get().coerceAtLeast(1).toFloat()
            val alpha = 1.0f / historySize
            rollingAvgBubbles = (rollingAvgBubbles * (1.0f - alpha)) + (count * alpha)
        }
    }

    private suspend fun <T> withRetry(
        times: Int = 2,
        initialDelayMs: Long = 1000,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        repeat(times - 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "فشل في محاولة $attempt، إعادة المحاولة بعد $currentDelay مللي ثانية..." }
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return block()
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

    fun forceRemoveRealtimeTranslation(chapterId: Long) {
        _queueState.update { queue ->
            val toCancel = queue.filter { it.chapter.id == chapterId && it.isRealtimeMode }
            toCancel.forEach { translation ->
                translation.status = Translation.State.NOT_TRANSLATED
                translation.takePageStreams()
                translation.existingPages.clear()
            }
            queue - toCancel.toSet()
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

                    val activeTranslationsFinishedFlow =
                        combine(activeTranslations.map(Translation::statusFlow)) { states ->
                            states.all { it == Translation.State.TRANSLATED || it == Translation.State.ERROR }
                        }.filter { it }
                    activeTranslationsFinishedFlow.first()
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
            logcat(LogPriority.ERROR, e) { "خطأ غير متوقع في جوب الترجمة" }
            translation.status = Translation.State.ERROR
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
        if (!validateEngineSupportsLanguage()) return

        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val hasOverride = mangaTranslationPreferences.hasOverride(manga.id).get()

        if (!hasOverride && shouldSkipTranslation(source.lang, toLang)) {
            return
        }

        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val translation = Translation(source, manga, chapter, fromLang, toLang, isRealtimeMode = false)
        addToQueue(translation)
    }

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

        val hasOverride = mangaTranslationPreferences.hasOverride(manga.id).get()
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())

        if (!hasOverride && shouldSkipTranslation(source.lang, toLang)) {
            return
        }

        scope.launch {
            val resolvedLang = resolveSourceLanguageForManga(manga.id, source, manga.title)

            val existingOnDisk = provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source)
                ?.let { runCatching { readTranslationFile(it) }.getOrNull() }
                ?: emptyMap()

            val translation = Translation(
                source = source,
                manga = manga,
                chapter = chapter,
                fromLang = resolvedLang.language,
                toLang = toLang,
                isRealtimeMode = true,
            )
            translation.existingPages.putAll(existingOnDisk)
            translation.addPageStreams(pageStreams)
            addToQueue(translation)
            start()
        }
    }

    private fun shouldSkipTranslation(sourceLang: String, toLang: TextTranslatorLanguage): Boolean {
        val normalizedSource = sourceLang.substringBefore("-").lowercase()
        val normalizedTarget = toLang.code.substringBefore("-").lowercase()

        if (normalizedSource == normalizedTarget) return true

        val excludedLangs = translationPreferences.translationExcludedLanguages().get()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (excludedLangs.contains(normalizedTarget) || excludedLangs.contains(normalizedSource)) return true

        return false
    }

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

    // تم إصلاح الدالة: تعيد اللغة وهل هي مؤكدة أم لا
    private suspend fun resolveSourceLanguageForManga(
        mangaId: Long,
        source: HttpSource,
        mangaTitle: String,
    ): ResolvedLanguage {
        // 1. تفضيل المستخدم الخاص (مؤكد 100%)
        val hasOverride = mangaTranslationPreferences.hasOverride(mangaId).get()
        if (hasOverride) {
            val lang = TextRecognizerLanguage.fromPref(mangaTranslationPreferences.sourceLanguage(mangaId))
            return ResolvedLanguage(lang, isDefinitive = true)
        }

        // 2. لغة عنوان المانجا (مؤكدة 100%)
        val titleLang = detectLanguageFromMangaTitle(mangaTitle)
        if (titleLang != null) {
            return ResolvedLanguage(titleLang, isDefinitive = true)
        }

        // 3. لغة المصدر التلقائية (غير مؤكدة، تحتاج تصويت عند قراءة الصفحات)
        val defaultLang = autoDetectSourceLanguage(source.lang) ?: TextRecognizerLanguage.ENGLISH
        return ResolvedLanguage(defaultLang, isDefinitive = false)
    }

    private val ML_KIT_MAX_HEIGHT = 20000

    private suspend fun recognizeLargeImageByTiles(imagePath: String, imageHeight: Int, imageWidth: Int): PageTranslation? {
        val tileHeight = ML_KIT_MAX_HEIGHT - 200 
        val tiles = mutableListOf<PageTranslation>()
        var offsetY = 0

        while (offsetY < imageHeight) {
            val tileH = minOf(tileHeight, imageHeight - offsetY)
            val rect = Rect(0, offsetY, imageWidth, offsetY + tileH)
            val tileBitmap = try {
                val decoder = BitmapRegionDecoder.newInstance(imagePath, false) ?: break
                val bmp = decoder.decodeRegion(rect, BitmapFactory.Options())
                decoder.recycle()
                bmp
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "فشل في قراءة tile عند offsetY=$offsetY" }
                offsetY += tileHeight
                continue
            }

            try {
                val tileTranslation = recognizeSingleBitmap(tileBitmap, imageWidth, tileH)
                if (tileTranslation != null) {
                    val adjustedBlocks = tileTranslation.blocks.map { block ->
                        block.copy(y = block.y + offsetY)
                    }
                    tiles.add(PageTranslation(adjustedBlocks.toMutableList()))
                }
            } finally {
                tileBitmap?.recycle()
            }

            offsetY += tileHeight
        }

        if (tiles.isEmpty()) return null
        return PageTranslation(tiles.flatMap { it.blocks }.toMutableList())
    }

    private suspend fun voteForLanguage(texts: List<String>): TextRecognizerLanguage? {
        val langScores = mutableMapOf<TextRecognizerLanguage, Float>()
        for (text in texts) {
            if (text.isBlank()) continue
            val (detectedLang, confidence) = detectLanguageWithMLKit(text)
            if (detectedLang != null && confidence > 0.5f) {
                langScores[detectedLang] = (langScores[detectedLang] ?: 0f) + confidence
            }
        }
        return langScores.maxByOrNull { it.value }?.key
    }

    private suspend fun detectLanguageWithMLKit(text: String): Pair<TextRecognizerLanguage?, Float> {
        val identifier = LanguageIdentification.getClient()
        return try {
            val possibilities = identifier.identifyPossibleLanguages(text).await()
            val best = possibilities.maxByOrNull { it.confidence }
            
            if (best == null || best.languageTag == "und") return Pair(null, 0f)

            val lang = when (best.languageTag) { 
                "zh" -> TextRecognizerLanguage.CHINESE
                "ja" -> TextRecognizerLanguage.JAPANESE
                "ko" -> TextRecognizerLanguage.KOREAN
                "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "id", "vi", "ro", "hu", "cs", "sk", "hr", "sl", "da", "sv", "no", "fi" -> TextRecognizerLanguage.ENGLISH
                else -> null
            }
            Pair(lang, best.confidence)
        } catch (e: Exception) {
            Pair(null, 0f)
        }
    }

    private fun detectLanguageFromMangaTitle(mangaTitle: String): TextRecognizerLanguage? {
        val bracketPattern = Regex("""[\[(]([^\])]+)[\])]""")
        val matches = bracketPattern.findAll(mangaTitle)

        for (match in matches) {
            val tagContent = match.groupValues[1].lowercase()
            val detected = mapLanguageNameToRecognizer(tagContent)
            if (detected != null) return detected
        }
        return null
    }

    private fun mapLanguageNameToRecognizer(tagContent: String): TextRecognizerLanguage? {
        return when {
            tagContent.contains("chinese") -> TextRecognizerLanguage.CHINESE
            tagContent.contains("japanese") -> TextRecognizerLanguage.JAPANESE
            tagContent.contains("korean") -> TextRecognizerLanguage.KOREAN
            tagContent.contains("english") ||
            tagContent.contains("spanish") ||
            tagContent.contains("french") ||
            tagContent.contains("german") ||
            tagContent.contains("italian") ||
            tagContent.contains("portuguese") ||
            tagContent.contains("dutch") ||
            tagContent.contains("polish") ||
            tagContent.contains("turkish") ||
            tagContent.contains("indonesian") ||
            tagContent.contains("vietnamese") ||
            tagContent.contains("romanian") ||
            tagContent.contains("hungarian") ||
            tagContent.contains("czech") ||
            tagContent.contains("slovak") ||
            tagContent.contains("croatian") ||
            tagContent.contains("slovenian") ||
            tagContent.contains("danish") ||
            tagContent.contains("swedish") ||
            tagContent.contains("norwegian") ||
            tagContent.contains("finnish") -> TextRecognizerLanguage.ENGLISH
            else -> null
        }
    }

    private fun autoDetectSourceLanguage(sourceLang: String): TextRecognizerLanguage? {
        val code = sourceLang.substringBefore("-").lowercase()
        return when (code) {
            "zh" -> TextRecognizerLanguage.CHINESE
            "ja" -> TextRecognizerLanguage.JAPANESE
            "ko" -> TextRecognizerLanguage.KOREAN
            "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "id", "vi",
            "ro", "hu", "cs", "sk", "hr", "sl", "da", "sv", "no", "fi",
            -> TextRecognizerLanguage.ENGLISH
            else -> null
        }
    }

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
            val pages = ConcurrentHashMap<String, PageTranslation>()
            val streams = getChapterPages(chapterPath)
            val totalPageCount = streams.size

            val concurrencyLimit = translationPreferences.batchOcrConcurrency().get().coerceAtLeast(1)
            val batchSemaphore = Semaphore(concurrencyLimit)

            withContext(Dispatchers.Default) {
                val deferredPages = streams.map { (fileName, streamFn) ->
                    async {
                        batchSemaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                          
                            val tmpFile = File(context.cacheDir, "ocr_tmp_${System.nanoTime()}.jpg")
                            try {
                                if (!tmpFile.exists()) tmpFile.createNewFile()
                                streamFn().use { input -> tmpFile.outputStream().use { out -> input.copyTo(out) } }

                                val pageTranslation = recognizePage(tmpFile.absolutePath)
                                if (pageTranslation != null && pageTranslation.blocks.isNotEmpty()) {
                                    pages[fileName] = pageTranslation
                                }
                            } finally {
                                if (tmpFile.exists()) tmpFile.delete()
                            }
                        }
                    }
                }
                deferredPages.awaitAll()
            }

            if (pages.isNotEmpty()) {
                withRetry(3, 1000) {
                    withContext(Dispatchers.IO) { textTranslator.translate(pages) }
                }
                writeTranslationFile(translationMangaDir, saveFile, pages)
            }
            
            translation.status = Translation.State.TRANSLATED

            val cid = translation.chapter.id
            if (cid != null) {
                mangaTranslationPreferences.updateTranslatedPages(cid, totalPageCount, totalPageCount)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error) { "فشل الترجمة العادية" }
        }
    }

    private suspend fun reTranslatePages(
        fileNames: List<String>,
        translation: Translation,
        translationMangaDir: UniFile,
        saveFile: String,
    ) {
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        if (engine != TextTranslators.MLKIT && engine != TextTranslators.GOOGLE) return

        val pagesToRetranslate = fileNames
            .mapNotNull { fileName ->
                val page = translation.existingPages[fileName]
                if (page != null && page.blocks.isNotEmpty()) fileName to page else null
            }
            .toMap().toMutableMap()

        if (pagesToRetranslate.isEmpty()) return

        logcat { "إعادة ترجمة ${pagesToRetranslate.size} صفحة بالـ fromLang الجديدة (بدون إعادة OCR)" }

        withRetry(3, 1000) {
            withContext(Dispatchers.IO) { textTranslator.translate(pagesToRetranslate) }
        }

        for ((fileName, pageTranslation) in pagesToRetranslate) {
            translation.existingPages[fileName] = pageTranslation
            translation.emitPageTranslated(fileName, pageTranslation)
        }

        persistRealtimeProgress(translationMangaDir, saveFile, translation)
    }

    private suspend fun translateChapterRealtime(translation: Translation) {
        ensureRecognizerAndTranslator(translation)

        val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
        val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)
        val chapterId = translation.chapter.id

        val finishFlag = AtomicBoolean(false)
        if (chapterId != null) finishRequests[chapterId] = finishFlag

        val resolvedLang = resolveSourceLanguageForManga(translation.manga.id, translation.source, translation.manga.title)
        var currentFromLang = resolvedLang.language
        
        // إذا كانت اللغة مؤكدة (من المستخدم أو العنوان)، فلن نحتاج لتصويت إطلاقاً
        var isLangResolved = resolvedLang.isDefinitive
        
        var processedPageCount = 0
        val pendingVoteTexts = CopyOnWriteArrayList<String>()
        
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        val canReTranslate = (engine == TextTranslators.MLKIT || engine == TextTranslators.GOOGLE)
        val pendingReTranslateNames = if (!isLangResolved && canReTranslate)
            CopyOnWriteArrayList<String>() else null

        var lastSaveTime = System.currentTimeMillis()

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (finishFlag.get()) break

                val newStreams = translation.takePageStreams()
                if (newStreams.isEmpty()) {
                    delay(translationPreferences.realtimePollIntervalMs().get().toLong())
                    continue
                }

                // ينفذ التصويت فقط إذا كانت اللغة غير مؤكدة (تستند على لغة المصدر فقط)
                val shouldVoteNow = !isLangResolved && pendingVoteTexts.isNotEmpty() &&
                    (pendingVoteTexts.size >= 5 || !translation.hasPendingPages())
                if (shouldVoteNow) {
                    val votedLang = voteForLanguage(pendingVoteTexts)
                    if (votedLang != null) {
                        val langChanged = votedLang != currentFromLang
                        logcat { "تأكيد لغة المصدر عبر التصويت: $currentFromLang → $votedLang (تغيّرت: $langChanged)" }

                        if (langChanged) {
                            currentFromLang = votedLang
                            if (currentFromLang != textRecognizer.language) {
                                textRecognizer.close()
                                textRecognizer = TextRecognizer(currentFromLang)
                            }
                            if (!pendingReTranslateNames.isNullOrEmpty()) {
                                reTranslatePages(pendingReTranslateNames, translation, translationMangaDir, saveFile)
                            }
                        }

                        isLangResolved = true
                        pendingVoteTexts.clear()
                        pendingReTranslateNames?.clear()
                        processedPageCount = 0
                    }
                }

                val currentAvg = rollingAvgBubbles
                val lowBubbleThreshold = translationPreferences.lowBubbleCountThreshold().get().toFloat()
                val concurrencyLimit = if (currentAvg < lowBubbleThreshold) {
                    translationPreferences.realtimeLowBubbleConcurrency().get().coerceAtLeast(1)
                } else {
                    translationPreferences.batchOcrConcurrency().get().coerceAtLeast(1)
                }
                val realtimeSemaphore = Semaphore(concurrencyLimit)

                withContext(Dispatchers.Default) {
                    val deferredList = newStreams.map { (fileName, streamFn) ->
                        async {
                            realtimeSemaphore.withPermit {
                                currentCoroutineContext().ensureActive()

                                if (translation.existingPages.containsKey(fileName)) {
                                    translation.emitPageTranslated(fileName, translation.existingPages[fileName]!!)
                                    return@async
                                }

                                val tmpFile = File(context.cacheDir, "ocr_tmp_rt_${System.nanoTime()}.jpg")
                                try {
                                    val pageTranslation = withContext(Dispatchers.IO) {
                                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        streamFn().use { stream -> BitmapFactory.decodeStream(stream, null, opts) }
                                        
                                        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@withContext null

                                        val aspectRatio = opts.outHeight.toFloat() / opts.outWidth.toFloat()
                                        val maxOcrHeight = translationPreferences.maxOcrHeight().get()
                                        val aspectRatioThreshold = translationPreferences.longImageAspectRatioThreshold().get().toFloatOrNull() ?: 2.5f
                                        
                                        val isLongImage = opts.outHeight > maxOcrHeight && aspectRatio > aspectRatioThreshold
                                        val isMassiveImage = (opts.outWidth * opts.outHeight) > 8_000_000

                                        if (!tmpFile.exists()) tmpFile.createNewFile()
                                        streamFn().use { input -> tmpFile.outputStream().use { out -> input.copyTo(out) } }
                                        
                                        if (!isLongImage) {
                                            if (isMassiveImage) {
                                                longImageSemaphore.withPermit {
                                                    val bitmap = BitmapFactory.decodeFile(tmpFile.absolutePath)
                                                    bitmap?.let {
                                                        try { recognizeSingleBitmap(it, opts.outWidth, opts.outHeight) }
                                                        finally { it.recycle() }
                                                    }
                                                }
                                            } else {
                                                val bitmap = BitmapFactory.decodeFile(tmpFile.absolutePath)
                                                bitmap?.let {
                                                    try { recognizeSingleBitmap(it, opts.outWidth, opts.outHeight) }
                                                    finally { it.recycle() }
                                                }
                                            }
                                        } else if (opts.outHeight > ML_KIT_MAX_HEIGHT) {
                                            longImageSemaphore.withPermit {
                                                recognizeLargeImageByTiles(tmpFile.absolutePath, opts.outHeight, opts.outWidth)
                                            }
                                        } else {
                                            longImageSemaphore.withPermit { recognizePage(tmpFile.absolutePath) }
                                        }
                                    }

                                    if (pageTranslation != null && pageTranslation.blocks.isNotEmpty()) {
                                        
                                        withRetry(times = 3, initialDelayMs = 1000) {
                                            withContext(Dispatchers.IO) { textTranslator.translate(mutableMapOf(fileName to pageTranslation)) }
                                        }

                                        translation.existingPages[fileName] = pageTranslation
                                        translation.emitPageTranslated(fileName, pageTranslation)

                                        // نجمع النصوص للتصويت فقط إذا لم تكن اللغة مؤكدة من البداية
                                        if (!isLangResolved) {
                                            val isLongPage = pageTranslation.blocks.size > 15
                                            if (isLongPage) {
                                                val sorted = pageTranslation.blocks.sortedBy { it.y }
                                                val third = (sorted.size / 3).coerceAtLeast(1)
                                                listOf(
                                                    sorted.take(third),
                                                    sorted.drop(third).take(third),
                                                    sorted.drop(third * 2)
                                                ).forEach { chunk ->
                                                    val text = chunk.joinToString(" ") { it.text }
                                                    if (text.isNotBlank()) pendingVoteTexts.add(text)
                                                }
                                            } else {
                                                val text = pageTranslation.blocks.joinToString(" ") { it.text }
                                                if (text.isNotBlank()) pendingVoteTexts.add(text)
                                            }

                                            if (pendingReTranslateNames != null) {
                                                pendingReTranslateNames.add(fileName)
                                            }
                                        }

                                        val currentTime = System.currentTimeMillis()
                                        if (currentTime - lastSaveTime > 5000) {
                                            lastSaveTime = currentTime
                                            scope.launch {
                                                persistRealtimeProgress(translationMangaDir, saveFile, translation)
                                            }
                                        }
                                    } else if (pageTranslation != null && pageTranslation.blocks.isEmpty()) {
                                         translation.existingPages[fileName] = pageTranslation
                                         translation.emitPageTranslated(fileName, pageTranslation)
                                    }

                                } catch (e: Exception) {
                                    logcat(LogPriority.ERROR, e) { "خطأ في معالجة الصفحة: $fileName" }
                                } finally {
                                    if (tmpFile.exists()) tmpFile.delete()
                                }
                            }
                        }
                    }
                    deferredList.awaitAll()
                }

                if (!isLangResolved) {
                    processedPageCount += newStreams.size
                    if (processedPageCount >= 10) {
                        processedPageCount = 0
                        val textsForVoting = pendingVoteTexts.toList()
                        pendingVoteTexts.clear()

                        val votedLang = voteForLanguage(textsForVoting)
                        if (votedLang != null && votedLang != currentFromLang) {
                            logcat { "تغيير لغة المصدر التلقائي: $currentFromLang → $votedLang" }
                            currentFromLang = votedLang
                            if (currentFromLang != textRecognizer.language) {
                                textRecognizer.close()
                                textRecognizer = TextRecognizer(currentFromLang)
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            translation.status = Translation.State.ERROR
        } finally {
            if (chapterId != null) finishRequests.remove(chapterId)
            runCatching { persistRealtimeProgress(translationMangaDir, saveFile, translation) }
            if (translation.status != Translation.State.ERROR) {
                translation.status = Translation.State.TRANSLATED
            }
        }
    }

    private suspend fun persistRealtimeProgress(
        translationMangaDir: UniFile,
        saveFile: String,
        translation: Translation,
    ) {
        val pagesSnapshot = translation.existingPages.toMap()
        
        jsonWriteMutex.withLock {
            withContext(Dispatchers.IO) {
                writeTranslationFile(translationMangaDir, saveFile, pagesSnapshot)
            }
        }
    }

    private fun readTranslationFile(file: UniFile): Map<String, PageTranslation> {
        return try {
            val data = Json.decodeFromStream<Map<String, PageTranslation>>(file.openInputStream())
            if (data.isEmpty()) {
                logcat(LogPriority.WARN) { "ملف الترجمة فارغ: ${file.uri}" }
            }
            data
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "فشل في قراءة ملف الترجمة: ${file.uri}" }
            emptyMap()
        }
    }

    private fun writeTranslationFile(
        dir: UniFile,
        saveFile: String,
        pages: Map<String, PageTranslation>,
    ) {
        try {
            if (pages.isEmpty()) {
                logcat(LogPriority.WARN) { "محاولة لحفظ ملف ترجمة فارغ، سيتم التخطي: $saveFile" }
                return
            }

            val tmpName = "${saveFile}.tmp"
            dir.findFile(tmpName)?.delete()
            val tmpJsonFile = dir.createFile(tmpName) ?: run {
                logcat(LogPriority.ERROR) { "فشل في إنشاء الملف المؤقت: $tmpName" }
                return
            }

            Json.encodeToStream(pages, tmpJsonFile.openOutputStream())

            dir.findFile(saveFile)?.delete()
            val finalFile = dir.createFile(saveFile) ?: run {
                logcat(LogPriority.ERROR) { "فشل في إنشاء الملف النهائي: $saveFile" }
                tmpJsonFile.delete()
                return
            }

            tmpJsonFile.openInputStream().use { input ->
                finalFile.openOutputStream().use { output -> input.copyTo(output) }
            }
            tmpJsonFile.delete()
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "فشل في حفظ ملف الترجمة: $saveFile" }
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

    private suspend fun recognizePage(filePath: String): PageTranslation? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, opts)
        val origW = opts.outWidth
        val origH = opts.outHeight
        if (origW <= 0 || origH <= 0) return null

        val aspectRatio = origH.toFloat() / origW.toFloat()
        val maxOcrHeight = translationPreferences.maxOcrHeight().get()
        val aspectRatioThreshold = translationPreferences.longImageAspectRatioThreshold().get().toFloatOrNull() ?: 2.5f

        if (origH <= maxOcrHeight || aspectRatio <= aspectRatioThreshold) {
            val bitmap = BitmapFactory.decodeFile(filePath) ?: return null
            try {
                return recognizeSingleBitmap(bitmap, origW, origH)
            } finally {
                bitmap.recycle()
            }
        }

        val fullBitmap = BitmapFactory.decodeFile(filePath) ?: return null
        val previewBlocks = try {
            val fullImage = InputImage.fromBitmap(fullBitmap, 0)
            val fullResult = textRecognizer.recognize(fullImage)
            fullResult.textBlocks
                .filter { it.boundingBox != null && it.text.length > 1 }
                .mapNotNull { block ->
                    block.boundingBox?.let { bounds ->
                        Pair(
                            (bounds.top / TextRecognizer.SCALE_FACTOR).toInt(),
                            (bounds.bottom / TextRecognizer.SCALE_FACTOR).toInt()
                        )
                    }
                }
        } finally {
            fullBitmap.recycle()
        }

        val splitLines = computeSmartSplitLines(origH, previewBlocks, maxOcrHeight)

        if (splitLines.size <= 2) {
            val bitmap = BitmapFactory.decodeFile(filePath) ?: return null
            try {
                return recognizeSingleBitmap(bitmap, origW, origH)
            } finally {
                bitmap.recycle()
            }
        }

        return recognizeWithSmartSplit(filePath, origW, origH, splitLines)
    }

    private fun computeSmartSplitLines(
        origH: Int,
        bubbleZones: List<Pair<Int, Int>>,
        maxHeight: Int,
    ): List<Int> {
        if (bubbleZones.isEmpty()) {
            val lines = mutableListOf(0)
            var current = 0
            while (current + maxHeight < origH) {
                current += maxHeight
                lines.add(current)
            }
            lines.add(origH)
            return lines
        }

        val forbiddenZones = bubbleZones.sortedBy { it.first }
            .fold(mutableListOf<Pair<Int, Int>>()) { acc, zone ->
                if (acc.isEmpty()) {
                    acc.add(zone)
                } else {
                    val last = acc.last()
                    if (zone.first <= last.second) {
                        acc[acc.size - 1] = Pair(last.first, max(last.second, zone.second))
                    } else {
                        acc.add(zone)
                    }
                }
                acc
            }

        val splitLines = mutableListOf(0)
        var currentY = 0

        while (currentY < origH) {
            val targetEnd = min(currentY + maxHeight, origH)

            val forbiddenInRange = forbiddenZones.find { zone ->
                zone.first < targetEnd && zone.second > currentY
            }

            if (forbiddenInRange == null) {
                currentY = targetEnd
                splitLines.add(currentY)
            } else {
                val afterForbidden = forbiddenInRange.second

                if (afterForbidden >= origH) {
                    splitLines.add(origH)
                    break
                }

                val nextForbidden = forbiddenZones.find { it.first <= afterForbidden && it.second > afterForbidden }
                if (nextForbidden == null) {
                    currentY = afterForbidden
                    splitLines.add(currentY)
                } else {
                    var safeY = afterForbidden
                    while (safeY < origH) {
                        val conflict = forbiddenZones.find { it.first <= safeY && it.second > safeY }
                        if (conflict == null) {
                            currentY = safeY
                            splitLines.add(currentY)
                            break
                        }
                        safeY = conflict.second
                    }
                    if (safeY >= origH) {
                        splitLines.add(origH)
                        break
                    }
                }
            }
        }

        if (splitLines.size >= 3) {
            val lastIdx = splitLines.size - 1
            val secondLast = splitLines[lastIdx - 1]
            val last = splitLines[lastIdx]
            if (last - secondLast < maxHeight * 0.2f && last == origH) {
                splitLines.removeAt(lastIdx - 1)
            }
        }

        return splitLines
    }

    private suspend fun recognizeWithSmartSplit(
        filePath: String,
        origW: Int,
        origH: Int,
        splitLines: List<Int>,
    ): PageTranslation? = withContext(Dispatchers.Default) {
        val decoder = BitmapRegionDecoder.newInstance(filePath, false) ?: return@withContext null
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until splitLines.size - 1) {
            pairs.add(Pair(splitLines[i], splitLines[i + 1]))
        }

        val tileConcurrency = translationPreferences.tileOcrConcurrency().get().coerceAtLeast(1)
        val localTileSemaphore = Semaphore(tileConcurrency)

        val deferredBlocks = pairs.map { (tileTop, tileBottom) ->
            async {
                localTileSemaphore.withPermit {
                    ensureActive()
                    val tileHeight = tileBottom - tileTop
                    if (tileHeight <= 0) return@async emptyList()
                    val region = Rect(0, tileTop, origW, tileBottom)
                    val tileBitmap = decoder.decodeRegion(region, null) ?: return@async emptyList()

                    try {
                        val image = InputImage.fromBitmap(tileBitmap, 0)
                        val result = textRecognizer.recognize(image)
                        result.textBlocks
                            .filter { it.boundingBox != null && it.text.length > 1 }
                            .map { block ->
                                val bounds = block.boundingBox!!
                                val symBounds = block.lines.firstOrNull()?.elements?.firstOrNull()
                                    ?.symbols?.firstOrNull()?.boundingBox
                                TranslationBlock(
                                    text = block.text,
                                    width = bounds.width().toFloat(),
                                    height = bounds.height().toFloat(),
                                    symWidth = symBounds?.width()?.toFloat() ?: 12f,
                                    symHeight = symBounds?.height()?.toFloat() ?: 12f,
                                    angle = block.lines.firstOrNull()?.angle ?: 0f,
                                    x = bounds.left.toFloat(),
                                    y = (tileTop * TextRecognizer.SCALE_FACTOR).toFloat() + bounds.top.toFloat(),
                                )
                            }
                    } finally {
                        tileBitmap.recycle()
                    }
                }
            }
        }

        val allBlocks = deferredBlocks.awaitAll().flatten()
        decoder.recycle()

        if (allBlocks.isEmpty()) return@withContext PageTranslation(imgWidth = origW.toFloat(), imgHeight = origH.toFloat())

        val enhancedW = origW * TextRecognizer.SCALE_FACTOR
        val enhancedH = origH * TextRecognizer.SCALE_FACTOR
        val pageTranslation = PageTranslation(imgWidth = enhancedW.toFloat(), imgHeight = enhancedH.toFloat())
        
        pageTranslation.blocks = smartMergeBlocks(allBlocks, enhancedW.toFloat(), enhancedH.toFloat())

        for (block in pageTranslation.blocks) {
            block.x /= TextRecognizer.SCALE_FACTOR
            block.y /= TextRecognizer.SCALE_FACTOR
            block.width /= TextRecognizer.SCALE_FACTOR
            block.height /= TextRecognizer.SCALE_FACTOR
        }
        pageTranslation.imgWidth /= TextRecognizer.SCALE_FACTOR
        pageTranslation.imgHeight /= TextRecognizer.SCALE_FACTOR

        pageTranslation
    }

    private fun recognizeSingleBitmap(bitmap: Bitmap, origW: Int, origH: Int): PageTranslation? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.recognize(image)
        val blocks = result.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }

        updateRollingAvg(blocks.size)

        if (blocks.isEmpty()) return PageTranslation(imgWidth = origW.toFloat(), imgHeight = origH.toFloat())

        return convertToPageTranslation(blocks, origW, origH)
    }

    private fun convertToPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int): PageTranslation {
        val translation = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        for (block in blocks) {
            val bounds = block.boundingBox!!
            val symBounds = block.lines.firstOrNull()?.elements?.firstOrNull()
                ?.symbols?.firstOrNull()?.boundingBox
            translation.blocks.add(
                TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat() / TextRecognizer.SCALE_FACTOR,
                    height = bounds.height().toFloat() / TextRecognizer.SCALE_FACTOR,
                    symWidth = (symBounds?.width()?.toFloat() ?: 12f) / TextRecognizer.SCALE_FACTOR,
                    symHeight = (symBounds?.height()?.toFloat() ?: 12f) / TextRecognizer.SCALE_FACTOR,
                    angle = block.lines.firstOrNull()?.angle ?: 0f,
                    x = bounds.left.toFloat() / TextRecognizer.SCALE_FACTOR,
                    y = bounds.top.toFloat() / TextRecognizer.SCALE_FACTOR,
                ),
            )
        }
        translation.blocks = smartMergeBlocks(translation.blocks, width.toFloat(), height.toFloat())
        return translation
    }

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
            
            var newHeight: Float
            var newWidth: Float
            if (abs(block.angle) in 70.0..110.0) {
                newHeight = block.height * finalScale
                newWidth = block.width * finalScale * 1.3f
            } else {
                newHeight = block.height * finalScale * 1.3f
                newWidth = block.width * finalScale
            }

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
            val axisX = cos(rad).toFloat()
            val axisY = sin(rad).toFloat()
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
            queue - translations.toSet()
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
