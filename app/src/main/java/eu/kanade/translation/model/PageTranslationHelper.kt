package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {

        fun mergeWithGraph(
            blocks: List<TranslationBlock>,
            xThreshold: Float = 2.5f,
            yThresholdFactor: Float = 1.6f
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            val n = blocks.size
            val parent = IntArray(n) { it }

            fun find(x: Int): Int {
                var cur = x
                while (parent[cur] != cur) {
                    parent[cur] = parent[parent[cur]]
                    cur = parent[cur]
                }
                return cur
            }

            fun union(a: Int, b: Int) {
                val rootA = find(a)
                val rootB = find(b)
                if (rootA != rootB) parent[rootB] = rootA
            }

            fun shouldMergeStrict(a: TranslationBlock, b: TranslationBlock): Boolean {
                val angleCheck = abs(a.angle - b.angle) < 10

                val r1Bottom = a.y + a.height
                val r2Bottom = b.y + b.height
                val verticalGap =
                    if (a.y < b.y) b.y - r1Bottom else a.y - r2Bottom

                val avgSymHeight = (a.symHeight + b.symHeight) / 2f
                val closeVertically =
                    verticalGap <= avgSymHeight * yThresholdFactor

                val horizontalOverlap =
                    maxOf(
                        0f,
                        minOf(a.x + a.width, b.x + b.width) -
                        maxOf(a.x, b.x)
                    )

                val avgSymWidth = (a.symWidth + b.symWidth) / 2f
                val closeHorizontally =
                    horizontalOverlap > 0 ||
                    abs(a.x - b.x) <= avgSymWidth * xThreshold

                val aInsideB =
                    a.x >= b.x && a.x + a.width <= b.x + b.width &&
                    a.y >= b.y && a.y + a.height <= b.y + b.height

                val bInsideA =
                    b.x >= a.x && b.x + b.width <= a.x + a.width &&
                    b.y >= a.y && b.y + b.height <= a.y + a.height

                return angleCheck &&
                        ((closeHorizontally && closeVertically) || aInsideB || bInsideA)
            }

            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (shouldMergeStrict(blocks[i], blocks[j])) {
                        union(i, j)
                    }
                }
            }

            val groups = mutableMapOf<Int, MutableList<TranslationBlock>>()
            for (i in 0 until n) {
                val root = find(i)
                groups.computeIfAbsent(root) { mutableListOf() }.add(blocks[i])
            }

            val mergedBlocks = mutableListOf<TranslationBlock>()
            for ((_, group) in groups) {
                val sortedGroup = group.sortedWith(compareBy({ it.y }, { it.x }))

                val minX = sortedGroup.minOf { it.x }
                val minY = sortedGroup.minOf { it.y }
                val maxX = sortedGroup.maxOf { it.x + it.width }
                val maxY = sortedGroup.maxOf { it.y + it.height }

                val totalLen =
                    sortedGroup.sumOf { it.text.length.coerceAtLeast(1) }

                val finalSymWidth =
                    sortedGroup.sumOf {
                        it.symWidth * it.text.length.coerceAtLeast(1)
                    } / totalLen

                val finalSymHeight =
                    sortedGroup.sumOf {
                        it.symHeight * it.text.length.coerceAtLeast(1)
                    } / totalLen

                // الميلان النهائي = الأقرب للصفر
                var finalAngle = sortedGroup.first().angle
                for (b in sortedGroup) {
                    if (abs(b.angle) < abs(finalAngle)) {
                        finalAngle = b.angle
                    }
                }

                val mergedText =
                    sortedGroup.joinToString("\n") { it.text }

                val mergedTrans =
                    sortedGroup.joinToString("\n") { it.translation }.trim()

                mergedBlocks.add(
                    TranslationBlock(
                        text = mergedText,
                        translation = mergedTrans,
                        x = minX,
                        y = minY,
                        width = maxX - minX,
                        height = maxY - minY,
                        angle = finalAngle,
                        symWidth = finalSymWidth,
                        symHeight = finalSymHeight
                    )
                )
            }

            return mergedBlocks
        }
    }
}
