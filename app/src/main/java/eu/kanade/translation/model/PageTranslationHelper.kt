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

            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            val filteredBlocks = blocks.onEach { block ->
                val cleanText = block.text.trim()
                val isLink = NOISE_REGEX.matches(cleanText)
                if (isLink || block.width <= 2 || block.height <= 2) {
                    block.translation = ""
                }
            }.filter { it.text.isNotBlank() }

            val sortedBlocks = if (isWebtoon) {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            } else {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            }

            var result = sortedBlocks.toMutableList()

            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

            var i = 0
            outer@ while (i < result.size) {
                var j = i + 1
                while (j < result.size) {
                    if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        result.removeAt(j)
                        i = 0
                        continue@outer
                    }
                    j++
                }
                i++
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
            val angleSimilar = angleDiff < 8 || abs(angleDiff - 180) < 8
            if (!angleSimilar) return false

            val isVertical = abs(r1.angle) in 75.0..105.0

            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
            val avgSymHeight = (r1.symHeight + r2.symHeight) / 2f

            return if (isVertical) {
                /* منطق المانجا العمودية المحسن بناءً على بيانات المستخدم */
                
                val xDist = abs(r1.x - r2.x)
                val yDist = abs(r1.y - r2.y)
                
                // 1. فحص قرب نقطة الأصل (Top-Left) مع رفع السماحية لـ 2.0f
                val isOriginsClose = yDist < (avgSymHeight * 2.0f) && xDist < (avgSymWidth * 4.0f)

                // 2. فحص تقارب (x + width) لكتلة مع (x) للأخرى (الدمج المتسلسل)
                val distRightToLeftX = if (r1.x < r2.x) abs(r1Right - r2.x) else abs(r2Right - r1.x)
                val isEndToStartClose = yDist < (avgSymHeight * 2.0f) && distRightToLeftX < (avgSymWidth * 2.5f)

                // 3. المنطق الكلاسيكي للفجوات مع تقليل شرط التداخل الرأسي (0.2f)
                val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
                val closeHorizontally = hGap < avgSymWidth * 2.0f
                val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
                val alignedVertically = vOverlap > (avgSymHeight * 0.2f) 

                isOriginsClose || isEndToStartClose || (closeHorizontally && alignedVertically)
            } else {
                /* منطق الأسطر الأفقية */
                val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
                val closeVertically = verticalGap <= avgSymHeight * (yThresholdFactor * 0.8f)
                
                val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
                val closeHorizontally = if (hOverlap > avgSymWidth * 0.5f) {
                    val centerDiff = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
                    centerDiff < avgSymWidth * xThreshold
                } else {
                    val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
                    hGap < avgSymWidth * 1.5f 
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

            // زيادة العرض بنسبة 30% فقط للنصوص العمودية (المانجا)
            val isVertical = abs(a.angle) in 75.0..105.0
            if (isVertical) {
                val expansion = finalWidth * 0.30f
                finalWidth += expansion
                finalX -= expansion / 2f // التوسيع من المركز للحفاظ على التوازن
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
