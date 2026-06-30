package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

/**
 * إعدادات ترجمة خاصة بكل مانجا على حدة.
 *
 * تُخزَّن بمفاتيح فريدة لكل manga.id في نفس PreferenceStore المستخدم للإعدادات العامة،
 * بنفس أسلوب حفظ readingMode/readerOrientation لكل مانجا — لكن بدون الحاجة لتعديل
 * schema قاعدة البيانات (Manga.viewerFlags)، لأن هذه الإعدادات أكثر تعقيداً (enable bool +
 * لغة مصدر + لغة هدف) ولا تحتاج أن تكون جزءاً من نموذج المانجا نفسه.
 *
 * القاعدة: الإعداد الخاص بالمانجا، إن وُجد (override = true)، يَجبّ الإعداد العام دائماً.
 * إن لم يوجد override، نستخدم سلوك الإعداد العام (realtimeTranslation العام) كما هو.
 */
class MangaTranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    /**
     * هل يوجد override خاص بهذه المانجا أصلاً؟
     * false يعني: اتبع الإعداد العام بالكامل (لا تطبيق لأي قيمة أدناه).
     */
    fun hasOverride(mangaId: Long) = preferenceStore.getBoolean("manga_translation_override_$mangaId", false)

    /**
     * تفعيل/تعطيل الترجمة الفورية لهذه المانجا تحديداً.
     * يُقرأ فقط إذا hasOverride(mangaId) == true.
     */
    fun enabled(mangaId: Long) = preferenceStore.getBoolean("manga_translation_enabled_$mangaId", false)

    /** لغة المصدر الخاصة بهذه المانجا (نفس قيم TextRecognizerLanguage.name) */
    fun sourceLanguage(mangaId: Long) =
        preferenceStore.getString("manga_translation_source_lang_$mangaId", "CHINESE")

    /** لغة الهدف الخاصة بهذه المانجا (نفس قيم TextTranslatorLanguage.name) */
    fun targetLanguage(mangaId: Long) =
        preferenceStore.getString("manga_translation_target_lang_$mangaId", "ENGLISH")

    /**
     * يمسح كل الإعدادات الخاصة بهذه المانجا فيعود السلوك للإعداد العام.
     * يُستدعى عند الضغط على زر "مسح الترجمة" في إعدادات القارئ، أو عند حذف المانجا.
     */
    fun clear(mangaId: Long) {
        hasOverride(mangaId).set(false)
        enabled(mangaId).set(false)
        sourceLanguage(mangaId).set("CHINESE")
        targetLanguage(mangaId).set("ENGLISH")
    }

    /**
     * منطق الأولوية الموحَّد لتحديد: هل تُفعَّل الترجمة الفورية فعلياً لهذه المانجا؟
     *   - hasOverride == true  → استخدم enabled(mangaId) فقط، بغض النظر عن الإعداد العام.
     *   - hasOverride == false → استخدم الإعداد العام globalRealtimeEnabled كما هو.
     *
     * هذه الدالة هي نقطة الحقيقة الوحيدة لهذا القرار، تُستدعى من PagerPageHolder و
     * WebtoonPageHolder كليهما لضمان تطابق السلوك بينهما تماماً.
     */
    fun resolveRealtimeEnabled(mangaId: Long, globalRealtimeEnabled: Boolean): Boolean {
        return if (hasOverride(mangaId).get()) {
            enabled(mangaId).get()
        } else {
            globalRealtimeEnabled
        }
    }
}
