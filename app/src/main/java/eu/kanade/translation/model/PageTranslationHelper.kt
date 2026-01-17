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

            // 1. التصفية
            val filteredBlocks = blocks.onEach { block ->
                val cleanText = block.text.trim()
                val isLink = isWebtoon && NOISE_REGEX.matches(cleanText)
                if (isLink || block.width <= 2 || block.height <= 2) {
                    block.translation = ""
                }
            }.filter { it.text.isNotBlank() }

            // 2. الترتيب الابتدائي
            val sortedBlocks = if (isWebtoon) {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            } else {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            }

            var result = sortedBlocks.toMutableList()

            // 3. ضبط العتبات
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)
            // 4. حلقة الدمج بتكرار مرتين لضمان دمج الكتل البعيدة
            repeat(2) {
                var mergedAny: Boolean
                do {
                    mergedAny = false
                    var i = 0
                    // إعادة تصميم الحلقة: عند حدوث دمج نعيد البدء من العنصر 0 لضمان عدم تفويت أزواج
                    outer@ while (i < result.size) {
                        var j = i + 1
                        while (j < result.size) {
                            if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                                result[i] = performMerge(result[i], result[j], isWebtoon)
                                result.removeAt(j)
                                mergedAny = true
                                // بعد الدمج، نعيد الفحص من البداية
                                i = 0
                                continue@outer
                            }
                            j++
                        }
                        i++
                    }
                } while (mergedAny)
            }
            return result
        }

        private fun shouldMerge(
    r1: TranslationBlock,
    r2: TranslationBlock,
    xThreshold: Float,
    yThresholdFactor: Float
): Boolean {
    // 1. تشابه الزوايا (تحسين للنص العمودي)
    val angleDiff = abs(r1.angle - r2.angle)
    val angleSimilar = angleDiff < 15 || abs(angleDiff - 180) < 15
    
    // 2. التقارب الرأسي
    val r1Bottom = r1.y + r1.height
    val r2Bottom = r2.y + r2.height
    val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
    val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
    
    val avgSymHeight = maxOf(r1.symHeight, r2.symHeight)
    val closeVertically = verticalGap <= avgSymHeight * yThresholdFactor * 2f || vOverlap > 0

    // 3. التقارب الأفقي (تحسين خاص للنص العمودي)
    val isVerticalText = abs(r1.angle) in 80.0..100.0 || abs(r2.angle) in 80.0..100.0
    
    if (isVerticalText) {
        // للنص العمودي، نتحقق من المحاذاة الأفقية بشكل مختلف
        val leftDiff = abs(r1.x - r2.x)
        val rightDiff = abs((r1.x + r1.width) - (r2.x + r2.width))
        val avgCharWidth = maxOf(r1.symWidth, r2.symWidth)
        
        val horizontallyAligned = leftDiff <= avgCharWidth * 3f || rightDiff <= avgCharWidth * 3f
        
        // للنص العمودي، نكون أكثر تساهلاً مع الفجوة الأفقية
        val hOverlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
        val hGap = if (r1.x < r2.x) r2.x - (r1.x + r1.width) else r1.x - (r2.x + r2.width)
        
        val closeHorizontally = horizontallyAligned || hOverlap > -avgCharWidth * 2f
        val closeVerticallyForVerticalText = vOverlap > avgSymHeight * 0.3f || verticalGap < avgSymHeight * 1.5f
        
        return angleSimilar && closeVerticallyForVerticalText && closeHorizontally
    } else {
        // النص الأفقي (الكود الأصلي مع تحسينات طفيفة)
        val centerDiff = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
        val hOverlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
        val hGap = if (r1.x < r2.x) r2.x - (r1.x + r1.width) else r1.x - (r2.x + r2.width)
        
        val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
        val closeHorizontally = (centerDiff <= avgSymWidth * xThreshold) || 
                               (hOverlap > 0) || 
                               (hGap < avgSymWidth * 1.2f)
        
        return angleSimilar && closeVertically && closeHorizontally
    }
        }
        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            val isVerticalSplit = abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f
            val blocksOrdered = if (isVerticalSplit) {
                if (a.y < b.y) listOf(a, b) else listOf(b, a)
            } else {
                if (isWebtoon) (if (a.x < b.x) listOf(a, b) else listOf(b, a))
                else (if (a.x > b.x) listOf(a, b) else listOf(b, a))
            }

            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
            val totalLen = lenA + lenB

            return TranslationBlock(
                text = blocksOrdered.joinToString(" ") { it.text.trim() },
                translation = blocksOrdered.joinToString(" ") { it.translation.trim() }.trim(),
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = if (abs(a.angle) <= abs(b.angle)) a.angle else b.angle,
                symWidth = (a.symWidth * lenA + b.symWidth * lenB) / totalLen,
                symHeight = (a.symHeight * lenA + b.symHeight * lenB) / totalLen
            )
        }
    }
}
