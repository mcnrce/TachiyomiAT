package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {
        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            imgWidth: Float,
            imgHeight: Float
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            // 1. تنظيف أولي للكتل الفارغة
            val result = blocks.filter { it.text.isNotBlank() }.toMutableList()

            // 2. ضبط العتبات (Thresholds) لتكون أكثر تحفظاً
            // تقليل العوامل لضمان عدم دمج فقاعات منفصلة
            val xThreshold = (2.0f * (imgWidth / 1200f).coerceAtMost(3.0f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.2f * (imgHeight / 2000f).coerceAtMost(2.0f)).coerceAtLeast(1.0f)
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            // 3. حلقة الدمج 
            var i = 0
            while (i < result.size) {
                var j = i + 1
                var mergedInThisRound = false
                
                while (j < result.size) {
                    if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        result.removeAt(j)
                        
                        i = 0 
                        mergedInThisRound = true
                        break 
                    }
                    j++
                }
                
                if (!mergedInThisRound) {
                    i++
                }
            }

            return result
        }

        private fun shouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {
            // تشابه الزوايا (بفارق ضئيل جداً لضمان الدقة)
            val angleDiff = abs(r1.angle - r2.angle)
            val angleSimilar = angleDiff < 10 || abs(angleDiff - 180) < 10
            if (!angleSimilar) return false

            val isVertical = abs(r1.angle) in 75.0..105.0
            
            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            val sW = maxOf(r1.symWidth, r2.symWidth, 12f)
            val sH = maxOf(r1.symHeight, r2.symHeight, 12f)

            val hGap = maxOf(0f, if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right)
            val vGap = maxOf(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
            
            val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
            val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)

            return if (isVertical) {
                /* --- دمج عمودي "تحفظي" --- */
                val dx = abs(r1.x - r2.x)
                val dy = abs(r1.y - r2.y)
                
                // تقليل المسافات المسموحة لضمان الانتماء لنفس الفقاعة
                val isOriginsClose = dy < (sH * 1.2f) && dx < (sW * 2.5f)
                val isSideBySide = hGap < (sW * 1.0f) && dy < (sH * 1.2f)
                val alignedVertically = vOverlap > (sH * 0.4f) && hGap < (sW * 1.0f)

                isOriginsClose || isSideBySide || alignedVertically
            } else {
                /* --- دمج أفقي "تحفظي" --- */
                // اشتراط تقارب رأسي شديد
                val closeVertically = vGap <= sH * (yThresholdFactor * 0.5f)
                val dxCenter = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
                
                val closeHorizontally = if (hOverlap > sW * 0.5f) {
                    dxCenter < sW * (xThreshold * 0.6f)
                } else {
                    hGap < sW * 0.8f // فجوة ضيقة جداً
                }
                closeVertically && closeHorizontally
            }
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            // دمج الأبعاد بدقة دون أي توسيع عشوائي (30% القديمة أزيلت)
            val finalWidth = maxX - minX
            val finalHeight = maxY - minY
            val finalX = minX

            val isVertical = abs(a.angle) in 75.0..105.0
            
            // ترتيب النصوص
            val blocksOrdered = if (isVertical) {
                if (a.x > b.x) listOf(a, b) else listOf(b, a)
            } else {
                val isVerticalSplit = abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.4f
                if (isVerticalSplit) {
                    if (a.y < b.y) listOf(a, b) else listOf(b, a)
                } else {
                    if (isWebtoon) (if (a.x < b.x) listOf(a, b) else listOf(b, a))
                    else (if (a.x > b.x) listOf(a, b) else listOf(b, a))
                }
            }

            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
            val totalLen = lenA + lenB

            return TranslationBlock(
                text = blocksOrdered.joinToString(" ") { it.text.trim() },
                translation = blocksOrdered.joinToString(" ") { it.translation?.trim() ?: "" }.trim(),
                width = finalWidth,
                height = finalHeight,
                x = finalX,
                y = minY,
                angle = if (lenA >= lenB) a.angle else b.angle,
                symWidth = (a.symWidth * lenA + b.symWidth * lenB) / totalLen,
                symHeight = (a.symHeight * lenA + b.symHeight * lenB) / totalLen
            )
        }
    }
}
