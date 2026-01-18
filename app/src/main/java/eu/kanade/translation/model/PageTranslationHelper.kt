package eu.kanade.translation.model

import kotlin.math.abs
import kotlin.math.sqrt

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

            // 3. العتبات
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

            // 4. حلقة الدمج الذكية
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
            // أ- فحص الزوايا
            val angleSimilar = abs(abs(r1.angle) - abs(r2.angle)) < 12

            // ب- منطق "نصف القطر" المقترح من قبلك
            val center1X = r1.x + r1.width / 2f
            val center1Y = r1.y + r1.height / 2f
            val center2X = r2.x + r2.width / 2f
            val center2Y = r2.y + r2.height / 2f

            val dx = center1X - center2X
            val dy = center1Y - center2Y
            val actualDistance = sqrt(dx * dx + dy * dy)

            // تطبيق قاعدة radius = max(w, h) / 2
            val radius1 = maxOf(r1.width, r1.height) / 2f
            val radius2 = maxOf(r2.width, r2.height) / 2f
            
            // إذا كان المركزان قريبين ضمن نصف القطر، ادمج فوراً (قوي جداً للفقاعات المنفصلة)
            if (actualDistance <= (radius1 + radius2) * 0.8f) return angleSimilar

            // ج- المنطق التقليدي (في حال فشل نصف القطر)
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height
            val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
            val avgSymHeight = maxOf(r1.symHeight, r2.symHeight)
            val closeVertically = verticalGap <= avgSymHeight * yThresholdFactor

            val centerDiffX = abs(center1X - center2X)
            val overlapX = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
            val closeHorizontally = (centerDiffX <= avgSymWidth * xThreshold) || (overlapX > 0)

            return angleSimilar && closeVertically && closeHorizontally
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

            // للمانجا اليابانية لا نستخدم مسافة، للويبتون نستخدم مسافة
            val separator = if (isWebtoon) " " else ""

            return TranslationBlock(
                text = blocksOrdered.joinToString(separator) { it.text.trim() },
                translation = blocksOrdered.joinToString(separator) { it.translation.trim() }.trim(),
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = if (abs(a.angle) <= abs(b.angle)) a.angle else b.angle,
                symWidth = (a.symWidth * a.text.length + b.symWidth * b.text.length) / (a.text.length + b.text.length).coerceAtLeast(1),
                symHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / (a.text.length + b.text.length).coerceAtLeast(1)
            )
        }
    }
}
