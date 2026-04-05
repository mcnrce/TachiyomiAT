package eu.kanade.translation.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import eu.kanade.translation.model.TranslationBlock

@Composable
fun SmartTranslationBlock(
    modifier: Modifier = Modifier,
    block: TranslationBlock,
    scaleFactor: Float,
    fontFamily: FontFamily,
    // offsetX/offsetY: موضع الزاوية العلوية للصورة بالبكسل على الشاشة.
    // القيمة الافتراضية 0f تحافظ على التوافق مع وضع الويبتون.
    // في وضع المانجا يُمرَّر viewTL.x و viewTL.y من PagerTranslationsView.
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    customPadX: Float = block.symWidth * 2f,
    customPadY: Float = block.symHeight,
) {
    if (block.translation.isNullOrBlank()) return

    val padX = customPadX
    val padY = customPadY

    // الموضع النهائي على الشاشة بالبكسل:
    // offset + (موضع البلوك في الصورة - نصف الحشو) × مقياس العرض
    // لا نستخدم max() لأن offsetX/offsetY كافيان لضمان ظهور البلوك ضمن الشاشة،
    // والـ clip الطبيعي لـ Compose يتكفل بالباقي.
    val xPx = offsetX + (block.x - padX / 2f) * scaleFactor
    val yPx = offsetY + (block.y - padY / 2f) * scaleFactor

    val width  = ((block.width  + padX) * scaleFactor).pxToDp()
    val height = ((block.height + padY) * scaleFactor).pxToDp()

    val isVertical = block.angle > 85

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.CenterStart, true)
            .offset(xPx.pxToDp(), yPx.pxToDp())
            .requiredSize(width, height),
    ) {
        val density = LocalDensity.current
        val fontSize = remember { mutableStateOf(16.sp) }

        SubcomposeLayout { constraints ->
            val maxWidthPx  = with(density) { width.roundToPx() }
            val maxHeightPx = with(density) { height.roundToPx() }

            // Binary search لإيجاد أكبر حجم خط يناسب المساحة
            var low     = 1
            var high    = 100
            var bestSize = low

            while (low <= high) {
                val mid = (low + high) / 2
                val textLayoutResult = subcompose(mid.sp) {
                    Text(
                        text       = block.translation,
                        fontSize   = mid.sp,
                        fontFamily = fontFamily,
                        color      = Color.Black,
                        overflow   = TextOverflow.Visible,
                        textAlign  = TextAlign.Center,
                        maxLines   = Int.MAX_VALUE,
                        softWrap   = true,
                        modifier   = Modifier
                            .width(width)
                            .rotate(if (isVertical) 0f else block.angle)
                            .align(Alignment.Center),
                    )
                }[0].measure(Constraints(maxWidth = maxWidthPx))

                if (textLayoutResult.height <= maxHeightPx) {
                    bestSize = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            fontSize.value = bestSize.sp

            // القياس النهائي
            val textPlaceable = subcompose(Unit) {
                Text(
                    text       = block.translation,
                    fontSize   = fontSize.value,
                    fontFamily = fontFamily,
                    color      = Color.Black,
                    softWrap   = true,
                    overflow   = TextOverflow.Visible,
                    textAlign  = TextAlign.Center,
                    maxLines   = Int.MAX_VALUE,
                    modifier   = Modifier
                        .width(width)
                        .rotate(if (isVertical) 0f else block.angle)
                        .align(Alignment.Center),
                )
            }[0].measure(constraints)

            layout(textPlaceable.width, textPlaceable.height) {
                textPlaceable.place(0, 0)
            }
        }
    }
}
