package eu.kanade.translation.model

import kotlin.math.abs
import java.util.HashMap
import java.util.ArrayList

class PageTranslationHelper {

    companion object {

        fun mergeWithGraph(blocks: List<TranslationBlock>): List<TranslationBlock> {
            if (blocks.isEmpty()) return emptyList()

            // فرز أولي لتقليل المقارنات
            val sorted = blocks.sortedBy { it.y }
            val parent = IntArray(sorted.size) { it }

            fun find(x: Int): Int {
                if (parent[x] != x) {
                    parent[x] = find(parent[x])
                }
                return parent[x]
            }

            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[rb] = ra
            }

            // بناء الروابط
            for (i in sorted.indices) {
                val a = sorted[i]
                var j = i + 1
                while (j < sorted.size) {
                    val b = sorted[j]

                    // إيقاف مبكر إذا ابتعدت رأسيًا كثيرًا
                    val verticalGap = b.y - (a.y + a.height)
                    if (verticalGap > a.symHeight * 2f) break

                    if (shouldMergeStrict(a, b)) {
                        union(i, j)
                    }
                    j++
                }
            }

            // تجميع المكونات المتصلة
            val groups = HashMap<Int, MutableList<TranslationBlock>>()
            for (i in sorted.indices) {
                val root = find(i)
                groups.getOrPut(root) { mutableListOf() }.add(sorted[i])
            }

            // دمج كل مجموعة
            val result = ArrayList<TranslationBlock>()
            for (group in groups.values) {
                result.add(mergeGroup(group))
            }

            return result
        }

        private fun mergeGroup(group: List<TranslationBlock>): TranslationBlock {
            val ordered = group.sortedWith(compareBy({ it.y }, { it.x }))

            val minX = ordered.minOf { it.x }
            val minY = ordered.minOf { it.y }
            val maxX = ordered.maxOf { it.x + it.width }
            val maxY = ordered.maxOf { it.y + it.height }

            var totalLen = 0
            var sumSymW = 0f
            var sumSymH = 0f

            for (b in ordered) {
                val len = maxOf(1, b.text.length)
                totalLen += len
                sumSymW += b.symWidth * len
                sumSymH += b.symHeight * len
            }

            // اختيار زاوية واحدة فقط: الأقرب للصفر
            val finalAngle = ordered.minBy { abs(it.angle) }.angle

            return TranslationBlock(
                text = ordered.joinToString("\n") { it.text },
                translation = ordered.joinToString("\n") { it.translation },
                x = minX,
                y = minY,
                width = maxX - minX,
                height = maxY - minY,
                angle = finalAngle,
                symWidth = sumSymW / totalLen,
                symHeight = sumSymH / totalLen
            )
        }

        private fun shouldMergeStrict(a: TranslationBlock, b: TranslationBlock): Boolean {
            if (abs(a.angle - b.angle) >= 10) return false

            val left = maxOf(a.x, b.x)
            val right = minOf(a.x + a.width, b.x + b.width)
            val horizontalOverlap = right - left

            if (horizontalOverlap <= 0) return false

            val verticalGap =
                if (a.y + a.height < b.y) b.y - (a.y + a.height)
                else if (b.y + b.height < a.y) a.y - (b.y + b.height)
                else 0

            return verticalGap <= (a.symHeight + b.symHeight) / 2f * 1.6f
        }
    }
}
