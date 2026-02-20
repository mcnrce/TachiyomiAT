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

            val result = blocks.filter { it.text.isNotBlank() }.toMutableList()

            // عتبات أكثر صرامة لمنع دمج الفقاعات المستقلة
            val xThreshold = (1.5f * (imgWidth / 1200f).coerceAtMost(2.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.0f * (imgHeight / 2000f).coerceAtMost(1.8f)).coerceAtLeast(1.0f)
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            var i = 0
            while (i < result.size) {
                var j = i + 1
                var mergedInThisRound = false
                while (j < result.size) {
                    if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        result.removeAt(j)
                        i = 0 
                        mergedInThisRound = true
                        break 
                    }
                    j++
                }
                if (!mergedInThisRound) i++
            }
            return result
        }

        private fun shouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {
            val angleDiff = abs(r1.angle - r2.angle)
            if (!(angleDiff < 10 || abs(angleDiff - 180) < 10)) return false

            val isVertical = abs(r1.angle) in 75.0..105.0
            
            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            val sW = maxOf(r1.symWidth, r2.symWidth, 12f)
            val sH = maxOf(r1.symHeight, r2.symHeight, 12f)

            // حساب التداخل الفعلي
            val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
            val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
            val vGap = maxOf(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
            val hGap = maxOf(0f, if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right)

            return if (isVertical) {
                // دمج عمودي محافظ
                val dx = abs(r1.x - r2.x)
                val dy = abs(r1.y - r2.y)
                
                val isOriginsClose = dy < (sH * 1.0f) && dx < (sW * 2.0f)
                // يجب أن يكون التداخل الرأسي كبيراً جداً لاعتبارهما عموداً واحداً
                val alignedVertically = vOverlap > (minOf(r1.height, r2.height) * 0.6f) && hGap < (sW * 0.8f)

                isOriginsClose || alignedVertically
            } else {
                /* --- الإصلاح هنا: منطق الدمج الأفقي الصارم --- */
                
                // 1. التداخل الأفقي الجوهري: لا ندمج لمجرد تلامس بسيط
                // يجب أن يشترك الصندوقان في 50% على الأقل من عرض الصندوق الأصغر أفقياً
                val minWidth = minOf(r1.width, r2.width)
                val hasSignificantHorizontalOverlap = hOverlap > (minWidth * 0.5f)

                // 2. القرب الرأسي: يجب أن تكون المسافة الرأسية أقل من نصف حجم الحرف
                val isVeryCloseVertically = vGap < (sH * 0.4f)

                // 3. دمج الكلمات المقطوعة في سطر واحد (تلامس أفقي مباشر)
                val sameLine = hGap < (sW * 0.6f) && vOverlap > (minOf(r1.height, r2.height) * 0.7f)

                (hasSignificantHorizontalOverlap && isVeryCloseVertically) || sameLine
            }
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            val isVertical = abs(a.angle) in 75.0..105.0
            
            // ترتيب النصوص بناءً على المحور المسيطر
            val blocksOrdered = if (isVertical) {
                if (a.x > b.x) listOf(a, b) else listOf(b, a)
            } else {
                // إذا كان الفارق الرأسي واضحاً، فالترتيب من الأعلى للأسفل
                if (abs(a.y - b.y) > 5f) {
                    if (a.y < b.y) listOf(a, b) else listOf(b, a)
                } else {
                    if (isWebtoon) (if (a.x < b.x) listOf(a, b) else listOf(b, a))
                    else (if (a.x > b.x) listOf(a, b) else listOf(b, a))
                }
            }

            return TranslationBlock(
                text = blocksOrdered.joinToString(" ") { it.text.trim() },
                translation = blocksOrdered.joinToString(" ") { it.translation?.trim() ?: "" }.trim(),
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = a.angle,
                symWidth = (a.symWidth + b.symWidth) / 2f,
                symHeight = (a.symHeight + b.symHeight) / 2f
            )
        }
    }
}
