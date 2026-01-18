package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {
        private val NOISE_REGEX = Regex("(?i).*\\.(com|net|org|co|me|io|link|info).*|.*(discord\\.gg|http|www).*")
        
        // خوارزمية دمج احتياطية
        private fun backupShouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float,
            readingDirectionRightToLeft: Boolean
        ): Boolean {
            // 1. خوارزمية الدمج العمودي (للمانجا)
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height
            val verticalOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
            
            // إذا كان هناك تداخل رأسي كبير، فهما في نفس السطر
            if (verticalOverlap > maxOf(r1.symHeight, r2.symHeight) * 0.7f) {
                val distanceX = abs(r1.x - r2.x)
                val avgCharWidth = (r1.symWidth + r2.symWidth) / 2f
                
                // المسافة الأفقية يجب أن تكون صغيرة للكتل في نفس السطر
                return distanceX < avgCharWidth * xThreshold * 3f
            }
            
            // 2. خوارزمية الدمج الأفقي (للكلمات المنفصلة في سطر واحد)
            val hOverlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            if (hOverlap > 0) {
                val verticalDistance = abs(r1.y - r2.y)
                return verticalDistance < maxOf(r1.symHeight, r2.symHeight) * 2f
            }
            
            // 3. خوارزمية خاصة بالقراءة من اليمين لليسار
            if (readingDirectionRightToLeft) {
                // للمانجا: النص من اليمين لليسار
                val r1Right = r1.x + r1.width
                val r2Right = r2.x + r2.width
                
                // إذا كانت الكتلة الثانية على يسار الأولى (في اتجاه القراءة)
                val inReadingOrder = r2.x < r1.x && abs(r1.y - r2.y) < r1.symHeight * 1.5f
                
                // المسافة بين نهاية الكتلة الأولى وبداية الثانية
                val gap = r1.x - (r2.x + r2.width)
                
                return inReadingOrder && gap > -r1.symWidth * 2f && gap < r1.symWidth * 10f
            } else {
                // للويبسوق: النص من اليسار لليمين
                val inReadingOrder = r2.x > r1.x && abs(r1.y - r2.y) < r1.symHeight * 1.5f
                val gap = r2.x - (r1.x + r1.width)
                
                return inReadingOrder && gap > -r1.symWidth * 2f && gap < r1.symWidth * 10f
            }
        }

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            imgWidth: Float,
            imgHeight: Float
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)
            val readingDirectionRightToLeft = !isWebtoon // المانجا: يمين لليسار، الويبسوق: يسار لليمين

            // 1. التصفية
            val filteredBlocks = blocks.onEach { block ->
                val cleanText = block.text.trim()
                val isLink = isWebtoon && NOISE_REGEX.matches(cleanText)
                if (isLink || block.width <= 2 || block.height <= 2) {
                    block.translation = ""
                }
            }.filter { it.text.isNotBlank() }

            // 2. الترتيب حسب اتجاه القراءة
            val sortedBlocks = if (isWebtoon) {
                // ويبسوق: من اليسار لليمين، من الأعلى للأسفل
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            } else {
                // مانجا: من اليمين لليسار، من الأعلى للأسفل
                filteredBlocks.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            }

            var result = sortedBlocks.toMutableList()

            // 3. ضبط العتبات
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

            // 4. دمج ذكي مع استخدام خوارزمية احتياطية
            var mergedAny = true
            var iteration = 0
            val maxIterations = 3
            
            while (mergedAny && iteration < maxIterations) {
                mergedAny = false
                var i = 0
                outer@ while (i < result.size) {
                    var j = i + 1
                    while (j < result.size) {
                        // المحاولة الأولى: الخوارزمية الأساسية
                        var shouldMerge = shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)
                        
                        // المحاولة الثانية: الخوارزمية الاحتياطية إذا فشلت الأولى
                        if (!shouldMerge) {
                            shouldMerge = backupShouldMerge(
                                result[i], 
                                result[j], 
                                xThreshold, 
                                yThresholdFactor,
                                readingDirectionRightToLeft
                            )
                        }
                        
                        if (shouldMerge) {
                            result[i] = performMerge(result[i], result[j], isWebtoon)
                            result.removeAt(j)
                            mergedAny = true
                            // العودة للبداية بعد كل دمج
                            i = 0
                            continue@outer
                        }
                        j++
                    }
                    i++
                }
                iteration++
            }

            return result
        }

        private fun shouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {
            // تحسين: السماح بفرق زوايا أكبر للنص العمودي
            val isVerticalText1 = abs(r1.angle) in 80.0..100.0
            val isVerticalText2 = abs(r2.angle) in 80.0..100.0
            
            val angleDiff = abs(abs(r1.angle) - abs(r2.angle))
            val angleSimilar = if (isVerticalText1 && isVerticalText2) {
                angleDiff < 25  // زيادة التساهل للنص العمودي
            } else {
                angleDiff < 12  // القيمة الأصلية للنص الأفقي
            }

            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height
            val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
            
            val avgSymHeight = maxOf(r1.symHeight, r2.symHeight)
            val closeVertically = verticalGap <= avgSymHeight * yThresholdFactor

            val centerDiff = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
            val overlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            
            val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
            
            // زيادة التساهل مع الفجوات الأفقية للنص العمودي
            val adjustedXThreshold = if (isVerticalText1 || isVerticalText2) {
                xThreshold * 1.8f
            } else {
                xThreshold
            }
            
            val closeHorizontally = (centerDiff <= avgSymWidth * adjustedXThreshold) || 
                                   (overlap > 0) ||
                                   (overlap > -avgSymWidth * 0.5f)  // السماح بفجوات صغيرة

            return angleSimilar && closeVertically && closeHorizontally
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            val isVerticalSplit = abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f
            
            // ترتيب النص حسب اتجاه القراءة
            val blocksOrdered = if (isVerticalSplit) {
                // ترتيب عمودي (من الأعلى للأسفل)
                if (a.y < b.y) listOf(a, b) else listOf(b, a)
            } else {
                // ترتيب أفقي حسب اتجاه القراءة
                if (isWebtoon) {
                    // ويبسوق: من اليسار لليمين
                    if (a.x < b.x) listOf(a, b) else listOf(b, a)
                } else {
                    // مانجا: من اليمين لليسار
                    if (a.x > b.x) listOf(a, b) else listOf(b, a)
                }
            }

            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
            val totalLen = lenA + lenB

            // تحديد فاصل مناسب بناءً على اللغة والاتجاه
            val separator = if (isWebtoon) " " else ""  // المانجا اليابانية لا تحتاج مسافات
            
            return TranslationBlock(
                text = blocksOrdered.joinToString(separator) { it.text.trim() },
                translation = blocksOrdered.joinToString(separator) { it.translation.trim() }.trim(),
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
