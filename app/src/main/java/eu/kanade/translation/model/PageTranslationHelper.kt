package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {
        // Regex احترافي لفلترة الروابط وضجيج الحقوق
        private val NOISE_REGEX = Regex("(?i).*\\.(com|net|org|co|me|io|link|info).*|.*(discord\\.gg|http|www).*")

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            imgWidth: Float,
            imgHeight: Float
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            // 1. التصفية الابتدائية (روابط، أحجام متناهية الصغر)
            val filteredBlocks = blocks.onEach { block ->
                val cleanText = block.text.trim()
                val isLink = NOISE_REGEX.matches(cleanText)
                
                if (isLink || block.width <= 2 || block.height <= 2) {
                    block.translation = ""
                }
            }.filter { it.text.isNotBlank() }

            // 2. الترتيب الابتدائي حسب نوع العمل (ويب تون أو مانجا)
            val sortedBlocks = if (isWebtoon) {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            } else {
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            }

            var result = sortedBlocks.toMutableList()

            // 3. عتبات الدمج (تعتمد على حجم الصورة)
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

            // 5. الخطوة النهائية: تحويل الهياكل العمودية إلى أفقية للترجمة
            return finalizeVerticalBlocks(result).toMutableList()
        }

        private fun shouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {
            val angleDiff = abs(r1.angle - r2.angle)
            val angleSimilar = angleDiff < 8 || abs(angleDiff - 180) < 8

            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height
            val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
            
            val avgSymHeight = (r1.symHeight + r2.symHeight) / 2f
            val closeVertically = verticalGap <= avgSymHeight * (yThresholdFactor * 0.8f)

            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
            val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f

            val closeHorizontally = if (hOverlap > avgSymWidth * 0.5f) {
                val centerDiff = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
                centerDiff < avgSymWidth * xThreshold
            } else {
                val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
                hGap < avgSymWidth * 1.5f 
            }

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
                angle = if (lenA >= lenB) a.angle else b.angle,
                symWidth = (a.symWidth * lenA + b.symWidth * lenB) / totalLen,
                symHeight = (a.symHeight * lenA + b.symHeight * lenB) / totalLen
            )
        }

        /**
         * دالة معالجة النصوص العمودية: تحويل المستطيلات الطويلة إلى مستطيلات عرضية
         * لتناسب اللغات الأفقية (العربية/الإنجليزية) ومنع تداخل الكلمات.
         */
        private fun finalizeVerticalBlocks(blocks: List<TranslationBlock>): List<TranslationBlock> {
            return blocks.onEach { block ->
                // كشف إذا كان البلوك عمودياً بالزاوية أو بالشكل (طويل ونحيف)
                val isVerticalAngle = abs(block.angle) in 75.0..105.0
                val isVerticalShape = block.height > block.width * 1.4f 

                if ((isVerticalAngle || isVerticalShape) && block.translation.isNotBlank()) {
                    // 1. حفظ المركز الأصلي للفقاعة
                    val centerX = block.x + block.width / 2f
                    val centerY = block.y + block.height / 2f

                    // 2. إعادة حساب الأبعاد: جعل العرض هو الارتفاع السابق مع زيادة طفيفة
                    // الارتفاع يصبح أقل ليتناسب مع سطر أو سطرين أوفقيين
                    val newWidth = block.height * 1.1f
                    val newHeight = (block.width * 1.5f).coerceAtLeast(block.symHeight * 1.8f)

                    block.width = newWidth
                    block.height = newHeight

                    // 3. تحديث الإحداثيات بناءً على المركز الجديد
                    block.x = centerX - (newWidth / 2f)
                    block.y = centerY - (newHeight / 2f)

                    // 4. تصحيح الزاوية لتصبح أفقية تماماً (0 درجة)
                    block.angle = 0f

                    // 5. تنظيف النص المترجم من تقسيم الأسطر العمودي القديم
                    // ليتم توزيعه أفقياً داخل العرض الجديد
                    block.translation = block.translation.replace("\n", " ").trim()
                }
            }
        }
    }
}
