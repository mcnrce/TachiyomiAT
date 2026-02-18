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

            val initialBlocks = blocks.filter { it.text.isNotBlank() }.toMutableList()
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            var result = initialBlocks
            
            // حلقة الدمج مع إعادة الضبط (Reset) لضمان فحص كل الاحتمالات
            var i = 0
            while (i < result.size) {
                var j = i + 1
                var merged = false
                while (j < result.size) {
                    if (shouldMergeExperimental(result[i], result[j])) {
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        result.removeAt(j)
                        i = 0 // العودة للبداية فوراً
                        merged = true
                        break
                    }
                    j++
                }
                if (!merged) i++
            }

            return result
        }

        private fun shouldMergeExperimental(r1: TranslationBlock, r2: TranslationBlock): Boolean {
            val angleDiff = abs(r1.angle - r2.angle)
            if (!(angleDiff < 15 || abs(angleDiff - 180) < 15)) return false

            val isVertical = abs(r1.angle) in 70.0..110.0

            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            // صمامات أمان للأحجام
            val sW = maxOf(r1.symWidth, r2.symWidth, 10f)
            val sH = maxOf(r1.symHeight, r2.symHeight, 10f)

            return if (isVertical) {
                /* --- المنطق التجريبي: محاكاة الأفقي --- */
                
                // 1. المسافة الأفقية بين الأعمدة (نعاملها كأنها مسافة رأسية بين أسطر)
                val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
                
                // 2. التداخل الرأسي (نعامله كأنه تداخل أفقي للأسطر)
                val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
                
                // 3. تطبيق شروط "الأفقي" على "العمودي":
                // - هل الأعمدة قريبة أفقياً؟ (بمساحة تصل لـ 2.5 ضعف عرض الحرف)
                val closeHorizontally = hGap < (sW * 2.5f)
                
                // - هل هناك أي تقاطع رأسي؟ (حتى لو بسيط جداً)
                val hasVerticalTouch = vOverlap > -5f // سماحية 5 بكسل حتى لو لم يلمسا بعضهما

                closeHorizontally && hasVerticalTouch
            } else {
                /* --- المنطق الأفقي التقليدي --- */
                val vGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
                val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
                
                vGap < (sH * 1.5f) && hOverlap > (sW * -0.5f)
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
                // زيادة العرض 30% للتنفس
                val expansion = finalWidth * 0.30f
                finalWidth += expansion
                finalX -= expansion / 2f 
            }

            val blocksOrdered = if (isVertical) {
                if (a.x > b.x) listOf(a, b) else listOf(b, a)
            } else {
                if (abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f) {
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
