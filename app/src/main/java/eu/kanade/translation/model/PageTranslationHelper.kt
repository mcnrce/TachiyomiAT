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

            // استخدام البيانات الخام مباشرة مع تنظيف المسافات فقط
            val result = blocks.toMutableList()

            // عتبات ديناميكية مبنية على حجم الصورة
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            var i = 0
            while (i < result.size) {
                var j = i + 1
                var merged = false
                
                while (j < result.size) {
                    // فحص الدمج بين r1 و r2
                    if (shouldMergeRaw(result[i], result[j], xThreshold, yThresholdFactor)) {
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        result.removeAt(j)
                        
                        // إعادة الضبط للبداية: الكتلة المدمجة قد يكون لها جيران "خلفها" في القائمة
                        i = 0 
                        merged = true
                        break 
                    }
                    j++
                }
                
                if (!merged) {
                    i++
                }
            }

            return result
        }

        private fun shouldMergeRaw(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {
            // تقارب الزاوية (مع تسامح لـ 15 درجة)
            val angleDiff = abs(r1.angle - r2.angle)
            val angleSimilar = angleDiff < 15 || abs(angleDiff - 180) < 15
            if (!angleSimilar) return false

            val isVertical = abs(r1.angle) in 70.0..110.0
            
            // حسابات الحدود
            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            // صمامات أمان للأحجام (البيانات الخام قد تعطي أرقاماً صغيرة جداً)
            val sW = maxOf(r1.symWidth, r2.symWidth, 12f)
            val sH = maxOf(r1.symHeight, r2.symHeight, 12f)

            // حساب الفجوات والتداخل مع حماية من القيم السالبة
            val hGap = maxOf(0f, if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right)
            val vGap = maxOf(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
            val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
            val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)

            return if (isVertical) {
                val dx = abs(r1.x - r2.x)
                val dy = abs(r1.y - r2.y)
                
                // 1. تقارب البدايات (مع رفع السماحية قليلاً للبيانات الخام)
                val isOriginsClose = dy < (sH * 2.8f) && dx < (sW * 4.8f)
                
                // 2. التماس الجانبي (Gaps)
                val isSideBySide = hGap < (sW * 2.8f) && dy < (sH * 2.5f)

                // 3. التداخل الرأسي (أعمدة مقطوعة)
                val alignedVertically = vOverlap > (sH * 0.10f)
                val closeHorizontally = hGap < (sW * 2.5f)

                isOriginsClose || isSideBySide || (closeHorizontally && alignedVertically)
            } else {
                // المنطق الأفقي
                val closeVertically = vGap <= sH * (yThresholdFactor * 1.0f)
                val dxCenter = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
                
                val closeHorizontally = if (hOverlap > sW * 0.4f) {
                    dxCenter < sW * xThreshold
                } else {
                    hGap < sW * 2.2f 
                }
                closeVertically && closeHorizontally
            }
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            var finalWidth = maxX - minX
            var finalX = minX

            val isVertical = abs(a.angle) in 70.0..110.0
            if (isVertical) {
                // زيادة عرض الفقاعة بنسبة 30% لضمان عدم قص النص العربي
                val expansion = finalWidth * 0.30f
                finalWidth += expansion
                finalX -= expansion / 2f 
            }

            // ترتيب النصوص: يمين ليسار للعمودي، وأعلى لأسفل للأفقي
            val blocksOrdered = if (isVertical) {
                if (a.x > b.x) listOf(a, b) else listOf(b, a)
            } else {
                val isVerticalSplit = abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f
                if (isVerticalSplit) {
                    if (a.y < b.y) listOf(a, b) else listOf(b, a)
                } else {
                    if (isWebtoon) (if (a.x < b.x) listOf(a, b) else listOf(b, a))
                    else (if (a.x > b.x) listOf(a, b) else listOf(b, a))
                }
            }

            val totalLen = (a.text.length + b.text.length).coerceAtLeast(1)
            return TranslationBlock(
                text = blocksOrdered.joinToString(" ") { it.text.trim() },
                translation = blocksOrdered.joinToString(" ") { it.translation.trim() }.trim(),
                width = finalWidth,
                height = maxY - minY,
                x = finalX,
                y = minY,
                angle = if (a.text.length >= b.text.length) a.angle else b.angle,
                symWidth = (a.symWidth * a.text.length + b.symWidth * b.text.length) / totalLen,
                symHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / totalLen
            )
        }
    }
}
