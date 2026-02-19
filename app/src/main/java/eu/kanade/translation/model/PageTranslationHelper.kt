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

            // 1. تحويل الفقاعات: إذا كانت عمودية، نقلب إحداثياتها لتصبح أفقية
            val transformedBlocks = blocks.map { block ->
                val isVertical = abs(block.angle) in 70.0..110.0
                if (isVertical) {
                    // تحويل منطقي: X يصبح Y، والارتفاع يصبح عرضاً
                    block.copy(
                        x = block.y,
                        y = block.x,
                        width = block.height,
                        height = block.width,
                        symWidth = block.symHeight,
                        symHeight = block.symWidth,
                        angle = 0f // أصبحت أفقية الآن في نظر الخوارزمية
                    )
                } else {
                    block
                }
            }.toMutableList()

            // 2. تطبيق خوارزمية الدمج الأفقية الموحدة على الكل
            var i = 0
            while (i < transformedBlocks.size) {
                var j = i + 1
                var merged = false
                while (j < transformedBlocks.size) {
                    if (shouldMergeHorizontal(transformedBlocks[i], transformedBlocks[j])) {
                        transformedBlocks[i] = performMergeHorizontal(transformedBlocks[i], transformedBlocks[j])
                        transformedBlocks.removeAt(j)
                        i = 0 
                        merged = true
                        break
                    }
                    j++
                }
                if (!merged) i++
            }

            // 3. إعادة الفقاعات لأصلها (إرجاع الإحداثيات العمودية)
            return transformedBlocks.map { block ->
                // إذا كانت القيمة الأصلية مخزنة أو إذا كان العرض أكبر من الارتفاع بشكل شاذ (دليل التحويل)
                // سنعتمد على منطق أننا نعرف أنها كانت عمودية إذا كانت ناتجة عن دمج عمودي
                if (abs(block.angle) < 1f && block.width > block.height * 2) { 
                    val restored = block.copy(
                        x = block.y,
                        y = block.x,
                        width = block.height,
                        height = block.width,
                        symWidth = block.symHeight,
                        symHeight = block.symWidth,
                        angle = 90f 
                    )
                    // تطبيق زيادة العرض 30% هنا بعد الاستعادة
                    val expansion = restored.width * 0.30f
                    restored.copy(
                        width = restored.width + expansion,
                        x = restored.x - (expansion / 2f)
                    )
                } else {
                    block
                }
            }.toMutableList()
        }

        // خوارزمية دمج أفقية "بحتة" وسهلة
        private fun shouldMergeHorizontal(r1: TranslationBlock, r2: TranslationBlock): Boolean {
            val vGap = if (r1.y < r2.y) r2.y - (r1.y + r1.height) else r1.y - (r2.y + r2.height)
            val hOverlap = minOf(r1.x + r1.width, r2.x + r2.width) - maxOf(r1.x, r2.x)
            
            // شروط دمج الأسطر الأفقية: مسافة رأسية صغيرة وتداخل أفقي
            return vGap < (r1.symHeight * 1.5f) && hOverlap > (r1.symWidth * -0.5f)
        }

        private fun performMergeHorizontal(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
            val minX = minOf(a.x, b.x)
            val minY = minOf(a.y, b.y)
            val maxX = maxOf(a.x + a.width, b.x + b.width)
            val maxY = maxOf(a.y + a.height, b.y + b.height)
            
            // ترتيب النصوص بناءً على Y (لأنها أصبحت أسطر)
            val ordered = if (a.y < b.y) listOf(a, b) else listOf(b, a)

            return a.copy(
                text = ordered.joinToString(" ") { it.text.trim() },
                translation = ordered.joinToString(" ") { it.translation.trim() }.trim(),
                x = minX, y = minY,
                width = maxX - minX, height = maxY - minY
            )
        }
    }
}

