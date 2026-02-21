package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {
        private val NOISE_REGEX = Regex("(?i).*\\.(com|net|org|co|me|io|link|info).*|.*(discord\\.gg|http|www).*")

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            imgWidth: Float,
            imgHeight: Float
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            // 1. تنظيف النصوص فقط (إزالة المسافات الزائدة) دون دمج البلوكات
            // سيعيد هذا كل سطر أو كلمة اكتشفها الـ OCR كبلوك منفصل تماماً
            return blocks.filter { it.text.isNotBlank() }
                .map { block ->
                    block.copy(
                        text = block.text.replace("\n", " ").trim(),
                        translation = block.translation?.replace("\n", " ")?.trim() ?: ""
                    )
                }.toMutableList()
            
            // تم حذف حلقة الدمج (while changed) وكل العمليات اللاحقة
        }

        // تم تعطيل الدوال التالية برمجياً لأنها لن تُستدعى، لكن تركناها فارغة لتجنب أخطاء الملف
        private fun shouldMerge(r1: TranslationBlock, r2: TranslationBlock, xThreshold: Float, yThresholdFactor: Float): Boolean = false
        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock = a
    }
}
