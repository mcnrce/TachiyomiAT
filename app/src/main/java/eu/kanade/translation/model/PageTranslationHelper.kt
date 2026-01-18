
package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {
        // استخدام Regex أكثر احترافية وسرعة لفلترة الروابط والضوضاء
        private val NOISE_REGEX = Regex("(?i).*\\.(com|net|org|co|me|io|link|info).*|.*(discord\\.gg|http|www).*")

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            imgWidth: Float,
            imgHeight: Float
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            // 1. التصفية (فلترة اللينك والحجم الصغير)
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

            // 3. ضبط العتبات
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

            // 4. حلقة الدمج الذكية والمختصرة
            var i = 0
            outer@ while (i < result.size) {
                var j = i + 1
                while (j < result.size) {
                    if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                        // دمج الكتلتين في الكتلة i
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        // حذف الكتلة j التي تم استهلاكها
                        result.removeAt(j)
                        
                        // إعادة الفحص من البداية لضمان الدمج المتسلسل
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
    // 1. تشابه الزوايا: للنصوص الإنجليزية، يجب أن تكون الزوايا متطابقة تقريباً (أفقية)
    val angleDiff = abs(r1.angle - r2.angle)
    val angleSimilar = angleDiff < 8 || abs(angleDiff - 180) < 8

    // 2. التحقق من التقارب الرأسي (الأسطر تحت بعضها)
    val r1Bottom = r1.y + r1.height
    val r2Bottom = r2.y + r2.height
    val verticalGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
    
    // في الإنجليزية، الفجوة الرأسية بين الأسطر يجب أن تكون صغيرة (أقل من حجم الخط)
    val avgSymHeight = (r1.symHeight + r2.symHeight) / 2f
    val closeVertically = verticalGap <= avgSymHeight * (yThresholdFactor * 0.8f)

    // 3. التحقق من التقارب الأفقي (الكلمات بجانب بعضها)
    val r1Right = r1.x + r1.width
    val r2Right = r2.x + r2.width
    val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
    
    // حساب التداخل الأفقي (Horizontal Overlap)
    val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
    val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f

    /* المنطق الذهبي لمنع دمج الفقاعات المجاورة:
       - إذا كان هناك "تداخل أفقي" كبير (Overlap)، فهما غالباً سطران فوق بعضهما في نفس الفقاعة.
       - إذا كانت هناك "فجوة أفقية" (hGap)، يجب أن تكون صغيرة جداً (مسافة كلمة واحدة فقط).
    */
    val closeHorizontally = if (hOverlap > avgSymWidth * 0.5f) {
        // سطران فوق بعضهما: يكفي أن تكون المراكز متقاربة أفقياً
        val centerDiff = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
        centerDiff < avgSymWidth * xThreshold
    } else {
        // كلمات بجانب بعضها: يجب أن تكون الفجوة صغيرة جداً (لتجنب دمج فقاعتين متجاورتين)
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
    }
}
