package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            xThreshold: Float = 2.5f,
            yThresholdFactor: Float = 1.6f
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            val result = blocks.filter { block ->
                block.text.isNotBlank() && block.width > 2 && block.height > 2
            }.toMutableList()

            var mergedAny: Boolean
            do {
                mergedAny = false
                var i = 0
                while (i < result.size) {
                    var j = i + 1
                    while (j < result.size) {
                        val first = result[i]
                        val second = result[j]

                        if (shouldMerge(first, second, xThreshold, yThresholdFactor)) {
                            result[i] = performMerge(first, second)
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

        private fun getTextLength(text: String): Int {
            val count = text.length
            return if (count < 1) 1 else count
        }

        private fun shouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {

            val angleSimilar = abs(r1.angle - r2.angle) < 10

            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            val verticalGap =
                if (r1.y < r2.y) r2.y - r1Bottom
                else r1.y - r2Bottom

            val avgSymHeight = (r1.symHeight + r2.symHeight) / 2f
            val closeVertically = verticalGap <= avgSymHeight * yThresholdFactor

            val left1 = r1.x
            val right1 = r1.x + r1.width
            val left2 = r2.x
            val right2 = r2.x + r2.width

            val horizontalOverlap =
                maxOf(0f, minOf(right1, right2) - maxOf(left1, left2))

            val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
            val closeHorizontally =
                horizontalOverlap > 0 ||
                abs(left1 - left2) <= avgSymWidth * xThreshold

            return angleSimilar && closeVertically && closeHorizontally
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock): TranslationBlock {

            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            val blocksOrdered =
                if (a.y < b.y || (a.y == b.y && a.x <= b.x))
                    listOf(a, b)
                else
                    listOf(b, a)

            val mergedText =
                blocksOrdered[0].text + "\n" + blocksOrdered[1].text

            val mergedTrans =
                (blocksOrdered[0].translation + "\n" +
                 blocksOrdered[1].translation).trim()

            val lenA = getTextLength(a.text)
            val lenB = getTextLength(b.text)
            val totalLen = lenA + lenB

            val finalSymWidth =
                (a.symWidth * lenA + b.symWidth * lenB) / totalLen

            val finalSymHeight =
                (a.symHeight * lenA + b.symHeight * lenB) / totalLen

            // الميلان النهائي = الأقرب للصفر
            val finalAngle =
                if (abs(a.angle) <= abs(b.angle)) a.angle else b.angle

            return TranslationBlock(
                text = mergedText,
                translation = mergedTrans,
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = finalAngle,
                symWidth = finalSymWidth,
                symHeight = finalSymHeight
            )
        }
    }
}
