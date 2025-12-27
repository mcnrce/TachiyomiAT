package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {  

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            xThreshold: Float = 60f,
            yThresholdFactor: Float = 1.6f
        ): MutableList<TranslationBlock> {
            if (blocks.isEmpty()) return mutableListOf()

            val result = blocks.filter { it.text.isNotBlank() && it.width > 2 && it.height > 2 }
                .toMutableList()

            var mergedAny: Boolean
            mergeLoop@ do {
                mergedAny = false
                var i = 0
                while (i < result.size) {
                    var j = i + 1
                    while (j < result.size) {
                        if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
    result[i] = performMerge(result[i], result[j])
    result.removeAt(j)
    mergedAny = true
    break@mergeLoop   // ← هذا هو التعديل الجوهري
} else {
    j++
                        }
                    }
                    i++
                }
            } while (mergedAny)

            return result
        }

        private fun getTextLength(text: String): Int {
            val count = text.length
            return if (count < 1) 1 else count
        }

        private fun shouldMerge(r1: TranslationBlock, r2: TranslationBlock, xThreshold: Float, yThresholdFactor: Float): Boolean {
            val angleSimilar = abs(r1.angle - r2.angle) < 10

            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
            val avgSymHeight = (r1.symHeight + r2.symHeight) / 2f
            val closeVertically = verticalGap <= avgSymHeight * yThresholdFactor

            val left1 = r1.x
            val right1 = r1.x + r1.width
            val left2 = r2.x
            val right2 = r2.x + r2.width

            val horizontalOverlap = maxOf(0f, minOf(right1, right2) - maxOf(left1, left2))
            val closeHorizontally = horizontalOverlap > 0 || abs(left1 - left2) < xThreshold

            return angleSimilar && closeVertically && closeHorizontally
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            // ترتيب النصوص حسب الموضع الرأسي أولًا ثم الأفقي
            val blocksOrdered = listOf(a, b).sortedWith(compareBy({ it.y }, { it.x }))

            val mergedText = blocksOrdered.joinToString("\n") { it.text }
            val mergedTrans = blocksOrdered.joinToString("\n") { it.translation }.trim()

            val lenA = getTextLength(a.text)
            val lenB = getTextLength(b.text)
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
