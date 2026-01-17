package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            imgWidth: Float,
            imgHeight: Float
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            // 1. تحديد نوع المحتوى: مانهوا/ويب تون (طولي) أم مانجا تقليدية
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            // 2. تصفية النصوص والروابط الضارة
            val noisePatterns = listOf(".com", ".net", ".org", ".co", ".me", "discord.gg", "www.", "http", ".link")
            
            val filteredBlocks = blocks.onEach { block ->
                val cleanText = block.text.lowercase().trim()
                val isLink = noisePatterns.any { cleanText.contains(it) }
                
                if (isLink || block.width <= 2 || block.height <= 2) {
                    block.translation = ""
                }
            }.filter { it.text.isNotBlank() }

            // 3. الترتيب الابتدائي حسب اتجاه القراءة
            val sortedBlocks = if (isWebtoon) {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            } else {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            }

            val result = sortedBlocks.toMutableList()

            // 4. ضبط عتبات المسافة مع سقف حماية (Cap)
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(3f)).coerceAtLeast(1.0f)

            // 5. حلقة الدمج الذكية
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
                            // تم تمرير isWebtoon لضمان ترتيب الكلمات داخل السطر بشكل صحيح
                            result[i] = performMerge(first, second, isWebtoon)
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

        private fun shouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {
            val angleSimilar = abs(abs(r1.angle) - abs(r2.angle)) < 12

            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height
            val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
            
            val avgSymHeight = maxOf(r1.symHeight, r2.symHeight)
            val closeVertically = verticalGap <= avgSymHeight * yThresholdFactor

            val r1CenterX = r1.x + (r1.width / 2f)
            val r2CenterX = r2.x + (r2.width / 2f)
            val centerDiff = abs(r1CenterX - r2CenterX)
            val overlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            
            val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
            val closeHorizontally = (centerDiff <= avgSymWidth * xThreshold) || (overlap > 0)

            return angleSimilar && closeVertically && closeHorizontally
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            // ترتيب العناصر المدمجة بناءً على السياق (رأسي أولاً ثم أفقي حسب النوع)
            val blocksOrdered = if (abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f) {
                if (a.y < b.y) listOf(a, b) else listOf(b, a)
            } else {
                if (isWebtoon) {
                    if (a.x < b.x) listOf(a, b) else listOf(b, a) // LTR
                } else {
                    if (a.x > b.x) listOf(a, b) else listOf(b, a) // RTL (Manga)
                }
            }

            val mergedText = blocksOrdered.joinToString(" ") { it.text.trim() }
            val mergedTrans = blocksOrdered.joinToString(" ") { it.translation.trim() }.trim()

            // حساب الأوزان بناءً على طول النص لضمان دقة قياس الحروف (Weighted Mean)
            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
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
                angle = if (abs(a.angle) <= abs(b.angle)) a.angle else b.angle,
                symWidth = finalSymWidth,
                symHeight = finalSymHeight
            )
        }
    }
}
