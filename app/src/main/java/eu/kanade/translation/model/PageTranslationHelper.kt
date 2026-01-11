package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            xThreshold: Float = 2.5f,
            yThresholdFactor: Float = 1.6f,
            mergeThreshold: Float = 0.4f
        ): MutableList<TranslationBlock> {

            val result = blocks.filter { it.text.isNotBlank() && it.width > 2 && it.height > 2 }.toMutableList()

            var merged: Boolean
            do {
                merged = false
                var bestScore = 0f
                var bestI = -1
                var bestJ = -1

                for (i in 0 until result.size) {
                    for (j in i + 1 until result.size) {
                        val score = mergeScore(result[i], result[j], xThreshold, yThresholdFactor)
                        if (score > bestScore) {
                            bestScore = score
                            bestI = i
                            bestJ = j
                        }
                    }
                }

                if (bestScore >= mergeThreshold && bestI >= 0 && bestJ >= 0) {
                    val mergedBlock = performMerge(result[bestI], result[bestJ])
                    result[bestI] = mergedBlock
                    result.removeAt(bestJ)
                    merged = true
                }

            } while (merged)

            return result
        }

        private fun getTextLength(text: String): Int {
            val count = text.length
            return if (count < 1) 1 else count
        }

        private fun mergeScore(
            a: TranslationBlock,
            b: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Float {

            val angleDiff = abs(abs(a.angle) - abs(b.angle))
            if (angleDiff > 12f) return 0f

            val verticalGap = if (a.y < b.y) b.y - (a.y + a.height) else a.y - (b.y + b.height)
            val avgSymHeight = (a.symHeight + b.symHeight) / 2f
            val vScore = 1f - (verticalGap / (avgSymHeight * yThresholdFactor))
            if (vScore <= 0f) return 0f

            val leftA = a.x
            val rightA = a.x + a.width
            val leftB = b.x
            val rightB = b.x + b.width

            val overlap = maxOf(0f, minOf(rightA, rightB) - maxOf(leftA, leftB))
            val avgSymWidth = (a.symWidth + b.symWidth) / 2f
            val hScore = if (overlap > 0) 1f else 1f - abs(leftA - leftB) / (avgSymWidth * xThreshold)
            if (hScore <= 0f) return 0f

            val centerDist = abs((a.x + a.width / 2f) - (b.x + b.width / 2f)) / avgSymWidth
            val cScore = 1f - centerDist

            return vScore * 0.5f + hScore * 0.3f + cScore * 0.2f
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock): TranslationBlock {

            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            val isVerticalA = a.height.toFloat() / a.width > 1.2
            val isVerticalB = b.height.toFloat() / b.width > 1.2
            val isVertical = isVerticalA || isVerticalB

            val blocksOrdered = if (isVertical) {
                if (a.y < b.y || (a.y == b.y && a.x >= b.x)) listOf(a, b) else listOf(b, a)
            } else {
                if (a.y < b.y || (a.y == b.y && a.x <= b.x)) listOf(a, b) else listOf(b, a)
            }

            val mergedText = blocksOrdered[0].text + "\n" + blocksOrdered[1].text
            val mergedTrans = (blocksOrdered[0].translation + "\n" + blocksOrdered[1].translation).trim()

            val lenA = getTextLength(blocksOrdered[0].text)
            val lenB = getTextLength(blocksOrdered[1].text)
            val totalLen = lenA + lenB

            val finalSymWidth = (blocksOrdered[0].symWidth * lenA + blocksOrdered[1].symWidth * lenB) / totalLen
            val finalSymHeight = (blocksOrdered[0].symHeight * lenA + blocksOrdered[1].symHeight * lenB) / totalLen

            val finalAngle = if (abs(blocksOrdered[0].angle) <= abs(blocksOrdered[1].angle))
                blocksOrdered[0].angle else blocksOrdered[1].angle

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
