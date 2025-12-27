package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {

        /**
         * الدالة الرئيسية لدمج الكتل النصية بشكل ذكي وشامل.
         * تم دمج منطق المسح المتكرر مع حساب الأحجام الموزونة لضمان أفضل دقة.
         */
        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            xThreshold: Float = 60f, 
            yThresholdFactor: Float = 1.6f 
        ): MutableList<TranslationBlock> {
            if (blocks.isEmpty()) return mutableListOf()

            // 1. تنظيف أولي للبيانات وترشيح الكتل غير المنطقية
            val result = blocks.filter { it.text.isNotBlank() && it.width > 2 && it.height > 2 }
                .toMutableList()

            var mergedAny: Boolean
            do {
                mergedAny = false
                var i = 0
                while (i < result.size) {
                    var j = i + 1
                    while (j < result.size) {
                        if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                            // دمج الفقاعتين
                            result[i] = performMerge(result[i], result[j])
                            result.removeAt(j)
                            mergedAny = true
                        } else {
                            j++
                        }
                    }
                    i++
                }
            } while (mergedAny)

            return result
        }

        /**
         * يحدد ما إذا كان يجب دمج فقاعتين بناءً على موقعهما الهندسي.
         */
        private fun shouldMerge(
        private fun shouldMerge(r1: TranslationBlock, r2: TranslationBlock): Boolean {

    // تشابه الزاوية
    val angleSimilar = kotlin.math.abs(r1.angle - r2.angle) < 10

    // الفجوة العمودية
    val top = maxOf(r1.y, r2.y)
    val bottom = minOf(r1.y + r1.height, r2.y + r2.height)
    val verticalGap = top - bottom
    val avgSymHeight = (r1.symHeight + r2.symHeight) / 2f
    val closeVertically = verticalGap <= avgSymHeight * 1.5f

    // الفجوة الأفقية (قرب نسبي فقط)
    val left = maxOf(r1.x, r2.x)
    val right = minOf(r1.x + r1.width, r2.x + r2.width)
    val horizontalGap = left - right
    val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
    val closeHorizontally = horizontalGap <= avgSymWidth * 2.5f

    // الدمج = قرب نسبي فقط
    return angleSimilar && closeVertically && closeHorizontally
}

        /**
         * دمج الخصائص الفيزيائية والنصية لكتلتين.
         */
        private fun performMerge(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
            // استخدام maxOf و minOf لحساب الحدود الجديدة
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            // ترتيب النص: الأقل Y (الأعلى في الصفحة) يأتي أولاً
            val first = if (a.y <= b.y) a else b
            val second = if (a.y <= b.y) b else a

            val mergedText = "${first.text}\n${second.text}"
            val mergedTrans = if (first.translation.isNotBlank() || second.translation.isNotBlank()) {
                "${first.translation}\n${second.translation}".trim()
            } else ""

            // حساب الحجم الموزون بناءً على طول النص (دقة الخط)
            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
            val totalLen = lenA + lenB

            val finalSymWidth = (a.symWidth * lenA + b.symWidth * lenB) / totalLen
            val finalSymHeight = (a.symHeight * lenA + b.symHeight * lenB) / totalLen

            return TranslationBlock(
                text = mergedText,
                translation = mergedTrans,
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = (a.angle + b.angle) / 2,
                symWidth = finalSymWidth,
                symHeight = finalSymHeight
            )
        }
    }
}
