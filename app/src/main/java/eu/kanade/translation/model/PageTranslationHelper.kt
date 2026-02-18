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

            // 1. تنظيف أولي
            val result = blocks.filter { it.text.isNotBlank() }.toMutableList()

            // 2. ضبط العتبات
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            // 3. حلقة الدمج مع إعادة الضبط للبداية (The Reset Logic)
            var i = 0
            while (i < result.size) {
                var j = i + 1
                var mergedInThisRound = false
                
                while (j < result.size) {
                    if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                        // تنفيذ الدمج
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        // حذف العنصر المدمج
                        result.removeAt(j)
                        
                        // --- التعديل الجوهري هنا ---
                        // بمجرد نجاح الدمج، نعود للمؤشر 0 لنفحص الكتلة الجديدة مع الكل
                        i = 0 
                        mergedInThisRound = true
                        break // اخرج من حلقة j لتبدأ من i=0 مجدداً
                    }
                    j++
                }
                
                // إذا لم يحدث دمج للكتلة الحالية مع أي كتلة أخرى، ننتقل للكتلة التالية
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
            val angleDiff = abs(r1.angle - r2.angle)
            val angleSimilar = angleDiff < 15 || abs(angleDiff - 180) < 15
            if (!angleSimilar) return false

            val isVertical = abs(r1.angle) in 70.0..110.0
            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            val sW = maxOf(r1.symWidth, r2.symWidth, 12f)
            val sH = maxOf(r1.symHeight, r2.symHeight, 12f)

            return if (isVertical) {
                val dx = abs(r1.x - r2.x)
                val dy = abs(r1.y - r2.y)
                
                val isOriginsClose = dy < (sH * 2.2f) && dx < (sW * 4.5f)
                val sideGap = if (r1.x < r2.x) abs(r2.x - r1Right) else abs(r1.x - r2Right)
                val isSideBySide = sideGap < (sW * 2.5f) && dy < (sH * 2.2f)

                val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
                val alignedVertically = vOverlap > (sH * 0.15f)
                val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
                val closeHorizontally = hGap < (sW * 2.2f)

                isOriginsClose || isSideBySide || (closeHorizontally && alignedVertically)
            } else {
                val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
                val closeVertically = verticalGap <= sH * (yThresholdFactor * 0.9f)
                val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
                val dx = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
                
                val closeHorizontally = if (hOverlap > sW * 0.5f) {
                    dx < sW * xThreshold
                } else {
                    val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
                    hGap < sW * 2.0f 
                }
                closeVertically && closeHorizontally
            }
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            var finalWidth = maxX - minX
            var finalX = minX

            val isVertical = abs(a.angle) in 70.0..110.0
            if (isVertical) {
                val expansion = finalWidth * 0.50f
                finalWidth += expansion
                finalX -= expansion / 2f 
            }

            val blocksOrdered = if (isVertical) {
                if (a.x > b.x) listOf(a, b) else listOf(b, a)
            } else {
                val isVerticalSplit = abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f
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
                translation = blocksOrdered.joinToString(" ") { it.translation.trim() }.trim(),
                width = finalWidth,
                height = maxY - minY,
                x = finalX,
                y = minY,
                angle = if (lenA >= lenB) a.angle else b.angle,
                symWidth = (a.symWidth * lenA + b.symWidth * lenB) / totalLen,
                symHeight = (a.symHeight * lenA + b.symHeight * lenB) / totalLen
            )
        }
    }
}
