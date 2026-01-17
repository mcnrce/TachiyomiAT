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

            // 1. التصفية والترتيب
            var result = blocks.onEach { block ->
                val cleanText = block.text.trim()
                if ((isWebtoon && NOISE_REGEX.matches(cleanText)) || block.width <= 2 || block.height <= 2) {
                    block.text = ""
                    block.translation = ""
                }
            }.filter { it.text.isNotBlank() }
             .sortedWith(if (isWebtoon) compareBy<TranslationBlock> { it.y }.thenBy { it.x } 
                         else compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
             .toMutableList()

            // ضبط العتبات
            val xT = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yT = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

            // --- تنفيذ الفكرة: تكرار دورتين كاملتين للدمج ---
            repeat(2) {
                // الجولة أ: الدمج الأصلي (الأفقي)
                result = runMergeCycle(result, isWebtoon) { b1, b2 ->
                    shouldMergeOriginal(b1, b2, xT, yT)
                }

                // الجولة ب: الدمج الياباني (العمودي) - يتم استدعاؤه دائماً لضمان الربط
                result = runMergeCycle(result, isWebtoon) { b1, b2 ->
                    shouldMergeVerticalJapanese(b1, b2, xT * 1.8f) // تسامح أكبر في التكرار
                }
            }

            return result
        }

        private fun runMergeCycle(
            list: MutableList<TranslationBlock>,
            isWebtoon: Boolean,
            checkLogic: (TranslationBlock, TranslationBlock) -> Boolean
        ): MutableList<TranslationBlock> {
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

        private fun shouldMergeOriginal(r1: TranslationBlock, r2: TranslationBlock, xT: Float, yT: Float): Boolean {
            val angleSimilar = abs(abs(r1.angle) - abs(r2.angle)) < 12
            val vGap = if (r1.y < r2.y) r2.y - (r1.y + r1.height) else r1.y - (r2.y + r2.height)
            val closeVertically = vGap <= maxOf(r1.symHeight, r2.symHeight) * yT
            val centerDiff = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
            val overlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            return angleSimilar && closeVertically && (centerDiff <= ((r1.symWidth + r2.symWidth) / 2f) * xT || overlap > 0)
        }

        private fun shouldMergeVerticalJapanese(r1: TranslationBlock, r2: TranslationBlock, xT: Float): Boolean {
            val isVertical = abs(r1.angle) in 80.0..100.0 || abs(r2.angle) in 80.0..100.0
            if (!isVertical) return false

            val angleSimilar = abs(r1.angle - r2.angle) < 15 || abs(abs(r1.angle - r2.angle) - 180) < 15
            val hGap = if (r1.x < r2.x) r2.x - (r1.x + r1.width) else r1.x - (r2.x + r2.width)
            
            // تحسين: التحقق من القرب الرأسي لضمان دمج الأعمدة حتى لو لم تتداخل تماماً
            val vOverlap = minOf(r1.y + r1.height, r2.y + r2.height) - maxOf(r1.y, r2.y)
            val vGap = if (r1.y + r1.height < r2.y) r2.y - (r1.y + r1.height) else if (r2.y + r2.height < r1.y) r1.y - (r2.y + r2.height) else 0f
            val isCloseVertically = vOverlap > 0 || vGap < maxOf(r1.symHeight, r2.symHeight) * 2f

            return angleSimilar && hGap <= ((r1.symWidth + r2.symWidth) / 2f) * xT && isCloseVertically
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
