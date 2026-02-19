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

            // 1. تنظيف أولي للكتل الفارغة
            val result = blocks.filter { it.text.isNotBlank() }.toMutableList()

            // 2. ضبط العتبات بناءً على حجم الصورة
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            // 3. حلقة الدمج الشاملة مع إعادة الضبط (Reset) لضمان فحص كل الروابط الممكنة
            var i = 0
            while (i < result.size) {
                var j = i + 1
                var mergedInThisRound = false
                
                while (j < result.size) {
                    if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                        // تنفيذ الدمج
                        result[i] = performMerge(result[i], result[j], isWebtoon)
                        // حذف العنصر المدمج الثاني
                        result.removeAt(j)
                        
                        // إعادة المؤشر للبداية لفحص الكتلة المدمجة الجديدة مع الجميع مجدداً
                        i = 0 
                        mergedInThisRound = true
                        break 
                    }
                    j++
                }
                
                if (!mergedInThisRound) {
                    i++
                }
            }

            return result
        }

        private fun shouldMerge(
            r1: TranslationBlock,
            r2: TranslationBlock,
            xThreshold: Float,
            yThresholdFactor: Float
        ): Boolean {
            // توحيد الزوايا (حماية ضد فرق الزاوية البسيط)
            val angleDiff = abs(r1.angle - r2.angle)
            val angleSimilar = angleDiff < 15 || abs(angleDiff - 180) < 15
            if (!angleSimilar) return false

            val isVertical = abs(r1.angle) in 70.0..110.0
            
            // تعريف الحدود بدقة
            val r1Right = r1.x + r1.width
            val r2Right = r2.x + r2.width
            val r1Bottom = r1.y + r1.height
            val r2Bottom = r2.y + r2.height

            val sW = maxOf(r1.symWidth, r2.symWidth, 12f)
            val sH = maxOf(r1.symHeight, r2.symHeight, 12f)

            // حساب الفجوات (Gaps) مع حماية ضد القيم السالبة الناتجة عن التداخل
            val hGap = maxOf(0f, if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right)
            val vGap = maxOf(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
            
            // حساب التداخل (Overlap) - يكون موجباً فقط في حال وجود تقاطع فعلي
            val hOverlap = minOf(r1Right, r2Right) - maxOf(r1.x, r2.x)
            val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)

            return if (isVertical) {
                /* --- منطق الدمج العمودي (المانجا) المحصن --- */
                
                val dx = abs(r1.x - r2.x)
                val dy = abs(r1.y - r2.y)
                
                // أ- تقارب الأصول (Top-Left): دمج الأعمدة التي تبدأ من نفس المستوى تقريباً
                val isOriginsClose = dy < (sH * 2.5f) && dx < (sW * 4.5f)
                
                // ب- التماس الجانبي: نهاية عمود مع بداية عمود آخر أفقياً
                val isSideBySide = hGap < (sW * 2.5f) && dy < (sH * 2.2f)

                // ج- المحاذاة العمودية مع تقارب أفقي: (لدمج الأجزاء المقطوعة من نفس العمود)
                val alignedVertically = vOverlap > (sH * 0.15f)
                val closeHorizontally = hGap < (sW * 2.2f)

                isOriginsClose || isSideBySide || (closeHorizontally && alignedVertically)
            } else {
                /* --- منطق الدمج الأفقي المحصن --- */
                
                val closeVertically = vGap <= sH * (yThresholdFactor * 0.9f)
                val dxCenter = abs((r1.x + r1.width / 2f) - (r2.x + r2.width / 2f))
                
                val closeHorizontally = if (hOverlap > sW * 0.5f) {
                    dxCenter < sW * xThreshold
                } else {
                    hGap < sW * 2.0f 
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
                // زيادة العرض 30% وتوسيط الفقاعة المدمجة
                val expansion = finalWidth * 0.50f
                finalWidth += expansion
                finalX -= expansion / 2f 
            }

            // ترتيب الكتل للنص النهائي
            val blocksOrdered = if (isVertical) {
                // في المانجا: من اليمين إلى اليسار
                if (a.x > b.x) listOf(a, b) else listOf(b, a)
            } else {
                // في الأفقي: من الأعلى للأسفل، ثم من اليسار لليمين
                val isVerticalSplit = abs(a.y - b.y) > maxOf(a.symHeight, b.symHeight) * 0.5f
                if (isVerticalSplit) {
                    if (a.y < b.y) listOf(a, b) else listOf(b, a)
                } else {
                    if (isWebtoon) (if (a.x < b.x) listOf(a, b) else listOf(b, a))
                    else (if (a.x > b.x) listOf(a, b) else listOf(b, a))
                }
            }

            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
            val totalLen = lenA + lenB

            return TranslationBlock(
                text = blocksOrdered.joinToString(" ") { it.text.trim() },
                translation = blocksOrdered.joinToString(" ") { it.translation.trim() }.trim(),
                width = finalWidth,
                height = maxY - minY,
                x = finalX,
                y = minY,
                angle = if (lenA >= lenB) a.angle else b.angle,
                symWidth = (a.symWidth * lenA + b.symWidth * lenB) / totalLen,
                symHeight = (a.symHeight * lenA + b.symHeight * lenB) / totalLen
            )
        }
    }
}
