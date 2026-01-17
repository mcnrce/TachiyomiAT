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

            // 1. التصفية والترتيب الأولي
            val filteredBlocks = blocks.onEach { block ->
                val cleanText = block.text.trim()
                if ((isWebtoon && NOISE_REGEX.matches(cleanText)) || block.width <= 2 || block.height <= 2) {
                    block.translation = ""
                }
            }.filter { it.text.isNotBlank() }

            var result = filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x }).toMutableList()

            // ضبط العتبات
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

            // 2. الجولة الأولى: التركيز على الدمج الرأسي (الأسطر فوق بعضها)
            // هنا نضع شروطاً صارمة للأفقية ومرنة للرأسية لتشكيل الأعمدة أولاً
            result = runMergeCycle(result, isWebtoon) { b1, b2 ->
                shouldMergeVerticalFirst(b1, b2, xThreshold, yThresholdFactor)
            }

            // 3. الجولة الثانية: دمج الأعمدة المكتملة بجانب بعضها (أفقياً)
            // هنا نفتح المجال لدمج الكتل التي تقع بجوار بعضها لتكوين الفقاعة
            result = runMergeCycle(result, isWebtoon) { b1, b2 ->
                shouldMergeHorizontalSecond(b1, b2, xThreshold)
            }

            return result
        }

        // دالة تشكيل الأعمدة (الأسطر فوق بعضها)
        private fun shouldMergeVerticalFirst(r1: TranslationBlock, r2: TranslationBlock, xT: Float, yT: Float): Boolean {
            val angleSimilar = abs(abs(r1.angle) - abs(r2.angle)) < 12
            
            val vGap = if (r1.y < r2.y) r2.y - (r1.y + r1.height) else r1.y - (r2.y + r2.height)
            val hOverlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            
            // يجب أن يكون هناك تداخل أفقي (واحد فوق الآخر) وفجوة رأسية صغيرة
            val closeVertically = vGap <= maxOf(r1.symHeight, r2.symHeight) * yT
            val isStacked = hOverlap > 0 || abs((r1.x + r1.width/2f) - (r2.x + r2.width/2f)) < (r1.symWidth * xT)

            return angleSimilar && closeVertically && isStacked
        }

        // دالة دمج الأعمدة بجانب بعضها (الفقاعة اليابانية)
        private fun shouldMergeHorizontalSecond(r1: TranslationBlock, r2: TranslationBlock, xT: Float): Boolean {
            val angleSimilar = abs(r1.angle - r2.angle) < 15 || abs(abs(r1.angle - r2.angle) - 180) < 15
            
            val hGap = if (r1.x < r2.x) r2.x - (r1.x + r1.width) else r1.x - (r2.x + r2.width)
            val vOverlap = minOf(r1.y + r1.height, r2.y + r2.height) - maxOf(r1.y, r2.y)
            
            // تقارب أفقي (جنباً إلى جنب) مع وجود تداخل رأسي (الأعمدة متوازية)
            val closeHorizontally = hGap <= ((r1.symWidth + r2.symWidth) / 2f) * xT
            val isParallel = vOverlap > 0

            return angleSimilar && closeHorizontally && isParallel
        }

        private fun runMergeCycle(list: MutableList<TranslationBlock>, isWebtoon: Boolean, checkLogic: (TranslationBlock, TranslationBlock) -> Boolean): MutableList<TranslationBlock> {
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
