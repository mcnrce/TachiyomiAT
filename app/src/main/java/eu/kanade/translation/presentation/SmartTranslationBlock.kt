package eu.kanade.translation.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
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
) {
    val padX = block.symWidth
    val padY = block.symHeight
    val xPx = (block.x - padX / 2) * scaleFactor
    val yPx = (block.y - padY / 2) * scaleFactor
    val width = ((block.width + padX) * scaleFactor).pxToDp()
    val height = ((block.height + padY) * scaleFactor).pxToDp()
    val isVertical = block.angle > 85

    val density = LocalDensity.current

    // إنشاء أداة القياس هنا بشكل صحيح لمرة واحدة لأنها دالة Composable
    val textMeasurer = rememberTextMeasurer()

    val fontSize = remember(block.translation, width, height, density) {
        val maxWidthPx = with(density) { width.roundToPx() }
        val maxHeightPx = with(density) { height.roundToPx() }

        var low = 1
        var high = 200
        var bestSize = low

        while (low <= high) {
            val mid = (low + high) / 2
            val style = androidx.compose.ui.text.TextStyle(
                fontSize = mid.sp,
                fontFamily = fontFamily,
            )
            // استخدام الكائن الجاهز هنا داخل الحلقة بدون مشاكل
            val result = textMeasurer.measure(
                text = block.translation ?: "",
                style = style,
                constraints = Constraints(maxWidth = maxWidthPx),
                overflow = TextOverflow.Clip,
                maxLines = Int.MAX_VALUE,
                softWrap = true,
            )

            if (result.size.height <= maxHeightPx) {
                bestSize = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        bestSize.sp
    }

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.CenterStart, true)
            .offset(xPx.pxToDp(), yPx.pxToDp())
            .requiredSize(width, height),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = block.translation ?: "",
            fontSize = fontSize,
            fontFamily = fontFamily,
            color = Color.Black,
            softWrap = true,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier
                .width(width)
                .rotate(if (isVertical) 0f else block.angle),
        )
    }
}
