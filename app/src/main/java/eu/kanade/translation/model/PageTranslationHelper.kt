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

            // 1. تنظيف أولي سريع
            val initialBlocks = blocks.filter { it.text.isNotBlank() }.toMutableList()

            // 2. ضبط عتبات المسافات بناءً على حجم الصورة
            val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
            val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)
            val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)

            var result = initialBlocks
            var changed = true

            // 3. حلقة الدمج الشاملة
            while (changed) {
                changed = false
                var i = 0
                while (i < result.size) {
                    var j = i + 1
                    while (j < result.size) {
                        if (shouldMerge(result[i], result[j], xThreshold, yThresholdFactor)) {
                            result[i] = performMerge(result[i], result[j], isWebtoon)
                            result.removeAt(j)
                            changed = true
                            continue 
                        }
                        j++
                    }
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
            val angleDiff = abs(r1.angle - r2.angle)
            val angleSimilar = angleDiff < 15 || abs(angleDiff - 180) < 15
            if (!angleSimilar) return false

            val isVertical = abs(r1.angle) in 70.0..110.0

            val r1Right = r1.x + r1.width
            val r1Bottom = r1.y + r1.height
            val r2Right = r2.x + r2.width
            val r2Bottom = r2.y + r2.height

            val sW = maxOf(r1.symWidth, r2.symWidth, 12f)
            val sH = maxOf(r1.symHeight, r2.symHeight, 12f)

            // حساب مساحة التقاطع (Intersection Area)
            val interLeft = maxOf(r1.x, r2.x)
            val interTop = maxOf(r1.y, r2.y)
            val interRight = minOf(r1Right, r2Right)
            val interBottom = minOf(r1Bottom, r2Bottom)

            val interWidth = maxOf(0f, interRight - interLeft)
            val interHeight = maxOf(0f, interBottom - interTop)
            val overlapArea = interWidth * interHeight

            val areaR1 = r1.width * r1.height
            val areaR2 = r2.width * r2.height
            val minArea = minOf(areaR1, areaR2)
            val minWidth = minOf(r1.width, r2.width)

            return if (isVertical) {
                /* --- خوارزمية المانجا العمودية الأصلية (بدون تعديل) --- */
                val dx = abs(r1.x - r2.x)
                val dy = abs(r1.y - r2.y)
                val isOriginsClose = dy < (sH * 2.2f) && dx < (sW * 4.5f)
                val sideGap = if (r1.x < r2.x) abs(r2.x - r1Right) else abs(r1.x - r2Right)
                val isSideBySide = sideGap < (sW * 2.5f) && dy < (sH * 2.2f)
                val vOverlap = minOf(r1Bottom, r2Bottom) - maxOf(r1.y, r2.y)
                val alignedVertically = vOverlap > (sH * 0.15f)
                val hGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
                val closeHorizontally = hGap < (sW * 2.2f)

                isOriginsClose || isSideBySide || (closeHorizontally && alignedVertically)

            } else {
                /* --- منطق الأسطر الأفقية (تحفظ شديد أفقياً) --- */
                
                // 1. فحص التراكم العمودي (Center Stack)
                val centerR1 = r1.x + r1.width / 2f
                val centerR2 = r2.x + r2.width / 2f
                val centerDiff = abs(centerR1 - centerR2)
                val vGap = maxOf(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
                
                // صرامة عالية: تطابق المركز بنسبة 10% فقط وفجوة رأسية ضيقة جداً
                val isStacked = centerDiff < (minWidth * 0.10f) && 
                                vGap < (sH * 0.35f * yThresholdFactor)

                // 2. فحص تداخل المساحة العميق (Deep Area Overlap):
                // رفعنا النسبة إلى 35% لضمان أن النصوص ليست متجاورة فحسب، بل متداخلة فعلياً
                val hasDeepAreaOverlap = overlapArea > (minArea * 0.35f)

                isStacked || hasDeepAreaOverlap
            }
        }

        private fun performMerge(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)

            val blocks = listOf(a, b)
            
            val lenA = a.text.length.coerceAtLeast(1)
            val lenB = b.text.length.coerceAtLeast(1)
            val totalLen = lenA + lenB

            return TranslationBlock(
                text = blocks.joinToString(" ") { it.text.trim() },
                translation = blocks.joinToString(" ") { it.translation.trim() }.trim(),
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
