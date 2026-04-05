package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.toFontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.flow.MutableStateFlow

class PagerTranslationsView : AbstractComposeView {

    private val translation: PageTranslation
    private val font: TranslationFont
    private val fontFamily: FontFamily

    companion object {
        private const val PAD_X_FACTOR = 2.5f
        private const val PAD_Y_FACTOR = 0.5f
    }

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = PageTranslation.EMPTY
        this.font = TranslationFont.ANIME_ACE
        this.fontFamily = Font(resId = font.res, weight = FontWeight.Bold).toFontFamily()
    }

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        translation: PageTranslation,
        font: TranslationFont? = null,
    ) : super(context, attrs, defStyleAttr) {
        this.translation = translation
        this.font = font ?: TranslationFont.ANIME_ACE
        this.fontFamily = Font(resId = this.font.res, weight = FontWeight.Bold).toFontFamily()
    }

    // يُضبط من updateTranslationCoords في PagerPageHolder:
    // scaleState  ← (tr.x - tl.x) / imgWidth   ← نفس scaleFactor في الويبتون
    // viewTLState ← sourceToViewCoord(0, 0)     ← موقع أعلى يسار الصورة (px)
    val scaleState  = MutableStateFlow(1f)
    val viewTLState = MutableStateFlow(PointF())

    @Composable
    override fun Content() {
        val viewTL by viewTLState.collectAsState()
        val scale  by scaleState.collectAsState()

        var viewSize by remember { mutableStateOf(IntSize.Zero) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it },
        ) {
            if (viewSize.width <= 0) return@Box

            val maxW = viewSize.width.toFloat()
            val maxH = viewSize.height.toFloat()

            TextBlockBackground(scale, viewTL, maxW, maxH)
            TextBlockContent(scale, viewTL, maxW, maxH)
        }
    }

    @Composable
    fun TextBlockBackground(scale: Float, viewTL: PointF, maxW: Float, maxH: Float) {
        translation.blocks.forEach { block ->
            if (block.translation.isNullOrBlank()) return@forEach

            val padX = block.symWidth  * PAD_X_FACTOR
            val padY = block.symHeight * PAD_Y_FACTOR

            // موقع نهائي = موقع حافة الصورة + (موقع الفقاعة في الصورة) × scale
            // — نفس المنطق الذي يستخدمه الويبتون —
            val bgX = (viewTL.x + (block.x - padX / 2f) * scale).coerceIn(0f, maxW)
            val bgY = (viewTL.y + (block.y - padY / 2f) * scale).coerceIn(0f, maxH)
            val bgW = ((block.width  + padX) * scale).coerceAtMost(maxW - bgX)
            val bgH = ((block.height + padY) * scale).coerceAtMost(maxH - bgY)

            if (bgW <= 0f || bgH <= 0f) return@forEach

            val isVertical = block.angle > 85

            Box(
                modifier = Modifier
                    .offset(bgX.pxToDp(), bgY.pxToDp())
                    .requiredSize(bgW.pxToDp(), bgH.pxToDp())
                    .rotate(if (isVertical) 0f else block.angle)
                    .background(Color.White, shape = RoundedCornerShape(4.dp)),
            )
        }
    }

    @Composable
    fun TextBlockContent(scale: Float, viewTL: PointF, maxW: Float, maxH: Float) {
        translation.blocks.forEach { block ->
            SmartTranslationBlock(
                block       = block,
                scaleFactor = scale,
                offsetX     = viewTL.x,
                offsetY     = viewTL.y,
                maxW        = maxW,
                maxH        = maxH,
                fontFamily  = fontFamily,
                customPadX  = block.symWidth  * PAD_X_FACTOR,
                customPadY  = block.symHeight * PAD_Y_FACTOR,
            )
        }
    }

    fun show() { isVisible = true }
    fun hide() { isVisible = false }
}
