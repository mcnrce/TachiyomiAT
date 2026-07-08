package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)
    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "CHINESE")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ENGLISH")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationEngine() = preferenceStore.getInt("translation_engine", 0)
    fun translationEngineModel() = preferenceStore.getString("translation_engine_model", "gemini-1.5-pro")
    fun translationEngineApiKey() = preferenceStore.getString("translation_engine_api_key", "")
    fun translationEngineTemperature() = preferenceStore.getString("translation_engine_temperature", "1")
    fun translationEngineMaxOutputTokens() = preferenceStore.getString("translation_engine_output_tokens", "8192")

    // كلمات مفلترة — يكتبها المستخدم مفصولة بفاصلة
    fun translationFilteredWords() = preferenceStore.getString("translation_filtered_words", "")

    // ترجمة فورية أثناء القراءة
    fun realtimeTranslation() = preferenceStore.getBoolean("realtime_translation", false)

    // ─── Performance & OCR ─────────────────────────────────────────────────
    // TachiyomiAT: إعدادات تقنية تتحكم في أداء الترجمة والـ OCR ومعالجة
    // الصور الطويلة. القيم الافتراضية مناسبة لمعظم الأجهزة؛ عدّلها فقط
    // إذا واجهت بطئاً أو استهلاك ذاكرة زائد.

    /**
     * الحد الأقصى لارتفاع الصورة (بالبكسل) قبل اعتبارها "طويلة" ومعالجتها
     * بالتقطيع الذكي بدل قراءتها دفعة واحدة. تقليل هذا الرقم يجعل صوراً
     * أكثر تُعامَل كطويلة (تقطيع أكثر، ذاكرة أقل لكل خطوة، لكن معالجة أبطأ
     * قليلاً بسبب خطوة الفحص الأولي الإضافية).
     */
    fun maxOcrHeight() = preferenceStore.getInt("ocr_max_height", 2500)

    /**
     * نسبة (ارتفاع ÷ عرض) الحد الأدنى لاعتبار الصورة "ويبتون طويل" فعلياً.
     * صورة عريضة عالية الجودة (نسبة معتدلة) لن تُعامَل كـ webtoon حتى لو
     * تجاوز ارتفاعها الحد الأقصى، فتتجنب تقطيعاً غير ضروري. زيادة هذا الرقم
     * تجعل الشرط أكثر تشدداً (صور أقل تُعتبر طويلة).
     */
    fun longImageAspectRatioThreshold() = preferenceStore.getString("ocr_aspect_ratio_threshold", "2.5")

    /**
     * عدد الصفحات التي تُعالَج بالتوازي (OCR في نفس الوقت) عند الترجمة العادية
     * لفصل كامل بعد التنزيل. رقم أعلى = أسرع، لكن يستهلك ذاكرة ومعالج أكثر.
     * الأجهزة الضعيفة يُفضَّل تبقيها منخفضة (1-2)، الأجهزة القوية يمكن رفعها (4-5).
     */
    fun batchOcrConcurrency() = preferenceStore.getInt("ocr_batch_concurrency", 3)

    /**
     * عدد الشرائح (tiles) التي تُعالَج بالتوازي عند تقطيع صورة webtoon طويلة
     * واحدة. نفس منطق batchOcrConcurrency لكن على مستوى الشرائح داخل الصورة
     * الواحدة بدل الصفحات المنفصلة.
     */
    fun tileOcrConcurrency() = preferenceStore.getInt("ocr_tile_concurrency", 3)

    /**
     * إذا كان متوسط عدد الفقاعات المكتشفة في آخر عدة صفحات (غير الطويلة) أقل
     * من هذا الرقم، يُعتبر الفصل "خفيف النص" (مشاهد أكشن مثلاً) وتُزاد سرعة
     * معالجة الصفحات المتتالية في وضع الترجمة الفورية.
     */
    fun lowBubbleCountThreshold() = preferenceStore.getInt("ocr_low_bubble_threshold", 4)

    /**
     * عدد الصفحات الأخيرة المُستخدمة لحساب متوسط عدد الفقاعات (انظر
     * lowBubbleCountThreshold أعلاه). رقم أكبر = قرار أكثر استقراراً لكن أبطأ
     * في التكيّف مع تغيّر نوع المحتوى (حوار كثيف ← مشهد أكشن مثلاً).
     */
    fun lowBubbleHistorySize() = preferenceStore.getInt("ocr_low_bubble_history_size", 5)

    /**
     * عدد الصفحات المعالَجة بالتوازي في وضع الترجمة الفورية عندما يُكتشف
     * أن الفصل "خفيف النص" (انظر lowBubbleCountThreshold). يُستخدم فقط
     * للصفحات القصيرة (غير الطويلة/الويبتون).
     */
    fun realtimeLowBubbleConcurrency() = preferenceStore.getInt("realtime_low_bubble_concurrency", 3)

    /**
     * المدة (بالثواني) التي ينتظرها وضع الترجمة الفورية بلا أي صفحة جديدة
     * قبل اعتبار الفصل منتهياً وإيقاف عملية الترجمة تلقائياً لتوفير الموارد.
     */
    fun realtimeIdleTimeoutSeconds() = preferenceStore.getInt("realtime_idle_timeout_seconds", 30)

    /**
     * الفاصل الزمني (بالميلي ثانية) بين كل محاولة فحص لوجود صفحات جديدة
     * لترجمتها في وضع الترجمة الفورية. رقم أقل = استجابة أسرع لكن استهلاك
     * معالج أعلى قليلاً أثناء الانتظار.
     */
    fun realtimePollIntervalMs() = preferenceStore.getInt("realtime_poll_interval_ms", 150)

    /**
     * الحد الأقصى لحجم الصفحات المحمَّلة مسبقاً في الذاكرة (بالميغابايت)
     * عند تفعيل الترجمة الفورية. يُستخدم لحساب كم صفحة يمكن تحميلها مسبقاً
     * (قبل وبعد الصفحة الحالية) دون تجاوز هذه الميزانية. رقم أعلى يعطي
     * ChapterTranslator وقتاً أطول للترجمة قبل وصول القارئ للصفحة، لكن
     * يزيد استهلاك ذاكرة التطبيق.
     */
    fun realtimePreloadMemoryBudgetMb() = preferenceStore.getInt("realtime_preload_memory_budget_mb", 150)

    /**
     * الحد الأدنى لعدد الصفحات المحمَّلة مسبقاً في وضع الترجمة الفورية
     * (يُستخدم إذا تعذّر تقدير حجم الصفحة الحالية لأي سبب).
     */
    fun realtimeDefaultPreloadCount() = preferenceStore.getInt("realtime_default_preload_count", 2)

    /**
     * الحد الأقصى المطلق لعدد الصفحات المحمَّلة مسبقاً في وضع الترجمة الفورية،
     * بغض النظر عن ميزانية الذاكرة، لتفادي تحميل عدد مفرط من الصفحات دفعة
     * واحدة حتى لو كانت صغيرة الحجم.
     */
    fun realtimeMaxPreloadCount() = preferenceStore.getInt("realtime_max_preload_count", 6)
}
