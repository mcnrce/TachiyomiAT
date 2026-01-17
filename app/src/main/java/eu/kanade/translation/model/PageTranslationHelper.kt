package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {
        // Regex لتنظيف الروابط (يستخدم في مرحلة التصفية)
        private val NOISE_REGEX = Regex("(?i).*\\.(com|net|org|co|me|io|link|info).*|.*(discord\\.gg|http|www).*")

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            imgWidth: Float,
            imgHeight: Float
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            // 1. التصفية الأولية
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

            // ضبط العتبات
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

            // --- الجولة الأولى: الخوارزمية الأصلية (الأفقية) ---
            result = runMergeCycle(result, isWebtoon) { b1, b2 ->
                shouldMergeOriginal(b1, b2, xThreshold, yThresholdFactor)
            }

            // --- الجولة الثانية: الخوارزمية اليابانية (للأعمدة) ---
            // نتحقق إذا كان هناك أي نص عمودي متبقي لم يندمج
            val hasVertical = result.any { abs(it.angle) in 80.0..100.0 }
            if (hasVertical) {
                result = runMergeCycle(result, isWebtoon) { b1, b2 ->
                    shouldMergeVerticalJapanese(b1, b2, xThreshold)
                }
            }

            return result
        }

        // دالة مساعدة لتنفيذ حلقات الدمج (لتقليل تكرار الكود)
        private fun runMergeCycle(
            inputBlocks: MutableList<TranslationBlock>,
            isWebtoon: Boolean,
            checkLogic: (TranslationBlock, TranslationBlock) -> Boolean
        ): MutableList<TranslationBlock> {
            val list = inputBlocks.toMutableList()
            var mergedAny: Boolean
            do {
                mergedAny = false
                var i = 0
                while (i < list.size) {
                    var j = i + 1
                    while (j < list.size) {
                        if (checkLogic(list[i], list[j])) {
                            list[i] = performMerge(list[i], list[j], isWebtoon)
                            list.removeAt(j)
                            mergedAny = true
                        } else {
                            j++
                        }
                    }
                    i++
                }
            } while (mergedAny)
            return list
        }

        // الخوارزمية الأصلية (التي لا تريد لمسها)
        private fun shouldMergeOriginal(r1: TranslationBlock, r2: TranslationBlock, xT: Float, yT: Float): Boolean {
            val angleSimilar = abs(abs(r1.angle) - abs(r2.angle)) < 12
            val verticalGap = if (r1.y < r2.y) r2.y - (r1.y + r1.height) else r1.y - (r2.y + r2.height)
            val closeVertically = verticalGap <= maxOf(r1.symHeight, r2.symHeight) * yT
            val centerDiff = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
            val overlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            val closeHorizontally = (centerDiff <= ((r1.symWidth + r2.symWidth) / 2f) * xT) || (overlap > 0)
            return angleSimilar && closeVertically && closeHorizontally
        }

        // الخوارزمية اليابانية (المرحلة الثانية)
        private fun shouldMergeVerticalJapanese(r1: TranslationBlock, r2: TranslationBlock, xT: Float): Boolean {
            val isR1Vertical = abs(r1.angle) in 80.0..100.0
            val isR2Vertical = abs(r2.angle) in 80.0..100.0
            if (!isR1Vertical && !isR2Vertical) return false // لا تعمل إلا إذا كان أحدهما عمودياً

            val angleSimilar = abs(r1.angle - r2.angle) < 12 || abs(abs(r1.angle - r2.angle) - 180) < 12
            val horizontalGap = if (r1.x < r2.x) r2.x - (r1.x + r1.width) else r1.x - (r2.x + r2.width)
            val vOverlap = minOf(r1.y + r1.height, r2.y + r2.height) - maxOf(r1.y, r2.y)
            
            return angleSimilar && horizontalGap <= ((r1.symWidth + r2.symWidth) / 2f) * xT && vOverlap > 0
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
                if (isWebtoon) {
                    if (a.x < b.x) listOf(a, b) else listOf(b, a)
                } else {
                    if (a.x > b.x) listOf(a, b) else listOf(b, a)
                }
            }

            val mergedText = blocksOrdered.joinToString(" ") { it.text.trim() }
            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
            val totalLen = lenA + lenB

            return TranslationBlock(
                text = mergedText,
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
