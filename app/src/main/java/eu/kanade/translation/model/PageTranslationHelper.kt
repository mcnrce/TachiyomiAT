package eu.kanade.translation.model

import kotlin.math.abs

class PageTranslationHelper {

    companion object {

        fun smartMergeBlocks(
            blocks: List<TranslationBlock>,
            xThreshold: Float = 2.5f,
            yThresholdFactor: Float = 1.6f
        ): MutableList<TranslationBlock> {

            if (blocks.isEmpty()) return mutableListOf()

            // 1. الفلترة المبدئية والترتيب (مهم جداً للترتيب المنطقي)
            // نرتب أولاً حسب Y (الأعلى أولاً) ثم حسب X (الأيمن أولاً - للمانجا)
            val result = blocks.filter { block ->
                block.text.isNotBlank() && block.width > 2 && block.height > 2
            }.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            .toMutableList()

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
                            result[i] = performMerge(first, second)
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
            // 1. زيادة المرونة في الزاوية إلى 20 درجة (المانجا غالباً بها نصوص مائلة)
            val angleSimilar = abs(abs(r1.angle) - abs(r2.angle)) < 20

            // 2. حساب المسافة الرأسية مع معالجة حالة التداخل
            val r1Top = r1.y
            val r1Bottom = r1.y + r1.height
            val r2Top = r2.y
            val r2Bottom = r2.y + r2.height

            // إذا كان هناك أي تداخل رأسي، نعتبر المسافة صفر (أي ادمج فوراً)
            val isVerticalOverlap = maxOf(r1Top, r2Top) < minOf(r1Bottom, r2Bottom)
            
            val verticalGap = if (isVerticalOverlap) 0f 
                             else if (r1Bottom < r2Top) r2Top - r1Bottom 
                             else r1Top - r2Bottom

            // استخدام الحجم الأكبر (Max) بدلاً من المتوسط لضمان عدم فشل الدمج في الفقاعات الكبيرة
            val referenceHeight = maxOf(r1.symHeight, r2.symHeight)
            val closeVertically = verticalGap <= (referenceHeight * yThresholdFactor)

            // 3. التقارب الأفقي (المراكز + التداخل)
            val r1CenterX = r1.x + (r1.width / 2f)
            val r2CenterX = r2.x + (r2.width / 2f)
            val centerDiff = abs(r1CenterX - r2CenterX)
            
            val avgSymWidth = (r1.symWidth + r2.symWidth) / 2f
            
            // هل أحدهما "يغطي" الآخر أفقياً؟
            val hasHorizontalOverlap = minOf(r1.x + r1.width, r2.x + r2.width) > maxOf(r1.x, r2.x)

            // ادمج إذا كانت المراكز متقاربة أو كان هناك تداخل أفقي
            val closeHorizontally = (centerDiff <= avgSymWidth * xThreshold) || hasHorizontalOverlap

            return angleSimilar && closeVertically && closeHorizontally
        }


        private fun performMerge(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            // الترتيب داخل الدمج: الأعلى أولاً، وإذا تساويا فالأيمن أولاً
            val blocksOrdered = if (a.y < b.y - (a.symHeight / 2)) {
                listOf(a, b)
            } else if (b.y < a.y - (b.symHeight / 2)) {
                listOf(b, a)
            } else {
                // إذا كانا على نفس السطر تقريباً، الأيمن (X أكبر) يسبق الأيسر
                if (a.x > b.x) listOf(a, b) else listOf(b, a)
            }

            val mergedText = blocksOrdered.joinToString(" ") { it.text.trim() }
            val mergedTrans = blocksOrdered.joinToString(" ") { it.translation.trim() }.trim()

            val totalLen = maxOf(1, a.text.length + b.text.length)
            val finalSymWidth = (a.symWidth * a.text.length + b.symWidth * b.text.length) / totalLen
            val finalSymHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / totalLen
            val finalAngle = if (abs(a.angle) <= abs(b.angle)) a.angle else b.angle

            return TranslationBlock(
                text = mergedText,
                translation = mergedTrans,
                width = maxX - minX,
                height = maxY - minY,
                x = minX,
                y = minY,
                angle = finalAngle,
                symWidth = finalSymWidth,
                symHeight = finalSymHeight
            )
        }
    }
}
